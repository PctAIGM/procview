package io.github.PctAIGM.procview.export

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SessionExporterNameTest {
    @Test
    fun producesSafeBoundedFileName() {
        val instant = 1_700_000_000_000L
        val timestamp = Instant.ofEpochMilli(instant)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))

        val value = SessionExporter.exportFileName(instant, " My / diagnostic : session? ")

        assertEquals("procview-session-$timestamp-My-diagnostic-session.zip", value)
        assertFalse(value.contains('/'))
        assertFalse(value.contains(':'))
    }

    @Test
    fun anonymousFileNameCanOmitAbsoluteTimestamp() {
        val value = SessionExporter.exportFileName(
            wallTimeMs = 1_700_000_000_000L,
            sessionName = "anonymous",
            includeTimestamp = false,
        )

        assertEquals("procview-session-anonymous.zip", value)
    }
}
