package io.github.PctAIGM.procview.sampler.procfs

import io.github.PctAIGM.procview.model.ProcessKey
import java.util.Locale
import kotlin.math.abs

/** Parsed fields from ProcView's fixed, read-only Toybox ps invocation. */
internal data class PsFallbackProcess(
    val pid: Int,
    val parentPid: Int,
    val uid: Int?,
    /** Toybox's numeric RSS column is expressed as KiB assuming 4 KiB pages. */
    val rssAtFourKilobytePagesKb: Long?,
    val state: Char,
    val elapsedCentiseconds: Long,
    val cpuCentiseconds: Long,
    val commandLine: String,
) {
    val processName: String
        get() = commandLine
            .trim()
            .substringBefore(' ')
            .takeIf(String::isNotBlank)
            ?: "pid-$pid"
}

internal data class PsFallbackParseResult(
    val processes: List<PsFallbackProcess>,
    val malformedLineCount: Int,
    val truncated: Boolean,
)

internal object PsFallbackParser {
    fun parse(output: String, maxProcesses: Int = MAX_PROCESS_COUNT): PsFallbackParseResult {
        require(maxProcesses > 0) { "maxProcesses must be positive" }
        val parsed = ArrayList<PsFallbackProcess>(minOf(maxProcesses, 256))
        var malformed = 0
        var truncated = false

        output.lineSequence().forEach { sourceLine ->
            val line = sourceLine.trim()
            if (line.isEmpty() || isHeader(line)) return@forEach
            val columns = line.split(WHITESPACE, limit = COLUMN_COUNT)
            val process = parseColumns(columns)
            if (process == null) {
                malformed++
                return@forEach
            }
            if (parsed.size >= maxProcesses) {
                truncated = true
                return@forEach
            }
            parsed += process
        }

        return PsFallbackParseResult(
            processes = parsed.distinctBy(PsFallbackProcess::pid).sortedBy(PsFallbackProcess::pid),
            malformedLineCount = malformed,
            truncated = truncated,
        )
    }

    /** Parses [[days-]hours:]minutes:seconds[.centiseconds] without locale input. */
    fun parseClockCentiseconds(value: String): Long? {
        val trimmed = value.trim()
        if (trimmed.isEmpty() || trimmed.startsWith('-')) return null
        val daySeparator = trimmed.indexOf('-')
        val days = if (daySeparator >= 0) {
            trimmed.substring(0, daySeparator).toLongOrNull()?.takeIf { it >= 0 } ?: return null
        } else {
            0L
        }
        val clock = if (daySeparator >= 0) trimmed.substring(daySeparator + 1) else trimmed
        val parts = clock.split(':')
        if (parts.size !in 2..3) return null
        val hours = if (parts.size == 3) parts[0].toLongOrNull() ?: return null else 0L
        val minutes = parts[parts.size - 2].toLongOrNull() ?: return null
        val secondParts = parts.last().split('.', limit = 2)
        val seconds = secondParts[0].toLongOrNull() ?: return null
        if (hours < 0 || minutes !in 0..59 || seconds !in 0..59) return null
        if (days > 0 && hours !in 0..23) return null
        val centiseconds = if (secondParts.size == 2) {
            val fraction = secondParts[1]
            if (fraction.isEmpty() || fraction.any { !it.isDigit() }) return null
            fraction.padEnd(2, '0').take(2).toLong()
        } else {
            0L
        }
        return runCatching {
            Math.addExact(
                Math.multiplyExact(
                    Math.addExact(
                        Math.multiplyExact(days, HOURS_PER_DAY),
                        hours,
                    ),
                    CENTISECONDS_PER_HOUR,
                ),
                Math.addExact(
                    Math.multiplyExact(minutes, CENTISECONDS_PER_MINUTE),
                    Math.addExact(Math.multiplyExact(seconds, CENTISECONDS_PER_SECOND), centiseconds),
                ),
            )
        }.getOrNull()
    }

    private fun parseColumns(columns: List<String>): PsFallbackProcess? {
        if (columns.size < MIN_COLUMN_COUNT) return null
        val pid = columns[0].toIntOrNull()?.takeIf { it > 0 } ?: return null
        val parentPid = columns[1].toIntOrNull()?.takeIf { it >= 0 } ?: return null
        val uid = columns[2].toIntOrNull()?.takeIf { it >= 0 }
        val rssKb = columns[3].toLongOrNull()?.takeIf { it >= 0 }
        val state = columns[4].firstOrNull() ?: '?'
        val elapsed = parseClockCentiseconds(columns[5]) ?: return null
        val cpu = parseClockCentiseconds(columns[6]) ?: return null
        val commandLine = columns.getOrNull(7)
            ?.trim()
            ?.take(MAX_COMMAND_LINE_CHARS)
            .orEmpty()
        return PsFallbackProcess(
            pid = pid,
            parentPid = parentPid,
            uid = uid,
            rssAtFourKilobytePagesKb = rssKb,
            state = state,
            elapsedCentiseconds = elapsed,
            cpuCentiseconds = cpu,
            commandLine = commandLine,
        )
    }

    private fun isHeader(line: String): Boolean {
        val normalized = line.uppercase(Locale.ROOT)
        return normalized.startsWith("PID ") &&
            normalized.contains("PPID") &&
            normalized.contains("UID")
    }

    private const val COLUMN_COUNT = 8
    private const val MIN_COLUMN_COUNT = 7
    private const val MAX_PROCESS_COUNT = 4096
    private const val MAX_COMMAND_LINE_CHARS = 4096
    private const val HOURS_PER_DAY = 24L
    private const val CENTISECONDS_PER_SECOND = 100L
    private const val CENTISECONDS_PER_MINUTE = 60L * CENTISECONDS_PER_SECOND
    private const val CENTISECONDS_PER_HOUR = 60L * CENTISECONDS_PER_MINUTE
    private val WHITESPACE = Regex("\\s+")
}

/** Unit conversions shared by the Toybox fallback and its host-side tests. */
internal object PsFallbackUnits {
    fun cpuTicksToCentiseconds(ticks: Long, clockTicksPerSecond: Long): Long? {
        if (ticks < 0 || clockTicksPerSecond <= 0) return null
        return runCatching {
            Math.addExact(
                Math.multiplyExact(ticks / clockTicksPerSecond, CENTISECONDS_PER_SECOND),
                Math.multiplyExact(ticks % clockTicksPerSecond, CENTISECONDS_PER_SECOND) /
                    clockTicksPerSecond,
            )
        }.getOrNull()
    }

    fun rssToKb(toyboxRssAtFourKilobytePagesKb: Long, runtimePageSizeKb: Long): Long? {
        if (toyboxRssAtFourKilobytePagesKb < 0 || runtimePageSizeKb <= 0) return null
        return runCatching {
            Math.multiplyExact(toyboxRssAtFourKilobytePagesKb, runtimePageSizeKb) /
                TOYBOX_RSS_PAGE_KB
        }.getOrNull()
    }

    private const val CENTISECONDS_PER_SECOND = 100L
    private const val TOYBOX_RSS_PAGE_KB = 4L
}

/**
 * Maintains a stable fallback identity despite ELAPSED being rounded to whole seconds.
 * Direct procfs remains the release-quality identity source; this path is explicitly degraded.
 */
internal class PsFallbackIdentityTracker {
    private val identities = mutableMapOf<Int, Identity>()
    private var previousLivePids: Set<Int> = emptySet()

    fun assign(
        processes: List<PsFallbackProcess>,
        sampledAtElapsedRealtimeNanos: Long,
    ): List<Pair<PsFallbackProcess, ProcessKey>> {
        require(sampledAtElapsedRealtimeNanos >= 0) { "sample time must not be negative" }
        val nowCentiseconds = sampledAtElapsedRealtimeNanos / NANOS_PER_CENTISECOND
        val livePids = HashSet<Int>(processes.size)
        val assigned = processes.map { process ->
            livePids += process.pid
            val estimatedStart = (nowCentiseconds - process.elapsedCentiseconds).coerceAtLeast(0L)
            val previous = identities[process.pid]
            val reusable = previous != null &&
                process.pid in previousLivePids &&
                previous.uid == process.uid &&
                previous.processName == process.processName &&
                previous.commandLine == process.commandLine &&
                process.cpuCentiseconds >= previous.cpuCentiseconds &&
                abs(previous.estimatedStartCentiseconds - estimatedStart) <= START_TIME_TOLERANCE_CS
            val startKey = if (reusable) {
                checkNotNull(previous).startKey
            } else {
                maxOf(estimatedStart, previous?.startKey?.saturatedIncrement() ?: 0L)
            }
            identities[process.pid] = Identity(
                uid = process.uid,
                processName = process.processName,
                commandLine = process.commandLine,
                cpuCentiseconds = process.cpuCentiseconds,
                estimatedStartCentiseconds = if (reusable) {
                    checkNotNull(previous).estimatedStartCentiseconds
                } else {
                    estimatedStart
                },
                startKey = startKey,
                lastSeenCentiseconds = nowCentiseconds,
            )
            process to ProcessKey(process.pid, startKey)
        }
        identities.entries.removeAll { (pid, identity) ->
            pid !in livePids && nowCentiseconds - identity.lastSeenCentiseconds > CACHE_GRACE_CS
        }
        // A PID that was absent from one complete fallback snapshot cannot be the same
        // process when it reappears. Keep the cached row only to allocate a distinct,
        // monotonic start key; never use it as a continuous CPU/PSS identity.
        previousLivePids = livePids
        return assigned
    }

    fun clear() {
        identities.clear()
        previousLivePids = emptySet()
    }

    private fun Long.saturatedIncrement(): Long = if (this == Long.MAX_VALUE) this else this + 1L

    private data class Identity(
        val uid: Int?,
        val processName: String,
        val commandLine: String,
        val cpuCentiseconds: Long,
        val estimatedStartCentiseconds: Long,
        val startKey: Long,
        val lastSeenCentiseconds: Long,
    )

    private companion object {
        const val NANOS_PER_CENTISECOND = 10_000_000L
        const val START_TIME_TOLERANCE_CS = 150L
        const val CACHE_GRACE_CS = 3_000L
    }
}
