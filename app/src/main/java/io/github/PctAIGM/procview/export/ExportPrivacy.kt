package io.github.PctAIGM.procview.export

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

object ExportPrivacy {
    fun sanitizeEventPayload(type: String?, payloadJson: String?): String? =
        payloadJson.takeUnless { type == "USER_NOTE" }

    fun sanitizeCapabilityReport(
        report: JsonObject,
        anonymous: Boolean,
        includeDeviceDetails: Boolean,
        includeAbsoluteTime: Boolean,
        anonymizer: ExportAnonymizer,
    ): JsonObject = JsonObject(
        report.mapNotNull { (key, value) ->
            when (key) {
                "bootId" -> if (!anonymous && includeDeviceDetails) key to value else null
                "probedAtWallTimeMs" -> if (includeAbsoluteTime) key to value else null
                "shizukuUid", "serviceUid" -> when {
                    anonymous -> key to JsonPrimitive(
                        anonymizer.uid((value as? JsonPrimitive)?.content?.toIntOrNull()) ?: 0,
                    )
                    includeDeviceDetails -> key to value
                    else -> null
                }
                "servicePid" -> when {
                    anonymous -> key to JsonPrimitive(0)
                    includeDeviceDetails -> key to value
                    else -> null
                }
                "shizukuSelinuxContext", "thermalSensorNames" ->
                    if (includeDeviceDetails) key to value else null
                else -> key to value
            }
        }.toMap(),
    )
}
