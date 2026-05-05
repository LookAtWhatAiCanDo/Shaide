package llc.lookatwhataicando.notifai

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import com.smartfoo.android.core.logging.FooLog

/**
 * ## Behavioral ground truth — when and why this service is needed
 *
 * ### Live notifications (app already running)
 * [MyNotificationListenerService] (NLS) handles these entirely. Apps such as Google Chat post
 * a silent `GROUP_SUMMARY` notification (no title/text/extras) immediately followed by one or
 * more content-bearing child notifications within milliseconds. NLS sees both; the 300 ms
 * cancellation window in `MyNotificationListenerService.schedulePendingLookup` lets the
 * content-bearing child cancel any pending accessibility lookup before it fires. Result: content
 * is spoken via the normal NLS path with **no accessibility service involvement**.
 *
 * ### Launch / catch-up (app just started) — primary scenario
 * When the app launches and iterates `getActiveNotifications()`, the content-bearing child
 * notifications may already be gone — consumed/dismissed after the previous app instance
 * processed them, or simply never re-posted. Only the empty `GROUP_SUMMARY` remains. NLS cannot
 * read content from it. **This is the primary scenario where `MyAccessibilityService` is
 * required:** it opens the notification shade, expands any collapsed rows, and reads their
 * content via the accessibility tree so the app can speak notifications it missed.
 *
 * See [llc.lookatwhataicando.notifai.notification.ObscuredNotification] for the confirmed
 * package `com.google.android.apps.dynamite` (Google Chat) and the stale-obscured model.
 *
 * ### Always-obscured live notifications — theoretical, unconfirmed
 * Some apps may never post a content-bearing sibling notification — not even during live
 * delivery. For these packages the accessibility path would fire unconditionally (both live
 * and stale). If this category exists, it is theorized to be limited to system-signed / AOSP /
 * Google apps. See `notification/parsers/README.md` for detection guidance.
 *
 * ### Secondary capability
 * Provides global navigation actions (open/dismiss shade, back, home, screenshot, media
 * play/pause) that NLS does not expose.
 *
 * ### Practical impact without this permission
 * Live notifications from stale-obscured apps work correctly (NLS reads their content-bearing
 * sibling). Only the launch catch-up path is degraded — the app silently skips stale obscured
 * notifications whose content-bearing child is already gone. If any always-obscured apps exist,
 * their live notifications would also be lost without this permission.
 *
 * ---
 *
 * ### Developer note — Android Studio kills Accessibility on deploy
 * The app's Accessibility permission is disabled whenever it is launched or stopped from
 * Android Studio. To keep Accessibility enabled across a deploy:
 * ```
 * adb shell am start -n llc.lookatwhataicando.notifai/.MainActivity
 * ```
 * Or re-enable Accessibility via the permissions check dialog that appears at launch.
 *
 * NOTE: `@SuppressLint("AccessibilityPolicy")` suppresses the following lint warning:
 * "AccessibilityService API usage must align with the API policy guidelines.
 * It must not be used to bypass privacy controls or change settings without consent."
 * This code honors that.
 */
@SuppressLint("AccessibilityPolicy")
class MyAccessibilityService : AccessibilityService() {
    companion object {
        var instance: MyAccessibilityService? = null
            private set

        private val TAG = FooLog.TAG(MyAccessibilityService::class)

        private val VERBOSE_LOG_ROOT_NULL  = false
        private val VERBOSE_LOG_SHADE_EMPTY = false

        /**
         * Slows the accessibility state machine for step-by-step visual observation.
         *
         * When true, [ShadeDelays.SLOW] is used so each expand/scroll/close step is visually
         * distinct. Set false and confirm [ShadeDelays.FAST] values once minimum working delays
         * are known.
         */
        @Suppress("SimplifyBooleanWithConstants", "KotlinConstantConditions")
        var DEBUG_SLOW_MODE = false && BuildConfig.DEBUG
        val delays: ShadeDelays = if (DEBUG_SLOW_MODE) ShadeDelays.SLOW else ShadeDelays.FAST

        /**
         * When true, any [MyNotificationListenerService] ParsedIgnored notification triggers a
         * full top-to-bottom shade scan ([DebugShadeScan]) instead of the normal app-label
         * search. No TTS is spoken. Disable when switching back to the normal search path.
         */
        var DEBUG_FULL_SCAN_MODE = false

        private val ACCESSIBILITY_EVENTS_NOTIFICATION_RELEVANT = setOf(
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
        )
    }

    // -------------------------------------------------------------------------
    // Global action helpers
    // -------------------------------------------------------------------------

    fun actionBack() {
        FooLog.i(TAG, "actionBack()")
        performGlobalAction(GLOBAL_ACTION_BACK)
    }

    fun actionHome() {
        FooLog.i(TAG, "actionHome()")
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    fun actionNotificationsShow(show: Boolean) {
        FooLog.i(TAG, "actionNotificationsShow($show)")
        if (show) {
            performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
        } else {
            performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
        }
    }

    fun actionTakeScreenshot() {
        FooLog.i(TAG, "actionTakeScreenshot()")
        performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
    }

    fun actionKeycodeHeadsetHook() {
        FooLog.i(TAG, "actionKeycodeHeadsetHook()")
        performGlobalAction(GLOBAL_ACTION_KEYCODE_HEADSETHOOK)
    }

    @RequiresApi(36)
    fun actionMediaPlayPause() {
        FooLog.i(TAG, "actionMediaPlayPause()")
        performGlobalAction(GLOBAL_ACTION_MEDIA_PLAY_PAUSE)
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override fun onCreate() {
        FooLog.v(TAG, "#ACCESSIBILITY onCreate()")
        super.onCreate()
    }

    override fun onDestroy() {
        FooLog.v(TAG, "#ACCESSIBILITY onDestroy()")
        super.onDestroy()
        instance = null
    }

    override fun onServiceConnected() {
        FooLog.v(TAG, "#ACCESSIBILITY onServiceConnected()")
        super.onServiceConnected()
        instance = this
    }

    override fun onInterrupt() {
        FooLog.v(TAG, "#ACCESSIBILITY onInterrupt()")
    }

    // -------------------------------------------------------------------------
    // Snapshot state + delegates
    // -------------------------------------------------------------------------

    private var lastSnapshot: List<NotificationShadeSnapshot.ShadeRow> = emptyList()

    private val searchQueue = ShadeRowSearchQueue(
        delays          = delays,
        getWindows      = { windows },
        getLastSnapshot = { lastSnapshot },
        openShade       = { actionNotificationsShow(true) },
        closeShade      = { actionNotificationsShow(false) },
    )

    private val debugScan = DebugShadeScan(
        delays          = delays,
        getWindows      = { windows },
        getLastSnapshot = { lastSnapshot },
        openShade       = { actionNotificationsShow(true) },
        closeShade      = { actionNotificationsShow(false) },
    )

    fun findRowForAppLabel(
        appLabel: String,
        shadeAlreadyOpen: Boolean,
        callback: (List<NotificationShadeSnapshot.ShadeRow>?) -> Unit,
    ) = searchQueue.findRowForAppLabel(appLabel, shadeAlreadyOpen, callback)

    fun debugFullShadeScan() = debugScan.debugFullShadeScan()

    // -------------------------------------------------------------------------
    // Accessibility events
    // -------------------------------------------------------------------------

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return

        //
        // For privacy/security reasons we only process `com.android.systemui` events
        //
        if (packageName != NotificationShadeSnapshot.COM_ANDROID_SYSTEMUI) return

        //
        // For privacy/security reasons we only process notification relevant events
        //
        if (event.eventType !in ACCESSIBILITY_EVENTS_NOTIFICATION_RELEVANT) return

        val eventTypeName = AccessibilityEvent.eventTypeToString(event.eventType)
        val root = event.source?.window?.root ?: run {
            if (VERBOSE_LOG_ROOT_NULL) {
                FooLog.v(TAG, "#ACCESSIBILITY onAccessibilityEvent[$eventTypeName]: source window root is null")
            }
            return
        }

        val snapshot = NotificationShadeSnapshot.snapshotShade(eventTypeName, root)
        // Debounce (ignore) identical snapshot collections
        if (snapshot == lastSnapshot) return
        lastSnapshot = snapshot

        if (snapshot.isEmpty()) {
            if (VERBOSE_LOG_SHADE_EMPTY) {
                FooLog.v(TAG, "#ACCESSIBILITY onAccessibilityEvent[$eventTypeName]: shade snapshot (empty)")
            }
        } else {
            val sb = StringBuilder()
            sb.append("#ACCESSIBILITY onAccessibilityEvent[$eventTypeName]: shade snapshot (${snapshot.size} rows):")
            snapshot.forEachIndexed { i, row -> sb.append("\n  [$i] $row") }
            FooLog.v(TAG, sb.toString())
        }

        searchQueue.advancePendingRowSearch()
    }
}
