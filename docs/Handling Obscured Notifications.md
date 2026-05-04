# Plan: Hybrid NLS + Accessibility Correlation for Solitary Empty Notifications

## Context

Some apps (notably `com.google.android.apps.dynamite` / Google Chat, `com.google.android.googlequicksearchbox`) post only a single `GROUP_SUMMARY` notification with no title, text, or content extras. `NotificationListenerService` receives this and logs "No notification parts found; ignoring". The content IS visible in the notification shade and IS readable by `MyAccessibilityService`, but the two services have no bridge.

The accessibility hack is only needed for **solitary empty** notifications — where no subsequent non-empty notification from the same `packageName` arrives within a short window. (Contrast: `com.qnap.qmanager` posts an empty GROUP_SUMMARY immediately followed by non-empty children — those children carry the content, so accessibility is unnecessary.)

**Critical constraint:** `app_name_text` (the field that populates `ShadeRow.appName`) only appears in the accessibility tree when a notification row is **expanded**. Collapsed rows show only `[veto, title, expand_button]`. The resolution flow must programmatically expand collapsed rows until it finds the matching one.

**Telemetry requirement:** "Solitary empty" events should be logged (packageName, appLabel, flags, timestamp, resolution outcome) for analytics — to grow a corpus of which apps exhibit this pattern and need bespoke handling. Firebase Analytics is preferred; local fallback is acceptable.

---

## Architecture

### Stage 1 — NLS: Detect and register pending lookup (on notification arrival)

When `defaultOnNotificationPosted` returns `ParsedIgnored` for a non-system package:
- Schedule a delayed `Runnable` (300 ms) in `pendingLookups: ConcurrentHashMap<String, Runnable>`
- The 300 ms window absorbs child notifications that arrive immediately after the GROUP_SUMMARY

If a non-ignored notification from the same `packageName` arrives before the runnable fires:
- Cancel it (`handler.removeCallbacks`) — real content arrived via NLS

### Stage 2 — NLS→Accessibility: Trigger resolution

After 300 ms with no cancellation, call `MyAccessibilityService.instance?.findRowForAppLabel(appLabel, shadeWasOpen, callback)`.

If `instance == null` (accessibility permission not granted): log and give up.

### Stage 3 — Accessibility: Expand-scan state machine

`findRowForAppLabel` in `MyAccessibilityService` drives a state machine across accessibility events:

1. **Check current snapshot** for any row where `appName == appLabel` → if found, return it immediately.
2. **Open the notification shade** (if not already open via `actionNotificationsShow(true)`).
3. **Scroll the shade to reveal all rows:** repeatedly call `container.performAction(ACTION_SCROLL_FORWARD)` until it returns `false` (end of list). Each scroll triggers an accessibility event; collect all visible row nodes after each scroll. This is required because the target row may be off-screen.
4. **Live-traverse the shade** to collect fresh row `AccessibilityNodeInfo` objects for all visible rows.
5. **Try each collapsed row** (those whose expand_button has `contentDescription == "Expand"`): call `rowNode.performAction(ACTION_EXPAND)`. Each expansion triggers an accessibility event; the state machine picks up on the next `onAccessibilityEvent` call.
6. After each expansion, **re-check snapshot** for the appName match.
7. If all rows tried with no match: invoke callback with `null`.
8. Close shade if it was opened by this flow.

State is held in a nullable `PendingRowSearch` field on the service instance:
```kotlin
private data class PendingRowSearch(
    val appLabel: String,
    val callback: (ShadeRow?) -> Unit,
    var rowsToExpand: List<AccessibilityNodeInfo>,
    val shadeOpenedByUs: Boolean
)
private var pendingRowSearch: PendingRowSearch? = null
```

`onAccessibilityEvent` checks `pendingRowSearch` after every normal snapshot update and advances the state machine.

### Stage 4 — NLS: Speak the result

The callback (called on the main thread via the accessibility service) delivers the matched `ShadeRow` back to `MyNotificationListenerService.speakShadeRow()`.

```kotlin
private fun speakShadeRow(appLabel: String, row: MyAccessibilityService.ShadeRow) {
    val builder = FooTextToSpeechBuilder(appLabel)
    row.title?.let { builder.appendSilenceWordBreak(); builder.appendSpeech(it) }
    if (row.messages.isNotEmpty()) {
        row.messages.forEach { builder.appendSilenceWordBreak(); builder.appendSpeech(it) }
    } else {
        row.text?.let { builder.appendSilenceWordBreak(); builder.appendSpeech(it) }
    }
    parserCallbacks.textToSpeech?.speak(builder)
}
```

---

## Telemetry / Generic Framework

### `ObscuredNotification` event data class (new file)
```kotlin
data class ObscuredNotification(
    val packageName: String,
    val appLabel: String,
    val notificationFlags: Int,  // Notification.FLAG_GROUP_SUMMARY etc.
    val postedAtMs: Long,
    val resolutionOutcome: ResolutionOutcome,  // FOUND / NOT_FOUND / ACCESSIBILITY_UNAVAILABLE
)
enum class ResolutionOutcome { FOUND, NOT_FOUND, ACCESSIBILITY_UNAVAILABLE }
```

### `ObscuredNotificationLogger` (new singleton object)
- `fun log(event: ObscuredNotification)` — persists to **Firebase Firestore** (structured, queryable) and also emits to Firebase Analytics (for visualization/dashboards)
- **Firestore schema:** collection `obscured_notifications`, document keyed by `packageName`; fields: `appLabel`, `firstSeenMs`, `lastSeenMs`, `count`, `knownOutcomes: Map<ResolutionOutcome, Int>`
- Also keeps a running in-memory set of seen `packageName`s so repeat occurrences do not create duplicate Firestore documents (upsert/merge strategy)
- Firebase Analytics event: `"obscured_notification"` with params `package_name`, `app_label`, `outcome`

This is the mechanism by which packages that require special handling are discovered and catalogued. Once a package appears frequently enough in Firestore, a bespoke `NotificationParser` subclass can be registered for it in `MyNotificationListenerService.addNotificationParser()`.

---

## Files to Modify / Create

| File | Change |
|------|--------|
| `MyAccessibilityService.kt` | Add `shadeSnapshot` to companion; add `PendingRowSearch` state; add `findRowForAppLabel()` + `getLiveRowNodes()` methods; advance state machine in `onAccessibilityEvent` |
| `MyNotificationListenerService.kt` | Add `pendingLookups` map + `handler` usage; modify `onNotificationPosted` to schedule/cancel; add `speakShadeRow()` |
| *(new)* `notification/ObscuredNotification.kt` | Data class + `ResolutionOutcome` enum |
| *(new)* `notification/ObscuredNotificationLogger.kt` | Logger singleton; Firestore structured storage + Firebase Analytics |

---

## Key Constants

```kotlin
const val PENDING_LOOKUP_DELAY_MS    = 300L   // window for child notifications to cancel lookup
const val SHADE_OPEN_SETTLE_DELAY_MS = 600L   // time for shade accessibility events to fire after open
```

---

## Known Limitations (out of scope)

- **Multi-account apps:** If two accounts of the same app post GROUP_SUMMARY simultaneously, the first matched row wins.
- **Screen locked (v2):** When the screen is locked, the notification may still appear on the lock screen but expand/collapse behavior via accessibility is unknown — may be a dead end. Weak v2 fallback: detect the locked state and speak something like `"Google Chat; please unlock your phone to read it"` rather than silently giving up.
- **gmail (`com.google.android.gm`):** Sends `tickerText="2 new messages"` so NLS already has partial content; will need a separate accessibility-assisted hack and is tracked separately.

---

## Verification

1. **Google Chat message arrives:**
   - NLS logs `No notification parts found; ignoring` for `com.google.android.apps.dynamite`
   - 300 ms later: `findRowForAppLabel("Chat", ...)` is called on `MyAccessibilityService`
   - If shade has expanded Chat row: TTS speaks `"Chat [pause] Carol Micek [pause] <message>"`
   - If Chat row is collapsed: `performAction(ACTION_EXPAND)` fires on it, accessibility event triggers, row is now expanded, TTS speaks
   - `SolitaryEmptyNotificationLogger` logs the event with outcome `FOUND`
2. **Qmanager batch arrives:**
   - Empty GROUP_SUMMARY schedules lookup
   - Non-empty child notification arrives within 300 ms → lookup cancelled
   - TTS speaks via normal NLS path; no accessibility invocation
3. **Accessibility service not granted:**
   - `instance == null` → logs `ACCESSIBILITY_UNAVAILABLE` → no crash
4. **No regression:** Discord, Android Messages, and other notifications behave as before
