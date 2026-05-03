package llc.lookatwhataicando.notifai

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import com.smartfoo.android.core.logging.FooLog

/**
 * The app's Accessibility is disabled when launched or stopped from Android Studio! :/
 * To launch and keep Accessibility enabled:
 * ```
 * adb shell am start -n llc.lookatwhataicando.notifai/.MainActivity
 * ```
 * Or, just follow the permissions check dialog during launch to re-enable.
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

        private val VERBOSE_LOG_ROOT_NULL = false
        private val VERBOSE_LOG_SHADE_EMPTY = false
        private val VERBOSE_LOG_NOTIFICATION_CONTAINER_ID_NOT_FOUND = false
        private val VERBOSE_LOG_SHADE_ROW_EMPTY = false

        private const val COM_ANDROID_SYSTEMUI = "com.android.systemui"

        private val ACCESSIBILITY_EVENTS_NOTIFICATION_RELEVANT = setOf(
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
        )

        /**
         * When true: dump every window and its full node tree to logcat so the actual
         * accessibility hierarchy can be observed and understood.
         * When false: use targeted container-finding + structured row extraction.
         *
         * Start with true to explore; switch to false once the structure is known.
         */
        private const val DEBUG_DUMP_WINDOWS = false

        /**
         * Known notification shade container view IDs, ordered by preference.
         * First match wins. When the heuristic fallback fires instead, it logs the actual
         * ID found — paste that into this list to avoid the heuristic cost on future runs.
         *
         * To add a new OEM: run with DEBUG_DUMP_WINDOWS = true, find the scrollable container
         * that holds the notification rows, and add its full viewIdResourceName here.
         */
        private val NOTIFICATION_CONTAINER_IDS = listOf(
            "com.android.systemui:id/notification_stack_scroller", // AOSP / Pixel (confirmed)
            "com.android.systemui:id/notification_panel",          // unconfirmed OEM variant
            "com.android.systemui:id/notification_list",           // unconfirmed OEM variant
        )

        /**
         * Known notification row view ID suffixes, ordered by preference.
         * Suffix matching (not full ID) tolerates differing package prefixes across OEMs.
         * First suffix that yields at least one matching child wins. When the heuristic
         * fallback fires instead, it logs the actual suffix found — add it here.
         *
         * To add a new OEM: run with DEBUG_DUMP_WINDOWS = true, find the direct children
         * of the container that represent individual notifications, and add the suffix of
         * their viewIdResourceName here.
         */
        private val NOTIFICATION_ROW_ID_SUFFIXES = listOf(
            "expandableNotificationRow", // AOSP / Pixel (confirmed)
        )
    }

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

    override fun onCreate() {
        FooLog.v(TAG, "onCreate()")
        super.onCreate()
    }

    override fun onDestroy() {
        FooLog.v(TAG, "onDestroy()")
        super.onDestroy()
        instance = null
    }

    override fun onServiceConnected() {
        FooLog.v(TAG, "onServiceConnected()")
        super.onServiceConnected()
        instance = this
    }

    override fun onInterrupt() {
        FooLog.v(TAG, "onInterrupt()")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return

        //
        // For privacy/security reasons we only process `com.android.systemui` events
        //
        if (packageName != COM_ANDROID_SYSTEMUI) return

        //
        // For privacy/security reasons we only process notification relevant events
        //
        if (event.eventType !in ACCESSIBILITY_EVENTS_NOTIFICATION_RELEVANT) return

        val eventTypeName = AccessibilityEvent.eventTypeToString(event.eventType)
        val root = event.source?.window?.root ?: run {
            if (VERBOSE_LOG_ROOT_NULL) {
                FooLog.v(TAG, "onAccessibilityEvent[$eventTypeName]: source window root is null")
            }
            return
        }

        val snapshot = snapshotShade(eventTypeName, root)
        // Debounce (ignore) identical snapshot collections
        if (snapshot == lastSnapshot) return
        lastSnapshot = snapshot

        if (snapshot.isEmpty()) {
            if (VERBOSE_LOG_SHADE_EMPTY) {
                FooLog.v(TAG, "onAccessibilityEvent[$eventTypeName]: shade snapshot (empty)")
            }
            return
        }

        val sb = StringBuilder()
        sb.append("onAccessibilityEvent[$eventTypeName]: shade snapshot (${snapshot.size} rows):")
        snapshot.forEachIndexed { i, row ->
            sb.append("\n  [$i] $row")
        }
        FooLog.v(TAG, sb.toString())
    }

    // Logs one line per node so logcat line-length limits never truncate the tree.
    private fun dumpNode(node: AccessibilityNodeInfo, depth: Int) {
        val indent = "  ".repeat(depth)
        val id = node.viewIdResourceName?.removePrefix("$COM_ANDROID_SYSTEMUI:id/") ?: ""
        val cls = node.className?.toString()?.substringAfterLast('.') ?: ""
        val text = node.text?.toString()?.replace('\n', '↵') ?: ""
        val desc = node.contentDescription?.toString()?.replace('\n', '↵') ?: ""
        val flags = buildString {
            if (node.isClickable)    append("click ")
            if (node.isScrollable)   append("scroll ")
            if (node.isCheckable)    append("check ")
            if (node.isEnabled)      append("enabled ")
            if (node.isVisibleToUser) append("visible ")
        }.trimEnd()

        val line = buildString {
            append("$indent[$cls] id=$id")
            if (text.isNotEmpty()) append(" text=\"$text\"")
            if (desc.isNotEmpty()) append(" desc=\"$desc\"")
            if (flags.isNotEmpty()) append(" [$flags]")
        }
        FooLog.v(TAG, line)

        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { dumpNode(it, depth + 1) }
        }
    }

    // -------------------------------------------------------------------------
    // Structured snapshot (used when DEBUG_DUMP_WINDOWS = false)
    // -------------------------------------------------------------------------

    /**
     * Structured snapshot of a single notification row in the notification shade.
     *
     * Fields are extracted by view ID suffix — suffix matching tolerates OEM/android:id/ variations.
     *   appName  — from .../app_name_text  (e.g. "Discord", "LOCKLY")
     *   title    — from .../title or .../notification_title  (channel, contact, or subject)
     *   sender   — from .../message_name  (individual sender in a group/messaging notification)
     *   messages — all .../message_text values in order  (one per bubble line)
     *   text     — from .../notification_text or .../big_text or .../text when messages is empty
     */
    data class ShadeRow(
        val appName: String?,
        val title: String?,
        val sender: String?,
        val messages: List<String>,
        val text: String?,
    ) {
        val isEmpty: Boolean get() = appName == null && title == null && sender == null && messages.isEmpty() && text == null
    }

    private var lastSnapshot: List<ShadeRow> = emptyList()

    private fun snapshotShade(eventTypeName: String, root: AccessibilityNodeInfo): List<ShadeRow> {
        if (DEBUG_DUMP_WINDOWS) {
            FooLog.v(TAG, "snapshotShade[$eventTypeName]: pkg=${root.packageName} id=${root.viewIdResourceName}")
            dumpNode(root, depth = 0)
        }

        val container = findNotificationContainer(root) ?: return emptyList()
        val rowNodes = findNotificationRows(container)
        val rows = rowNodes.mapNotNull { extractRowData(it).takeUnless { row -> row.isEmpty } }
        if (rows.isEmpty() && VERBOSE_LOG_SHADE_ROW_EMPTY) {
            FooLog.v(TAG, "snapshotShade: container found but all rows were empty (childCount=${container.childCount})")
        }
        return rows
    }

    /**
     * Finds the notification shade container in the accessibility tree.
     *
     * Tier 1 — known IDs: tries each entry in [NOTIFICATION_CONTAINER_IDS] via fast view-ID lookup.
     * Tier 2 — heuristic: walks the tree looking for the first scrollable node whose direct
     *   children are predominantly clickable (a reliable structural signature of a notification list).
     *   Logs the discovered ID so it can be added to [NOTIFICATION_CONTAINER_IDS] to skip the
     *   heuristic on future runs on the same device.
     */
    private fun findNotificationContainer(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (id in NOTIFICATION_CONTAINER_IDS) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            if (nodes.isNotEmpty()) {
                //FooLog.v(TAG, "findNotificationContainer: known id=$id")
                return nodes.first()
            }
        }

        // Heuristic fallback: find the first scrollable node with mostly-clickable children.
        val found = findNotificationContainerHeuristic(root)
        if (found != null) {
            FooLog.w(TAG, "findNotificationContainer: heuristic match id=${found.viewIdResourceName} — add to NOTIFICATION_CONTAINER_IDS")
        } else if (VERBOSE_LOG_NOTIFICATION_CONTAINER_ID_NOT_FOUND) {
            FooLog.w(TAG, "findNotificationContainer: no container found")
        }
        return found
    }

    private fun findNotificationContainerHeuristic(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable) {
            val childCount = node.childCount
            if (childCount > 0) {
                val clickable = (0 until childCount).count { node.getChild(it)?.isClickable == true }
                if (clickable * 2 >= childCount) return node  // majority clickable
            }
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { findNotificationContainerHeuristic(it) }?.let { return it }
        }
        return null
    }

    /**
     * Returns the direct children of [container] that represent individual notification rows.
     *
     * Tier 1 — known suffixes: tries each entry in [NOTIFICATION_ROW_ID_SUFFIXES].
     * Tier 2 — heuristic: falls back to all clickable direct children.
     *   Logs the discovered suffix so it can be added to [NOTIFICATION_ROW_ID_SUFFIXES].
     */
    private fun findNotificationRows(container: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        for (suffix in NOTIFICATION_ROW_ID_SUFFIXES) {
            val matched = (0 until container.childCount)
                .mapNotNull { container.getChild(it) }
                .filter { it.viewIdResourceName?.endsWith(suffix) == true }
            if (matched.isNotEmpty()) {
                //FooLog.v(TAG, "findNotificationRows: known suffix=$suffix (${matched.size} rows)")
                return matched
            }
        }

        // Heuristic fallback: clickable direct children.
        val matched = (0 until container.childCount)
            .mapNotNull { container.getChild(it) }
            .filter { it.isClickable }
        if (matched.isNotEmpty()) {
            val sampleId = matched.first().viewIdResourceName ?: "<no-id>"
            val suffix = sampleId.substringAfterLast('/')
            FooLog.w(TAG, "findNotificationRows: heuristic match (clickable children, sample suffix=$suffix) — add to NOTIFICATION_ROW_ID_SUFFIXES")
        }
        return matched
    }

    /**
     * Walks a notification row's subtree and extracts structured fields by view ID suffix.
     * Suffix matching tolerates both `android:id/` and `com.android.systemui:id/` prefixes.
     *
     * Observed structure (Pixel, Android 15):
     *   android:id/app_name_text  — source app label
     *   android:id/title          — channel / contact / subject
     *   android:id/message_name   — sender within a group/messaging notification
     *   android:id/message_text   — one per message bubble (may be multiple)
     *   android:id/big_text       — expanded single-message body
     *   android:id/text           — compact single-line body
     *   notification_title        — title for apps using custom notification layouts (e.g. LOCKLY)
     *   notification_text         — body for apps using custom notification layouts
     */
    private fun extractRowData(row: AccessibilityNodeInfo): ShadeRow {
        var appName: String? = null
        var title: String? = null
        var sender: String? = null
        val messages = mutableListOf<String>()
        var text: String? = null

        fun walk(node: AccessibilityNodeInfo) {
            val id = node.viewIdResourceName
            val nodeText = node.text?.toString()
            if (!nodeText.isNullOrBlank()) {
                when {
                    id?.endsWith("/app_name_text")    == true -> appName = nodeText
                    id?.endsWith("/title")             == true -> if (title == null) title = nodeText
                    id?.endsWith("/notification_title")== true -> if (title == null) title = nodeText
                    id?.endsWith("/message_name")      == true -> sender = nodeText
                    id?.endsWith("/message_text")      == true -> messages.add(nodeText)
                    id?.endsWith("/big_text")          == true -> if (text == null) text = nodeText
                    id?.endsWith("/notification_text") == true -> if (text == null) text = nodeText
                    id?.endsWith("/text")              == true -> if (text == null) text = nodeText
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { walk(it) }
            }
        }

        walk(row)

        return ShadeRow(
            appName  = appName,
            title    = title,
            sender   = sender,
            messages = messages,
            text     = text,
        )
    }
}
