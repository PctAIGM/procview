package io.github.PctAIGM.procview.shizuku.user

import io.github.PctAIGM.procview.network.MAX_NETWORK_ENTRIES
import io.github.PctAIGM.procview.network.NetworkParsers
import io.github.PctAIGM.procview.network.NetworkReadStatus
import io.github.PctAIGM.procview.network.NetworkSnapshot
import io.github.PctAIGM.procview.network.TrafficParseResult
import java.io.File

/** Fixed read-only probes. No caller-controlled paths, commands, UIDs, or options. */
internal class NetworkSnapshotReader(private val commands: ReadOnlyCommandRunner) {
    private var cachedTraffic: TrafficParseResult? = null
    private var trafficSampledAtMillis = 0L
    private var lastTrafficReadNanos = Long.MIN_VALUE
    private var cachedSnapshot: NetworkSnapshot? = null
    private var lastSnapshotNanos = Long.MIN_VALUE

    @Synchronized
    fun read(): NetworkSnapshot {
        val now = System.nanoTime()
        cachedSnapshot?.let {
            if (now - lastSnapshotNanos in 0 until SOCKET_CACHE_NANOS) return it
        }
        if (cachedTraffic == null || now - lastTrafficReadNanos >= TRAFFIC_CACHE_NANOS) {
            val result = runCatching { commands.readNetworkStats() }.getOrNull()
            cachedTraffic = if (result == null || result.exitCode != 0 || result.timedOut || result.truncated) {
                // Clipped histories are not valid totals.
                TrafficParseResult(NetworkReadStatus.UNAVAILABLE, emptyList())
            } else {
                NetworkParsers.parseNetstats(result.output)
            }
            trafficSampledAtMillis = System.currentTimeMillis()
            lastTrafficReadNanos = System.nanoTime()
        }
        val tables = listOf("tcp" to false, "tcp6" to true, "udp" to false, "udp6" to true)
        var readable = 0
        var partial = false
        val connections = tables.flatMap { (name, ipv6) ->
            val text = readSocketTable(name)
            if (text == null) {
                partial = true
                emptyList()
            } else {
                val parsed = NetworkParsers.parseSockets(text, if (name.startsWith("tcp")) "TCP" else "UDP", ipv6)
                if (parsed.recognized) readable++ else partial = true
                partial = partial || parsed.partial
                parsed.entries
            }
        }.distinct()
        val traffic = requireNotNull(cachedTraffic)
        return NetworkSnapshot(
            System.currentTimeMillis(), trafficSampledAtMillis, traffic.status,
            when {
                readable == 0 -> NetworkReadStatus.UNAVAILABLE
                partial || connections.size > MAX_NETWORK_ENTRIES -> NetworkReadStatus.PARTIAL
                else -> NetworkReadStatus.AVAILABLE
            },
            traffic.entries, connections.take(MAX_NETWORK_ENTRIES),
        ).also {
            cachedSnapshot = it
            lastSnapshotNanos = System.nanoTime()
        }
    }

    private fun readSocketTable(name: String): String? = runCatching {
        File("/proc/net/$name").inputStream().use { input ->
            // readNBytes is not available on every supported Android runtime.
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (output.size() <= MAX_SOCKET_FILE_BYTES) {
                val count = input.read(buffer, 0, minOf(buffer.size, MAX_SOCKET_FILE_BYTES + 1 - output.size()))
                if (count < 0) break
                output.write(buffer, 0, count)
            }
            if (output.size() > MAX_SOCKET_FILE_BYTES) null else output.toString(Charsets.UTF_8.name())
        }
    }.getOrNull()

    private companion object {
        const val MAX_SOCKET_FILE_BYTES = 512 * 1024
        const val SOCKET_CACHE_NANOS = 4_000_000_000L
        const val TRAFFIC_CACHE_NANOS = 15_000_000_000L
    }
}
