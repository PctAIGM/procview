package io.github.PctAIGM.procview.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportAnonymizerTest {
    private val salt = ByteArray(32) { it.toByte() }

    @Test
    fun identifiersAreStableOnlyWithinTheSameSalt() {
        val first = ExportAnonymizer.withSaltForTesting(salt)
        val second = ExportAnonymizer.withSaltForTesting(salt)
        val different = ExportAnonymizer.withSaltForTesting(ByteArray(32) { (it + 1).toByte() })

        assertEquals(first.packageName("com.example.secret"), first.packageName("com.example.secret"))
        assertEquals(first.packageName("com.example.secret"), second.packageName("com.example.secret"))
        assertNotEquals(first.packageName("com.example.secret"), different.packageName("com.example.secret"))
        assertFalse(first.packageName("com.example.secret").contains("example"))
    }

    @Test
    fun candidatesAndUidsAreConsistentlyRemapped() {
        val anonymizer = ExportAnonymizer.withSaltForTesting(salt)
        val candidates = anonymizer.packageCandidates("com.z|com.a|com.z")

        assertEquals(2, candidates.split('|').size)
        assertTrue(candidates.split('|').all { it.startsWith("app_") })
        assertEquals(1, anonymizer.uid(10001))
        assertEquals(2, anonymizer.uid(10002))
        assertEquals(1, anonymizer.uid(10001))
    }

    @Test
    fun namesAndCommandLinesNeverSurviveVerbatim() {
        val anonymizer = ExportAnonymizer.withSaltForTesting(salt)

        assertTrue(anonymizer.applicationName(null, "native secret").startsWith("native_"))
        assertTrue(anonymizer.processName("com.example.secret:worker").startsWith("process_"))
        assertTrue(anonymizer.commandLine("/system/bin/secret --token value").startsWith("command_"))
    }
}
