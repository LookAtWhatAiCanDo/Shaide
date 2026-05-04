package llc.lookatwhataicando.notifai.notification

/**
 * Describes a notification whose content is obscured from NotificationListenerService —
 * no title/text/extras are present in the NLS payload — but whose content IS visible
 * in the notification shade via the AccessibilityService.
 *
 * This commonly occurs with GROUP_SUMMARY notifications (e.g. Google Chat,
 * Google Search) where the real content is only accessible after expanding
 * the collapsed row in the shade.
 */
data class ObscuredNotification(
    val packageName: String,
    val appLabel: String,
    val notificationFlags: Int,
    val postedAtMs: Long,
    val resolutionOutcome: ResolutionOutcome,
)

enum class ResolutionOutcome {
    /** Accessibility found and read the shade row for this notification. */
    FOUND,
    /** Accessibility was available but no matching row was found in the shade. */
    NOT_FOUND,
    /** MyAccessibilityService.instance was null — permission not granted. */
    ACCESSIBILITY_UNAVAILABLE,
}
