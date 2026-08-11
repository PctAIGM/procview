package io.github.PctAIGM.procview.sampler.procfs

internal data class ProcessStat(
    val pid: Int,
    val command: String,
    val state: Char,
    val parentPid: Int,
    val userTicks: Long,
    val systemTicks: Long,
    val startTimeTicks: Long,
) {
    val cpuTicks: Long get() = userTicks + systemTicks
}

internal data class ProcessStatus(
    val state: Char?,
    val parentPid: Int?,
    val uid: Int?,
    val vmRssKb: Long?,
)

internal data class SystemCpuStat(
    val totalTicks: Long,
    val idleTicks: Long,
)

internal data class SystemMemoryInfo(
    val totalKb: Long,
    val availableKb: Long,
)

internal object ProcParsers {
    fun parseSystemCpuStat(text: String): SystemCpuStat? {
        val fields = text.lineSequence().firstOrNull()?.trim()?.split(WHITESPACE).orEmpty()
        if (fields.firstOrNull() != "cpu" || fields.size < MIN_SYSTEM_CPU_FIELDS + 1) return null
        val ticks = fields.drop(1).take(MIN_SYSTEM_CPU_FIELDS).map { field ->
            field.toLongOrNull()?.takeIf { it >= 0 } ?: return null
        }
        val total = ticks.fold(0L) { sum, value ->
            if (Long.MAX_VALUE - sum < value) return null
            sum + value
        }
        val idle = ticks[CPU_IDLE_INDEX] + ticks[CPU_IOWAIT_INDEX]
        return SystemCpuStat(totalTicks = total, idleTicks = idle)
    }

    fun parseSystemMemoryInfo(text: String): SystemMemoryInfo? {
        var totalKb: Long? = null
        var availableKb: Long? = null
        text.lineSequence().forEach { line ->
            val separator = line.indexOf(':')
            if (separator <= 0) return@forEach
            when (line.substring(0, separator)) {
                "MemTotal" -> totalKb = parseKilobytes(line.substring(separator + 1).trim())
                "MemAvailable" -> availableKb = parseKilobytes(line.substring(separator + 1).trim())
            }
        }
        val total = totalKb?.takeIf { it > 0 } ?: return null
        val available = availableKb?.takeIf { it >= 0 } ?: return null
        return SystemMemoryInfo(totalKb = total, availableKb = available.coerceAtMost(total))
    }

    fun parseProcessStat(text: String): ProcessStat? {
        val open = text.indexOf('(')
        val close = text.lastIndexOf(')')
        if (open <= 0 || close <= open || close + 2 >= text.length) return null

        val pid = text.substring(0, open).trim().toIntOrNull()?.takeIf { it > 0 } ?: return null
        val command = text.substring(open + 1, close)
        val fields = text.substring(close + 1).trim().split(WHITESPACE)
        if (fields.size < MIN_PROCESS_STAT_FIELDS_AFTER_COMMAND) return null

        val userTicks = fields[11].toLongOrNull() ?: return null
        val systemTicks = fields[12].toLongOrNull() ?: return null
        if (userTicks < 0 || systemTicks < 0 || Long.MAX_VALUE - userTicks < systemTicks) return null
        val startTimeTicks = fields[19].toLongOrNull()?.takeIf { it >= 0 } ?: return null
        return ProcessStat(
            pid = pid,
            command = command,
            state = fields[0].singleOrNull() ?: return null,
            parentPid = fields[1].toIntOrNull() ?: return null,
            userTicks = userTicks,
            systemTicks = systemTicks,
            startTimeTicks = startTimeTicks,
        )
    }

    fun parseProcessStatus(text: String): ProcessStatus? {
        var state: Char? = null
        var parentPid: Int? = null
        var uid: Int? = null
        var vmRssKb: Long? = null
        var recognized = false

        text.lineSequence().forEach { line ->
            val separator = line.indexOf(':')
            if (separator <= 0) return@forEach
            val key = line.substring(0, separator)
            val value = line.substring(separator + 1).trim()
            when (key) {
                "State" -> {
                    state = value.firstOrNull()
                    recognized = true
                }
                "PPid" -> {
                    parentPid = value.substringBefore(' ').toIntOrNull()
                    recognized = true
                }
                "Uid" -> {
                    uid = value.split(WHITESPACE).firstOrNull()?.toIntOrNull()
                    recognized = true
                }
                "VmRSS" -> {
                    vmRssKb = parseKilobytes(value)
                    recognized = true
                }
            }
        }

        return if (recognized) ProcessStatus(state, parentPid, uid, vmRssKb) else null
    }

    fun parseStatmResidentPages(text: String): Long? =
        text.trim().split(WHITESPACE).getOrNull(1)?.toLongOrNull()?.takeIf { it >= 0 }

    fun parsePsPidCount(text: String): Int {
        return text.lineSequence()
            .map(String::trim)
            .count { line -> line.isNotEmpty() && line.toIntOrNull()?.let { it > 0 } == true }
    }

    fun parseCheckinTotalPssKb(text: String, expectedPid: Int): Long? {
        if (expectedPid <= 0) return null
        return text.lineSequence().map(String::trim).firstNotNullOfOrNull { line ->
            if (line.isEmpty() || line.startsWith("time,")) return@firstNotNullOfOrNull null
            val fields = line.split(',')
            if (fields.size <= CHECKIN_TOTAL_PSS_INDEX) return@firstNotNullOfOrNull null
            val version = fields[0].toIntOrNull() ?: return@firstNotNullOfOrNull null
            val pid = fields[1].toIntOrNull() ?: return@firstNotNullOfOrNull null
            if (version < MIN_SUPPORTED_CHECKIN_VERSION || pid != expectedPid) {
                return@firstNotNullOfOrNull null
            }
            fields[CHECKIN_TOTAL_PSS_INDEX].toLongOrNull()?.takeIf { it >= 0 }
        }
    }

    fun parseCheckinTotalPssByPid(text: String, expectedPids: Set<Int>): Map<Int, Long> {
        if (expectedPids.isEmpty()) return emptyMap()
        val values = linkedMapOf<Int, Long>()
        text.lineSequence().map(String::trim).forEach { line ->
            if (line.isEmpty() || line.startsWith("time,")) return@forEach
            val fields = line.split(',')
            if (fields.size <= CHECKIN_TOTAL_PSS_INDEX) return@forEach
            val version = fields[0].toIntOrNull() ?: return@forEach
            val pid = fields[1].toIntOrNull() ?: return@forEach
            val pssKb = fields[CHECKIN_TOTAL_PSS_INDEX].toLongOrNull() ?: return@forEach
            if (version >= MIN_SUPPORTED_CHECKIN_VERSION && pid in expectedPids && pssKb >= 0) {
                values[pid] = pssKb
            }
        }
        return values
    }

    fun normalizeCmdline(raw: String): String = raw
        .trimEnd('\u0000', '\n', '\r')
        .replace('\u0000', ' ')
        .take(MAX_COMMAND_LINE_CHARS)

    private fun parseKilobytes(value: String): Long? {
        val parts = value.split(WHITESPACE)
        val amount = parts.firstOrNull()?.toLongOrNull()?.takeIf { it >= 0 } ?: return null
        val unit = parts.getOrNull(1)?.lowercase()
        return when (unit) {
            null, "kb" -> amount
            "mb" -> safeMultiply(amount, 1024L)
            else -> null
        }
    }

    private fun safeMultiply(left: Long, right: Long): Long? =
        if (left != 0L && Long.MAX_VALUE / left < right) null else left * right

    private val WHITESPACE = Regex("\\s+")
    private const val MIN_PROCESS_STAT_FIELDS_AFTER_COMMAND = 20
    private const val MIN_SYSTEM_CPU_FIELDS = 8
    private const val CPU_IDLE_INDEX = 3
    private const val CPU_IOWAIT_INDEX = 4
    private const val MIN_SUPPORTED_CHECKIN_VERSION = 3
    private const val CHECKIN_TOTAL_PSS_INDEX = 18
    private const val MAX_COMMAND_LINE_CHARS = 4096
}
