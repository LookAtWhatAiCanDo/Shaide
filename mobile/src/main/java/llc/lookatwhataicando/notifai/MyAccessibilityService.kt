package llc.lookatwhataicando.notifai

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
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
 * ### Launch / catch-up (app just started)
 * When the app launches and iterates `getActiveNotifications()`, the content-bearing child
 * notifications may already be gone — consumed/dismissed after the previous app instance
 * processed them, or simply never re-posted. Only the empty `GROUP_SUMMARY` remains. NLS cannot
 * read content from it. **This is the primary scenario where `MyAccessibilityService` is
 * required:** it opens the notification shade, expands any collapsed rows, and reads their
 * content via the accessibility tree so the app can speak notifications it missed.
 *
 * ### Secondary capability
 * Provides global navigation actions (open/dismiss shade, back, home, screenshot, media
 * play/pause) that NLS does not expose.
 *
 * ### Practical impact without this permission
 * Live notifications work correctly. Only the launch catch-up path for "obscured" notifications
 * (see [llc.lookatwhataicando.notifai.notification.ObscuredNotification]) is degraded — the
 * app silently skips any notification whose content was only ever in the shade.
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

        private val VERBOSE_LOG_ROOT_NULL = false
        private val VERBOSE_LOG_SHADE_EMPTY = false
        private val VERBOSE_LOG_NOTIFICATION_CONTAINER_ID_NOT_FOUND = false

        /**
         * Slows the accessibility state machine for step-by-step visual observation.
         *
         * When true:
         *   - [SHADE_OPEN_SETTLE_DELAY_MS] uses [DEBUG_SLOW_SHADE_SETTLE_MS] so the shade is
         *     fully visible before scanning begins.
         *   - [DEBUG_PRE_EXPAND_DELAY_MS] pauses before [AccessibilityNodeInfo.ACTION_EXPAND] so
         *     the collapsed state can be observed.
         *   - [DEBUG_PRE_CLOSE_DELAY_MS] holds the shade open after content is found so the
         *     expanded state can be observed before dismissal.
         *
         * Set false and tune the fast constants once minimum working delays are known.
         */
        private const val DEBUG_SLOW_MODE = true

        // Fast (production) values
        private const val FAST_SHADE_SETTLE_MS     = 600L
        private const val FAST_PRE_EXPAND_DELAY_MS = 0L
        private const val FAST_SCROLL_SETTLE_MS    = 0L
        private const val FAST_PRE_CLOSE_DELAY_MS  = 0L

        // Slow (observation) values — used when DEBUG_SLOW_MODE = true
        private const val DEBUG_SLOW_SHADE_SETTLE_MS     = 3000L
        private const val DEBUG_SLOW_PRE_EXPAND_DELAY_MS = 2000L
        private const val DEBUG_SLOW_SCROLL_SETTLE_MS    = 1500L
        private const val DEBUG_SLOW_PRE_CLOSE_DELAY_MS  = 4000L

        /**
         * When true, any [MyNotificationListenerService] ParsedIgnored notification triggers a
         * full top-to-bottom shade scan instead of the normal app-label search.
         *
         * The scan: opens shade → expands every collapsed row → attempts scroll → repeats until
         * no new rows appear → logs every [ShadeRow] found → closes shade.
         *
         * Use to:
         *  - Verify whether [AccessibilityNodeInfo.ACTION_SCROLL_FORWARD] actually scrolls the shade
         *  - Observe the complete accessibility tree structure of all current notifications
         *  - Diagnose why Carol Micek (or any second conversation) is not appearing after expand
         *
         * No TTS is spoken. Disable when switching back to the normal search path.
         */
        const val DEBUG_FULL_SCAN_MODE = false

        // How long to wait for expand animations to finish before re-snapshotting
        private const val DEBUG_SCAN_EXPAND_SETTLE_MS = 1000L
        // How long to wait after a scroll before checking whether new rows appeared
        private const val DEBUG_SCAN_SCROLL_SETTLE_MS = 500L

        private const val COM_ANDROID_SYSTEMUI = "com.android.systemui"

        private val ACCESSIBILITY_EVENTS_NOTIFICATION_RELEVANT = setOf(
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
        )

        /**
         * When true: dump every node to logcat so the accessibility hierarchy can be observed.
         * When false: short-circuit traversal once the container and rows are found.
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
         * When the heuristic fallback fires instead, it logs the actual suffix found — add it here.
         *
         * To add a new OEM: run with DEBUG_DUMP_WINDOWS = true, find the direct children
         * of the container that represent individual notifications, and add the suffix of
         * their viewIdResourceName here.
         */
        private val NOTIFICATION_ROW_ID_SUFFIXES = listOf(
            "expandableNotificationRow", // AOSP / Pixel (confirmed)
        )

        /** Content-description on the chevron button of a collapsed notification row. */
        private const val EXPAND_BUTTON_DESC = "Expand"
        /** Content-description on the chevron button of an already-expanded notification row. */
        private const val COLLAPSE_BUTTON_DESC = "Collapse"

        /** How long to wait after opening the shade before starting the search (ms). */
        val SHADE_OPEN_SETTLE_DELAY_MS: Long = if (DEBUG_SLOW_MODE) DEBUG_SLOW_SHADE_SETTLE_MS else FAST_SHADE_SETTLE_MS
        /** How long to pause before/after each row expansion so the animation is visually observable. */
        val DEBUG_PRE_EXPAND_DELAY_MS: Long  = if (DEBUG_SLOW_MODE) DEBUG_SLOW_PRE_EXPAND_DELAY_MS else FAST_PRE_EXPAND_DELAY_MS
        /** How long to pause after a scroll before repopulating the expand queue. */
        val SCROLL_SETTLE_DELAY_MS: Long     = if (DEBUG_SLOW_MODE) DEBUG_SLOW_SCROLL_SETTLE_MS else FAST_SCROLL_SETTLE_MS
        val DEBUG_PRE_CLOSE_DELAY_MS: Long   = if (DEBUG_SLOW_MODE) DEBUG_SLOW_PRE_CLOSE_DELAY_MS else FAST_PRE_CLOSE_DELAY_MS

        private const val MAX_SCROLL_ATTEMPTS = 10
        // Max individual row expansions per scroll cycle (one row expanded per pass).
        private const val MAX_EXPAND_PASSES_PER_SCROLL = 20
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

    // -------------------------------------------------------------------------
    // Accessibility events
    // -------------------------------------------------------------------------

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
        } else {
            val sb = StringBuilder()
            sb.append("onAccessibilityEvent[$eventTypeName]: shade snapshot (${snapshot.size} rows):")
            snapshot.forEachIndexed { i, row ->
                sb.append("\n  [$i] $row")
            }
            FooLog.v(TAG, sb.toString())
        }

        // Advance the pending row search state machine (if active) on every snapshot update.
        advancePendingRowSearch(root)
    }

    // -------------------------------------------------------------------------
    // Structured snapshot
    // -------------------------------------------------------------------------

    /**
     * Structured snapshot of a single notification row in the notification shade.
     *
     * Fields are extracted by view ID suffix — suffix matching tolerates OEM/android:id/ variations.
     *   appName  — from .../app_name_text  (e.g. "Discord", "Chat")
     *   title    — from .../title or .../notification_title  (channel, contact, or subject)
     *   sender   — from .../message_name  (individual sender in a group/messaging notification)
     *   messages — all .../message_text values in order  (one per bubble line)
     *   text     — from .../notification_text or .../big_text or .../text when messages is empty
     *   time     — from .../time (Android provides the long form "6 hours ago" vs the UI "6h")
     */
    data class ShadeRow(
        val appName: String?,
        val title: String?,
        val sender: String?,
        val messages: List<String>,
        val text: String?,
        val time: String?,
    ) {
        val isEmpty: Boolean get() = appName == null && title == null && sender == null && messages.isEmpty() && text == null
    }

    private var lastSnapshot: List<ShadeRow> = emptyList()

    /**
     * Single DFS from [root] that simultaneously dumps (when [DEBUG_DUMP_WINDOWS] is true),
     * locates the notification shade container, and collects row nodes.
     *
     * Collecting rows during this traversal — rather than in a separate pass — is essential.
     * Each call to [AccessibilityNodeInfo.getChild] can return a new pooled wrapper; a second
     * call for the same child index may yield a stale wrapper with an incomplete childCount.
     * Row nodes obtained here (first access) have correct childCount, so [extractRowData] can
     * reach all siblings including app_name_text and time without needing refresh().
     *
     * Container search: Tier 1 = known IDs in [NOTIFICATION_CONTAINER_IDS].
     *                   Tier 2 = structural heuristic (scrollable, majority-clickable children).
     * Row collection:   Tier 1 = direct children matching [NOTIFICATION_ROW_ID_SUFFIXES].
     *                   Tier 2 = all clickable direct children of the container.
     */
    private fun snapshotShade(eventTypeName: String, root: AccessibilityNodeInfo): List<ShadeRow> {
        if (DEBUG_DUMP_WINDOWS) {
            FooLog.v(TAG, "snapshotShade[$eventTypeName]: pkg=${root.packageName} id=${root.viewIdResourceName}")
        }

        val rowNodes = mutableListOf<AccessibilityNodeInfo>()

        fun logNode(node: AccessibilityNodeInfo, depth: Int) {
            val indent = "  ".repeat(depth)
            val id = node.viewIdResourceName?.removePrefix("$COM_ANDROID_SYSTEMUI:id/") ?: ""
            val cls = node.className?.toString()?.substringAfterLast('.') ?: ""
            val text = node.text?.toString()?.replace('\n', '↵') ?: ""
            val desc = node.contentDescription?.toString()?.replace('\n', '↵') ?: ""
            val flags = buildString {
                if (node.isClickable)     append("click ")
                if (node.isScrollable)    append("scroll ")
                if (node.isCheckable)     append("check ")
                if (node.isEnabled)       append("enabled ")
                if (node.isVisibleToUser) append("visible ")
            }.trimEnd()
            FooLog.v(TAG, buildString {
                append("$indent[$cls] id=$id")
                if (text.isNotEmpty()) append(" text=\"$text\"")
                if (desc.isNotEmpty()) append(" desc=\"$desc\"")
                if (flags.isNotEmpty()) append(" [$flags]")
            })
        }

        // Returns true when the container is found and rows are collected, so the caller can
        // short-circuit when DEBUG_DUMP_WINDOWS is false. When true, traversal continues for
        // the full dump but skips further container searching.
        fun traverse(node: AccessibilityNodeInfo, depth: Int): Boolean {
            if (DEBUG_DUMP_WINDOWS) logNode(node, depth)

            if (node.viewIdResourceName in NOTIFICATION_CONTAINER_IDS) {
                // Collect direct children as rows using the same first-access node objects.
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { child ->
                        if (NOTIFICATION_ROW_ID_SUFFIXES.any { child.viewIdResourceName?.endsWith(it) == true }) {
                            rowNodes.add(child)
                        }
                        if (DEBUG_DUMP_WINDOWS) traverse(child, depth + 1)
                    }
                }
                return true
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                if (traverse(child, depth + 1) && !DEBUG_DUMP_WINDOWS) return true
            }
            return false
        }

        traverse(root, 0)

        // Heuristic fallback when no container was matched by known ID.
        if (rowNodes.isEmpty()) {
            val heuristic = findNotificationContainerHeuristic(root)
            if (heuristic != null) {
                FooLog.w(TAG, "snapshotShade: heuristic container id=${heuristic.viewIdResourceName} — add to NOTIFICATION_CONTAINER_IDS")
                val matched = (0 until heuristic.childCount)
                    .mapNotNull { heuristic.getChild(it) }
                    .filter { it.isClickable }
                if (matched.isNotEmpty()) {
                    val sampleSuffix = (matched.first().viewIdResourceName ?: "<no-id>").substringAfterLast('/')
                    FooLog.w(TAG, "snapshotShade: heuristic rows sample suffix=$sampleSuffix — add to NOTIFICATION_ROW_ID_SUFFIXES")
                    rowNodes.addAll(matched)
                }
            } else if (VERBOSE_LOG_NOTIFICATION_CONTAINER_ID_NOT_FOUND) {
                FooLog.w(TAG, "snapshotShade: no notification container found")
            }
        }

        return rowNodes.mapNotNull { extractRowData(it).takeUnless { row -> row.isEmpty } }
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
        var time: String? = null

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
                    id?.endsWith("/time")              == true -> if (time == null) time = nodeText
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
            time     = time,
        )
    }

    // -------------------------------------------------------------------------
    // PendingRowSearch — expand-scan state machine
    // -------------------------------------------------------------------------

    /**
     * State for a single in-flight search for a notification row by app label.
     *
     * @param appLabel             Human-readable label to match against [ShadeRow.appName].
     * @param callback             Invoked on the main thread with all found rows (or null if none).
     * @param shadeOpenedByUs      True if this search opened the shade; we should close it on finish.
     * @param readyToScan          False while waiting for the shade-open settle delay; true once the
     *                             settle runnable fires and populates [rowsToExpand]. Blocks
     *                             [advancePendingRowSearch] from entering expand/scroll logic
     *                             prematurely on events that fire during the settle window.
     * @param rowsToExpand         Live row nodes queued for [AccessibilityNodeInfo.ACTION_EXPAND].
     *                             Populated by the settle runnable (not pre-open) so nodes are fresh.
     *                             Each is tried once; the next event re-checks for the match.
     * @param scrollAttemptsLeft   Number of remaining [AccessibilityNodeInfo.ACTION_SCROLL_FORWARD]
     *                             calls allowed before giving up on off-screen rows.
     * @param rowExpanded          True after we have already performed one expand on the target row.
     *                             Prevents re-expanding on the next snapshot check.
     * @param settling             True while a [mainHandler.postDelayed] settle window is in-flight
     *                             (pre-expand pause, post-expand settle, or post-scroll settle).
     *                             Blocks [advancePendingRowSearch] from reacting to events during
     *                             that window so the animation is fully visible before we continue.
     */
    private data class PendingRowSearch(
        val appLabel: String,
        val callback: (List<ShadeRow>?) -> Unit,
        var shadeOpenedByUs: Boolean,
        var readyToScan: Boolean,
        val rowsToExpand: MutableList<AccessibilityNodeInfo> = mutableListOf(),
        var scrollAttemptsLeft: Int = MAX_SCROLL_ATTEMPTS,
        var rowExpanded: Boolean = false,
        var settling: Boolean = false,
    )

    /**
     * Queue of in-flight and waiting app-label searches. The head is the active search; all
     * others are waiting for the active one to complete before the shade is handed to them.
     * Using a queue instead of a single slot prevents concurrent [findRowForAppLabel] calls
     * (e.g. Chat and Google both firing at startup within 30 ms) from silently cancelling each
     * other.
     */
    private val pendingSearchQueue: ArrayDeque<PendingRowSearch> = ArrayDeque()
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Initiates an asynchronous search for a shade row whose [ShadeRow.appName] matches [appLabel].
     *
     * Called from [MyNotificationListenerService] after the 300 ms window expires with no
     * non-empty sibling notification from the same package.
     *
     * If another search is already active the new search is queued and starts automatically
     * when the active one completes ([finishSearch] hands the open shade to the next entry).
     * This prevents multiple near-simultaneous [findRowForAppLabel] calls — e.g. Chat and
     * Google firing at startup within 30 ms — from silently cancelling each other.
     *
     * Flow:
     *  1. If queue non-empty: enqueue and return (will be started by [finishSearch]).
     *  2. Otherwise: open shade (if needed), wait [SHADE_OPEN_SETTLE_DELAY_MS], then let
     *     [advancePendingRowSearch] drive expand → match → callback.
     *
     * @param appLabel          Label to search for (e.g. "Chat").
     * @param shadeAlreadyOpen  True if the shade is already visible (skip open + settle delay).
     * @param callback          Called on the main thread with the matched rows or null.
     */
    fun findRowForAppLabel(
        appLabel: String,
        shadeAlreadyOpen: Boolean,
        callback: (List<ShadeRow>?) -> Unit,
    ) {
        val queueSize = pendingSearchQueue.size
        FooLog.i(TAG, "findRowForAppLabel: appLabel=$appLabel shadeAlreadyOpen=$shadeAlreadyOpen queueSize=$queueSize")

        // Shade is considered already open when another search has it open.
        val shadeIsOpen = shadeAlreadyOpen || pendingSearchQueue.isNotEmpty()
        val search = PendingRowSearch(
            appLabel        = appLabel,
            callback        = callback,
            shadeOpenedByUs = !shadeIsOpen,
            readyToScan     = shadeIsOpen,
        )

        if (pendingSearchQueue.isNotEmpty()) {
            // Another search is active — queue and wait.
            FooLog.i(TAG, "findRowForAppLabel: queuing $appLabel behind active search for ${pendingSearchQueue.first().appLabel}")
            pendingSearchQueue.addLast(search)
            return
        }

        pendingSearchQueue.addLast(search)

        if (!shadeAlreadyOpen) {
            actionNotificationsShow(true)
            // After the shade animates open, collect fresh row nodes and begin scanning.
            // rowsToExpand intentionally NOT pre-populated: nodes collected before the shade
            // is open would be stale / empty.
            mainHandler.postDelayed({
                val s = pendingSearchQueue.firstOrNull() ?: return@postDelayed
                if (s !== search) return@postDelayed
                s.rowsToExpand.addAll(getLiveRowNodes())
                s.readyToScan = true
                tryExpandNextRow(s)
            }, SHADE_OPEN_SETTLE_DELAY_MS)
        } else {
            search.rowsToExpand.addAll(getLiveRowNodes())
            tryExpandNextRow(search)
        }
    }

    /**
     * Called from [onAccessibilityEvent] after every snapshot update while a search is active.
     *
     * Re-checks for a match, then tries the next expand or scroll as needed.
     */
    private fun advancePendingRowSearch(root: AccessibilityNodeInfo) {
        val search = pendingSearchQueue.firstOrNull() ?: return

        // A settle window is in-flight (pre/post-expand or post-scroll); ignore events until it clears.
        if (search.settling) return

        // Check if the latest snapshot now has our target.
        val match = lastSnapshot.firstOrNull { it.appName.equals(search.appLabel, ignoreCase = true) }
        if (match != null) {
            if (!search.rowExpanded) {
                // The row may be a collapsed group summary ("2 v" button). Expand it first so we
                // capture all child notifications, not just the first summary line.
                // Use findDirectRowButton (not hasExpandButtonInSubtree) so we don't mistake a
                // child row's Expand chevron for the parent's — which would toggle the parent back
                // to collapsed via ACTION_EXPAND, causing an expand/collapse loop.
                val rawNode = findRawRowWithAppName(search.appLabel)
                val expandBtn = rawNode?.let { findDirectRowButton(it, EXPAND_BUTTON_DESC) }
                if (expandBtn != null) {
                    if (DEBUG_PRE_EXPAND_DELAY_MS > 0) {
                        FooLog.v(TAG, "advancePendingRowSearch: pausing ${DEBUG_PRE_EXPAND_DELAY_MS}ms before expanding ${search.appLabel}")
                        search.settling = true
                        mainHandler.postDelayed({
                            val s = pendingSearchQueue.firstOrNull() ?: return@postDelayed
                            if (s !== search) return@postDelayed
                            s.settling = false
                            val freshBtn = findRawRowWithAppName(s.appLabel)?.let { findDirectRowButton(it, EXPAND_BUTTON_DESC) }
                            if (freshBtn != null) {
                                FooLog.v(TAG, "advancePendingRowSearch: now expanding ${s.appLabel}")
                                freshBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                s.rowExpanded = true
                            }
                        }, DEBUG_PRE_EXPAND_DELAY_MS)
                    } else {
                        FooLog.v(TAG, "advancePendingRowSearch: expanding ${search.appLabel} before reading content")
                        expandBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        search.rowExpanded = true
                    }
                    return
                }
            }
            val allMatches = lastSnapshot.filter { it.appName.equals(search.appLabel, ignoreCase = true) }
            FooLog.i(TAG, "advancePendingRowSearch: found ${search.appLabel} (${allMatches.size} rows)")
            finishSearch(allMatches)
            return
        }

        // Don't attempt expand/scroll until the shade-open settle delay has fired.
        // Events during the settle window (shade animating) would find rowsToExpand empty
        // and prematurely fall through to scroll → finishSearch(null).
        if (!search.readyToScan) return

        if (search.rowsToExpand.isNotEmpty()) {
            // Previous expand fired an event — check next queued row.
            tryExpandNextRow(search)
        } else if (search.scrollAttemptsLeft > 0) {
            // No more rows to expand in view — scroll to reveal more.
            val container = findContainerNode(root)
            if (container != null && container.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
                search.scrollAttemptsLeft--
                FooLog.v(TAG, "advancePendingRowSearch: scrolled (${search.scrollAttemptsLeft} attempts left)")
                if (SCROLL_SETTLE_DELAY_MS > 0) {
                    search.settling = true
                    mainHandler.postDelayed({
                        val s = pendingSearchQueue.firstOrNull() ?: return@postDelayed
                        if (s !== search) return@postDelayed
                        s.settling = false
                        s.rowsToExpand.clear()
                        s.rowsToExpand.addAll(getLiveRowNodes())
                    }, SCROLL_SETTLE_DELAY_MS)
                } else {
                    search.rowsToExpand.addAll(getLiveRowNodes())
                }
            } else {
                FooLog.i(TAG, "advancePendingRowSearch: scroll exhausted or container null — giving up on ${search.appLabel}")
                finishSearch()
            }
        } else {
            FooLog.i(TAG, "advancePendingRowSearch: all rows tried, no match for ${search.appLabel}")
            finishSearch()
        }
    }

    /**
     * Pops the next row from [PendingRowSearch.rowsToExpand] and clicks its direct Expand chevron
     * if it is collapsed. Uses [findDirectRowButton] (not [hasExpandButtonInSubtree]) so we don't
     * descend into nested [expandableNotificationRow] children and find their chevrons instead.
     * Already-expanded rows are skipped.
     */
    private fun tryExpandNextRow(search: PendingRowSearch) {
        while (search.rowsToExpand.isNotEmpty()) {
            val row = search.rowsToExpand.removeAt(0)
            val expandBtn = findDirectRowButton(row, EXPAND_BUTTON_DESC)
            if (expandBtn != null) {
                val clicked = expandBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                FooLog.v(TAG, "tryExpandNextRow: ACTION_CLICK on Expand result=$clicked for ${row.viewIdResourceName}")
                if (DEBUG_PRE_EXPAND_DELAY_MS > 0) {
                    // Hold off event processing so the expand animation is visually observable.
                    search.settling = true
                    mainHandler.postDelayed({
                        val s = pendingSearchQueue.firstOrNull() ?: return@postDelayed
                        if (s !== search) return@postDelayed
                        s.settling = false
                    }, DEBUG_PRE_EXPAND_DELAY_MS)
                }
                return
            }
            // Row is already expanded — keep popping.
        }
        // All visible rows tried without needing expansion; check snapshot immediately.
        val allMatches = lastSnapshot.filter { it.appName.equals(search.appLabel, ignoreCase = true) }
        if (allMatches.isNotEmpty()) {
            FooLog.i(TAG, "tryExpandNextRow: match found without expanding for ${search.appLabel}")
            finishSearch(allMatches)
        }
        // Otherwise wait for next event (scroll may still deliver more rows).
    }

    private fun finishSearch(results: List<ShadeRow> = emptyList()) {
        val search = pendingSearchQueue.removeFirstOrNull() ?: return
        val nextSearch = pendingSearchQueue.firstOrNull()
        FooLog.i(TAG, "finishSearch: appLabel=${search.appLabel} rows=${results.size} queueRemaining=${pendingSearchQueue.size}")

        if (nextSearch != null) {
            // Hand the open shade to the next search immediately (no close/reopen cycle).
            // Transfer shade-close responsibility so the last search in the chain closes it.
            if (search.shadeOpenedByUs) nextSearch.shadeOpenedByUs = true
            nextSearch.readyToScan = true
            nextSearch.rowsToExpand.addAll(getLiveRowNodes())
            tryExpandNextRow(nextSearch)
            search.callback(results.takeIf { it.isNotEmpty() })
        } else {
            val closeAndCallback = {
                if (search.shadeOpenedByUs) actionNotificationsShow(false)
                search.callback(results.takeIf { it.isNotEmpty() })
            }
            if (DEBUG_PRE_CLOSE_DELAY_MS > 0) {
                FooLog.v(TAG, "finishSearch: holding shade open ${DEBUG_PRE_CLOSE_DELAY_MS}ms for observation (DEBUG_SLOW_MODE)")
                mainHandler.postDelayed(closeAndCallback, DEBUG_PRE_CLOSE_DELAY_MS)
            } else {
                closeAndCallback()
            }
        }
    }

    /**
     * Walks the live accessibility tree to collect fresh first-access row nodes.
     * Uses the same logic as [snapshotShade] container-finding but returns raw nodes.
     */
    private fun getLiveRowNodes(): List<AccessibilityNodeInfo> {
        val windows = windows ?: return emptyList()
        for (window in windows) {
            val root = window.root ?: continue
            if (root.packageName?.toString() != COM_ANDROID_SYSTEMUI) continue
            val rows = mutableListOf<AccessibilityNodeInfo>()
            collectRowNodes(root, rows)
            if (rows.isNotEmpty()) return rows
        }
        return emptyList()
    }

    private fun collectRowNodes(node: AccessibilityNodeInfo, rows: MutableList<AccessibilityNodeInfo>): Boolean {
        if (node.viewIdResourceName in NOTIFICATION_CONTAINER_IDS) {
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { child ->
                    if (NOTIFICATION_ROW_ID_SUFFIXES.any { child.viewIdResourceName?.endsWith(it) == true }) {
                        rows.add(child)
                    }
                }
            }
            return true
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (collectRowNodes(child, rows)) return true
        }
        return false
    }

    private fun findContainerNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (root.viewIdResourceName in NOTIFICATION_CONTAINER_IDS) return root
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val found = findContainerNode(child)
            if (found != null) return found
        }
        return null
    }

    private fun findRawRowWithAppName(appLabel: String): AccessibilityNodeInfo? =
        getLiveRowNodes().firstOrNull { hasAppNameInSubtree(it, appLabel) }

    private fun hasAppNameInSubtree(node: AccessibilityNodeInfo, appLabel: String): Boolean {
        if (node.viewIdResourceName?.endsWith("/app_name_text") == true &&
            node.text?.toString()?.equals(appLabel, ignoreCase = true) == true) return true
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (hasAppNameInSubtree(child, appLabel)) return true
        }
        return false
    }

    /**
     * Finds the expand/collapse chevron button that belongs **directly** to this notification
     * row — i.e. the button whose [contentDescription] matches [desc] — without descending into
     * nested [expandableNotificationRow] children, which have their own independent chevrons.
     *
     * This prevents falsely detecting a child row's "Expand" button as belonging to an
     * already-expanded parent GROUP_SUMMARY, which would cause ACTION_EXPAND on the parent to
     * toggle it back to collapsed (expand→collapse→expand… loop).
     *
     * @param isRoot  true only for the top-level call; child expandableNotificationRow nodes
     *                stop recursion immediately.
     */
    private fun findDirectRowButton(
        node: AccessibilityNodeInfo,
        desc: String,
        isRoot: Boolean = true,
    ): AccessibilityNodeInfo? {
        if (!isRoot && NOTIFICATION_ROW_ID_SUFFIXES.any { node.viewIdResourceName?.endsWith(it) == true }) return null
        if (node.contentDescription?.toString() == desc) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findDirectRowButton(child, desc, isRoot = false)?.let { return it }
        }
        return null
    }

    private fun hasExpandButtonInSubtree(node: AccessibilityNodeInfo): Boolean {
        if (node.contentDescription?.toString() == EXPAND_BUTTON_DESC) return true
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (hasExpandButtonInSubtree(child)) return true
        }
        return false
    }

    private fun getLiveContainerNode(): AccessibilityNodeInfo? {
        val windows = windows ?: return null
        for (window in windows) {
            val root = window.root ?: continue
            if (root.packageName?.toString() != COM_ANDROID_SYSTEMUI) continue
            val container = findContainerNode(root)
            if (container != null) return container
        }
        return null
    }

    // -------------------------------------------------------------------------
    // Debug full shade scan (DEBUG_FULL_SCAN_MODE)
    // -------------------------------------------------------------------------

    private var debugScanActive = false

    /**
     * Opens the notification shade and performs a full top-to-bottom scan:
     * expands every collapsed row (looping until none remain), scrolls to reveal off-screen rows,
     * and logs every [ShadeRow] found. No app-label matching; no TTS.
     *
     * Guarded by [debugScanActive] so concurrent triggers are ignored.
     */
    fun debugFullShadeScan() {
        if (debugScanActive) {
            FooLog.d(TAG, "debugFullShadeScan: scan already in progress, ignoring")
            return
        }
        debugScanActive = true
        FooLog.i(TAG, "debugFullShadeScan: starting — opening shade")
        actionNotificationsShow(true)
        mainHandler.postDelayed(
            { debugScanPass(linkedSetOf(), MAX_SCROLL_ATTEMPTS, MAX_EXPAND_PASSES_PER_SCROLL) },
            SHADE_OPEN_SETTLE_DELAY_MS,
        )
    }

    /**
     * Expands collapsed rows one at a time, each separated by [DEBUG_SCAN_EXPAND_SETTLE_MS].
     *
     * Each call scans all visible rows via [findDirectRowButton]:
     *  - Already expanded ("Collapse" button found): log at WARN and skip.
     *  - Collapsed ("Expand" button found): log at ERROR, click the chevron, then
     *    schedule the next pass after [DEBUG_SCAN_EXPAND_SETTLE_MS] — then RETURN
     *    immediately so only one expansion fires per delay interval.
     *  - No chevron: silently skip.
     *
     * Once no collapsed row is found, proceeds to [debugScanCollect].
     * Guarded by [expandPassesLeft] to prevent an unbounded loop if an expand somehow
     * produces new collapsed rows indefinitely.
     */
    private fun debugScanPass(
        accumulated: LinkedHashSet<ShadeRow>,
        scrollAttemptsLeft: Int,
        expandPassesLeft: Int,
    ) {
        val rows = getLiveRowNodes()
        FooLog.v(TAG, "debugScanPass: ${rows.size} rows visible (expandPassesLeft=$expandPassesLeft)")
        rows.forEachIndexed { idx, rowNode ->
            val collapseBtn = findDirectRowButton(rowNode, COLLAPSE_BUTTON_DESC)
            if (collapseBtn != null) {
                //FooLog.v(TAG, "debugScanPass: [$idx] already expanded — skipping chevron")
                return@forEachIndexed
            }
            val expandBtn = findDirectRowButton(rowNode, EXPAND_BUTTON_DESC)
            if (expandBtn != null) {
                //FooLog.i(TAG, "debugScanPass: [$idx] collapsed — clicking expand chevron")
                expandBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (expandPassesLeft > 0) {
                    mainHandler.postDelayed(
                        { debugScanPass(accumulated, scrollAttemptsLeft, expandPassesLeft - 1) },
                        DEBUG_SCAN_EXPAND_SETTLE_MS,
                    )
                } else {
                    FooLog.w(TAG, "debugScanPass: expand pass limit reached")
                    debugScanCollect(accumulated, scrollAttemptsLeft)
                }
                return  // only one expansion per delay interval
            }
        }
        // No collapsed rows remain — proceed to collect.
        debugScanCollect(accumulated, scrollAttemptsLeft)
    }

    /**
     * Snapshot the current rows, merge into [accumulated] (deduplicating via [ShadeRow] equality),
     * then attempt a scroll.
     */
    private fun debugScanCollect(accumulated: LinkedHashSet<ShadeRow>, scrollAttemptsLeft: Int) {
        val snapshot = lastSnapshot
        val sb = StringBuilder("debugScanCollect: ${snapshot.size} rows in snapshot:")
        snapshot.forEachIndexed { i, row -> sb.append("\n  [$i] $row") }
        FooLog.i(TAG, sb.toString())
        accumulated.addAll(snapshot.filter { !it.isEmpty })
        val rowCountBeforeScroll = getLiveRowNodes().size
        debugScanScroll(accumulated, scrollAttemptsLeft, rowCountBeforeScroll)
    }

    /**
     * Attempt [AccessibilityNodeInfo.ACTION_SCROLL_FORWARD] on the container. After a short
     * settle, compare row count: if new rows appeared, continue scanning; otherwise finish.
     */
    private fun debugScanScroll(
        accumulated: LinkedHashSet<ShadeRow>,
        scrollAttemptsLeft: Int,
        rowCountBefore: Int,
    ) {
        if (scrollAttemptsLeft <= 0) {
            FooLog.i(TAG, "debugScanScroll: scroll attempts exhausted")
            debugScanFinish(accumulated)
            return
        }
        val container = getLiveContainerNode()
        if (container == null) {
            FooLog.w(TAG, "debugScanScroll: container not found")
            debugScanFinish(accumulated)
            return
        }
        val scrolled = container.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        FooLog.v(TAG, "debugScanScroll: ACTION_SCROLL_FORWARD=$scrolled ($scrollAttemptsLeft attempts left)")
        if (!scrolled) {
            FooLog.i(TAG, "debugScanScroll: scroll returned false — at bottom or scroll unsupported")
            debugScanFinish(accumulated)
            return
        }
        mainHandler.postDelayed({
            val rowCountAfter = getLiveRowNodes().size
            if (rowCountAfter <= rowCountBefore) {
                FooLog.i(TAG, "debugScanScroll: no new rows after scroll ($rowCountBefore → $rowCountAfter) — at bottom")
                debugScanFinish(accumulated)
            } else {
                FooLog.v(TAG, "debugScanScroll: new rows after scroll ($rowCountBefore → $rowCountAfter) — continuing")
                debugScanPass(accumulated, scrollAttemptsLeft - 1, MAX_EXPAND_PASSES_PER_SCROLL)
            }
        }, DEBUG_SCAN_SCROLL_SETTLE_MS)
    }

    /** Log the deduplicated corpus and close the shade after the observation delay. */
    private fun debugScanFinish(accumulated: LinkedHashSet<ShadeRow>) {
        val rows = accumulated.toList()
        val sb = StringBuilder("debugScanFinish: complete corpus (${rows.size} rows):")
        rows.forEachIndexed { i, row -> sb.append("\n  [$i] $row") }
        FooLog.i(TAG, sb.toString())
        mainHandler.postDelayed({
            FooLog.i(TAG, "debugScanFinish: closing shade")
            actionNotificationsShow(false)
            debugScanActive = false
        }, DEBUG_PRE_CLOSE_DELAY_MS)
    }
}
