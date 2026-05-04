package llc.lookatwhataicando.notifai

import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.smartfoo.android.core.logging.FooLog

/**
 * Full top-to-bottom shade scan used when [MyAccessibilityService.DEBUG_FULL_SCAN_MODE] is true.
 *
 * Opens the shade, expands every collapsed row one at a time (with a settle delay between each),
 * scrolls to reveal off-screen rows, and logs every [ShadeRow] found. No app-label matching;
 * no TTS. Guarded by [active] so concurrent triggers are ignored.
 *
 * Disable [MyAccessibilityService.DEBUG_FULL_SCAN_MODE] when switching back to the normal
 * app-label search path.
 */
internal class DebugShadeScan(
    private val delays: ShadeDelays,
    private val getWindows: () -> List<AccessibilityWindowInfo>?,
    private val getLastSnapshot: () -> List<ShadeRow>,
    private val openShade: () -> Unit,
    private val closeShade: () -> Unit,
) {
    companion object {
        private val TAG = FooLog.TAG(DebugShadeScan::class)
        private const val EXPAND_SETTLE_MS = 1000L
        private const val SCROLL_SETTLE_MS = 500L
        private const val MAX_SCROLL_ATTEMPTS = 10
        private const val MAX_EXPAND_PASSES_PER_SCROLL = 20
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var active = false

    fun debugFullShadeScan() {
        if (active) {
            FooLog.d(TAG, "debugFullShadeScan: scan already in progress, ignoring")
            return
        }
        active = true
        FooLog.i(TAG, "debugFullShadeScan: starting — opening shade")
        openShade()
        mainHandler.postDelayed(
            { debugScanPass(linkedSetOf(), MAX_SCROLL_ATTEMPTS, MAX_EXPAND_PASSES_PER_SCROLL) },
            delays.shadeSettle,
        )
    }

    /**
     * Expands collapsed rows one at a time, each separated by [EXPAND_SETTLE_MS].
     *
     * Each call scans all visible rows via [findDirectRowButton]:
     *  - Already expanded ("Collapse" button): skip.
     *  - Collapsed ("Expand" button): click chevron, schedule next pass after [EXPAND_SETTLE_MS],
     *    then RETURN — only one expansion per delay interval.
     *  - No chevron: skip.
     *
     * Once no collapsed row is found, proceeds to [debugScanCollect].
     * Guarded by [expandPassesLeft] to prevent an unbounded loop.
     */
    private fun debugScanPass(
        accumulated: LinkedHashSet<ShadeRow>,
        scrollAttemptsLeft: Int,
        expandPassesLeft: Int,
    ) {
        val rows = getLiveRowNodes(getWindows())
        FooLog.v(TAG, "debugScanPass: ${rows.size} rows visible (expandPassesLeft=$expandPassesLeft)")
        rows.forEachIndexed { _, rowNode ->
            if (findDirectRowButton(rowNode, COLLAPSE_BUTTON_DESC) != null) return@forEachIndexed
            val expandBtn = findDirectRowButton(rowNode, EXPAND_BUTTON_DESC)
            if (expandBtn != null) {
                expandBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (expandPassesLeft > 0) {
                    mainHandler.postDelayed(
                        { debugScanPass(accumulated, scrollAttemptsLeft, expandPassesLeft - 1) },
                        EXPAND_SETTLE_MS,
                    )
                } else {
                    FooLog.w(TAG, "debugScanPass: expand pass limit reached")
                    debugScanCollect(accumulated, scrollAttemptsLeft)
                }
                return  // only one expansion per delay interval
            }
        }
        debugScanCollect(accumulated, scrollAttemptsLeft)
    }

    /** Snapshot current rows, merge into [accumulated], then attempt a scroll. */
    private fun debugScanCollect(accumulated: LinkedHashSet<ShadeRow>, scrollAttemptsLeft: Int) {
        val snapshot = getLastSnapshot()
        val sb = StringBuilder("debugScanCollect: ${snapshot.size} rows in snapshot:")
        snapshot.forEachIndexed { i, row -> sb.append("\n  [$i] $row") }
        FooLog.i(TAG, sb.toString())
        accumulated.addAll(snapshot.filter { !it.isEmpty })
        val rowCountBeforeScroll = getLiveRowNodes(getWindows()).size
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
        val container = getLiveContainerNode(getWindows())
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
            val rowCountAfter = getLiveRowNodes(getWindows()).size
            if (rowCountAfter <= rowCountBefore) {
                FooLog.i(TAG, "debugScanScroll: no new rows after scroll ($rowCountBefore → $rowCountAfter) — at bottom")
                debugScanFinish(accumulated)
            } else {
                FooLog.v(TAG, "debugScanScroll: new rows after scroll ($rowCountBefore → $rowCountAfter) — continuing")
                debugScanPass(accumulated, scrollAttemptsLeft - 1, MAX_EXPAND_PASSES_PER_SCROLL)
            }
        }, SCROLL_SETTLE_MS)
    }

    /** Log the deduplicated corpus then close the shade after the observation delay. */
    private fun debugScanFinish(accumulated: LinkedHashSet<ShadeRow>) {
        val rows = accumulated.toList()
        val sb = StringBuilder("debugScanFinish: complete corpus (${rows.size} rows):")
        rows.forEachIndexed { i, row -> sb.append("\n  [$i] $row") }
        FooLog.i(TAG, sb.toString())
        mainHandler.postDelayed({
            FooLog.i(TAG, "debugScanFinish: closing shade")
            closeShade()
            active = false
        }, delays.preClose)
    }
}
