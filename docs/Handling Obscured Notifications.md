# Handling Obscured Notifications

## What is an "obscured" notification?

An **obscured notification** is one whose content is absent from the
[`NotificationListenerService`](https://developer.android.com/reference/android/service/notification/NotificationListenerService)
(NLS) payload — no title, text, or extras — but whose content IS visible in the notification
shade and IS readable via the
[`AccessibilityService`](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService)
accessibility tree.

This happens when an app posts a `GROUP_SUMMARY` notification that carries only metadata
(flags, groupKey, etc.) and relies on the rendered shade row — not the NLS extras — to display
content to the user.

---

## Two sub-classes of obscured notification

### 1. Stale-obscured (confirmed, common)

**Behavior during live delivery (app running):**
The app posts a silent `GROUP_SUMMARY` *immediately followed* by one or more content-bearing
child notifications (e.g. `CHAT_CHIME`, `MessagingStyle`) within milliseconds. NLS sees both.
The 300 ms `PENDING_LOOKUP_DELAY_MS` cancellation window in `schedulePendingLookup` gives the
content-bearing child time to arrive and cancel the accessibility lookup before it fires.

Result: **the normal NLS path speaks the content; the accessibility tree is not consulted for the
content itself.** Note: `MyAccessibilityService` must still be enabled — it is a hard requirement
that gates all NLS processing, including live notifications (see
[Requirement.ACCESSIBILITY_SERVICE] in `StartupCoordinator.kt`).

**Behavior during stale catch-up (app just started):**
When the app restarts and `initializeActiveNotifications` calls `getActiveNotifications()`, the
content-bearing child notifications may already be gone — consumed, dismissed, or never
re-posted. Only the empty `GROUP_SUMMARY` remains. No cancel arrives within 300 ms, so the
accessibility lookup fires: the shade is opened, collapsed rows are expanded, and the row
content is read from the accessibility tree.

**Confirmed stale-obscured packages:**
- `com.google.android.apps.dynamite` — Google Chat. Live delivery: GROUP_SUMMARY + CHAT_CHIME
  sibling. Stale catch-up: GROUP_SUMMARY only. Accessibility is the sole source of content at
  catch-up time.

### 2. Always-obscured (theoretical, unconfirmed)

**Hypothesis:** some apps never post a content-bearing sibling notification — not even during
live delivery. The GROUP_SUMMARY is the only notification they ever post for a given message.
Content is only available in the shade. These packages would require the accessibility path for
*both* live and stale delivery, and the `PENDING_LOOKUP_DELAY_MS` cancellation window would
never fire regardless of whether the app was just started or has been running for hours.

**Theory on which apps this applies to:** if this category exists, it is almost certainly
limited to **system-signed / AOSP / Google apps**. Third-party apps generally provide readable
content in the NLS payload or in a sibling notification. System apps often have parallel
delivery channels (e.g. FCM data messages delivered directly to the app process) and may use
the `GROUP_SUMMARY` notification solely as a visual shade badge.

**Currently uncharacterized packages** (appeared in logs, live vs. stale behavior not yet
confirmed):
- `com.google.android.googlequicksearchbox` — Google search / assistant

---

## Detection and corpus-building

`ObscuredNotificationLogger` records every accessibility lookup outcome
(`FOUND` / `NOT_FOUND` / `ACCESSIBILITY_UNAVAILABLE`) keyed by `packageName`.

To identify candidates for the **always-obscured** category, look for packages that
consistently reach the accessibility path even during live notification delivery — not only at
app startup. A `packageName` that triggers `findRowForAppLabel` on freshly arriving
notifications (and consistently returns `FOUND`) is a strong candidate.

---

## Architecture summary

```
onNotificationPosted(sbn)
  └─ defaultOnNotificationPosted → ParsedIgnored
        └─ schedulePendingLookup(300 ms)
              │
              ├─ [live, stale-obscured] sibling child arrives within 300 ms
              │     └─ cancelPendingLookup → spoken via NLS path
              │
              └─ [stale catch-up, or always-obscured live] 300 ms elapses with no cancel
                    └─ MyAccessibilityService.findRowForAppLabel(appLabel)
                          └─ ShadeRowSearchQueue: open shade → expand rows → match appName
                                └─ callback → speakShadeRows → TTS
```

Multiple concurrent `findRowForAppLabel` calls (e.g. Chat and Google both firing within 30 ms
at startup) are serialized by `ShadeRowSearchQueue` — each search waits for the previous to
finish before the shade is handed to it.

---

## Key constants

| Constant | Location | Value | Purpose |
|---|---|---|---|
| `PENDING_LOOKUP_DELAY_MS` | `MyNotificationListenerService` | 300 ms | Window for content-bearing child to cancel the accessibility lookup |
| `ShadeDelays.FAST.shadeSettle` | `ShadeDelays` | 600 ms | Shade animation settle time (production) |
| `ShadeDelays.SLOW.shadeSettle` | `ShadeDelays` | 3000 ms | Shade animation settle time (debug/observation) |
| `ShadeDelays.FAST.preExpand` | `ShadeDelays` | 0 ms | Pause before each row expansion (production) |
| `ShadeDelays.SLOW.preExpand` | `ShadeDelays` | 2000 ms | Pause before each row expansion (debug/observation) |
| `MAX_SCROLL_ATTEMPTS` | `ShadeRowSearchQueue` | 10 | Max scroll attempts before giving up on off-screen rows |

Toggle `MyAccessibilityService.DEBUG_SLOW_MODE = true` to use the SLOW delays for visual
step-by-step observation of the shade automation. Set false and confirm FAST values once
minimum working delays are established.

---

## Known limitations

- **Multi-account apps:** if two accounts of the same app post GROUP_SUMMARY simultaneously,
  the first matching shade row wins; the second is not spoken.
- **Screen locked:** when the screen is locked, expand/collapse behavior via the accessibility
  tree is unknown and the shade row content may be unavailable.
- **`com.google.android.gm` (Gmail):** sends `tickerText` with partial content via NLS; the
  accessibility path is not currently required but a bespoke `NotificationParser` may improve
  the spoken output.
