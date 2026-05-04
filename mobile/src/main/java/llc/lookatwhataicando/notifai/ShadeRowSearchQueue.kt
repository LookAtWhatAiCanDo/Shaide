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
    private val getLastSnapshot: () -> List<ShadeRow>,
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
     * @param appLabel             Human-readable label to match against [ShadeRow.appName].
     * @param callback             Invoked on the main thread with all found rows (or null if none).
     * @param shadeOpenedByUs      True if this search is responsible for closing the shade when done.
     *                             Transferred to the next queued search so the last one closes it.
     * @param readyToScan          False while waiting for the shade-open settle delay; true once the
     *                             settle runnable fires and populates [rowsToExpand].
     * @param rowsToExpand         Live row nodes queued for [AccessibilityNodeInfo.ACTION_CLICK] on
     *                             their expand chevron. Populated after settle so nodes are fresh.
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
        val callback: (List<ShadeRow>?) -> Unit,
        var shadeOpenedByUs: Boolean,
        var readyToScan: Boolean,
        val rowsToExpand: MutableList<AccessibilityNodeInfo> = mutableListOf(),
        var scrollAttemptsLeft: Int = MAX_SCROLL_ATTEMPTS,
        var rowExpanded: Boolean = false,
        var settling: Boolean = false,
    )

    /** Head is the active search; all others wait for the active one to finish. */
    private val queue: ArrayDeque<PendingRowSearch> = ArrayDeque()

    /**
     * Initiates a search for the shade row whose [ShadeRow.appName] matches [appLabel].
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
        callback: (List<ShadeRow>?) -> Unit,
    ) {
        FooLog.i(TAG, "findRowForAppLabel: appLabel=$appLabel shadeAlreadyOpen=$shadeAlreadyOpen queueSize=${queue.size}")

        val shadeIsOpen = shadeAlreadyOpen || queue.isNotEmpty()
        val search = PendingRowSearch(
            appLabel        = appLabel,
            callback        = callback,
            shadeOpenedByUs = !shadeIsOpen,
            readyToScan     = shadeIsOpen,
        )

        if (queue.isNotEmpty()) {
            FooLog.i(TAG, "findRowForAppLabel: queuing $appLabel behind active search for ${queue.first().appLabel}")
            queue.addLast(search)
            return
        }

        queue.addLast(search)

        if (!shadeAlreadyOpen) {
            openShade()
            // rowsToExpand intentionally NOT pre-populated: nodes before shade open are stale.
            mainHandler.postDelayed({
                val s = queue.firstOrNull() ?: return@postDelayed
                if (s !== search) return@postDelayed
                s.rowsToExpand.addAll(getLiveRowNodes(getWindows()))
                s.readyToScan = true
                tryExpandNextRow(s)
            }, delays.shadeSettle)
        } else {
            search.rowsToExpand.addAll(getLiveRowNodes(getWindows()))
            tryExpandNextRow(search)
        }
    }

    /**
     * Called from [MyAccessibilityService.onAccessibilityEvent] after every snapshot update.
     * Re-checks for a match, then tries the next expand or scroll as needed.
     */
    fun advancePendingRowSearch(root: AccessibilityNodeInfo) {
        val search = queue.firstOrNull() ?: return

        // A settle window is in-flight (pre/post-expand or post-scroll); ignore events until it clears.
        if (search.settling) return

        val match = getLastSnapshot().firstOrNull { it.appName.equals(search.appLabel, ignoreCase = true) }
        if (match != null) {
            if (!search.rowExpanded) {
                // The row may be a collapsed group summary. Expand it first so we capture all
                // child notifications. Use findDirectRowButton (not a subtree search) so we don't
                // mistake a child row's chevron for the parent's — which would toggle it back to
                // collapsed (expand→collapse→expand loop).
                val rawNode = findRawRowWithAppName(search.appLabel, getWindows())
                val expandBtn = rawNode?.let { findDirectRowButton(it, EXPAND_BUTTON_DESC) }
                if (expandBtn != null) {
                    if (delays.preExpand > 0) {
                        FooLog.v(TAG, "advancePendingRowSearch: pausing ${delays.preExpand}ms before expanding ${search.appLabel}")
                        search.settling = true
                        mainHandler.postDelayed({
                            val s = queue.firstOrNull() ?: return@postDelayed
                            if (s !== search) return@postDelayed
                            s.settling = false
                            val freshBtn = findRawRowWithAppName(s.appLabel, getWindows())
                                ?.let { findDirectRowButton(it, EXPAND_BUTTON_DESC) }
                            if (freshBtn != null) {
                                FooLog.v(TAG, "advancePendingRowSearch: now expanding ${s.appLabel}")
                                freshBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                s.rowExpanded = true
                            }
                        }, delays.preExpand)
                    } else {
                        FooLog.v(TAG, "advancePendingRowSearch: expanding ${search.appLabel} before reading content")
                        expandBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        search.rowExpanded = true
                    }
                    return
                }
            }
            val allMatches = getLastSnapshot().filter { it.appName.equals(search.appLabel, ignoreCase = true) }
            FooLog.i(TAG, "advancePendingRowSearch: found ${search.appLabel} (${allMatches.size} rows)")
            finishSearch(allMatches)
            return
        }

        // Don't attempt expand/scroll until the shade-open settle delay has fired.
        if (!search.readyToScan) return

        if (search.rowsToExpand.isNotEmpty()) {
            tryExpandNextRow(search)
        } else if (search.scrollAttemptsLeft > 0) {
            val container = findContainerNode(root)
            if (container != null && container.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
                search.scrollAttemptsLeft--
                FooLog.v(TAG, "advancePendingRowSearch: scrolled (${search.scrollAttemptsLeft} attempts left)")
                if (delays.scrollSettle > 0) {
                    search.settling = true
                    mainHandler.postDelayed({
                        val s = queue.firstOrNull() ?: return@postDelayed
                        if (s !== search) return@postDelayed
                        s.settling = false
                        s.rowsToExpand.clear()
                        s.rowsToExpand.addAll(getLiveRowNodes(getWindows()))
                    }, delays.scrollSettle)
                } else {
                    search.rowsToExpand.addAll(getLiveRowNodes(getWindows()))
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
     * if it is collapsed. Uses [findDirectRowButton] so we don't descend into nested
     * [expandableNotificationRow] children and find their chevrons instead.
     * Already-expanded rows are skipped silently.
     */
    private fun tryExpandNextRow(search: PendingRowSearch) {
        while (search.rowsToExpand.isNotEmpty()) {
            val row = search.rowsToExpand.removeAt(0)
            val expandBtn = findDirectRowButton(row, EXPAND_BUTTON_DESC)
            if (expandBtn != null) {
                val clicked = expandBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                FooLog.v(TAG, "tryExpandNextRow: ACTION_CLICK on Expand result=$clicked for ${row.viewIdResourceName}")
                if (delays.preExpand > 0) {
                    search.settling = true
                    mainHandler.postDelayed({
                        val s = queue.firstOrNull() ?: return@postDelayed
                        if (s !== search) return@postDelayed
                        s.settling = false
                    }, delays.preExpand)
                }
                return
            }
            // Row is already expanded — keep popping.
        }
        val allMatches = getLastSnapshot().filter { it.appName.equals(search.appLabel, ignoreCase = true) }
        if (allMatches.isNotEmpty()) {
            FooLog.i(TAG, "tryExpandNextRow: match found without expanding for ${search.appLabel}")
            finishSearch(allMatches)
        }
        // Otherwise wait for next event (scroll may still deliver more rows).
    }

    private fun finishSearch(results: List<ShadeRow> = emptyList()) {
        val search = queue.removeFirstOrNull() ?: return
        val nextSearch = queue.firstOrNull()
        FooLog.i(TAG, "finishSearch: appLabel=${search.appLabel} rows=${results.size} queueRemaining=${queue.size}")

        if (nextSearch != null) {
            // Hand the open shade to the next search immediately (no close/reopen cycle).
            // Transfer shade-close responsibility so the last search in the chain closes it.
            if (search.shadeOpenedByUs) nextSearch.shadeOpenedByUs = true
            nextSearch.readyToScan = true
            nextSearch.rowsToExpand.addAll(getLiveRowNodes(getWindows()))
            tryExpandNextRow(nextSearch)
            search.callback(results.takeIf { it.isNotEmpty() })
        } else {
            val closeAndCallback = {
                if (search.shadeOpenedByUs) closeShade()
                search.callback(results.takeIf { it.isNotEmpty() })
            }
            if (delays.preClose > 0) {
                FooLog.v(TAG, "finishSearch: holding shade open ${delays.preClose}ms for observation (DEBUG_SLOW_MODE)")
                mainHandler.postDelayed(closeAndCallback, delays.preClose)
            } else {
                closeAndCallback()
            }
        }
    }
}
