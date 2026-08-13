package io.github.PctAIGM.procview.sampler

import io.github.PctAIGM.procview.model.MetricFrame
import io.github.PctAIGM.procview.model.ProcessCatalogEntry
import io.github.PctAIGM.procview.model.ProcessMetric

data class ApplicationAggregate(
    val stableId: String,
    val primaryPackage: String?,
    val packageCandidates: List<String>,
    val displayName: String,
    val uid: Int?,
    val isSystem: Boolean,
    val isNative: Boolean,
    val isSharedUid: Boolean,
    val cpuPercentBasisPoints: Int?,
    val cpuComplete: Boolean,
    val rssKb: Long?,
    val rssComplete: Boolean,
    val pssKb: Long?,
    val pssComplete: Boolean,
    val processes: List<ProcessMetric>,
)

object ApplicationAggregator {
    fun aggregate(
        frame: MetricFrame,
        catalog: List<ProcessCatalogEntry>,
        resolutions: List<ProcessPackageResolution>,
    ): List<ApplicationAggregate> {
        val catalogByKey = catalog.associateBy(ProcessCatalogEntry::key)
        val resolutionByKey = resolutions.associateBy(ProcessPackageResolution::key)
        val grouped = frame.metrics.groupBy { metric ->
            val identity = catalogByKey[metric.key]
            val resolution = resolutionByKey[metric.key]
            when {
                resolution?.primaryPackage != null -> "app:${resolution.primaryPackage}"
                resolution?.isSharedUid == true -> "uid:${identity?.uid ?: -1}"
                resolution?.isNative == true ->
                    "native:${identity?.uid ?: -1}:${identity?.processName.orEmpty()}"
                else -> "process:${metric.key.pid}:${metric.key.startTimeTicks}"
            }
        }

        return grouped.map { (stableId, processes) ->
            val identities = processes.mapNotNull { catalogByKey[it.key] }
            val processResolutions = processes.mapNotNull { resolutionByKey[it.key] }
            val primaryPackage = processResolutions.firstNotNullOfOrNull { it.primaryPackage }
            val candidates = processResolutions.flatMap { it.packageCandidates }.distinct().sorted()
            val cpuValues = processes.mapNotNull(ProcessMetric::cpuPercentBasisPoints)
            val rssValues = processes.mapNotNull { it.rssKb?.takeIf { value -> value >= 0 } }
            val pssValues = processes.mapNotNull { it.pssKb?.takeIf { value -> value >= 0 } }
            ApplicationAggregate(
                stableId = stableId,
                primaryPackage = primaryPackage,
                packageCandidates = candidates,
                displayName = processResolutions.firstNotNullOfOrNull { it.displayName }
                    ?: primaryPackage
                    ?: identities.firstOrNull()?.processName
                    ?: stableId,
                uid = identities.mapNotNull(ProcessCatalogEntry::uid).distinct().singleOrNull(),
                isSystem = processResolutions.any(ProcessPackageResolution::isSystem),
                isNative = processResolutions.isNotEmpty() &&
                    processResolutions.all(ProcessPackageResolution::isNative),
                isSharedUid = processResolutions.any(ProcessPackageResolution::isSharedUid),
                cpuPercentBasisPoints = cpuValues.takeIf { it.isNotEmpty() }
                    ?.sum()
                    ?.coerceAtMost(MAX_BASIS_POINTS),
                cpuComplete = cpuValues.size == processes.size,
                rssKb = rssValues.takeIf { it.isNotEmpty() }?.saturatedSum(),
                rssComplete = rssValues.size == processes.size,
                pssKb = pssValues.takeIf { it.isNotEmpty() }?.saturatedSum(),
                pssComplete = pssValues.size == processes.size,
                processes = processes.sortedWith(
                    compareByDescending<ProcessMetric> { it.cpuPercentBasisPoints ?: -1 }
                        .thenBy { it.key.pid },
                ),
            )
        }.sortedWith(
            compareByDescending<ApplicationAggregate> { it.cpuPercentBasisPoints ?: -1 }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.displayName }
                .thenBy(ApplicationAggregate::stableId),
        )
    }

    private fun List<Long>.saturatedSum(): Long {
        var sum = 0L
        forEach { value ->
            if (Long.MAX_VALUE - sum < value) return Long.MAX_VALUE
            sum += value
        }
        return sum
    }

    private const val MAX_BASIS_POINTS = 10_000
}
