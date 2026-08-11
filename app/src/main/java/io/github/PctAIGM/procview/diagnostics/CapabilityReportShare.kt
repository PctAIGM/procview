package io.github.PctAIGM.procview.diagnostics

import android.content.Context
import android.content.Intent
import android.os.Build
import io.github.PctAIGM.procview.BuildConfig
import io.github.PctAIGM.procview.R
import io.github.PctAIGM.procview.model.CapabilityReport
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class CapabilityDeviceSnapshot(
    val manufacturer: String,
    val model: String,
    val androidRelease: String,
    val androidSdk: Int,
    val romDisplay: String,
)

@Serializable
data class CapabilityReportEnvelope(
    val schemaVersion: Int = 1,
    val generatedAtWallTimeMs: Long,
    val applicationId: String,
    val procViewVersion: String,
    val device: CapabilityDeviceSnapshot,
    val capability: CapabilityReport,
)

object CapabilityReportJson {
    private val json = Json {
        encodeDefaults = true
        prettyPrint = true
    }

    fun createEnvelope(report: CapabilityReport): CapabilityReportEnvelope = CapabilityReportEnvelope(
        generatedAtWallTimeMs = System.currentTimeMillis(),
        applicationId = BuildConfig.APPLICATION_ID,
        procViewVersion = BuildConfig.VERSION_NAME,
        device = CapabilityDeviceSnapshot(
            manufacturer = Build.MANUFACTURER.orEmpty(),
            model = Build.MODEL.orEmpty(),
            androidRelease = Build.VERSION.RELEASE.orEmpty(),
            androidSdk = Build.VERSION.SDK_INT,
            romDisplay = Build.DISPLAY.orEmpty(),
        ),
        capability = report,
    )

    fun encode(envelope: CapabilityReportEnvelope): String = json.encodeToString(envelope)
}

fun shareCapabilityReport(context: Context, report: CapabilityReport): Boolean {
    val json = CapabilityReportJson.encode(CapabilityReportJson.createEnvelope(report))
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = "application/json"
        putExtra(Intent.EXTRA_SUBJECT, "ProcView capability report")
        putExtra(Intent.EXTRA_TEXT, json)
    }
    val chooser = Intent.createChooser(
        sendIntent,
        context.getString(R.string.action_share_capability_report),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return runCatching {
        context.applicationContext.startActivity(chooser)
        true
    }.getOrDefault(false)
}
