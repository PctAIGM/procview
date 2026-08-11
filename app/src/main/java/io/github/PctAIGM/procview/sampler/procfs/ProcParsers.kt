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

internal object ProcParsers {
    fun parseProcessStat(text: String): ProcessStat? {
        val open = text.indexOf('(')
        val close = text.lastIndexOf(')')
        if (open <= 0 || close <= open || close + 2 >= text.length) return null

        val pid = text.substring(0, open).trim().toIntOrNull()?.takeIf { it > 0 } ?: return null
        val command = text.substring(open + 1, close)
        val fields = text.substring(close + 1).trim().split(WHITESPACE)
        if (fields.size < MIN_PROCESS_STAT_FIELDS_AFTER_COMMAND) return null

        return ProcessStat(
            pid = pid,
            command = command,
            state = fields[0].singleOrNull() ?: return null,
            parentPid = fields[1].toIntOrNull() ?: return null,
            userTicks = fields[11].toLongOrNull() ?: return null,
            systemTicks = fields[12].toLongOrNull() ?: return null,
            startTimeTicks = fields[19].toLongOrNull() ?: return null,
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
            "mb" -> amount * 1024L
            else -> null
        }
    }

    private val WHITESPACE = Regex("\\s+")
    private const val MIN_PROCESS_STAT_FIELDS_AFTER_COMMAND = 20
    private const val MIN_SUPPORTED_CHECKIN_VERSION = 3
    private const val CHECKIN_TOTAL_PSS_INDEX = 18
    private const val MAX_COMMAND_LINE_CHARS = 4096
}
