package io.github.PctAIGM.procview.export

import io.github.PctAIGM.procview.settings.ExportDefaults

data class ExportOptions(
    val anonymous: Boolean,
    val includeSessionName: Boolean,
    val includeNote: Boolean,
    val includeDeviceDetails: Boolean,
    val includeAbsoluteTime: Boolean,
    val includeCommandLine: Boolean,
)

fun ExportDefaults.toExportOptions(anonymous: Boolean): ExportOptions = ExportOptions(
    anonymous = anonymous,
    includeSessionName = includeSessionName,
    includeNote = includeNote,
    includeDeviceDetails = includeDeviceDetails,
    includeAbsoluteTime = includeAbsoluteTime,
    includeCommandLine = includeCommandLine,
)

sealed interface SessionExportOutcome {
    data class Success(
        val systemRows: Long,
        val processRows: Long,
        val eventRows: Long,
    ) : SessionExportOutcome

    data object SessionMissing : SessionExportOutcome
    data class Failure(val category: String) : SessionExportOutcome
}
