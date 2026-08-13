package io.github.PctAIGM.procview.export

import android.content.Context
import android.database.Cursor
import android.net.Uri
import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.PctAIGM.procview.data.db.ProcViewDatabase
import io.github.PctAIGM.procview.data.db.SessionEntity
import java.io.BufferedOutputStream
import java.io.OutputStreamWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.max
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class SessionExporter(
    context: Context,
    private val database: ProcViewDatabase,
) {
    private val applicationContext = context.applicationContext
    private val json = Json { prettyPrint = true }

    suspend fun export(
        sessionId: String,
        target: Uri,
        options: ExportOptions,
    ): SessionExportOutcome = withContext(Dispatchers.IO) {
        val session = database.sessionDao().session(sessionId)
            ?: return@withContext SessionExportOutcome.SessionMissing
        if (session.status != "COMPLETED" && session.status != "INTERRUPTED") {
            return@withContext SessionExportOutcome.Failure("session_active")
        }
        val output = try {
            applicationContext.contentResolver.openOutputStream(target, "w")
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        } ?: return@withContext SessionExportOutcome.Failure("open_target")

        try {
            var systemRows = 0L
            var processRows = 0L
            var eventRows = 0L
            output.use { rawOutput ->
                val anonymizer = if (options.anonymous) ExportAnonymizer.random() else null
                val databaseHandle = database.openHelper.readableDatabase
                ZipOutputStream(BufferedOutputStream(rawOutput)).use { zip ->
                    writeTextEntry(zip, MANIFEST_ENTRY, manifest(session, options))
                    systemRows = writeSystemCsv(zip, databaseHandle, session.id, options)
                    processRows = writeProcessesCsv(
                        zip = zip,
                        database = databaseHandle,
                        session = session,
                        options = options,
                        anonymizer = anonymizer,
                    )
                    eventRows = writeEventsCsv(zip, databaseHandle, session.id, options)
                    writeTextEntry(
                        zip,
                        CAPABILITIES_ENTRY,
                        capabilities(databaseHandle, session, options, anonymizer),
                    )
                    writeTextEntry(zip, README_ENTRY, readme(options))
                }
            }
            check(database.sessionDao().session(session.id) != null) {
                "session was deleted during export"
            }
            SessionExportOutcome.Success(systemRows, processRows, eventRows)
        } catch (cancellation: CancellationException) {
            runCatching { applicationContext.contentResolver.delete(target, null, null) }
            throw cancellation
        } catch (_: Exception) {
            runCatching { applicationContext.contentResolver.delete(target, null, null) }
            SessionExportOutcome.Failure("write_zip")
        }
    }

    fun suggestedFileName(session: SessionEntity, options: ExportOptions): String = exportFileName(
        wallTimeMs = System.currentTimeMillis(),
        sessionName = when {
            options.anonymous -> "anonymous"
            options.includeSessionName -> session.name
            else -> "session"
        },
        includeTimestamp = !options.anonymous || options.includeAbsoluteTime,
    )

    private fun manifest(session: SessionEntity, options: ExportOptions): String {
        val durationMs = max(0L, session.elapsedDurationMs)
        val document = buildJsonObject {
            put("schemaVersion", EXPORT_SCHEMA_VERSION)
            put("anonymous", options.anonymous)
            if (options.includeAbsoluteTime) put("generatedAtWallTimeMs", System.currentTimeMillis())
            putJsonObject("session") {
                put("id", if (options.anonymous) "session_001" else session.id)
                if (options.includeSessionName) put("name", session.name)
                if (options.includeNote) put("note", session.note)
                put("status", session.status)
                put("durationMs", durationMs)
                put("samplingProfile", session.samplingProfile)
                put("backendMode", session.backendMode)
                put("lastSampleSequence", session.lastSampleSequence)
                if (options.includeAbsoluteTime) {
                    put("startWallTimeMs", session.startWallTimeMs)
                    put("endWallTimeMs", session.endWallTimeMs)
                }
            }
            if (options.includeDeviceDetails) {
                putJsonObject("device") {
                    put("model", session.deviceModel)
                    put("androidVersion", session.androidVersion)
                    put("romDisplay", session.romDisplay)
                    put("shizukuVersion", session.shizukuVersion)
                }
            }
            putJsonObject("procView") {
                put("version", session.procViewVersion)
                put("backendMode", session.backendMode)
                put("metricDataSourceColumn", "$SYSTEM_ENTRY:data_source")
            }
            putJsonObject("privacy") {
                put("anonymous", options.anonymous)
                put("sessionNameIncluded", options.includeSessionName)
                put("noteIncluded", options.includeNote)
                put("deviceDetailsIncluded", options.includeDeviceDetails)
                put("absoluteTimeIncluded", options.includeAbsoluteTime)
                put("commandLineIncluded", options.includeCommandLine)
                put("deviceIdentifiersCollected", false)
                if (options.anonymous) {
                    put("identifierSaltIncluded", false)
                    put("uidRemapped", true)
                    put("packageAndProcessNamesPseudonymized", true)
                }
            }
            put("entries", buildJsonArray {
                add(JsonPrimitive(MANIFEST_ENTRY))
                add(JsonPrimitive(SYSTEM_ENTRY))
                add(JsonPrimitive(PROCESSES_ENTRY))
                add(JsonPrimitive(EVENTS_ENTRY))
                add(JsonPrimitive(CAPABILITIES_ENTRY))
                add(JsonPrimitive(README_ENTRY))
            })
        }
        return json.encodeToString(JsonElement.serializer(), document)
    }

    private suspend fun writeSystemCsv(
        zip: ZipOutputStream,
        database: SupportSQLiteDatabase,
        sessionId: String,
        options: ExportOptions,
    ): Long = writeCsvEntry(zip, SYSTEM_ENTRY) { csv ->
        val header = mutableListOf("elapsed_offset_ms")
        if (options.includeAbsoluteTime) header += "wall_time_ms"
        header += listOf(
            "sequence",
            "cpu_percent",
            "memory_total_kb",
            "memory_available_kb",
            "battery_level_percent",
            "battery_temperature_deci_c",
            "charging_state",
            "thermal_status",
            "thermal_value_milli_c",
            "thermal_sensor_name",
            "screen_interactive",
            "sampling_interval_ms",
            "collection_duration_ms",
            "data_source",
            "frame_flags",
        )
        csv.row(header)
        database.query(
            """
            SELECT elapsedOffsetMs, wallTimeMs, sequence, cpuPercentBasisPoints,
                memoryTotalKb, memoryAvailableKb, batteryLevelPercent,
                batteryTemperatureDeciC, chargingState, thermalStatus,
                thermalValueMilliC, thermalSensorName, screenInteractive,
                samplingIntervalMs, collectionDurationMs, dataSource, frameFlags
            FROM system_samples WHERE sessionId = ? ORDER BY sequence
            """.trimIndent(),
            arrayOf(sessionId),
        ).use { cursor ->
            var count = 0L
            while (cursor.moveToNext()) {
                if (count % CANCELLATION_CHECK_ROWS == 0L) {
                    currentCoroutineContext().ensureActive()
                }
                val row = mutableListOf<Any?>(cursor.longOrNull(0))
                if (options.includeAbsoluteTime) row.add(cursor.longOrNull(1))
                row.addAll(listOf(
                    cursor.longOrNull(2),
                    cursor.intOrNull(3)?.div(100.0),
                    cursor.longOrNull(4),
                    cursor.longOrNull(5),
                    cursor.intOrNull(6),
                    cursor.intOrNull(7),
                    cursor.stringOrNull(8),
                    cursor.intOrNull(9),
                    cursor.intOrNull(10),
                    cursor.stringOrNull(11).takeIf { options.includeDeviceDetails },
                    cursor.intOrNull(12),
                    cursor.longOrNull(13),
                    cursor.longOrNull(14),
                    cursor.stringOrNull(15),
                    cursor.intOrNull(16),
                ))
                csv.row(row)
                count += 1
            }
            count
        }
    }

    private suspend fun writeProcessesCsv(
        zip: ZipOutputStream,
        database: SupportSQLiteDatabase,
        session: SessionEntity,
        options: ExportOptions,
        anonymizer: ExportAnonymizer?,
    ): Long = writeCsvEntry(zip, PROCESSES_ENTRY) { csv ->
        val header = mutableListOf("elapsed_offset_ms")
        if (options.includeAbsoluteTime) header += "wall_time_ms"
        header += listOf(
            "system_sample_sequence",
            "pid",
            "start_time_ticks",
            "uid",
            "package_name",
            "package_candidates",
            "application_name",
            "process_name",
        )
        if (options.includeCommandLine) header += "command_line"
        header += listOf(
            "is_system",
            "is_native",
            "cpu_percent",
            "rss_kb",
            "pss_kb",
            "pss_sample_offset_ms",
            "process_state",
            "rank",
            "retention_reason_flags",
        )
        csv.row(header)
        database.query(
            """
            SELECT ss.elapsedOffsetMs, ss.wallTimeMs, ps.systemSampleSequence,
                pi.pid, pi.startTimeTicks, pi.uid, pi.packageName,
                (SELECT GROUP_CONCAT(packageName, '|') FROM (
                    SELECT candidate.packageName AS packageName
                    FROM process_package_candidates candidate
                    WHERE candidate.processIdentityId = pi.id
                    ORDER BY candidate.packageName COLLATE NOCASE
                )) AS packageCandidates,
                pi.displayName, pi.processName, pi.commandLine, pi.isSystem, pi.isNative,
                ps.cpuPercentBasisPoints, ps.rssKb, ps.pssKb,
                ps.pssSampleElapsedRealtimeNanos, ps.processState, ps.rank, ps.reasonKept
            FROM process_samples ps
            JOIN process_identities pi ON pi.id = ps.processIdentityId
            JOIN system_samples ss ON ss.sessionId = ps.sessionId
                AND ss.sequence = ps.systemSampleSequence
            WHERE ps.sessionId = ?
            ORDER BY ps.systemSampleSequence, ps.processIdentityId
            """.trimIndent(),
            arrayOf(session.id),
        ).use { cursor ->
            var count = 0L
            while (cursor.moveToNext()) {
                if (count % CANCELLATION_CHECK_ROWS == 0L) {
                    currentCoroutineContext().ensureActive()
                }
                val rawPackage = cursor.stringOrNull(6)
                val rawDisplayName = cursor.stringOrNull(8).orEmpty()
                val rawProcessName = cursor.stringOrNull(9).orEmpty()
                val rawCommandLine = cursor.stringOrNull(10).orEmpty()
                val packageName = anonymizer?.packageName(rawPackage) ?: rawPackage
                val candidates = anonymizer?.packageCandidates(cursor.stringOrNull(7))
                    ?: cursor.stringOrNull(7)
                val displayName = anonymizer?.applicationName(rawPackage, rawDisplayName)
                    ?: rawDisplayName
                val processName = anonymizer?.processName(rawProcessName) ?: rawProcessName
                val commandLine = anonymizer?.commandLine(rawCommandLine) ?: rawCommandLine
                val pssOffsetMs = cursor.longOrNull(16)?.let { elapsedNanos ->
                    ((elapsedNanos - session.startElapsedRealtimeNanos).coerceAtLeast(0L) /
                        NANOS_PER_MILLISECOND)
                }
                val row = mutableListOf<Any?>(cursor.longOrNull(0))
                if (options.includeAbsoluteTime) row.add(cursor.longOrNull(1))
                row.addAll(listOf(
                    cursor.longOrNull(2),
                    cursor.intOrNull(3),
                    cursor.longOrNull(4),
                    anonymizer?.uid(cursor.intOrNull(5)) ?: cursor.intOrNull(5),
                    packageName,
                    candidates,
                    displayName,
                    processName,
                ))
                if (options.includeCommandLine) row.add(commandLine)
                row.addAll(listOf(
                    cursor.intOrNull(11),
                    cursor.intOrNull(12),
                    cursor.intOrNull(13)?.div(100.0),
                    cursor.longOrNull(14),
                    cursor.longOrNull(15),
                    pssOffsetMs,
                    cursor.stringOrNull(17),
                    cursor.intOrNull(18),
                    cursor.intOrNull(19),
                ))
                csv.row(row)
                count += 1
            }
            count
        }
    }

    private suspend fun writeEventsCsv(
        zip: ZipOutputStream,
        database: SupportSQLiteDatabase,
        sessionId: String,
        options: ExportOptions,
    ): Long = writeCsvEntry(zip, EVENTS_ENTRY) { csv ->
        val header = mutableListOf("elapsed_offset_ms")
        if (options.includeAbsoluteTime) header += "wall_time_ms"
        header += "type"
        if (!options.anonymous) header += "payload_json"
        csv.row(header)
        database.query(
            """
            SELECT elapsedOffsetMs, wallTimeMs, type, payloadJson
            FROM session_events WHERE sessionId = ? ORDER BY elapsedOffsetMs, id
            """.trimIndent(),
            arrayOf(sessionId),
        ).use { cursor ->
            var count = 0L
            while (cursor.moveToNext()) {
                if (count % CANCELLATION_CHECK_ROWS == 0L) {
                    currentCoroutineContext().ensureActive()
                }
                val type = cursor.stringOrNull(2)
                val row = mutableListOf<Any?>(cursor.longOrNull(0))
                if (options.includeAbsoluteTime) row.add(cursor.longOrNull(1))
                row.add(type)
                if (!options.anonymous) {
                    row.add(ExportPrivacy.sanitizeEventPayload(type, cursor.stringOrNull(3)))
                }
                csv.row(row)
                count += 1
            }
            count
        }
    }

    private fun capabilities(
        database: SupportSQLiteDatabase,
        session: SessionEntity,
        options: ExportOptions,
        anonymizer: ExportAnonymizer?,
    ): String {
        val capabilityId = session.capabilityReportId
            ?: return unavailableCapabilities(options, "missing_reference")
        val reportJson = database.query(
            "SELECT reportJson FROM capability_reports WHERE id = ? LIMIT 1",
            arrayOf(capabilityId),
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.stringOrNull(0) else null
        } ?: return unavailableCapabilities(options, "missing_report")
        val report = runCatching { json.parseToJsonElement(reportJson).jsonObject }.getOrNull()
            ?: return unavailableCapabilities(options, "unparseable_source_report")
        val safeReport = if (
            options.anonymous ||
            !options.includeDeviceDetails ||
            !options.includeAbsoluteTime
        ) {
            ExportPrivacy.sanitizeCapabilityReport(
                report = report,
                anonymous = options.anonymous,
                includeDeviceDetails = options.includeDeviceDetails,
                includeAbsoluteTime = options.includeAbsoluteTime,
                anonymizer = anonymizer ?: ExportAnonymizer.random(),
            )
        } else {
            report
        }
        return json.encodeToString(
            JsonElement.serializer(),
            buildJsonObject {
                put("schemaVersion", EXPORT_SCHEMA_VERSION)
                put("anonymous", options.anonymous)
                put("capability", safeReport)
            },
        )
    }

    private fun unavailableCapabilities(options: ExportOptions, reason: String): String =
        json.encodeToString(
            JsonElement.serializer(),
            buildJsonObject {
                put("schemaVersion", EXPORT_SCHEMA_VERSION)
                put("anonymous", options.anonymous)
                put("available", false)
                put("reason", reason)
            },
        )

    private fun readme(options: ExportOptions): String = buildString {
        appendLine("ProcView session export · schema $EXPORT_SCHEMA_VERSION")
        appendLine()
        appendLine("All CSV files are UTF-8, comma-delimited, and use CRLF records.")
        appendLine("CPU values are percentages. Memory and PSS values are KiB.")
        appendLine("battery_temperature_deci_c uses tenths of a degree Celsius.")
        appendLine("elapsed_offset_ms and pss_sample_offset_ms are relative to session start.")
        appendLine("retention_reason_flags is a bit mask: CPU Top 20, RSS Top 20, pinned, detail.")
        appendLine("A missing value is emitted as an empty CSV field; it is never inferred.")
        appendLine("Text beginning with a spreadsheet formula marker is prefixed with an apostrophe.")
        appendLine()
        if (options.anonymous) {
            appendLine("Anonymous export is enabled.")
            appendLine("Package, application, process, command, and UID values are pseudonymized.")
            appendLine("A fresh random salt was used for this ZIP and is not included.")
            appendLine("Event payloads are omitted to prevent future payload fields leaking identifiers.")
        } else {
            appendLine("Anonymous export is disabled; local process metadata may be sensitive.")
        }
        appendLine("ProcView does not collect device serial numbers, Android ID, accounts, or phone numbers.")
    }

    private suspend inline fun writeCsvEntry(
        zip: ZipOutputStream,
        name: String,
        block: suspend (CsvWriter) -> Long,
    ): Long {
        putEntry(zip, name)
        var failure: Throwable? = null
        return try {
            val writer = OutputStreamWriter(zip, Charsets.UTF_8)
            val csv = CsvWriter(writer)
            block(csv).also { csv.flush() }
        } catch (throwable: Throwable) {
            failure = throwable
            throw throwable
        } finally {
            closeEntryPreservingFailure(zip, failure)
        }
    }

    private fun writeTextEntry(zip: ZipOutputStream, name: String, value: String) {
        putEntry(zip, name)
        var failure: Throwable? = null
        try {
            zip.write(value.toByteArray(Charsets.UTF_8))
        } catch (throwable: Throwable) {
            failure = throwable
            throw throwable
        } finally {
            closeEntryPreservingFailure(zip, failure)
        }
    }

    private fun closeEntryPreservingFailure(zip: ZipOutputStream, failure: Throwable?) {
        if (failure == null) {
            zip.closeEntry()
            return
        }
        try {
            zip.closeEntry()
        } catch (closeFailure: Throwable) {
            if (closeFailure !== failure) failure.addSuppressed(closeFailure)
        }
    }

    private fun putEntry(zip: ZipOutputStream, name: String) {
        zip.putNextEntry(ZipEntry(name).apply { time = ZIP_ENTRY_TIME_MS })
    }

    private fun Cursor.longOrNull(index: Int): Long? =
        if (isNull(index)) null else getLong(index)

    private fun Cursor.intOrNull(index: Int): Int? =
        if (isNull(index)) null else getInt(index)

    private fun Cursor.stringOrNull(index: Int): String? =
        if (isNull(index)) null else getString(index)

    companion object {
        const val MANIFEST_ENTRY = "manifest.json"
        const val SYSTEM_ENTRY = "system.csv"
        const val PROCESSES_ENTRY = "processes.csv"
        const val EVENTS_ENTRY = "events.csv"
        const val CAPABILITIES_ENTRY = "capabilities.json"
        const val README_ENTRY = "README.txt"
        private const val EXPORT_SCHEMA_VERSION = 1
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        private const val CANCELLATION_CHECK_ROWS = 512L
        private const val ZIP_ENTRY_TIME_MS = 315_532_800_000L
        private val FILE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

        fun exportFileName(
            wallTimeMs: Long,
            sessionName: String,
            includeTimestamp: Boolean = true,
        ): String {
            val safeName = sessionName.trim()
                .map { character ->
                    if (character.isLetterOrDigit() || character == '-' || character == '_') {
                        character
                    } else {
                        '-'
                    }
                }
                .joinToString("")
                .replace(Regex("-+"), "-")
                .trim('-')
                .take(48)
                .ifBlank { "session" }
            if (!includeTimestamp) return "procview-session-$safeName.zip"
            val timestamp = Instant.ofEpochMilli(wallTimeMs)
                .atZone(ZoneId.systemDefault())
                .format(FILE_TIME_FORMATTER)
            return "procview-session-$timestamp-$safeName.zip"
        }
    }
}
