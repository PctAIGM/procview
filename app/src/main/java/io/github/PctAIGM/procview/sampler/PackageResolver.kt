package io.github.PctAIGM.procview.sampler

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.SystemClock
import io.github.PctAIGM.procview.model.ProcessCatalogEntry
import io.github.PctAIGM.procview.model.ProcessKey
import java.util.LinkedHashMap
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

fun interface ProcessPackageResolver {
    suspend fun resolve(catalog: List<ProcessCatalogEntry>): List<ProcessPackageResolution>
}

class AndroidPackageResolver(
    private val packageManager: PackageManager,
    monotonicTimeMs: () -> Long = SystemClock::elapsedRealtime,
) : ProcessPackageResolver {
    private val uidPackagesCache = ExpiringBoundedCache<Int, List<String>>(
        maxEntries = MAX_UID_CACHE_ENTRIES,
        ttlMs = PACKAGE_CACHE_TTL_MS,
        monotonicTimeMs = monotonicTimeMs,
    )
    private val packageMetadataCache = ExpiringBoundedCache<String, CachedPackageMetadata>(
        maxEntries = MAX_PACKAGE_CACHE_ENTRIES,
        ttlMs = PACKAGE_CACHE_TTL_MS,
        monotonicTimeMs = monotonicTimeMs,
    )

    override suspend fun resolve(catalog: List<ProcessCatalogEntry>): List<ProcessPackageResolution> =
        withContext(Dispatchers.IO) {
            val uidPackages = mutableMapOf<Int, List<String>>()
            val packageMetadata = mutableMapOf<String, CachedPackageMetadata>()

            catalog.map { entry ->
                val uid = entry.uid
                val candidates = if (uid == null) {
                    emptyList()
                } else {
                    uidPackages.getOrPut(uid) {
                        try {
                            uidPackagesCache.getOrLoad(uid) {
                                packageManager.getPackagesForUid(uid)
                                    .orEmpty()
                                    .filter(String::isNotBlank)
                                    .distinct()
                                    .sorted()
                            }
                        } catch (_: RuntimeException) {
                            // A transient PackageManager/Binder failure is not an authoritative
                            // native-process result and must not poison the cross-frame cache.
                            emptyList()
                        }
                    }
                }
                val primary = PackageCandidateSelector.selectPrimary(entry.processName, candidates)
                val candidateIsSystem = candidates.any { packageName ->
                    packageMetadata.getOrPut(packageName) { loadPackageMetadata(packageName) }
                        .isSystem
                }
                val label = primary?.let { packageName ->
                    packageMetadata.getOrPut(packageName) { loadPackageMetadata(packageName) }.label
                }
                ProcessPackageResolution(
                    key = entry.key,
                    packageCandidates = candidates,
                    primaryPackage = primary,
                    displayName = label,
                    isSystem = candidateIsSystem ||
                        (uid != null && uid < FIRST_APPLICATION_UID),
                    isNative = candidates.isEmpty(),
                    isSharedUid = candidates.size > 1,
                )
            }
        }

    private fun loadPackageMetadata(packageName: String): CachedPackageMetadata = try {
        packageMetadataCache.getOrLoad(packageName) {
            val info = loadApplicationInfo(packageName)
            CachedPackageMetadata(
                isSystem = hasApplicationFlag(info?.flags, ApplicationInfo.FLAG_SYSTEM),
                label = runCatching {
                    info?.let {
                        packageManager.getApplicationLabel(it).toString().takeIf(String::isNotBlank)
                    }
                }.getOrNull(),
            )
        }
    } catch (_: RuntimeException) {
        // Preserve the current frame with unresolved metadata and retry on the next catalog
        // revision instead of caching a transient Binder failure as an installed-app fact.
        CachedPackageMetadata(isSystem = false, label = null)
    }

    @Suppress("DEPRECATION")
    private fun loadApplicationInfo(packageName: String): ApplicationInfo? = try {
        packageManager.getApplicationInfo(packageName, 0)
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    private data class CachedPackageMetadata(
        val isSystem: Boolean,
        val label: String?,
    )

    private companion object {
        const val FIRST_APPLICATION_UID = 10_000
        const val PACKAGE_CACHE_TTL_MS = 30_000L
        const val MAX_UID_CACHE_ENTRIES = 512
        const val MAX_PACKAGE_CACHE_ENTRIES = 1_024
    }
}

internal class ExpiringBoundedCache<K, V>(
    private val maxEntries: Int,
    private val ttlMs: Long,
    private val monotonicTimeMs: () -> Long,
) {
    private val lock = Any()
    private val entries = LinkedHashMap<K, TimedValue<V>>(16, 0.75f, true)

    init {
        require(maxEntries > 0) { "cache capacity must be positive" }
        require(ttlMs > 0) { "cache TTL must be positive" }
    }

    fun getOrLoad(key: K, loader: () -> V): V {
        val nowMs = monotonicTimeMs()
        val cached = synchronized(lock) {
            entries[key]?.takeIf { value -> nowMs < value.expiresAtMs }
                ?: run {
                    entries.remove(key)
                    null
                }
        }
        if (cached != null) return cached.value

        val loaded = loader()
        synchronized(lock) {
            entries[key] = TimedValue(
                value = loaded,
                expiresAtMs = saturatedAdd(monotonicTimeMs(), ttlMs),
            )
            while (entries.size > maxEntries) {
                val iterator = entries.entries.iterator()
                if (!iterator.hasNext()) break
                iterator.next()
                iterator.remove()
            }
        }
        return loaded
    }

    private data class TimedValue<V>(
        val value: V,
        val expiresAtMs: Long,
    )

    private companion object {
        fun saturatedAdd(value: Long, increment: Long): Long =
            if (value > Long.MAX_VALUE - increment) Long.MAX_VALUE else value + increment
    }
}

internal fun hasApplicationFlag(flags: Int?, flag: Int): Boolean =
    flags?.let { value -> value and flag != 0 } == true
