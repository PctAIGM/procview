package io.github.PctAIGM.procview.shizuku.user

import android.content.Context
import android.os.Process
import androidx.annotation.Keep
import io.github.PctAIGM.procview.sampler.procfs.ProcParsers
import io.github.PctAIGM.procview.sampler.procfs.ProcFileReader
import io.github.PctAIGM.procview.sampler.procfs.ProcSnapshotReader
import io.github.PctAIGM.procview.model.ProcessCatalogEntry
import io.github.PctAIGM.procview.model.ProcessKey
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
    private val sampleLock = Any()
    private var sequence = 0L
    private var catalogRevision = 0L
    private var currentCatalog: List<ProcessCatalogEntry> = emptyList()
    private var lastPssRequestElapsedNanos = Long.MIN_VALUE

    constructor()

    @Keep
    constructor(@Suppress("UNUSED_PARAMETER") context: Context)

    override fun getProtocolVersion(): Int = PROTOCOL_VERSION

    override fun runCapabilityProbe(): CapabilityProbeParcel {
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
        val psAvailable = psResult?.let { it.exitCode == 0 && !it.timedOut } == true
        val psPidCount = if (psAvailable) ProcParsers.parsePsPidCount(psResult.output) else 0
        if (!psAvailable || psPidCount == 0) errors = errors or ProbeErrorFlags.PS_COMMAND
        if (psResult?.truncated == true) errors = errors or ProbeErrorFlags.COMMAND_OUTPUT_TRUNCATED

        val servicePid = Process.myPid()
        val knownPids = processScan.knownPids + servicePid
        val pssResult = runCatching { commandRunner.readPssCheckin(servicePid, knownPids) }.getOrNull()
        val pssAvailable = pssResult?.let { it.exitCode == 0 && !it.timedOut } == true
        val pssKb = if (pssAvailable) {
            ProcParsers.parseCheckinTotalPssKb(pssResult.output, servicePid)
        } else {
            null
        }
        if (!pssAvailable) errors = errors or ProbeErrorFlags.PSS_COMMAND
        if (pssAvailable && pssKb == null) errors = errors or ProbeErrorFlags.PSS_PARSE
        if (pssResult?.truncated == true) errors = errors or ProbeErrorFlags.COMMAND_OUTPUT_TRUNCATED

        val thermal = ProcCapabilityScanner.scanThermalZones()
        if (thermal.zoneCount == 0 || thermal.readableCount == 0) {
            errors = errors or ProbeErrorFlags.THERMAL
        }

        return CapabilityProbeParcel().also { result ->
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
            result.statReadableCount = processScan.statReadableCount
            result.statusReadableCount = processScan.statusReadableCount
            result.cmdlineReadableCount = processScan.cmdlineReadableCount
            result.rssReadableCount = processScan.rssReadableCount
            result.cpuAndRssReadableCount = processScan.cpuAndRssReadableCount
            result.pid1StatReadable = 1 in processScan.knownPids &&
                ProcFileReader.readText(File("/proc/1/stat"), 4096)
                    ?.let(ProcParsers::parseProcessStat) != null
            result.psCommandAvailable = psAvailable
            result.pssCommandAvailable = pssAvailable
            result.pssValueParsed = pssKb != null
            result.pssProbeKb = pssKb ?: -1L
            result.pssProbeDurationMs = pssResult?.durationMs ?: 0L
            result.thermalZoneCount = thermal.zoneCount
            result.thermalReadableCount = thermal.readableCount
            result.errorFlags = errors
            result.processListTruncated = processScan.truncated
            result.sampledUids = processScan.sampledUids
            result.thermalSensorNames = thermal.sensorNames
        }
    }

    override fun collectMetricFrame(): RawMetricFrameParcel = synchronized(sampleLock) {
        val snapshot = snapshotReader.read()
        if (snapshot.catalog != currentCatalog) {
            currentCatalog = snapshot.catalog
            catalogRevision++
        }
        sequence++
        RawMetricFrameParcel().also { frame ->
            frame.sequence = sequence
            frame.elapsedRealtimeNanos = snapshot.elapsedRealtimeNanos
            frame.wallTimeMillis = snapshot.wallTimeMillis
            frame.systemTotalCpuTicks = snapshot.systemCpu?.totalTicks ?: -1L
            frame.systemIdleCpuTicks = snapshot.systemCpu?.idleTicks ?: -1L
            frame.memoryTotalKb = snapshot.systemMemory?.totalKb ?: -1L
            frame.memoryAvailableKb = snapshot.systemMemory?.availableKb ?: -1L
            frame.collectionDurationMs = snapshot.collectionDurationMs
            frame.catalogRevision = catalogRevision
            frame.sourceCode = IpcCodes.SOURCE_PROCFS
            frame.frameFlags = snapshot.frameFlags
            frame.metrics = snapshot.metrics.map { metric ->
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
        val knownKeys = synchronized(sampleLock) { currentCatalog.asSequence().map { it.key }.toSet() }
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

        val values = requestedKeys.mapNotNull { key ->
            val currentStat = ProcFileReader.readText(File("/proc/${key.pid}/stat"), MAX_PROCESS_STAT_BYTES)
                ?.let(ProcParsers::parseProcessStat)
            if (currentStat?.pid != key.pid || currentStat.startTimeTicks != key.startTimeTicks) {
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
            result.durationMs = commandResult?.durationMs ?: 0L
            result.commandAvailable = commandAvailable
            result.timedOut = commandResult?.timedOut == true
            result.outputTruncated = commandResult?.truncated == true
            result.errorFlags = errorFlags
            result.values = values.toTypedArray()
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

    private companion object {
        const val PROTOCOL_VERSION = 2
        const val MAX_CORE_PROC_BYTES = 128 * 1024
        const val MAX_BOOT_ID_BYTES = 256
        const val MAX_BOOT_ID_CHARS = 128
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val MAX_CATALOG_CHUNK_ENTRIES = 32
        const val MAX_PSS_KEYS = 128
        const val MAX_PROCESS_STAT_BYTES = 4096
        const val MIN_PSS_REQUEST_INTERVAL_NANOS = 1_000_000_000L
    }
}
