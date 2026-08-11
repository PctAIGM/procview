package io.github.PctAIGM.procview.shizuku.user

import android.content.Context
import android.os.Process
import androidx.annotation.Keep
import io.github.PctAIGM.procview.sampler.procfs.ProcParsers
import io.github.PctAIGM.procview.shizuku.ipc.CapabilityProbeParcel
import io.github.PctAIGM.procview.shizuku.ipc.IProcViewUserService
import java.io.File

@Keep
class ProcViewUserService : IProcViewUserService.Stub {
    private val commandRunner = ReadOnlyCommandRunner()

    constructor()

    @Keep
    constructor(@Suppress("UNUSED_PARAMETER") context: Context)

    override fun getProtocolVersion(): Int = PROTOCOL_VERSION

    override fun runCapabilityProbe(): CapabilityProbeParcel {
        val startedWallTime = System.currentTimeMillis()
        val startedNanos = System.nanoTime()
        var errors = 0

        val procStatReadable = ProcCapabilityScanner.readBoundedText(
            File("/proc/stat"),
            MAX_CORE_PROC_BYTES,
        )?.startsWith("cpu ") == true
        if (!procStatReadable) errors = errors or ProbeErrorFlags.PROC_STAT

        val procMeminfoReadable = ProcCapabilityScanner.readBoundedText(
            File("/proc/meminfo"),
            MAX_CORE_PROC_BYTES,
        )?.contains("MemTotal:") == true
        if (!procMeminfoReadable) errors = errors or ProbeErrorFlags.PROC_MEMINFO

        val bootId = ProcCapabilityScanner.readBoundedText(
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
                ProcCapabilityScanner.readBoundedText(File("/proc/1/stat"), 4096)
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

    override fun destroy() {
        commandRunner.close()
        System.exit(0)
    }

    private companion object {
        const val PROTOCOL_VERSION = 1
        const val MAX_CORE_PROC_BYTES = 128 * 1024
        const val MAX_BOOT_ID_BYTES = 256
        const val MAX_BOOT_ID_CHARS = 128
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
