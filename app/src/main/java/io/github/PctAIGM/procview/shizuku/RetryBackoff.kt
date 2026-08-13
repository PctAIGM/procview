package io.github.PctAIGM.procview.shizuku

internal class RetryBackoff(
    private val initialDelayMs: Long,
    private val maximumDelayMs: Long,
) {
    private var nextDelayMs = initialDelayMs

    init {
        require(initialDelayMs > 0) { "initial delay must be positive" }
        require(maximumDelayMs >= initialDelayMs) { "maximum delay must include initial delay" }
    }

    fun takeNextDelayMs(): Long {
        val current = nextDelayMs
        nextDelayMs = if (current >= maximumDelayMs || current > Long.MAX_VALUE / 2L) {
            maximumDelayMs
        } else {
            (current * 2L).coerceAtMost(maximumDelayMs)
        }
        return current
    }

    fun reset() {
        nextDelayMs = initialDelayMs
    }
}
