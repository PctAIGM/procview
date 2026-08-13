package io.github.PctAIGM.procview.export

import java.io.StringWriter
import java.io.Writer
import org.junit.Assert.assertEquals
import org.junit.Test

class CsvWriterTest {
    @Test
    fun quotesCommaQuotesAndNewlinesUsingRfc4180Rules() {
        val target = StringWriter()
        CsvWriter(target).row(listOf("plain", "a,b", "say \"hi\"", "line1\nline2", "中文"))

        assertEquals(
            "plain,\"a,b\",\"say \"\"hi\"\"\",\"line1\nline2\",中文\r\n",
            target.toString(),
        )
    }

    @Test
    fun nullIsAnEmptyField() {
        val target = StringWriter()
        CsvWriter(target).row(listOf(null, 42, false))

        assertEquals(",42,false\r\n", target.toString())
    }

    @Test
    fun untrustedTextCannotBecomeASpreadsheetFormula() {
        val target = StringWriter()
        CsvWriter(target).row(listOf("=HYPERLINK(\"https://example.invalid\")", "+cmd", -1))

        assertEquals(
            "\"'=HYPERLINK(\"\"https://example.invalid\"\")\",'+cmd,-1\r\n",
            target.toString(),
        )
    }

    @Test
    fun formulaMarkerAfterLeadingWhitespaceIsAlsoNeutralized() {
        val output = StringWriter()
        CsvWriter(output).row(
            listOf(
                "  =SUM(1,2)",
                "\u2003@command",
                "\tcommand",
                "\rcommand",
                "\ncommand",
            ),
        )

        assertEquals(
            "\"'  =SUM(1,2)\",'\u2003@command,'\tcommand,\"'\rcommand\",\"'\ncommand\"\r\n",
            output.toString(),
        )
    }

    @Test
    fun largeExportsDoNotRequireAnInMemoryCsv() {
        var characterCount = 0L
        val countingWriter = object : Writer() {
            override fun write(buffer: CharArray, offset: Int, length: Int) {
                characterCount += length
            }

            override fun write(value: String) {
                characterCount += value.length
            }

            override fun write(value: Int) {
                characterCount += 1
            }

            override fun flush() = Unit
            override fun close() = Unit
        }
        val csv = CsvWriter(countingWriter)

        repeat(100_000) { index -> csv.row(listOf(index, "app,$index", "中文")) }

        assertEquals(true, characterCount > 1_000_000L)
    }
}
