package io.github.PctAIGM.procview.export

import java.io.Writer

class CsvWriter(private val writer: Writer) {
    fun row(values: Iterable<Any?>) {
        var first = true
        values.forEach { value ->
            if (!first) writer.write(','.code)
            val text = value?.toString().orEmpty()
            val rawPrefix = text.firstOrNull()
            val visiblePrefix = text.dropWhile(Char::isWhitespace).firstOrNull()
            val unsafeText = rawPrefix in CONTROL_FORMULA_PREFIXES ||
                visiblePrefix in VISIBLE_FORMULA_PREFIXES
            val safeText = if (value is String && unsafeText) {
                "'$text"
            } else {
                text
            }
            writer.write(escape(safeText))
            first = false
        }
        writer.write("\r\n")
    }

    fun flush() = writer.flush()

    companion object {
        private val VISIBLE_FORMULA_PREFIXES = setOf('=', '+', '-', '@')
        private val CONTROL_FORMULA_PREFIXES = setOf('\t', '\r', '\n')

        fun escape(value: String): String {
            if (value.none { it == ',' || it == '"' || it == '\r' || it == '\n' }) return value
            return buildString(value.length + 2) {
                append('"')
                value.forEach { character ->
                    if (character == '"') append("\"\"") else append(character)
                }
                append('"')
            }
        }
    }
}
