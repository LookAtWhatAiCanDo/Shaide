package llc.lookatwhataicando.notifai

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
        val FAST = ShadeDelays(shadeSettle = 600L,  preExpand = 0L,    scrollSettle = 0L,    preClose = 0L)
        val SLOW = ShadeDelays(shadeSettle = 3000L, preExpand = 2000L, scrollSettle = 1500L, preClose = 4000L)
    }
}
