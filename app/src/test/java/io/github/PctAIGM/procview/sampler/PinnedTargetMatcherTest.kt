package io.github.PctAIGM.procview.sampler

import io.github.PctAIGM.procview.model.PinnedTarget
import io.github.PctAIGM.procview.model.ProcessCatalogEntry
import io.github.PctAIGM.procview.model.ProcessKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinnedTargetMatcherTest {
    private val appKey = ProcessKey(10, 100)
    private val workerKey = ProcessKey(11, 101)
    private val nativeKey = ProcessKey(12, 102)
    private val catalog = listOf(
        entry(appKey, 10_123, "com.example"),
        entry(workerKey, 10_123, "com.example:worker"),
        entry(nativeKey, 1_000, "surfaceflinger"),
    )
    private val resolutions = listOf(
        resolution(appKey, "com.example"),
        resolution(workerKey, "com.example"),
        ProcessPackageResolution(
            key = nativeKey,
            packageCandidates = emptyList(),
            primaryPackage = null,
            displayName = null,
            isSystem = true,
            isNative = true,
            isSharedUid = false,
        ),
    )

    @Test
    fun packagePinMatchesEveryCurrentChildProcess() {
        val keys = PinnedTargetMatcher.matchingKeys(
            targets = setOf(PinnedTarget.packageTarget("com.example")),
            catalog = catalog,
            resolutions = resolutions,
        )

        assertEquals(setOf(appKey, workerKey), keys)
    }

    @Test
    fun processAndNativePinsRemainNarrow() {
        val keys = PinnedTargetMatcher.matchingKeys(
            targets = setOf(
                PinnedTarget.packageProcessTarget("com.example", "com.example:worker"),
                PinnedTarget.commandUidTarget("surfaceflinger", 1_000),
            ),
            catalog = catalog,
            resolutions = resolutions,
        )

        assertEquals(setOf(workerKey, nativeKey), keys)
    }

    @Test
    fun sharedUidApplicationPinMatchesEveryProcessInTheUid() {
        val first = entry(ProcessKey(20, 200), 10_321, "shared.one")
        val second = entry(ProcessKey(21, 201), 10_321, "shared.two")
        val application = ApplicationAggregate(
            stableId = "uid:10321",
            primaryPackage = null,
            packageCandidates = listOf("com.one", "com.two"),
            displayName = "Shared UID",
            uid = 10_321,
            isSystem = false,
            isNative = false,
            isSharedUid = true,
            cpuPercentBasisPoints = 500,
            cpuComplete = true,
            rssKb = 1_000L,
            rssComplete = true,
            pssKb = null,
            pssComplete = false,
            processes = emptyList(),
        )
        val target = requireNotNull(
            PinnedTargetMatcher.targetForApplication(
                application = application,
                catalog = listOf(first, second),
                resolutions = emptyList(),
            ),
        )

        assertEquals(PinnedTarget.uidTarget(10_321), target)
        assertEquals(
            setOf(first.key, second.key),
            PinnedTargetMatcher.matchingKeys(
                targets = setOf(target),
                catalog = listOf(first, second),
                resolutions = emptyList(),
            ),
        )
    }

    @Test
    fun malformedTargetsNeverMatch() {
        val invalid = PinnedTarget.packageTarget(" ")
        assertTrue(!invalid.isValid())
        assertTrue(!PinnedTarget.uidTarget(-1).isValid())
        assertTrue(!PinnedTarget.commandUidTarget("surfaceflinger", -1).isValid())
        assertTrue(
            PinnedTargetMatcher.matchingKeys(setOf(invalid), catalog, resolutions).isEmpty(),
        )
    }

    private fun entry(key: ProcessKey, uid: Int, name: String) = ProcessCatalogEntry(
        key = key,
        parentPid = 1,
        uid = uid,
        processName = name,
        commandLine = name,
    )

    private fun resolution(key: ProcessKey, packageName: String) = ProcessPackageResolution(
        key = key,
        packageCandidates = listOf(packageName),
        primaryPackage = packageName,
        displayName = "Example",
        isSystem = false,
        isNative = false,
        isSharedUid = false,
    )
}
