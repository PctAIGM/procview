package io.github.PctAIGM.procview.sampler

data class ScheduledTick(
    val targetMs: Long,
    val skippedTicks: Long,
)

object FixedTargetSchedule {
    fun next(previousTargetMs: Long, nowMs: Long, intervalMs: Long): ScheduledTick {
        require(previousTargetMs >= 0 && nowMs >= 0) { "clock values must not be negative" }
        require(intervalMs > 0) { "interval must be positive" }
        var target = safeAdd(previousTargetMs, intervalMs)
        if (target >= nowMs) return ScheduledTick(target, skippedTicks = 0)

        val behind = nowMs - target
        val skipped = ((behind - 1) / intervalMs) + 1
        target = safeAdd(target, safeMultiply(skipped, intervalMs))
        return ScheduledTick(target, skipped)
    }

    private fun safeAdd(left: Long, right: Long): Long =
        if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right

    private fun safeMultiply(left: Long, right: Long): Long =
        if (left != 0L && Long.MAX_VALUE / left < right) Long.MAX_VALUE else left * right
}
