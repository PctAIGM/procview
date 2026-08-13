package io.github.PctAIGM.procview.sampler

import io.github.PctAIGM.procview.model.MetricFrame
import io.github.PctAIGM.procview.model.MetricDataSource
import io.github.PctAIGM.procview.model.MetricFrameFlags
import io.github.PctAIGM.procview.model.ProcessKey
import io.github.PctAIGM.procview.model.ProcessMetric
import io.github.PctAIGM.procview.model.RawMetricFrame
import kotlin.math.roundToInt

class MetricFrameAssembler {
    private var previousSystemTotalTicks: Long? = null
    private var previousSystemIdleTicks: Long? = null
    private var previousProcessTicks: Map<ProcessKey, Long> = emptyMap()
    private var previousSource: MetricDataSource? = null
    private val pssCache = mutableMapOf<ProcessKey, PssSample>()

    fun recordPss(
        valuesKb: Map<ProcessKey, Long>,
        sampledAtElapsedRealtimeNanos: Long,
        source: MetricDataSource,
    ) {
        require(sampledAtElapsedRealtimeNanos >= 0) { "PSS sample time must not be negative" }
        if (previousSource != null && previousSource != source) return
        valuesKb.forEach { (key, value) ->
            if (value >= 0) pssCache[key] = PssSample(value, sampledAtElapsedRealtimeNanos)
        }
    }

    fun assemble(raw: RawMetricFrame): MetricFrame {
        var flags = raw.frameFlags
        if (previousSource != null && previousSource != raw.source) {
            previousSystemTotalTicks = null
            previousSystemIdleTicks = null
            previousProcessTicks = emptyMap()
            // Fallback start keys are estimated in centiseconds rather than procfs clock
            // ticks. Even an accidental numeric collision must not carry a PSS value across
            // two incomparable identity domains.
            pssCache.clear()
            flags = flags or MetricFrameFlags.DATA_SOURCE_CHANGED
        }
        val total = raw.systemTotalCpuTicks
        val idle = raw.systemIdleCpuTicks
        val previousTotal = previousSystemTotalTicks
        val previousIdle = previousSystemIdleTicks
        var totalDelta: Long? = null
        var systemCpuBasisPoints: Int? = null

        if (total != null && idle != null && previousTotal != null && previousIdle != null) {
            val candidateTotalDelta = total - previousTotal
            val candidateIdleDelta = idle - previousIdle
            if (candidateTotalDelta > 0 && candidateIdleDelta in 0..candidateTotalDelta) {
                totalDelta = candidateTotalDelta
                systemCpuBasisPoints = toBasisPoints(
                    numerator = candidateTotalDelta - candidateIdleDelta,
                    denominator = candidateTotalDelta,
                )
            } else {
                flags = flags or MetricFrameFlags.CPU_COUNTER_RESET
            }
        }

        val currentProcessTicks = HashMap<ProcessKey, Long>(raw.metrics.size)
        val liveKeys = HashSet<ProcessKey>(raw.metrics.size)
        val metrics = raw.metrics.map { process ->
            liveKeys += process.key
            currentProcessTicks[process.key] = process.cpuTicks
            val previousTicks = previousProcessTicks[process.key]
            val processCpu = if (totalDelta != null && previousTicks != null) {
                val delta = process.cpuTicks - previousTicks
                if (delta >= 0) {
                    toBasisPoints(delta, totalDelta)
                } else {
                    flags = flags or MetricFrameFlags.PROCESS_COUNTER_RESET
                    null
                }
            } else {
                null
            }
            val pss = pssCache[process.key]
            ProcessMetric(
                key = process.key,
                cpuPercentBasisPoints = processCpu,
                rssKb = process.rssKb,
                pssKb = pss?.valueKb,
                pssSampleElapsedRealtimeNanos = pss?.sampledAtElapsedRealtimeNanos,
                state = process.state,
            )
        }

        pssCache.keys.retainAll(liveKeys)
        previousProcessTicks = currentProcessTicks
        previousSystemTotalTicks = total
        previousSystemIdleTicks = idle
        previousSource = raw.source

        return MetricFrame(
            sequence = raw.sequence,
            elapsedRealtimeNanos = raw.elapsedRealtimeNanos,
            wallTimeMillis = raw.wallTimeMillis,
            systemCpuPercentBasisPoints = systemCpuBasisPoints,
            memoryTotalKb = raw.memoryTotalKb,
            memoryAvailableKb = raw.memoryAvailableKb,
            collectionDurationMs = raw.collectionDurationMs,
            catalogRevision = raw.catalogRevision,
            source = raw.source,
            frameFlags = flags,
            metrics = metrics,
        )
    }

    fun reset() {
        previousSystemTotalTicks = null
        previousSystemIdleTicks = null
        previousProcessTicks = emptyMap()
        previousSource = null
        pssCache.clear()
    }

    private fun toBasisPoints(numerator: Long, denominator: Long): Int {
        if (numerator <= 0 || denominator <= 0) return 0
        return ((numerator.toDouble() / denominator.toDouble()) * MAX_BASIS_POINTS)
            .roundToInt()
            .coerceIn(0, MAX_BASIS_POINTS)
    }

    private data class PssSample(
        val valueKb: Long,
        val sampledAtElapsedRealtimeNanos: Long,
    )

    private companion object {
        const val MAX_BASIS_POINTS = 10_000
    }
}
