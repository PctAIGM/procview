package io.github.PctAIGM.procview.shizuku.user

import android.system.Os
import android.system.OsConstants
import io.github.PctAIGM.procview.sampler.procfs.ProcParsers
import java.io.File
import io.github.PctAIGM.procview.sampler.procfs.ProcFileReader

internal data class ProcScanResult(
    val pidCount: Int,
    val knownPids: Set<Int>,
    val statReadableCount: Int,
    val statusReadableCount: Int,
    val cmdlineReadableCount: Int,
    val rssReadableCount: Int,
    val cpuAndRssReadableCount: Int,
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

        var statReadable = 0
        var statusReadable = 0
        var cmdlineReadable = 0
        var rssReadable = 0
        var cpuAndRssReadable = 0
        val uids = linkedSetOf<Int>()

        selectedPids.forEach { pid ->
            val directory = File(procRoot, pid.toString())
            val stat = ProcFileReader.readText(File(directory, "stat"), MAX_STAT_BYTES)
                ?.let(ProcParsers::parseProcessStat)
            if (stat != null) statReadable++

            val status = ProcFileReader.readText(File(directory, "status"), MAX_STATUS_BYTES)
                ?.let(ProcParsers::parseProcessStatus)
            if (status != null) {
                statusReadable++
                status.uid?.let(uids::add)
            }

            val rssKb = status?.vmRssKb ?: ProcFileReader.readText(File(directory, "statm"), MAX_STATM_BYTES)
                ?.let(ProcParsers::parseStatmResidentPages)
                ?.times(pageSizeKb)
            if (rssKb != null) rssReadable++
            if (stat != null && rssKb != null) cpuAndRssReadable++

            if (ProcFileReader.readText(File(directory, "cmdline"), MAX_CMDLINE_BYTES) != null) {
                cmdlineReadable++
            }
        }

        return ProcScanResult(
            pidCount = allPids.size,
            knownPids = selectedPids.toSet(),
            statReadableCount = statReadable,
            statusReadableCount = statusReadable,
            cmdlineReadableCount = cmdlineReadable,
            rssReadableCount = rssReadable,
            cpuAndRssReadableCount = cpuAndRssReadable,
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
