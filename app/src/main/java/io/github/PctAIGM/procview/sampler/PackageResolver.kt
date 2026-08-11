package io.github.PctAIGM.procview.sampler

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import io.github.PctAIGM.procview.model.ProcessCatalogEntry
import io.github.PctAIGM.procview.model.ProcessKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ProcessPackageResolution(
    val key: ProcessKey,
    val packageCandidates: List<String>,
    val primaryPackage: String?,
    val displayName: String?,
    val isSystem: Boolean,
    val isNative: Boolean,
    val isSharedUid: Boolean,
)

object PackageCandidateSelector {
    fun selectPrimary(processName: String, candidates: List<String>): String? {
        val normalized = candidates.asSequence().filter(String::isNotBlank).distinct().sorted().toList()
        if (normalized.size == 1) return normalized.single()
        return normalized
            .filter { candidate -> processName == candidate || processName.startsWith("$candidate:") }
            .maxByOrNull(String::length)
    }
}

class AndroidPackageResolver(
    private val packageManager: PackageManager,
) {
    suspend fun resolve(catalog: List<ProcessCatalogEntry>): List<ProcessPackageResolution> =
        withContext(Dispatchers.IO) {
            val uidPackages = mutableMapOf<Int, List<String>>()
            val applicationInfo = mutableMapOf<String, ApplicationInfo?>()
            val labels = mutableMapOf<String, String?>()

            catalog.map { entry ->
                val uid = entry.uid
                val candidates = if (uid == null) {
                    emptyList()
                } else {
                    uidPackages.getOrPut(uid) {
                        runCatching { packageManager.getPackagesForUid(uid) }
                            .getOrNull()
                            .orEmpty()
                            .filter(String::isNotBlank)
                            .distinct()
                            .sorted()
                    }
                }
                val primary = PackageCandidateSelector.selectPrimary(entry.processName, candidates)
                val info = primary?.let { packageName ->
                    applicationInfo.getOrPut(packageName) { loadApplicationInfo(packageName) }
                }
                val label = primary?.let { packageName ->
                    labels.getOrPut(packageName) {
                        runCatching {
                            info?.let {
                                packageManager.getApplicationLabel(it).toString().takeIf(String::isNotBlank)
                            }
                        }.getOrNull()
                    }
                }
                ProcessPackageResolution(
                    key = entry.key,
                    packageCandidates = candidates,
                    primaryPackage = primary,
                    displayName = label,
                    isSystem = (info?.flags?.and(ApplicationInfo.FLAG_SYSTEM) ?: 0) != 0 ||
                        (uid != null && uid < FIRST_APPLICATION_UID),
                    isNative = candidates.isEmpty(),
                    isSharedUid = candidates.size > 1,
                )
            }
        }

    @Suppress("DEPRECATION")
    private fun loadApplicationInfo(packageName: String): ApplicationInfo? = runCatching {
        packageManager.getApplicationInfo(packageName, 0)
    }.getOrNull()

    private companion object {
        const val FIRST_APPLICATION_UID = 10_000
    }
}
