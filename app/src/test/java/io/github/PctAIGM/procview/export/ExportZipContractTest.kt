package io.github.PctAIGM.procview.export

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Test

class ExportZipContractTest {
    @Test
    fun requiredZipEntriesCanBeParsedAgain() {
        val required = listOf(
            SessionExporter.MANIFEST_ENTRY,
            SessionExporter.SYSTEM_ENTRY,
            SessionExporter.PROCESSES_ENTRY,
            SessionExporter.EVENTS_ENTRY,
            SessionExporter.CAPABILITIES_ENTRY,
            SessionExporter.README_ENTRY,
        )
        val bytes = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                required.forEach { name ->
                    zip.putNextEntry(java.util.zip.ZipEntry(name))
                    zip.write("ok".toByteArray())
                    zip.closeEntry()
                }
            }
        }.toByteArray()
        val parsed = buildList {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    add(entry.name)
                    zip.readBytes()
                    zip.closeEntry()
                }
            }
        }

        assertEquals(required, parsed)
    }
}
