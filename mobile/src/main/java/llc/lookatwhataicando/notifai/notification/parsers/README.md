# Notification Parsing — Ground Truth

## Two delivery paths

### Path 1 — NotificationListenerService (NLS), `MyNotificationListenerService`

The primary path. The system delivers every `StatusBarNotification` to `onNotificationPosted`.
`defaultOnNotificationPosted` extracts title, text, ticker, and `MessagingStyle` messages from
the notification extras and speaks them via TTS.

This covers the vast majority of apps and all well-behaved notifications.

### Path 2 — AccessibilityService, `MyAccessibilityService`

A fallback for notifications whose content is **not present in the NLS payload** but **is
visible in the notification shade**. The accessibility tree exposes the rendered shade rows,
including content that the app only populates in the visual layout.

---

## "Obscured" notifications (`ObscuredNotification`)

Some apps (notably `com.google.android.apps.dynamite` / Google Chat) post a `GROUP_SUMMARY`
notification whose NLS payload contains no title, text, or extras — only metadata. The actual
message content is only accessible by reading the expanded notification row in the shade.

### Live delivery (app running)

These apps typically post a silent `GROUP_SUMMARY` **immediately followed** by a
content-bearing child notification (`CHAT_CHIME`, `MessagingStyle`, full extras) within
milliseconds. NLS sees both:

1. `GROUP_SUMMARY` arrives → `ParsedIgnored` → `schedulePendingLookup(300 ms)`
2. `CHAT_CHIME` arrives → has content → `cancelPendingLookup` fires before the 300 ms runnable

Result: spoken via the normal NLS path. **No accessibility involvement.**

### Launch / catch-up (app just started, `initializeActiveNotifications`)

When the app starts and iterates `getActiveNotifications()`, the content-bearing child
notifications may already be dismissed — only the empty `GROUP_SUMMARY` remains. No cancel
arrives within 300 ms, so:

1. `GROUP_SUMMARY` → `ParsedIgnored` → `schedulePendingLookup(300 ms)`
2. 300 ms elapses, no cancel → `MyAccessibilityService.findRowForAppLabel()`
3. Shade opens → rows scanned (expanding collapsed rows as needed, scrolling for off-screen) →
   content read from accessibility tree → spoken via TTS

**This is the primary scenario requiring the Accessibility permission.**

### DM-type notifications (unconfirmed, under investigation)

Some direct-message notification types may only ever post `GROUP_SUMMARY`, even for live
delivery — never a content-bearing child. These would always fall through to the accessibility
path. The `ObscuredNotificationLogger` builds a corpus of which packages exhibit this pattern
so bespoke `NotificationParser` subclasses can be written for them.

---

## Open questions / assumptions to validate

### Do `ParsedIgnored` notifications always have content when delivered live?

When a stale (catch-up) notification parses as `ParsedIgnored` — empty NLS payload, no title,
text, or extras — it is **unknown** whether the live version of that same notification would
also be `ParsedIgnored`, or whether live delivery always produces a content-bearing payload
(either in the original notification or in a sibling child that arrives within the 300 ms window).

For Google Chat this is confirmed: the live `GROUP_SUMMARY` is immediately followed by a
content-bearing child that cancels the accessibility lookup before it fires. The stale
`GROUP_SUMMARY` has no such child, so it falls through to the accessibility path.

**The open question:** is there any app/notification type that is `ParsedIgnored` for both
live and stale delivery — i.e., a notification that never carries NLS content regardless of
when it arrives? If so, those packages would require the accessibility path even for live
notifications, and the 300 ms `PENDING_LOOKUP_DELAY_MS` window assumption breaks down.

**Action needed:** Use `ObscuredNotificationLogger` to identify any `packageName` that
consistently logs `NOT_FOUND` or `ACCESSIBILITY_UNAVAILABLE` for live notifications, which
would indicate it belongs to this category.

---

## Adding a new parser

1. Create `NotificationParserXxx.kt` extending `NotificationParser`.
2. Override `packageName` and `onNotificationPosted`.
3. Register in `MyNotificationListenerService.addNotificationParsers()`.

If the app only posts `GROUP_SUMMARY` with no content extras, the accessibility path handles
it automatically — no custom parser required unless bespoke field extraction is needed.

---

## Constants

| Constant | Location | Value | Purpose |
|---|---|---|---|
| `PENDING_LOOKUP_DELAY_MS` | `MyNotificationListenerService` | 300 ms | Window for content-bearing child to cancel the accessibility lookup |
| `SHADE_OPEN_SETTLE_DELAY_MS` | `MyAccessibilityService` | 600 ms | Time for shade animation + first accessibility events before scanning |
| `MAX_SCROLL_ATTEMPTS` | `MyAccessibilityService` | 10 | Max `ACTION_SCROLL_FORWARD` calls before giving up on off-screen rows |
