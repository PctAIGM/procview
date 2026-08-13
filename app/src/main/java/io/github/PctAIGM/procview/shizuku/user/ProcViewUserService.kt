package io.github.PctAIGM.procview.shizuku.user

import android.content.Context
import android.os.Process
import android.system.Os
import android.system.OsConstants
import androidx.annotation.Keep
import io.github.PctAIGM.procview.model.MetricFrameFlags
import io.github.PctAIGM.procview.sampler.procfs.ProcParsers
import io.github.PctAIGM.procview.sampler.procfs.ProcFileReader
import io.github.PctAIGM.procview.sampler.procfs.ProcSnapshot
import io.github.PctAIGM.procview.sampler.procfs.ProcSnapshotReader
import io.github.PctAIGM.procview.sampler.procfs.PsFallbackIdentityTracker
import io.github.PctAIGM.procview.sampler.procfs.PsFallbackParser
import io.github.PctAIGM.procview.sampler.procfs.PsFallbackUnits
import io.github.PctAIGM.procview.model.ProcessCatalogEntry
import io.github.PctAIGM.procview.model.ProcessKey
import io.github.PctAIGM.procview.model.RawProcessMetric
import io.github.PctAIGM.procview.shizuku.ipc.CapabilityProbeParcel
import io.github.PctAIGM.procview.shizuku.ipc.IProcViewUserService
import io.github.PctAIGM.procview.shizuku.ipc.IpcCodes
import io.github.PctAIGM.procview.shizuku.ipc.ProcessCatalogChunkParcel
import io.github.PctAIGM.procview.shizuku.ipc.ProcessCatalogEntryParcel
import io.github.PctAIGM.procview.shizuku.ipc.ProcessKeyParcel
import io.github.PctAIGM.procview.shizuku.ipc.ProcessMetricParcel
import io.github.PctAIGM.procview.shizuku.ipc.PssResultParcel
import io.github.PctAIGM.procview.shizuku.ipc.PssValueParcel
import io.github.PctAIGM.procview.shizuku.ipc.RawMetricFrameParcel
import java.io.File

@Keep
class ProcViewUserService : IProcViewUserService.Stub {
    private val commandRunner = ReadOnlyCommandRunner()
    private val snapshotReader = ProcSnapshotReader()
    private val fallbackIdentityTracker = PsFallbackIdentityTracker()
    private val sampleLock = Any()
    private val clockTicksPerSecond = runCatching { Os.sysconf(OsConstants._SC_CLK_TCK) }
        .getOrNull()
        ?.takeIf { it > 0 }
        ?: DEFAULT_CLOCK_TICKS_PER_SECOND
    private val pageSizeKb = runCatching {
        Os.sysconf(OsConstants._SC_PAGESIZE) / BYTES_PER_KILOBYTE
    }.getOrNull()?.takeIf { it > 0 } ?: DEFAULT_PAGE_SIZE_KB
    private var sequence = 0L
    private var catalogRevision = 0L
    private var currentCatalog: List<ProcessCatalogEntry> = emptyList()
    private var currentSourceCode = IpcCodes.SOURCE_PROCFS
    private var lastPssRequestElapsedNanos = Long.MIN_VALUE
    private var cachedCapabilityProbe: CapabilityProbeParcel? = null
    private var cachedCapabilityProbeCompletedNanos = Long.MIN_VALUE
    @Volatile
    private var preferPsFallback = false

    constructor()

    @Keep
    constructor(@Suppress("UNUSED_PARAMETER") context: Context)

    override fun getProtocolVersion(): Int = PROTOCOL_VERSION

    override fun getBootId(): String = ProcFileReader.readText(
        File("/proc/sys/kernel/random/boot_id"),
        MAX_BOOT_ID_BYTES,
    )?.trim()
        ?.takeIf { it.isNotEmpty() && it.length <= MAX_BOOT_ID_CHARS }
        ?: throw IllegalStateException("boot ID is unavailable")

    @Synchronized
    override fun runCapabilityProbe(): CapabilityProbeParcel {
        val requestedAtNanos = System.nanoTime()
        val cached = cachedCapabilityProbe
        val cacheAgeNanos = if (cachedCapabilityProbeCompletedNanos == Long.MIN_VALUE) {
            Long.MAX_VALUE
        } else {
            requestedAtNanos - cachedCapabilityProbeCompletedNanos
        }
        if (cached != null &&
            cacheAgeNanos in 0L..CAPABILITY_PROBE_REUSE_NANOS
        ) {
            return cached
        }
        val startedWallTime = System.currentTimeMillis()
        val startedNanos = System.nanoTime()
        var errors = 0

        val procStatReadable = ProcFileReader.readText(
            File("/proc/stat"),
            MAX_CORE_PROC_BYTES,
        )?.startsWith("cpu ") == true
        if (!procStatReadable) errors = errors or ProbeErrorFlags.PROC_STAT

        val procMeminfoReadable = ProcFileReader.readText(
            File("/proc/meminfo"),
            MAX_CORE_PROC_BYTES,
        )?.contains("MemTotal:") == true
        if (!procMeminfoReadable) errors = errors or ProbeErrorFlags.PROC_MEMINFO

        val bootId = ProcFileReader.readText(
            File("/proc/sys/kernel/random/boot_id"),
            MAX_BOOT_ID_BYTES,
        )?.trim()?.take(MAX_BOOT_ID_CHARS).orEmpty()
        if (bootId.isEmpty()) errors = errors or ProbeErrorFlags.BOOT_ID

        val processScan = ProcCapabilityScanner.scanProcesses()
        if (processScan.pidCount == 0) errors = errors or ProbeErrorFlags.PROC_ENUMERATION

        val psResult = runCatching { commandRunner.listAllPids() }.getOrNull()
        val psAvailable = psResult?.let {
            it.exitCode == 0 && !it.timedOut && !it.truncated
        } == true
        val psPids = if (psAvailable) ProcParsers.parsePsPids(psResult.output) else emptySet()
        val psPidCount = psPids.size
        val metricReferencePids = psPids.takeIf { it.isNotEmpty() } ?: processScan.knownPids
        val statReadableCount = processScan.statReadablePids.count(metricReferencePids::contains)
        val statusReadableCount = processScan.statusReadablePids.count(metricReferencePids::contains)
        val cmdlineReadableCount = processScan.cmdlineReadablePids.count(metricReferencePids::contains)
        val rssReadableCount = processScan.rssReadablePids.count(metricReferencePids::contains)
        val cpuAndRssReadableCount = processScan.cpuAndRssReadablePids.count(
            metricReferencePids::contains,
        )
        val fallbackResult = runCatching { commandRunner.readProcessSnapshot() }.getOrNull()
        val fallbackCommandCompleted = fallbackResult?.let {
            it.exitCode == 0 && !it.timedOut
        } == true
        val fallbackParsed = if (fallbackCommandCompleted) {
            PsFallbackParser.parse(fallbackResult?.output.orEmpty())
        } else {
            null
        }
        val fallbackTruncated = fallbackResult?.truncated == true ||
            fallbackParsed?.truncated == true
        val fallbackAvailable = psAvailable &&
            fallbackCommandCompleted &&
            !fallbackTruncated &&
            fallbackParsed?.processes?.isNotEmpty() == true
        val fallbackCpuAndRssReadableCount = fallbackParsed?.processes.orEmpty().count { process ->
            process.pid in metricReferencePids && process.rssAtFourKilobytePagesKb != null
        }
        val pathDecision = CapabilityPathSelector.select(
            procReadableCount = cpuAndRssReadableCount,
            fallbackReadableCount = fallbackCpuAndRssReadableCount,
            referenceCount = metricReferencePids.size,
            procTruncated = processScan.truncated,
            fallbackAvailable = fallbackAvailable,
            fallbackTruncated = fallbackTruncated,
        )
        preferPsFallback = pathDecision.useFallback
        if (!psAvailable || !fallbackAvailable || psPidCount == 0) {
            errors = errors or ProbeErrorFlags.PS_COMMAND
        }
        if ((fallbackParsed?.malformedLineCount ?: 0) > 0) {
            errors = errors or ProbeErrorFlags.FALLBACK_PARSE
        }
        if (psResult?.truncated == true || fallbackTruncated) {
            errors = errors or ProbeErrorFlags.COMMAND_OUTPUT_TRUNCATED
        }

        val servicePid = Process.myPid()
        val knownPids = processScan.knownPids + psPids + servicePid
        val pssProbeResult = runCatching {
            commandRunner.readPssCheckin(servicePid, knownPids)
        }.getOrNull()
        val pssProbeAvailable = pssProbeResult?.let {
            it.exitCode == 0 && !it.timedOut && !it.truncated
        } == true
        val pssProbeKb = if (pssProbeAvailable) {
            ProcParsers.parseCheckinTotalPssKb(pssProbeResult.output, servicePid)
        } else {
            null
        }
        val pssReferencePids = metricReferencePids
        val pssResult = runCatching { commandRunner.readPssCheckinBatch() }.getOrNull()
        val pssAvailable = pssResult?.let {
            it.exitCode == 0 && !it.timedOut && !it.truncated
        } == true
        val pssByPid = if (pssAvailable) {
            ProcParsers.parseCheckinTotalPssByPid(
                pssResult.output,
                pssReferencePids + servicePid,
            )
        } else {
            emptyMap()
        }
        val pssReadableCount = pssByPid.keys.count(pssReferencePids::contains)
        if (!pssProbeAvailable || !pssAvailable) errors = errors or ProbeErrorFlags.PSS_COMMAND
        if ((pssProbeAvailable && pssProbeKb == null) ||
            (pssAvailable && pssReadableCount == 0)
        ) {
            errors = errors or ProbeErrorFlags.PSS_PARSE
        }
        if (pssProbeResult?.truncated == true || pssResult?.truncated == true) {
            errors = errors or ProbeErrorFlags.COMMAND_OUTPUT_TRUNCATED
        }

        val thermal = ProcCapabilityScanner.scanThermalZones()
        if (thermal.zoneCount == 0 || thermal.readableCount == 0) {
            errors = errors or ProbeErrorFlags.THERMAL
        }

        val result = CapabilityProbeParcel().also { result ->
            result.protocolVersion = PROTOCOL_VERSION
            result.servicePid = servicePid
            result.serviceUid = Process.myUid()
            result.probeStartedWallTimeMs = startedWallTime
            result.totalDurationMs = (System.nanoTime() - startedNanos) / NANOS_PER_MILLISECOND
            result.procScanDurationMs = processScan.durationMs
            result.procStatReadable = procStatReadable
            result.procMeminfoReadable = procMeminfoReadable
            result.bootIdReadable = bootId.isNotEmpty()
            result.bootId = bootId
            result.procPidCount = processScan.pidCount
            result.psPidCount = psPidCount
            result.statReadableCount = statReadableCount
            result.statusReadableCount = statusReadableCount
            result.cmdlineReadableCount = cmdlineReadableCount
            result.rssReadableCount = rssReadableCount
            result.cpuAndRssReadableCount = pathDecision.effectiveReadableCount
            result.pid1StatReadable = 1 in processScan.knownPids &&
                ProcFileReader.readText(File("/proc/1/stat"), 4096)
                    ?.let(ProcParsers::parseProcessStat) != null
            // This field retains its original meaning: the independent `ps -A -o PID`
            // reference enumeration succeeded. Fixed-snapshot viability is reflected by
            // the effective coverage count and the PS/FALLBACK_PARSE error flags.
            result.psCommandAvailable = psAvailable
            result.pssCommandAvailable = pssAvailable
            result.pssValueParsed = pssReadableCount > 0
            result.pssReadableCount = pssReadableCount
            result.pssProbeKb = pssProbeKb ?: -1L
            result.pssProbeDurationMs = pssProbeResult?.durationMs ?: 0L
            result.pssBatchProbeDurationMs = pssResult?.durationMs ?: 0L
            result.thermalZoneCount = thermal.zoneCount
            result.thermalReadableCount = thermal.readableCount
            result.errorFlags = errors
            result.processListTruncated = pathDecision.selectedPathTruncated
            result.psSnapshotAvailable = fallbackAvailable
            result.psSnapshotPidCount = fallbackParsed?.processes?.count { process ->
                process.pid in metricReferencePids
            } ?: 0
            result.psSnapshotCpuAndRssReadableCount = fallbackCpuAndRssReadableCount
            result.psSnapshotDurationMs = fallbackResult?.durationMs ?: 0L
            result.psFallbackSelected = pathDecision.useFallback
            result.sampledUids = processScan.sampledUids
            result.thermalSensorNames = thermal.sensorNames
        }
        cachedCapabilityProbe = result
        cachedCapabilityProbeCompletedNanos = System.nanoTime()
        return result
    }

    override fun collectMetricFrame(): RawMetricFrameParcel = synchronized(sampleLock) {
        val sample = if (preferPsFallback) {
            readPsFallbackSample() ?: snapshotReader.read().toSample()
        } else {
            val procSample = snapshotReader.read()
            if (procSample.metrics.isEmpty()) readPsFallbackSample() ?: procSample.toSample()
            else procSample.toSample()
        }
        if (sample.catalog != currentCatalog) {
            currentCatalog = sample.catalog
            catalogRevision++
        }
        currentSourceCode = sample.sourceCode
        sequence++
        RawMetricFrameParcel().also { frame ->
            frame.sequence = sequence
            frame.elapsedRealtimeNanos = sample.elapsedRealtimeNanos
            frame.wallTimeMillis = sample.wallTimeMillis
            frame.systemTotalCpuTicks = sample.systemTotalCpuTicks ?: -1L
            frame.systemIdleCpuTicks = sample.systemIdleCpuTicks ?: -1L
            frame.memoryTotalKb = sample.memoryTotalKb ?: -1L
            frame.memoryAvailableKb = sample.memoryAvailableKb ?: -1L
            frame.collectionDurationMs = sample.collectionDurationMs
            frame.catalogRevision = catalogRevision
            frame.sourceCode = sample.sourceCode
            frame.frameFlags = sample.frameFlags
            frame.metrics = sample.metrics.map { metric ->
                ProcessMetricParcel().also { parcel ->
                    parcel.pid = metric.key.pid
                    parcel.startTimeTicks = metric.key.startTimeTicks
                    parcel.cpuTicks = metric.cpuTicks
                    parcel.rssKb = metric.rssKb ?: -1L
                    parcel.stateCode = metric.state.code
                }
            }.toTypedArray()
        }
    }

    private fun ProcSnapshot.toSample(): UserServiceSample = UserServiceSample(
        elapsedRealtimeNanos = elapsedRealtimeNanos,
        wallTimeMillis = wallTimeMillis,
        systemTotalCpuTicks = systemCpu?.totalTicks,
        systemIdleCpuTicks = systemCpu?.idleTicks,
        memoryTotalKb = systemMemory?.totalKb,
        memoryAvailableKb = systemMemory?.availableKb,
        collectionDurationMs = collectionDurationMs,
        sourceCode = IpcCodes.SOURCE_PROCFS,
        frameFlags = frameFlags,
        metrics = metrics,
        catalog = catalog,
    )

    private fun readPsFallbackSample(): UserServiceSample? {
        val startedNanos = System.nanoTime()
        val command = runCatching { commandRunner.readProcessSnapshot() }.getOrNull()
            ?: return null
        if (command.exitCode != 0 || command.timedOut) return null
        val parsed = PsFallbackParser.parse(command.output)
        if (parsed.processes.isEmpty()) return null
        val sampledAtNanos = android.os.SystemClock.elapsedRealtimeNanos()
        val keyed = fallbackIdentityTracker.assign(parsed.processes, sampledAtNanos)
        val systemCpu = ProcFileReader.readText(File("/proc/stat"), MAX_CORE_PROC_BYTES)
            ?.let(ProcParsers::parseSystemCpuStat)
        val systemMemory = ProcFileReader.readText(File("/proc/meminfo"), MAX_CORE_PROC_BYTES)
            ?.let(ProcParsers::parseSystemMemoryInfo)
        var flags = MetricFrameFlags.NONE
        if (systemCpu == null) flags = flags or MetricFrameFlags.SYSTEM_CPU_UNREADABLE
        if (systemMemory == null) flags = flags or MetricFrameFlags.SYSTEM_MEMORY_UNREADABLE
        if (command.truncated || parsed.truncated) {
            flags = flags or MetricFrameFlags.PROCESS_LIST_TRUNCATED
        }
        if (parsed.malformedLineCount > 0) {
            flags = flags or MetricFrameFlags.FALLBACK_PARSE_PARTIAL
        }
        val catalog = keyed.map { (process, key) ->
            ProcessCatalogEntry(
                key = key,
                parentPid = process.parentPid,
                uid = process.uid,
                processName = process.processName.take(MAX_PROCESS_NAME_CHARS),
                commandLine = process.commandLine.take(MAX_COMMAND_LINE_CHARS),
            )
        }
        val metrics = keyed.map { (process, key) ->
            RawProcessMetric(
                key = key,
                cpuTicks = process.cpuCentiseconds,
                rssKb = process.rssAtFourKilobytePagesKb?.let {
                    PsFallbackUnits.rssToKb(it, pageSizeKb)
                },
                state = process.state,
            )
        }
        return UserServiceSample(
            elapsedRealtimeNanos = sampledAtNanos,
            wallTimeMillis = System.currentTimeMillis(),
            systemTotalCpuTicks = systemCpu?.totalTicks?.let {
                PsFallbackUnits.cpuTicksToCentiseconds(it, clockTicksPerSecond)
            },
            systemIdleCpuTicks = systemCpu?.idleTicks?.let {
                PsFallbackUnits.cpuTicksToCentiseconds(it, clockTicksPerSecond)
            },
            memoryTotalKb = systemMemory?.totalKb,
            memoryAvailableKb = systemMemory?.availableKb,
            collectionDurationMs = ((System.nanoTime() - startedNanos).coerceAtLeast(0L)) /
                NANOS_PER_MILLISECOND,
            sourceCode = IpcCodes.SOURCE_PS_FALLBACK,
            frameFlags = flags,
            metrics = metrics,
            catalog = catalog,
        )
    }

    override fun getCatalogChunk(
        expectedRevision: Long,
        offset: Int,
        limit: Int,
    ): ProcessCatalogChunkParcel = synchronized(sampleLock) {
        require(expectedRevision >= 0) { "expectedRevision must not be negative" }
        require(offset >= 0) { "offset must not be negative" }
        require(limit in 1..MAX_CATALOG_CHUNK_ENTRIES) { "invalid catalog chunk limit" }

        val restartRequired = expectedRevision != 0L &&
            expectedRevision != catalogRevision &&
            offset > 0
        val safeOffset = if (restartRequired) 0 else offset
        require(safeOffset <= currentCatalog.size) { "catalog offset exceeds entry count" }
        val end = minOf(safeOffset + limit, currentCatalog.size)
        val entries = if (restartRequired) emptyList() else currentCatalog.subList(safeOffset, end)

        ProcessCatalogChunkParcel().also { chunk ->
            chunk.revision = catalogRevision
            chunk.restartRequired = restartRequired
            chunk.offset = safeOffset
            chunk.totalEntries = currentCatalog.size
            chunk.nextOffset = when {
                restartRequired -> 0
                end < currentCatalog.size -> end
                else -> -1
            }
            chunk.entries = entries.map { entry -> entry.toParcel() }.toTypedArray()
        }
    }

    @Synchronized
    override fun readPss(keys: Array<ProcessKeyParcel>): PssResultParcel {
        require(keys.size <= MAX_PSS_KEYS) { "too many PSS keys" }
        val requestedKeys = keys.map { parcel ->
            ProcessKey(parcel.pid, parcel.startTimeTicks)
        }.distinct()
        if (requestedKeys.isEmpty()) return emptyPssResult()
        val requestTime = android.os.SystemClock.elapsedRealtimeNanos()
        if (lastPssRequestElapsedNanos != Long.MIN_VALUE &&
            requestTime - lastPssRequestElapsedNanos < MIN_PSS_REQUEST_INTERVAL_NANOS
        ) {
            throw IllegalStateException("PSS request rate limit exceeded")
        }
        val (knownKeys, sourceCode) = synchronized(sampleLock) {
            currentCatalog.asSequence().map { it.key }.toSet() to currentSourceCode
        }
        if (!knownKeys.containsAll(requestedKeys)) {
            throw SecurityException("PSS keys must belong to the current process catalog")
        }
        lastPssRequestElapsedNanos = requestTime

        val commandResult = runCatching { commandRunner.readPssCheckinBatch() }.getOrNull()
        val commandAvailable = commandResult?.let { it.exitCode == 0 && !it.timedOut } == true
        val pssByPid = if (commandAvailable) {
            ProcParsers.parseCheckinTotalPssByPid(
                commandResult.output,
                requestedKeys.asSequence().map { it.pid }.toSet(),
            )
        } else {
            emptyMap()
        }
        var errorFlags = 0
        if (!commandAvailable || pssByPid.isEmpty()) {
            errorFlags = errorFlags or IpcCodes.PSS_COMMAND_FAILED
        }
        if (commandResult?.truncated == true) {
            errorFlags = errorFlags or IpcCodes.PSS_OUTPUT_TRUNCATED
        }
        val fallbackValidationKeys = if (sourceCode == IpcCodes.SOURCE_PS_FALLBACK) {
            readCurrentFallbackKeys()
        } else {
            null
        }
        if (sourceCode == IpcCodes.SOURCE_PS_FALLBACK && fallbackValidationKeys == null) {
            errorFlags = errorFlags or IpcCodes.PSS_IDENTITY_CHANGED
        }

        val values = requestedKeys.mapNotNull { key ->
            val identityValid = if (sourceCode == IpcCodes.SOURCE_PS_FALLBACK) {
                key in fallbackValidationKeys.orEmpty()
            } else {
                val currentStat = ProcFileReader.readText(
                    File("/proc/${key.pid}/stat"),
                    MAX_PROCESS_STAT_BYTES,
                )?.let(ProcParsers::parseProcessStat)
                currentStat?.pid == key.pid && currentStat.startTimeTicks == key.startTimeTicks
            }
            if (!identityValid) {
                errorFlags = errorFlags or IpcCodes.PSS_IDENTITY_CHANGED
                return@mapNotNull null
            }
            val pssKb = pssByPid[key.pid] ?: return@mapNotNull null
            PssValueParcel().also { value ->
                value.pid = key.pid
                value.startTimeTicks = key.startTimeTicks
                value.pssKb = pssKb
            }
        }

        return PssResultParcel().also { result ->
            result.sampledAtElapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
            result.durationMs =
                (result.sampledAtElapsedRealtimeNanos - requestTime).coerceAtLeast(0L) /
                NANOS_PER_MILLISECOND
            result.commandAvailable = commandAvailable
            result.timedOut = commandResult?.timedOut == true
            result.outputTruncated = commandResult?.truncated == true
            result.errorFlags = errorFlags
            result.values = values.toTypedArray()
        }
    }

    private fun readCurrentFallbackKeys(): Set<ProcessKey>? {
        val command = runCatching { commandRunner.readProcessSnapshot() }.getOrNull()
            ?: return null
        if (command.exitCode != 0 || command.timedOut || command.truncated) return null
        val parsed = PsFallbackParser.parse(command.output)
        if (parsed.processes.isEmpty() || parsed.truncated) return null
        val sampledAtNanos = android.os.SystemClock.elapsedRealtimeNanos()
        return synchronized(sampleLock) {
            fallbackIdentityTracker.assign(parsed.processes, sampledAtNanos)
                .asSequence()
                .map { it.second }
                .toSet()
        }
    }

    override fun destroy() {
        commandRunner.close()
        System.exit(0)
    }

    private fun ProcessCatalogEntry.toParcel(): ProcessCatalogEntryParcel =
        ProcessCatalogEntryParcel().also { parcel ->
            parcel.pid = key.pid
            parcel.startTimeTicks = key.startTimeTicks
            parcel.parentPid = parentPid
            parcel.uid = uid ?: -1
            parcel.processName = processName
            parcel.commandLine = commandLine
        }

    private fun emptyPssResult(): PssResultParcel = PssResultParcel().also { result ->
        result.sampledAtElapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
        result.values = emptyArray()
    }

    private data class UserServiceSample(
        val elapsedRealtimeNanos: Long,
        val wallTimeMillis: Long,
        val systemTotalCpuTicks: Long?,
        val systemIdleCpuTicks: Long?,
        val memoryTotalKb: Long?,
        val memoryAvailableKb: Long?,
        val collectionDurationMs: Long,
        val sourceCode: Int,
        val frameFlags: Int,
        val metrics: List<RawProcessMetric>,
        val catalog: List<ProcessCatalogEntry>,
    )

    private companion object {
        const val PROTOCOL_VERSION = 4
        const val MAX_CORE_PROC_BYTES = 128 * 1024
        const val MAX_BOOT_ID_BYTES = 256
        const val MAX_BOOT_ID_CHARS = 128
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val MAX_CATALOG_CHUNK_ENTRIES = 32
        const val MAX_PSS_KEYS = 128
        const val MAX_PROCESS_STAT_BYTES = 4096
        const val MIN_PSS_REQUEST_INTERVAL_NANOS = 1_000_000_000L
        const val MAX_PROCESS_NAME_CHARS = 256
        const val MAX_COMMAND_LINE_CHARS = 4096
        const val BYTES_PER_KILOBYTE = 1024L
        const val DEFAULT_PAGE_SIZE_KB = 4L
        const val DEFAULT_CLOCK_TICKS_PER_SECOND = 100L
        const val CAPABILITY_PROBE_REUSE_NANOS = 2_000_000_000L
    }
}
