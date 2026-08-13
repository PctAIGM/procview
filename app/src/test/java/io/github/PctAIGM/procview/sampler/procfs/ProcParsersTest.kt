package io.github.PctAIGM.procview.sampler.procfs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcParsersTest {
    @Test
    fun systemCpuUsesAggregateFieldsWithoutDoubleCountingGuestTime() {
        val cpu = ProcParsers.parseSystemCpuStat(
            "cpu  100 20 30 400 50 6 7 8 999 999\ncpu0 1 2 3 4",
        )

        requireNotNull(cpu)
        assertEquals(621L, cpu.totalTicks)
        assertEquals(450L, cpu.idleTicks)
    }

    @Test
    fun systemCpuRejectsMissingNegativeAndOverflowingCounters() {
        assertNull(ProcParsers.parseSystemCpuStat("intr 1 2 3"))
        assertNull(ProcParsers.parseSystemCpuStat("cpu 1 2 3 -1 5 6 7 8"))
        assertNull(
            ProcParsers.parseSystemCpuStat(
                "cpu ${Long.MAX_VALUE} 1 0 0 0 0 0 0",
            ),
        )
    }

    @Test
    fun meminfoUsesMemAvailableAndBoundsItToTotal() {
        val memory = ProcParsers.parseSystemMemoryInfo(
            "MemTotal: 8192 kB\nMemFree: 100 kB\nMemAvailable: 9000 kB",
        )

        requireNotNull(memory)
        assertEquals(8192L, memory.totalKb)
        assertEquals(8192L, memory.availableKb)
        assertNull(ProcParsers.parseSystemMemoryInfo("MemTotal: 8192 kB"))
    }

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
        assertNull(
            ProcParsers.parseProcessStat(
                "4 (x) S 1 2 3 4 5 6 7 8 9 10 ${Long.MAX_VALUE} 1 13 14 15 16 17 18 19 20",
            ),
        )
        assertNull(
            ProcParsers.parseProcessStat(
                "4 (x) S 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 -1 20",
            ),
        )
        assertNull(
            ProcParsers.parseProcessStat(
                "4 (x) S -1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20",
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
        val invalidIdentity = ProcParsers.parseProcessStatus("PPid: -1\nUid: -2 -2 -2 -2")
        requireNotNull(invalidIdentity)
        assertNull(invalidIdentity.parentPid)
        assertNull(invalidIdentity.uid)
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
            42
            worker
            -3

            2000
        """.trimIndent()

        assertEquals(3, ProcParsers.parsePsPidCount(output))
        assertEquals(setOf(1, 42, 2000), ProcParsers.parsePsPids(output))
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
    fun checkinBatchParserReturnsOnlyRequestedValidRows() {
        val output = """
            time,100,200
            4,10,one,0,0,0,0,0,0,0,0,0,0,0,0,1,2,3,6
            4,11,two,0,0,0,0,0,0,0,0,0,0,0,0,2,3,4,9
            2,12,old,0,0,0,0,0,0,0,0,0,0,0,0,3,4,5,12
            5,13,future,0,0,0,0,0,0,0,0,0,0,0,0,4,5,6,15
            malformed
        """.trimIndent()

        assertEquals(
            mapOf(10 to 6L, 11 to 9L),
            ProcParsers.parseCheckinTotalPssByPid(output, setOf(10, 11, 12, 13, 99)),
        )
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
                "5,7,proc,0,0,0,0,0,0,0,0,0,0,0,0,1,1,1,3",
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
