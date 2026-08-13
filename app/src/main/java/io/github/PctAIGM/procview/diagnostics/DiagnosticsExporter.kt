package io.github.PctAIGM.procview.diagnostics

import android.content.Context
import android.net.Uri
import io.github.PctAIGM.procview.model.CapabilityReport
import java.io.BufferedOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DiagnosticsExporter(context: Context) {
    private val applicationContext = context.applicationContext

    suspend fun export(target: Uri, report: CapabilityReport): Boolean =
        withContext(Dispatchers.IO) {
            val output = try {
                applicationContext.contentResolver.openOutputStream(target, "w")
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                null
            } ?: return@withContext false
            try {
                output.use { rawOutput ->
                    ZipOutputStream(BufferedOutputStream(rawOutput)).use { zip ->
                        writeEntry(
                            zip,
                            CAPABILITY_ENTRY,
                            CapabilityReportJson.encode(CapabilityReportJson.createEnvelope(report)),
                        )
                        writeEntry(
                            zip,
                            README_ENTRY,
                            """
                            ProcView compatibility diagnostics

                            This user-created package contains app/device/ROM version strings and the
                            read-only Shizuku capability probe. It can include the temporary per-boot ID,
                            Shizuku and service UIDs, SELinux context, thermal sensor names, capability
                            counts, timings, and error flags. ProcView does not collect device serial
                            numbers, Android ID, accounts, phone numbers, or monitoring session data here.
                            Share this ZIP only with a recipient you trust.
                            """.trimIndent(),
                        )
                    }
                }
                true
            } catch (cancellation: CancellationException) {
                runCatching { applicationContext.contentResolver.delete(target, null, null) }
                throw cancellation
            } catch (_: Exception) {
                runCatching { applicationContext.contentResolver.delete(target, null, null) }
                false
            }
        }

    fun suggestedFileName(wallTimeMillis: Long = System.currentTimeMillis()): String {
        val timestamp = Instant.ofEpochMilli(wallTimeMillis)
            .atZone(ZoneId.systemDefault())
            .format(FILE_TIME_FORMATTER)
        return "procview-diagnostics-$timestamp.zip"
    }

    private fun writeEntry(zip: ZipOutputStream, name: String, content: String) {
        zip.putNextEntry(ZipEntry(name).apply { time = ZIP_ENTRY_TIME_MS })
        var failure: Throwable? = null
        try {
            zip.write(content.toByteArray(Charsets.UTF_8))
        } catch (throwable: Throwable) {
            failure = throwable
            throw throwable
        } finally {
            if (failure == null) {
                zip.closeEntry()
            } else {
                try {
                    zip.closeEntry()
                } catch (closeFailure: Throwable) {
                    if (closeFailure !== failure) failure.addSuppressed(closeFailure)
                }
            }
        }
    }

    companion object {
        const val CAPABILITY_ENTRY = "capabilities.json"
        const val README_ENTRY = "README.txt"
        private const val ZIP_ENTRY_TIME_MS = 315_532_800_000L
        private val FILE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    }
}
