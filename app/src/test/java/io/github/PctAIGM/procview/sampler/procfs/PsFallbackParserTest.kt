package io.github.PctAIGM.procview.sampler.procfs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PsFallbackParserTest {
    @Test
    fun parsesFixedToyboxColumnsAndPreservesCommandTail() {
        val result = PsFallbackParser.parse(
            """
            PID  PPID   UID   RSS S     ELAPSED     TIME+ CMDLINE
             42     1 10123  4096 S       00:12  00:00.25 com.example --token 密钥
              7     2     -     0 R  1-02:03:04  01:02:03.45 [worker]
            not a process row
            """.trimIndent(),
        )

        assertEquals(2, result.processes.size)
        assertEquals(1, result.malformedLineCount)
        assertEquals("com.example --token 密钥", result.processes[1].commandLine)
        assertEquals(1_200L, result.processes[1].elapsedCentiseconds)
        assertEquals(25L, result.processes[1].cpuCentiseconds)
        assertEquals(7, result.processes[0].pid)
        assertNull(result.processes[0].uid)
        assertEquals(9_378_400L, result.processes[0].elapsedCentiseconds)
        assertEquals(372_345L, result.processes[0].cpuCentiseconds)
    }

    @Test
    fun clockParserRejectsMalformedAndOverflowingValues() {
        assertEquals(31_234L, PsFallbackParser.parseClockCentiseconds("05:12.34"))
        assertEquals(9_006_700L, PsFallbackParser.parseClockCentiseconds("1-01:01:07"))
        assertNull(PsFallbackParser.parseClockCentiseconds("00:60"))
        assertNull(PsFallbackParser.parseClockCentiseconds("-1:00"))
        assertNull(PsFallbackParser.parseClockCentiseconds("999999999999999999-00:00:00"))
    }

    @Test
    fun parserCapsRowsAndMarksTruncation() {
        val result = PsFallbackParser.parse(
            """
            PID PPID UID RSS S ELAPSED TIME+ CMDLINE
            1 0 0 4 S 00:01 00:00.01 one
            2 0 0 4 S 00:01 00:00.01 two
            """.trimIndent(),
            maxProcesses = 1,
        )

        assertEquals(listOf(1), result.processes.map(PsFallbackProcess::pid))
        assertTrue(result.truncated)
    }

    @Test
    fun identityTrackerAbsorbsElapsedRoundingButSplitsPidReuse() {
        val tracker = PsFallbackIdentityTracker()
        val process = PsFallbackProcess(
            pid = 42,
            parentPid = 1,
            uid = 10_123,
            rssAtFourKilobytePagesKb = 4_096,
            state = 'S',
            elapsedCentiseconds = 5_000,
            cpuCentiseconds = 25,
            commandLine = "com.example --first",
        )
        val first = tracker.assign(listOf(process), 100_000_000_000L).single().second
        val roundedNext = tracker.assign(
            listOf(process.copy(elapsedCentiseconds = 5_100, cpuCentiseconds = 30)),
            101_100_000_000L,
        ).single().second
        val changedArguments = tracker.assign(
            listOf(process.copy(commandLine = "com.example --second", elapsedCentiseconds = 10)),
            101_200_000_000L,
        ).single().second
        val regressedCpu = tracker.assign(
            listOf(
                process.copy(
                    commandLine = "com.example --second",
                    elapsedCentiseconds = 20,
                    cpuCentiseconds = 1,
                ),
            ),
            101_300_000_000L,
        ).single().second

        assertEquals(first, roundedNext)
        assertNotEquals(first, changedArguments)
        assertNotEquals(changedArguments, regressedCpu)
    }

    @Test
    fun identityTrackerSplitsSameMetadataPidAfterAnObservedGap() {
        val tracker = PsFallbackIdentityTracker()
        val process = PsFallbackProcess(
            pid = 42,
            parentPid = 1,
            uid = 10_123,
            rssAtFourKilobytePagesKb = 4_096,
            state = 'S',
            elapsedCentiseconds = 0,
            cpuCentiseconds = 0,
            commandLine = "com.example",
        )
        val first = tracker.assign(listOf(process), 100_000_000_000L).single().second

        tracker.assign(emptyList(), 100_500_000_000L)
        val reused = tracker.assign(listOf(process), 101_000_000_000L).single().second

        assertNotEquals(first, reused)
    }

    @Test
    fun fallbackCpuUnitsSupportNonDefaultClockRatesAndRejectOverflow() {
        assertEquals(100L, PsFallbackUnits.cpuTicksToCentiseconds(250L, 250L))
        assertEquals(50L, PsFallbackUnits.cpuTicksToCentiseconds(125L, 250L))
        assertEquals(1L, PsFallbackUnits.cpuTicksToCentiseconds(1L, 100L))
        assertNull(PsFallbackUnits.cpuTicksToCentiseconds(-1L, 100L))
        assertNull(PsFallbackUnits.cpuTicksToCentiseconds(1L, 0L))
        assertNull(PsFallbackUnits.cpuTicksToCentiseconds(Long.MAX_VALUE, 1L))
    }

    @Test
    fun fallbackRssUnitsCorrectRuntimePageSizeAndRejectOverflow() {
        assertEquals(4_096L, PsFallbackUnits.rssToKb(4_096L, 4L))
        assertEquals(16_384L, PsFallbackUnits.rssToKb(4_096L, 16L))
        assertNull(PsFallbackUnits.rssToKb(-1L, 4L))
        assertNull(PsFallbackUnits.rssToKb(1L, 0L))
        assertNull(PsFallbackUnits.rssToKb(Long.MAX_VALUE, 16L))
    }
}
