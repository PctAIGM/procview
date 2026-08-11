package io.github.PctAIGM.procview.sampler

import io.github.PctAIGM.procview.model.MetricFrame
import io.github.PctAIGM.procview.model.ProcessKey
import io.github.PctAIGM.procview.model.ProcessMetric

object RetentionReason {
    const val TOP_CPU = 1 shl 0
    const val TOP_RSS = 1 shl 1
    const val PINNED = 1 shl 2
    const val DETAIL = 1 shl 3
}

data class PssSelection(
    val keys: List<ProcessKey>,
    val truncated: Boolean,
)

object RetentionPolicy {
    fun reasons(
        frame: MetricFrame,
        pinnedKeys: Set<ProcessKey>,
        detailKeys: Set<ProcessKey>,
        topCount: Int = DEFAULT_TOP_COUNT,
    ): Map<ProcessKey, Int> {
        require(topCount >= 0) { "topCount must not be negative" }
        val liveKeys = frame.metrics.asSequence().map(ProcessMetric::key).toSet()
        val reasons = linkedMapOf<ProcessKey, Int>()
        fun add(key: ProcessKey, reason: Int) {
            if (key in liveKeys) reasons[key] = (reasons[key] ?: 0) or reason
        }

        frame.metrics.filter { it.cpuPercentBasisPoints != null }
            .sortedWith(CPU_ORDER)
            .take(topCount)
            .forEach { add(it.key, RetentionReason.TOP_CPU) }
        frame.metrics.filter { it.rssKb != null }
            .sortedWith(RSS_ORDER)
            .take(topCount)
            .forEach { add(it.key, RetentionReason.TOP_RSS) }
        pinnedKeys.sortedWith(KEY_ORDER).forEach { add(it, RetentionReason.PINNED) }
        detailKeys.sortedWith(KEY_ORDER).forEach { add(it, RetentionReason.DETAIL) }
        return reasons
    }

    fun selectPssTargets(
        frame: MetricFrame,
        pinnedKeys: Set<ProcessKey>,
        detailKeys: Set<ProcessKey>,
        topCount: Int = DEFAULT_TOP_COUNT,
        maxTargets: Int = DEFAULT_MAX_PSS_TARGETS,
    ): PssSelection {
        require(maxTargets > 0) { "maxTargets must be positive" }
        val live = frame.metrics.associateBy(ProcessMetric::key)
        val ordered = linkedSetOf<ProcessKey>()
        detailKeys.sortedWith(KEY_ORDER).filter(live::containsKey).forEach(ordered::add)
        pinnedKeys.sortedWith(KEY_ORDER).filter(live::containsKey).forEach(ordered::add)
        frame.metrics.filter { it.cpuPercentBasisPoints != null }
            .sortedWith(CPU_ORDER)
            .take(topCount)
            .mapTo(ordered, ProcessMetric::key)
        frame.metrics.filter { it.rssKb != null }
            .sortedWith(RSS_ORDER)
            .take(topCount)
            .mapTo(ordered, ProcessMetric::key)
        return PssSelection(
            keys = ordered.take(maxTargets),
            truncated = ordered.size > maxTargets,
        )
    }

    private val KEY_ORDER = compareBy<ProcessKey>({ it.pid }, { it.startTimeTicks })
    private val CPU_ORDER = compareByDescending<ProcessMetric> { it.cpuPercentBasisPoints ?: -1 }
        .thenByDescending { it.rssKb ?: -1L }
        .thenBy { it.key.pid }
        .thenBy { it.key.startTimeTicks }
    private val RSS_ORDER = compareByDescending<ProcessMetric> { it.rssKb ?: -1L }
        .thenByDescending { it.cpuPercentBasisPoints ?: -1 }
        .thenBy { it.key.pid }
        .thenBy { it.key.startTimeTicks }

    private const val DEFAULT_TOP_COUNT = 20
    private const val DEFAULT_MAX_PSS_TARGETS = 128
}
