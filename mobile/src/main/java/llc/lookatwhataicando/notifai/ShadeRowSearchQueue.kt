package llc.lookatwhataicando.notifai

import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.smartfoo.android.core.logging.FooLog

/**
 * Serializes asynchronous notification shade row searches for [MyAccessibilityService].
 *
 * Each [findRowForAppLabel] call is either started immediately (if no search is active) or
 * queued behind the current one. When a search finishes, the open shade is handed directly to
 * the next queued search — no close/reopen cycle.
 *
 * This prevents multiple near-simultaneous calls — e.g. Chat and Google both firing at startup
 * within 30 ms of each other — from silently cancelling each other.
 */
internal class ShadeRowSearchQueue(
    private val delays: ShadeDelays,
    private val getWindows: () -> List<AccessibilityWindowInfo>?,
    private val getLastSnapshot: () -> List<NotificationShadeSnapshot.ShadeRow>,
    private val openShade: () -> Unit,
    private val closeShade: () -> Unit,
) {
    companion object {
        private val TAG = FooLog.TAG(ShadeRowSearchQueue::class)
        private const val MAX_SCROLL_ATTEMPTS = 10
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * State for a single in-flight search for a notification row by app label.
     *
     * @param appLabel             Human-readable label to match against [NotificationShadeSnapshot.ShadeRow.appName].
     * @param callback             Invoked on the main thread with all found rows (or null if none).
     * @param shadeOpenedByUs      True if this search is responsible for closing the shade when done.
     *                             Transferred to the next queued search so the last one closes it.
     * @param readyToScan          False while waiting for the shade-open settle delay; true once the
     *                             settle runnable fires.
     * @param scrollAttemptsLeft   Remaining [AccessibilityNodeInfo.ACTION_SCROLL_FORWARD] calls
     *                             allowed before giving up on off-screen rows.
     * @param rowExpanded          True after we performed one expand on the target row, preventing
     *                             re-expanding on the next snapshot check.
     * @param settling             True while a [mainHandler.postDelayed] settle window is in-flight
     *                             (pre-expand pause, post-expand settle, or post-scroll settle).
     *                             Blocks [advancePendingRowSearch] from reacting during that window.
     */
    private data class PendingRowSearch(
        val appLabel: String,
        val callback: (List<NotificationShadeSnapshot.ShadeRow>?) -> Unit,
        var shadeOpenedByUs: Boolean,
        var readyToScan: Boolean,
        var scrollAttemptsLeft: Int = MAX_SCROLL_ATTEMPTS,
        var rowExpanded: Boolean = false,
        var settling: Boolean = false,
    )

    /** Head is the active search; all others wait for the active one to finish. */
    private val queue: ArrayDeque<PendingRowSearch> = ArrayDeque()

    /**
     * Initiates a search for the shade row whose [NotificationShadeSnapshot.ShadeRow.appName] matches [appLabel].
     *
     * If another search is active, this one is queued and will start automatically when the
     * current one finishes ([finishSearch] hands the open shade to the next entry).
     *
     * @param appLabel          Label to search for (e.g. "Chat").
     * @param shadeAlreadyOpen  True if the shade is already visible (skip open + settle delay).
     * @param callback          Called on the main thread with the matched rows or null.
     */
    fun findRowForAppLabel(
        appLabel: String,
        shadeAlreadyOpen: Boolean,
        callback: (List<NotificationShadeSnapshot.ShadeRow>?) -> Unit,
    ) {
        FooLog.i(TAG, "#ACCESSIBILITY findRowForAppLabel: appLabel=$appLabel shadeAlreadyOpen=$shadeAlreadyOpen queueSize=${queue.size}")

        val shadeIsOpen = shadeAlreadyOpen || queue.isNotEmpty()
        val search = PendingRowSearch(
            appLabel        = appLabel,
            callback        = callback,
            shadeOpenedByUs = !shadeIsOpen,
            readyToScan     = shadeIsOpen,
        )

        if (queue.isNotEmpty()) {
            FooLog.i(TAG, "#ACCESSIBILITY findRowForAppLabel: queuing $appLabel behind active search for ${queue.first().appLabel}")
            queue.addLast(search)
            return
        }

        queue.addLast(search)

        if (!shadeAlreadyOpen) {
            openShade()
            mainHandler.postDelayed({
                val s = queue.firstOrNull() ?: return@postDelayed
                if (s !== search) return@postDelayed
                s.readyToScan = true
                selfAdvance(s)
            }, delays.shadeSettle)
        } else {
            selfAdvance(search)
        }
    }

    /**
     * Called from [MyAccessibilityService.onAccessibilityEvent] after every snapshot update.
     * Delegates to [selfAdvance] so that event-driven and timer-driven paths share one code path.
     */
    fun advancePendingRowSearch() {
        val search = queue.firstOrNull() ?: return
        if (search.settling) return
        selfAdvance(search)
    }

    /**
     * Central state-machine advance. Called from event-driven [advancePendingRowSearch] AND
     * proactively from every settle-timer callback so the search never stalls waiting for a
     * passive accessibility event.
     *
     * Priority order:
     * 1. If target app is in the snapshot and the row is collapsed, expand it (with optional
     *    pre-expand pause), then re-enter to finish.
     * 2. If target app is in the snapshot and expanded (or no expand needed), finish the search.
     * 3. If no match yet, expand the next collapsed visible row to reveal more content.
     * 4. When all visible rows are expanded, scroll and repeat.
     * 5. When scroll is exhausted or fails, give up.
     */
    private fun selfAdvance(search: PendingRowSearch) {
        if (!search.readyToScan) return

        val match = getLastSnapshot().firstOrNull { it.appName.equals(search.appLabel, ignoreCase = true) }
        if (match != null) {
            if (!search.rowExpanded) {
                // The row may be a collapsed group summary. Expand it first so we capture all
                // child notifications. ACTION_EXPAND is locale-agnostic. We require EXPAND present
                // AND COLLAPSE absent to avoid acting on transitional or ambiguous actionList state.
                val rawNode = NotificationShadeSnapshot.findRawRowWithAppName(search.appLabel, getWindows())
                val isCollapsed = rawNode?.actionList?.let { a ->
                    a.any { it.id == AccessibilityNodeInfo.ACTION_EXPAND } &&
                    a.none { it.id == AccessibilityNodeInfo.ACTION_COLLAPSE }
                } == true
                if (isCollapsed) {
                    if (delays.preExpand > 0) {
                        FooLog.v(TAG, "#ACCESSIBILITY selfAdvance: pausing ${delays.preExpand}ms before expanding ${search.appLabel}")
                        search.settling = true
                        mainHandler.postDelayed({
                            val s = queue.firstOrNull() ?: return@postDelayed
                            if (s !== search) return@postDelayed
                            s.settling = false
                            val freshNode = NotificationShadeSnapshot.findRawRowWithAppName(s.appLabel, getWindows())
                            val freshIsCollapsed = freshNode?.actionList?.let { a ->
                                a.any { it.id == AccessibilityNodeInfo.ACTION_EXPAND } &&
                                a.none { it.id == AccessibilityNodeInfo.ACTION_COLLAPSE }
                            } == true
                            if (freshIsCollapsed) {
                                FooLog.v(TAG, "#ACCESSIBILITY selfAdvance: now expanding ${s.appLabel}")
                                freshNode!!.performAction(AccessibilityNodeInfo.ACTION_EXPAND)
                                s.rowExpanded = true
                            }
                            selfAdvance(s)
                        }, delays.preExpand)
                    } else {
                        FooLog.v(TAG, "#ACCESSIBILITY selfAdvance: expanding ${search.appLabel} before reading content")
                        rawNode!!.performAction(AccessibilityNodeInfo.ACTION_EXPAND)
                        search.rowExpanded = true
                        // Fast mode: snapshot may not yet reflect the expand. Let the next
                        // accessibility event drive the re-check via advancePendingRowSearch.
                    }
                    return
                }
            }
            val allMatches = getLastSnapshot().filter { it.appName.equals(search.appLabel, ignoreCase = true) }
            FooLog.i(TAG, "#ACCESSIBILITY selfAdvance: found ${search.appLabel} (${allMatches.size} rows)")
            finishSearch(allMatches)
            return
        }

        if (!tryExpandNextRow(search)) {
            val container = NotificationShadeSnapshot.getLiveContainerNode(getWindows())
            if (search.scrollAttemptsLeft > 0 && container != null &&
                container.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
                search.scrollAttemptsLeft--
                FooLog.v(TAG, "#ACCESSIBILITY selfAdvance: scrolled (${search.scrollAttemptsLeft} attempts left)")
                if (delays.scrollSettle > 0) {
                    search.settling = true
                    mainHandler.postDelayed({
                        val s = queue.firstOrNull() ?: return@postDelayed
                        if (s !== search) return@postDelayed
                        s.settling = false
                        selfAdvance(s)
                    }, delays.scrollSettle)
                } else {
                    selfAdvance(search)
                }
            } else {
                FooLog.i(TAG, "#ACCESSIBILITY selfAdvance: scroll exhausted or container null — giving up on ${search.appLabel}")
                finishSearch()
            }
        }
    }

    /**
     * Re-fetches live row nodes and performs ACTION_EXPAND on the first collapsed row found.
     * Locale-agnostic — detected via actionList (EXPAND present, COLLAPSE absent). Nodes are
     * fetched fresh on every call; no stale references are held between async delays.
     *
     * Returns true if an expand was performed, false if all visible rows are already expanded
     * (caller should scroll or give up). After any settle delay, proactively calls [selfAdvance]
     * so the search advances without waiting for a passive accessibility event.
     */
    private fun tryExpandNextRow(search: PendingRowSearch): Boolean {
        for (row in NotificationShadeSnapshot.getLiveRowNodes(getWindows())) {
            val actions = row.actionList
            if (actions.any { it.id == AccessibilityNodeInfo.ACTION_EXPAND } &&
                actions.none { it.id == AccessibilityNodeInfo.ACTION_COLLAPSE }) {
                val expanded = row.performAction(AccessibilityNodeInfo.ACTION_EXPAND)
                FooLog.v(TAG, "#ACCESSIBILITY tryExpandNextRow: ACTION_EXPAND result=$expanded for ${row.viewIdResourceName}")
                if (delays.preExpand > 0) {
                    search.settling = true
                    mainHandler.postDelayed({
                        val s = queue.firstOrNull() ?: return@postDelayed
                        if (s !== search) return@postDelayed
                        s.settling = false
                        selfAdvance(s)
                    }, delays.preExpand)
                }
                return true
            }
        }
        return false
    }

    private fun finishSearch(results: List<NotificationShadeSnapshot.ShadeRow> = emptyList()) {
        val search = queue.removeFirstOrNull() ?: return
        val nextSearch = queue.firstOrNull()
        FooLog.i(TAG, "#ACCESSIBILITY finishSearch: appLabel=${search.appLabel} rows=${results.size} queueRemaining=${queue.size}")

        if (nextSearch != null) {
            // Hand the open shade to the next search immediately (no close/reopen cycle).
            // Transfer shade-close responsibility so the last search in the chain closes it.
            if (search.shadeOpenedByUs) nextSearch.shadeOpenedByUs = true
            nextSearch.readyToScan = true
            // Defer selfAdvance to the next looper iteration so the accessibility tree has time
            // to reflect the just-completed search's final expand (avoids stale ACTION_EXPAND
            // on an already-expanded row being seen by the incoming search's first scan).
            nextSearch.settling = true
            mainHandler.post {
                val s = queue.firstOrNull() ?: return@post
                if (s !== nextSearch) return@post
                s.settling = false
                selfAdvance(s)
            }
            search.callback(results.takeIf { it.isNotEmpty() })
        } else {
            val closeAndCallback = {
                if (search.shadeOpenedByUs) closeShade()
                search.callback(results.takeIf { it.isNotEmpty() })
            }
            if (delays.preClose > 0) {
                FooLog.v(TAG, "#ACCESSIBILITY finishSearch: holding shade open ${delays.preClose}ms for observation (DEBUG_SLOW_MODE)")
                mainHandler.postDelayed(closeAndCallback, delays.preClose)
            } else {
                closeAndCallback()
            }
        }
    }
}
