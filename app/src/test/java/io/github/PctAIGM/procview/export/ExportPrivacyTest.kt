package io.github.PctAIGM.procview.export

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportPrivacyTest {
    @Test
    fun noteEventPayloadIsNeverExportedButOperationalPayloadIsPreserved() {
        assertNull(ExportPrivacy.sanitizeEventPayload("USER_NOTE", "{\"note\":\"old\"}"))
        assertEquals(
            "{\"interactive\":false}",
            ExportPrivacy.sanitizeEventPayload(
                "SCREEN_CHANGED",
                "{\"interactive\":false}",
            ),
        )
    }

    @Test
    fun anonymousCapabilityRemovesBootTimeAndLocalIdentity() {
        val source = buildJsonObject {
            put("bootId", "secret-boot-id")
            put("probedAtWallTimeMs", 1_700_000_000_000L)
            put("shizukuUid", 2_000)
            put("serviceUid", 2_000)
            put("servicePid", 42)
            put("procPidCount", 123)
        }
        val sanitized = ExportPrivacy.sanitizeCapabilityReport(
            report = source,
            anonymous = true,
            includeDeviceDetails = false,
            includeAbsoluteTime = false,
            anonymizer = ExportAnonymizer.withSaltForTesting(ByteArray(32) { it.toByte() }),
        )

        assertFalse("bootId" in sanitized)
        assertFalse("probedAtWallTimeMs" in sanitized)
        assertEquals(JsonPrimitive(0), sanitized["servicePid"])
        assertEquals(JsonPrimitive(1), sanitized["shizukuUid"])
        assertEquals(JsonPrimitive(1), sanitized["serviceUid"])
        assertEquals(JsonPrimitive(123), sanitized["procPidCount"])
        assertTrue("secret-boot-id" !in sanitized.toString())
    }
}
