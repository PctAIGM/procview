package io.github.PctAIGM.procview.sampler

class PssCadence(intervalMs: Long) {
    private val intervalNanos: Long
    private var nextTargetNanos: Long? = null

    init {
        require(intervalMs > 0 && intervalMs <= Long.MAX_VALUE / NANOS_PER_MILLISECOND) {
            "invalid PSS interval"
        }
        intervalNanos = intervalMs * NANOS_PER_MILLISECOND
    }

    fun isDue(elapsedRealtimeNanos: Long): Boolean {
        require(elapsedRealtimeNanos >= 0) { "elapsed time must not be negative" }
        val target = nextTargetNanos
        if (target == null) {
            nextTargetNanos = safeAdd(elapsedRealtimeNanos, intervalNanos)
            return true
        }
        if (elapsedRealtimeNanos < target) return false

        val behind = elapsedRealtimeNanos - target
        val intervalsToAdvance = (behind / intervalNanos) + 1
        nextTargetNanos = safeAdd(target, safeMultiply(intervalsToAdvance, intervalNanos))
        return true
    }

    fun reset() {
        nextTargetNanos = null
    }

    private fun safeAdd(left: Long, right: Long): Long =
        if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right

    private fun safeMultiply(left: Long, right: Long): Long =
        if (left != 0L && Long.MAX_VALUE / left < right) Long.MAX_VALUE else left * right

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
