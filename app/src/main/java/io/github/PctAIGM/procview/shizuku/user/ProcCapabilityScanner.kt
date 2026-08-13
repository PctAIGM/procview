package io.github.PctAIGM.procview.shizuku.user

import android.system.Os
import android.system.OsConstants
import io.github.PctAIGM.procview.sampler.procfs.ProcFileReader
import io.github.PctAIGM.procview.sampler.procfs.ProcParsers
import java.io.File

internal data class ProcScanResult(
    val pidCount: Int,
    val knownPids: Set<Int>,
    val statReadablePids: Set<Int>,
    val statusReadablePids: Set<Int>,
    val cmdlineReadablePids: Set<Int>,
    val rssReadablePids: Set<Int>,
    val cpuAndRssReadablePids: Set<Int>,
    val sampledUids: IntArray,
    val truncated: Boolean,
    val durationMs: Long,
)

internal data class ThermalScanResult(
    val zoneCount: Int,
    val readableCount: Int,
    val sensorNames: Array<String>,
)

internal object ProcCapabilityScanner {
    fun scanProcesses(procRoot: File = File("/proc")): ProcScanResult {
        val started = System.nanoTime()
        val allPids = procRoot.listFiles()
            .orEmpty()
            .asSequence()
            .filter(File::isDirectory)
            .mapNotNull { it.name.toIntOrNull()?.takeIf { pid -> pid > 0 } }
            .sorted()
            .toList()
        val selectedPids = allPids.take(MAX_PROCESS_COUNT)
        val pageSizeKb = runCatching {
            Os.sysconf(OsConstants._SC_PAGESIZE) / BYTES_PER_KILOBYTE
        }.getOrNull()?.takeIf { it > 0 } ?: DEFAULT_PAGE_SIZE_KB

        val statReadable = linkedSetOf<Int>()
        val statusReadable = linkedSetOf<Int>()
        val cmdlineReadable = linkedSetOf<Int>()
        val rssReadable = linkedSetOf<Int>()
        val cpuAndRssReadable = linkedSetOf<Int>()
        val uids = linkedSetOf<Int>()

        selectedPids.forEach { pid ->
            val directory = File(procRoot, pid.toString())
            val stat = ProcFileReader.readText(File(directory, "stat"), MAX_STAT_BYTES)
                ?.let(ProcParsers::parseProcessStat)
            if (stat != null) statReadable += pid

            val status = ProcFileReader.readText(File(directory, "status"), MAX_STATUS_BYTES)
                ?.let(ProcParsers::parseProcessStatus)
            if (status != null) {
                statusReadable += pid
                status.uid?.let(uids::add)
            }

            val rssKb = status?.vmRssKb ?: ProcFileReader.readText(File(directory, "statm"), MAX_STATM_BYTES)
                ?.let(ProcParsers::parseStatmResidentPages)
                ?.let { pages ->
                    if (pages != 0L && Long.MAX_VALUE / pages < pageSizeKb) null
                    else pages * pageSizeKb
                }
            if (rssKb != null) rssReadable += pid
            if (stat != null && rssKb != null) cpuAndRssReadable += pid

            if (ProcFileReader.readText(File(directory, "cmdline"), MAX_CMDLINE_BYTES) != null) {
                cmdlineReadable += pid
            }
        }

        return ProcScanResult(
            pidCount = allPids.size,
            knownPids = selectedPids.toSet(),
            statReadablePids = statReadable,
            statusReadablePids = statusReadable,
            cmdlineReadablePids = cmdlineReadable,
            rssReadablePids = rssReadable,
            cpuAndRssReadablePids = cpuAndRssReadable,
            sampledUids = uids.take(MAX_UID_SAMPLE_COUNT).toIntArray(),
            truncated = allPids.size > selectedPids.size || uids.size > MAX_UID_SAMPLE_COUNT,
            durationMs = (System.nanoTime() - started) / NANOS_PER_MILLISECOND,
        )
    }

    fun scanThermalZones(thermalRoot: File = File("/sys/class/thermal")): ThermalScanResult {
        val zones = thermalRoot.listFiles()
            .orEmpty()
            .filter { it.isDirectory && it.name.startsWith("thermal_zone") }
            .sortedBy(File::getName)
        var readable = 0
        val names = ArrayList<String>(minOf(zones.size, MAX_THERMAL_SENSOR_COUNT))

        zones.forEach { zone ->
            val type = ProcFileReader.readText(File(zone, "type"), MAX_THERMAL_TYPE_BYTES)
                ?.trim()
                ?.take(MAX_THERMAL_NAME_CHARS)
                .orEmpty()
            val temperature = ProcFileReader.readText(File(zone, "temp"), MAX_THERMAL_TEMP_BYTES)
                ?.trim()
                ?.toLongOrNull()
            if (temperature != null) readable++
            if (names.size < MAX_THERMAL_SENSOR_COUNT && type.isNotEmpty()) names += type
        }

        return ThermalScanResult(zones.size, readable, names.distinct().toTypedArray())
    }

    private const val MAX_PROCESS_COUNT = 4096
    private const val MAX_UID_SAMPLE_COUNT = 2048
    private const val MAX_THERMAL_SENSOR_COUNT = 64
    private const val MAX_THERMAL_NAME_CHARS = 64
    private const val MAX_STAT_BYTES = 4096
    private const val MAX_STATUS_BYTES = 16 * 1024
    private const val MAX_STATM_BYTES = 1024
    private const val MAX_CMDLINE_BYTES = 8 * 1024
    private const val MAX_THERMAL_TYPE_BYTES = 512
    private const val MAX_THERMAL_TEMP_BYTES = 128
    private const val BYTES_PER_KILOBYTE = 1024L
    private const val DEFAULT_PAGE_SIZE_KB = 4L
    private const val NANOS_PER_MILLISECOND = 1_000_000L
}
