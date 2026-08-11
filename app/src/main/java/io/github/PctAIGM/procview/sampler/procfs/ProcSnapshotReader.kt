package io.github.PctAIGM.procview.sampler.procfs

import android.system.Os
import android.system.OsConstants
import io.github.PctAIGM.procview.model.MetricFrameFlags
import io.github.PctAIGM.procview.model.ProcessCatalogEntry
import io.github.PctAIGM.procview.model.ProcessKey
import io.github.PctAIGM.procview.model.RawProcessMetric
import java.io.File

internal data class ProcSnapshot(
    val elapsedRealtimeNanos: Long,
    val wallTimeMillis: Long,
    val systemCpu: SystemCpuStat?,
    val systemMemory: SystemMemoryInfo?,
    val collectionDurationMs: Long,
    val frameFlags: Int,
    val metrics: List<RawProcessMetric>,
    val catalog: List<ProcessCatalogEntry>,
)

internal class ProcSnapshotReader(
    private val procRoot: File = File("/proc"),
    private val elapsedRealtimeNanos: () -> Long = android.os.SystemClock::elapsedRealtimeNanos,
    private val wallTimeMillis: () -> Long = System::currentTimeMillis,
    private val pageSizeKb: Long = detectPageSizeKb(),
) {
    fun read(): ProcSnapshot {
        val startedNanos = elapsedRealtimeNanos()
        var flags = MetricFrameFlags.NONE
        val systemCpu = ProcFileReader.readText(File(procRoot, "stat"), MAX_SYSTEM_STAT_BYTES)
            ?.let(ProcParsers::parseSystemCpuStat)
        if (systemCpu == null) flags = flags or MetricFrameFlags.SYSTEM_CPU_UNREADABLE
        val systemMemory = ProcFileReader.readText(File(procRoot, "meminfo"), MAX_MEMINFO_BYTES)
            ?.let(ProcParsers::parseSystemMemoryInfo)
        if (systemMemory == null) flags = flags or MetricFrameFlags.SYSTEM_MEMORY_UNREADABLE

        val allPids = procRoot.listFiles()
            .orEmpty()
            .asSequence()
            .filter(File::isDirectory)
            .mapNotNull { it.name.toIntOrNull()?.takeIf { pid -> pid > 0 } }
            .sorted()
            .toList()
        val selectedPids = allPids.take(MAX_PROCESS_COUNT)
        if (selectedPids.size < allPids.size) flags = flags or MetricFrameFlags.PROCESS_LIST_TRUNCATED

        val metrics = ArrayList<RawProcessMetric>(selectedPids.size)
        val catalog = ArrayList<ProcessCatalogEntry>(selectedPids.size)
        var disappeared = false
        selectedPids.forEach { pid ->
            val processDirectory = File(procRoot, pid.toString())
            val firstStat = readProcessStat(processDirectory)
                ?.takeIf { it.pid == pid }
                ?: return@forEach
            val status = ProcFileReader.readText(File(processDirectory, "status"), MAX_STATUS_BYTES)
                ?.let(ProcParsers::parseProcessStatus)
            val rssKb = status?.vmRssKb ?: ProcFileReader.readText(
                File(processDirectory, "statm"),
                MAX_STATM_BYTES,
            )?.let(ProcParsers::parseStatmResidentPages)?.let { pages ->
                if (pages != 0L && Long.MAX_VALUE / pages < pageSizeKb) null else pages * pageSizeKb
            }
            val commandLine = ProcFileReader.readText(
                File(processDirectory, "cmdline"),
                MAX_CMDLINE_BYTES,
            )?.let(ProcParsers::normalizeCmdline).orEmpty()
            val verifiedStat = readProcessStat(processDirectory)
            if (
                verifiedStat == null ||
                verifiedStat.pid != pid ||
                verifiedStat.startTimeTicks != firstStat.startTimeTicks
            ) {
                disappeared = true
                return@forEach
            }

            val key = ProcessKey(pid = pid, startTimeTicks = verifiedStat.startTimeTicks)
            metrics += RawProcessMetric(
                key = key,
                cpuTicks = verifiedStat.cpuTicks,
                rssKb = rssKb,
                state = verifiedStat.state,
            )
            catalog += ProcessCatalogEntry(
                key = key,
                parentPid = status?.parentPid ?: verifiedStat.parentPid,
                uid = status?.uid,
                processName = verifiedStat.command.take(MAX_PROCESS_NAME_CHARS),
                commandLine = commandLine.take(MAX_COMMAND_LINE_CHARS),
            )
        }
        if (disappeared) flags = flags or MetricFrameFlags.PROCESS_DISAPPEARED_DURING_READ
        val finishedNanos = elapsedRealtimeNanos()
        return ProcSnapshot(
            elapsedRealtimeNanos = finishedNanos,
            wallTimeMillis = wallTimeMillis(),
            systemCpu = systemCpu,
            systemMemory = systemMemory,
            collectionDurationMs = ((finishedNanos - startedNanos).coerceAtLeast(0L)) /
                NANOS_PER_MILLISECOND,
            frameFlags = flags,
            metrics = metrics,
            catalog = catalog,
        )
    }

    private fun readProcessStat(directory: File): ProcessStat? = ProcFileReader.readText(
        File(directory, "stat"),
        MAX_PROCESS_STAT_BYTES,
    )?.let(ProcParsers::parseProcessStat)

    private companion object {
        fun detectPageSizeKb(): Long = runCatching {
            Os.sysconf(OsConstants._SC_PAGESIZE) / BYTES_PER_KILOBYTE
        }.getOrNull()?.takeIf { it > 0 } ?: DEFAULT_PAGE_SIZE_KB

        const val MAX_PROCESS_COUNT = 4096
        const val MAX_SYSTEM_STAT_BYTES = 64 * 1024
        const val MAX_MEMINFO_BYTES = 128 * 1024
        const val MAX_PROCESS_STAT_BYTES = 4096
        const val MAX_STATUS_BYTES = 16 * 1024
        const val MAX_STATM_BYTES = 1024
        const val MAX_CMDLINE_BYTES = 8 * 1024
        const val MAX_PROCESS_NAME_CHARS = 256
        const val MAX_COMMAND_LINE_CHARS = 4096
        const val BYTES_PER_KILOBYTE = 1024L
        const val DEFAULT_PAGE_SIZE_KB = 4L
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
