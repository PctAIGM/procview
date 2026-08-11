package io.github.PctAIGM.procview.sampler.procfs

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream

internal object ProcFileReader {
    fun readText(file: File, maxBytes: Int): String? {
        require(maxBytes > 0) { "maxBytes must be positive" }
        return runCatching {
            FileInputStream(file).use { input ->
                val output = ByteArrayOutputStream(maxBytes)
                val buffer = ByteArray(minOf(BUFFER_BYTES, maxBytes))
                while (output.size() < maxBytes) {
                    val count = input.read(buffer, 0, minOf(buffer.size, maxBytes - output.size()))
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
                output.toString(Charsets.UTF_8.name())
            }
        }.getOrNull()
    }

    private const val BUFFER_BYTES = 4096
}
