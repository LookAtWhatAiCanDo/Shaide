package llc.lookatwhataicando.notifai

import llc.lookatwhataicando.notifai.ShadeDelays.Companion.FAST
import llc.lookatwhataicando.notifai.ShadeDelays.Companion.SLOW


/**
 * Timing constants for the notification shade accessibility state machine.
 *
 * Use [SLOW] while debugging (visual observation of each step).
 * Use [FAST] in production once minimum working delays are confirmed.
 * Toggle via [MyAccessibilityService.DEBUG_SLOW_MODE].
 */
data class ShadeDelays(
    val shadeSettle: Long,
    val preExpand: Long,
    val scrollSettle: Long,
    val preClose: Long,
) {
    companion object {
        val FAST = ShadeDelays(shadeSettle = 200L, preExpand = 50L, scrollSettle = 50L,  preClose = 50L)
        val SLOW = ShadeDelays(shadeSettle = 2000L, preExpand = 1000L, scrollSettle = 1500L, preClose = 4000L)
    }
}
