package io.github.PctAIGM.procview.shizuku.user

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal data class CommandResult(
    val exitCode: Int?,
    val output: String,
    val timedOut: Boolean,
    val truncated: Boolean,
    val durationMs: Long,
)

internal class ReadOnlyCommandRunner {
    private val streamExecutor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "procview-command-reader").apply { isDaemon = true }
    }

    fun listAllPids(): CommandResult = execute(
        command = listOf("/system/bin/ps", "-A", "-o", "PID"),
        timeoutMs = FAST_COMMAND_TIMEOUT_MS,
        maxOutputBytes = MAX_COMMAND_OUTPUT_BYTES,
    )

    fun readPssCheckin(pid: Int, knownPids: Set<Int>): CommandResult {
        require(pid > 0 && pid in knownPids) { "PID must belong to the current catalog" }
        return execute(
            command = listOf(
                "/system/bin/dumpsys",
                "meminfo",
                "--local",
                "--checkin",
                pid.toString(),
            ),
            timeoutMs = PSS_COMMAND_TIMEOUT_MS,
            maxOutputBytes = MAX_COMMAND_OUTPUT_BYTES,
        )
    }

    fun readPssCheckinBatch(): CommandResult = execute(
        command = listOf(
            "/system/bin/dumpsys",
            "meminfo",
            "--local",
            "--checkin",
        ),
        timeoutMs = PSS_BATCH_TIMEOUT_MS,
        maxOutputBytes = MAX_PSS_BATCH_OUTPUT_BYTES,
    )

    fun close() {
        streamExecutor.shutdownNow()
    }

    private fun execute(
        command: List<String>,
        timeoutMs: Long,
        maxOutputBytes: Int,
    ): CommandResult {
        val started = System.nanoTime()
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()
        val outputFuture = streamExecutor.submit(Callable {
            readBounded(process.inputStream, maxOutputBytes)
        })
        val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) process.destroyForcibly()

        val boundedOutput = try {
            outputFuture.get(STREAM_DRAIN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            outputFuture.cancel(true)
            BoundedOutput("", truncated = true)
        }

        return CommandResult(
            exitCode = if (finished) process.exitValue() else null,
            output = boundedOutput.text,
            timedOut = !finished,
            truncated = boundedOutput.truncated,
            durationMs = (System.nanoTime() - started) / NANOS_PER_MILLISECOND,
        )
    }

    private fun readBounded(input: InputStream, maxOutputBytes: Int): BoundedOutput = input.use { stream ->
        val buffer = ByteArray(STREAM_BUFFER_BYTES)
        val collected = ByteArrayOutputStream(minOf(maxOutputBytes, MAX_COMMAND_OUTPUT_BYTES))
        var truncated = false
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            val remaining = maxOutputBytes - collected.size()
            if (remaining > 0) {
                val accepted = minOf(count, remaining)
                collected.write(buffer, 0, accepted)
            }
            if (count > remaining) truncated = true
        }
        BoundedOutput(collected.toString(Charsets.UTF_8.name()), truncated)
    }

    private data class BoundedOutput(val text: String, val truncated: Boolean)

    private companion object {
        const val FAST_COMMAND_TIMEOUT_MS = 2_000L
        const val PSS_COMMAND_TIMEOUT_MS = 5_000L
        const val PSS_BATCH_TIMEOUT_MS = 12_000L
        const val STREAM_DRAIN_TIMEOUT_MS = 750L
        const val MAX_COMMAND_OUTPUT_BYTES = 256 * 1024
        const val MAX_PSS_BATCH_OUTPUT_BYTES = 4 * 1024 * 1024
        const val STREAM_BUFFER_BYTES = 8 * 1024
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
