package io.github.PctAIGM.procview.sampler

import io.github.PctAIGM.procview.model.PinnedTarget
import io.github.PctAIGM.procview.model.PinnedTargetKind
import io.github.PctAIGM.procview.model.ProcessCatalogEntry
import io.github.PctAIGM.procview.model.ProcessKey

object PinnedTargetMatcher {
    fun matchingKeys(
        targets: Set<PinnedTarget>,
        catalog: List<ProcessCatalogEntry>,
        resolutions: List<ProcessPackageResolution>,
    ): Set<ProcessKey> {
        if (targets.isEmpty()) return emptySet()
        val packageTargets = mutableSetOf<String>()
        val packageProcessTargets = mutableMapOf<String, MutableSet<String>>()
        val uidTargets = mutableSetOf<Int>()
        val commandUidTargets = mutableMapOf<String, MutableSet<Int>>()
        targets.asSequence().filter(PinnedTarget::isValid).forEach { target ->
            when (target.kind) {
                PinnedTargetKind.PACKAGE -> packageTargets += checkNotNull(target.packageName)
                PinnedTargetKind.PACKAGE_PROCESS -> packageProcessTargets
                    .getOrPut(checkNotNull(target.processName)) { mutableSetOf() }
                    .add(checkNotNull(target.packageName))
                PinnedTargetKind.UID -> uidTargets += checkNotNull(target.uid)
                PinnedTargetKind.COMMAND_UID -> commandUidTargets
                    .getOrPut(checkNotNull(target.processName)) { mutableSetOf() }
                    .add(checkNotNull(target.uid))
            }
        }
        val resolutionByKey = resolutions.associateBy(ProcessPackageResolution::key)
        return catalog.asSequence()
            .filter { entry ->
                val resolution = resolutionByKey[entry.key]
                val processPackages = packageProcessTargets[entry.processName]
                val candidatePackageMatches = resolution?.packageCandidates.orEmpty().any {
                    it in packageTargets || it in processPackages.orEmpty()
                }
                resolution?.primaryPackage?.let { packageName ->
                    packageName in packageTargets || packageName in processPackages.orEmpty()
                } == true ||
                    candidatePackageMatches ||
                    entry.uid?.let(uidTargets::contains) == true ||
                    entry.uid?.let { uid ->
                        uid in commandUidTargets[entry.processName].orEmpty()
                    } == true
            }
            .map(ProcessCatalogEntry::key)
            .toSet()
    }

    fun targetForProcess(
        entry: ProcessCatalogEntry,
        resolution: ProcessPackageResolution?,
    ): PinnedTarget? {
        val packageName = resolution?.primaryPackage
            ?: resolution?.packageCandidates?.singleOrNull()
        return if (packageName != null) {
            PinnedTarget.packageProcessTarget(packageName, entry.processName)
        } else {
            entry.uid?.let { uid -> PinnedTarget.commandUidTarget(entry.processName, uid) }
        }?.takeIf(PinnedTarget::isValid)
    }

    fun targetForApplication(
        application: ApplicationAggregate,
        catalog: List<ProcessCatalogEntry>,
        resolutions: List<ProcessPackageResolution>,
    ): PinnedTarget? {
        val packageName = application.primaryPackage
            ?: application.packageCandidates.singleOrNull()
        if (packageName != null) return PinnedTarget.packageTarget(packageName)
        if (application.isSharedUid && application.uid != null) {
            return PinnedTarget.uidTarget(application.uid)
        }

        val catalogByKey = catalog.associateBy(ProcessCatalogEntry::key)
        val resolutionByKey = resolutions.associateBy(ProcessPackageResolution::key)
        val representative = application.processes.asSequence()
            .mapNotNull { metric -> catalogByKey[metric.key] }
            .sortedWith(compareBy<ProcessCatalogEntry> { it.processName }.thenBy { it.key.pid })
            .firstOrNull()
            ?: return null
        return targetForProcess(representative, resolutionByKey[representative.key])
    }

    fun PinnedTarget.matches(
        entry: ProcessCatalogEntry,
        resolution: ProcessPackageResolution?,
    ): Boolean = when (kind) {
        PinnedTargetKind.PACKAGE -> packageName != null && (
            resolution?.primaryPackage == packageName ||
                packageName in resolution?.packageCandidates.orEmpty()
            )
        PinnedTargetKind.PACKAGE_PROCESS -> packageName != null &&
            processName == entry.processName && (
            resolution?.primaryPackage == packageName ||
                packageName in resolution?.packageCandidates.orEmpty()
            )
        PinnedTargetKind.UID -> uid != null && uid == entry.uid
        PinnedTargetKind.COMMAND_UID -> uid == entry.uid && processName == entry.processName
    }
}
