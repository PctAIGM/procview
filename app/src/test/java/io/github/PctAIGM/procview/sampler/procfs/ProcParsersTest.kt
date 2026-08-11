package io.github.PctAIGM.procview.sampler.procfs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcParsersTest {
    @Test
    fun processStatUsesLastParenthesisAndStableFieldOffsets() {
        val stat = ProcParsers.parseProcessStat(
            "42 (worker (render) 世界) S 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20",
        )

        requireNotNull(stat)
        assertEquals(42, stat.pid)
        assertEquals("worker (render) 世界", stat.command)
        assertEquals('S', stat.state)
        assertEquals(1, stat.parentPid)
        assertEquals(11L, stat.userTicks)
        assertEquals(12L, stat.systemTicks)
        assertEquals(23L, stat.cpuTicks)
        assertEquals(19L, stat.startTimeTicks)
    }

    @Test
    fun processStatRejectsMissingAndMalformedFields() {
        assertNull(ProcParsers.parseProcessStat("42 broken"))
        assertNull(ProcParsers.parseProcessStat("0 (idle) S 1 2 3"))
        assertNull(
            ProcParsers.parseProcessStat(
                "4 (x) S 1 2 3 4 5 6 7 8 9 10 nope 12 13 14 15 16 17 18 19",
            ),
        )
    }

    @Test
    fun processStatusReadsUidParentStateAndRssInKilobytes() {
        val status = ProcParsers.parseProcessStatus(
            """
            Name: com.example
            State: R (running)
            PPid: 17
            Uid: 10234 10234 10234 10234
            VmRSS: 12 MB
            """.trimIndent(),
        )

        requireNotNull(status)
        assertEquals('R', status.state)
        assertEquals(17, status.parentPid)
        assertEquals(10234, status.uid)
        assertEquals(12L * 1024L, status.vmRssKb)
    }

    @Test
    fun processStatusKeepsMissingRssNullable() {
        val status = ProcParsers.parseProcessStatus("Uid: 2000 2000 2000 2000")
        requireNotNull(status)
        assertEquals(2000, status.uid)
        assertNull(status.vmRssKb)
    }

    @Test
    fun statmReadsResidentPageCount() {
        assertEquals(37L, ProcParsers.parseStatmResidentPages("100 37 20 5 0 9 0"))
        assertNull(ProcParsers.parseStatmResidentPages("missing"))
        assertNull(ProcParsers.parseStatmResidentPages("100 -1"))
    }

    @Test
    fun psPidCountIgnoresHeadersAndMalformedRows() {
        val output = """
            PID
              1
            42
            worker
            -3

            2000
        """.trimIndent()

        assertEquals(3, ProcParsers.parsePsPidCount(output))
    }

    @Test
    fun checkinParserSelectsExpectedPidAndTotalPss() {
        val output = """
            time,100,200
            4,321,proc,0,0,0,0,0,0,0,0,0,0,0,0,10,20,30,60
        """.trimIndent()

        assertEquals(60L, ProcParsers.parseCheckinTotalPssKb(output, 321))
        assertNull(ProcParsers.parseCheckinTotalPssKb(output, 322))
    }

    @Test
    fun checkinParserRejectsUnsupportedOrNegativeValues() {
        assertNull(
            ProcParsers.parseCheckinTotalPssKb(
                "2,7,proc,0,0,0,0,0,0,0,0,0,0,0,0,1,1,1,3",
                7,
            ),
        )
        assertNull(
            ProcParsers.parseCheckinTotalPssKb(
                "4,7,proc,0,0,0,0,0,0,0,0,0,0,0,0,1,1,1,-1",
                7,
            ),
        )
    }

    @Test
    fun cmdlineReplacesNullSeparatorsAndBoundsOutput() {
        val normalized = ProcParsers.normalizeCmdline("com.example\u0000--flag\u0000\u0000")
        assertEquals("com.example --flag", normalized)
        assertTrue(ProcParsers.normalizeCmdline("x".repeat(5000)).length <= 4096)
    }
}
