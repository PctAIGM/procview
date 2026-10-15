package io.github.PctAIGM.procview.network

import java.net.InetAddress

internal data class TrafficParseResult(val status: NetworkReadStatus, val entries: List<UidTraffic>)
internal data class ConnectionParseResult(
    val recognized: Boolean,
    val partial: Boolean,
    val entries: List<NetworkConnection>,
)

internal object NetworkParsers {
    private val whitespace = Regex("\\s+")
    private val uidPattern = Regex("(?:^|\\s)uid=(-?\\d+)(?=\\s|$)")
    private val setPattern = Regex("(?:^|\\s)set=(\\S+)")
    private val tagPattern = Regex("(?:^|\\s)tag=(\\S+)")
    private val fieldPattern = Regex("(?:^|\\s)(st|rb|tb)=(\\d+)(?=\\s|$)")

    /** Only UID recorder since-boot history; exclude tagged and VPN debug accounting sets. */
    fun parseNetstats(text: String): TrafficParseResult {
        var inUidSection = false
        var inHistory = false
        var sawHistory = false
        var partial = false
        var uid: Int? = null
        val totals = mutableMapOf<Int, Pair<Long, Long>>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line == "UID stats:") {
                inUidSection = true
                inHistory = false
                uid = null
                continue
            }
            if (!inUidSection) continue
            if (line == "History since boot:") {
                inHistory = true
                sawHistory = true
                continue
            }
            // Complete history overlaps since-boot history. Never count it twice.
            if (line.endsWith(":") && line != "History since boot:") {
                inHistory = false
                uid = null
                if (!raw.startsWith(' ') && !raw.startsWith('\t')) inUidSection = false
                continue
            }
            if (!inHistory) continue
            if (line.startsWith("ident=")) {
                val candidate = uidPattern.find(line)?.groupValues?.get(1)?.toIntOrNull()
                val set = setPattern.find(line)?.groupValues?.get(1)
                val tag = tagPattern.find(line)?.groupValues?.get(1)
                if (candidate == null || set == null || tag == null) partial = true
                if (set != null && set !in setOf("DEFAULT", "FOREGROUND", "0", "1") &&
                    !set.startsWith("DBG_") && set !in setOf("1001", "1002")) partial = true
                uid = candidate?.takeIf {
                    it >= 0 && set in setOf("DEFAULT", "FOREGROUND", "0", "1") &&
                        tag in setOf("0x0", "0", "0x00000000")
                }
                continue
            }
            if (line.contains("(omitting")) partial = true
            val currentUid = uid ?: continue
            if (!line.startsWith("st=")) continue
            val fields = fieldPattern.findAll(line).associate { it.groupValues[1] to it.groupValues[2] }
            val rx = fields["rb"]?.toLongOrNull()
            val tx = fields["tb"]?.toLongOrNull()
            if (rx == null || tx == null) {
                partial = true
                continue
            }
            val previous = totals[currentUid] ?: (0L to 0L)
            if (Long.MAX_VALUE - previous.first < rx || Long.MAX_VALUE - previous.second < tx) {
                partial = true
                continue
            }
            totals[currentUid] = previous.first + rx to previous.second + tx
        }
        val entries = totals.map { (key, value) -> UidTraffic(key, value.first, value.second) }
            .sortedByDescending { it.downloadedBytes.toDouble() + it.uploadedBytes.toDouble() }
        return TrafficParseResult(
            when {
                !sawHistory -> NetworkReadStatus.UNSUPPORTED
                partial || entries.size > MAX_NETWORK_ENTRIES -> NetworkReadStatus.PARTIAL
                else -> NetworkReadStatus.AVAILABLE
            },
            entries.take(MAX_NETWORK_ENTRIES),
        )
    }

    fun parseSockets(text: String, protocol: String, ipv6: Boolean): ConnectionParseResult {
        var recognized = false
        var partial = false
        val connections = mutableListOf<NetworkConnection>()
        for (line in text.lineSequence()) {
            if (line.contains("local_address") &&
                (line.contains("rem_address") || line.contains("remote_address"))) {
                recognized = true
                continue
            }
            if (line.isBlank() || !recognized) continue
            val fields = line.trim().split(whitespace)
            if (fields.size < 10) {
                partial = true
                continue
            }
            val connection = runCatching {
                val local = parseEndpoint(fields[1], ipv6)
                val remote = parseEndpoint(fields[2], ipv6)
                val uid = fields[7].toInt().also { require(it >= 0) }
                val state = fields[3].toInt(16)
                NetworkConnection(uid, protocol, local.first, local.second, remote.first, remote.second,
                    if (protocol == "TCP") tcpState(state) else "UDP")
            }.getOrNull()
            if (connection == null) {
                partial = true
            } else if (connection.remotePort != 0 && fields[3] != "0A") {
                // Unconnected UDP and listeners do not identify a remote target.
                if (connections.size < MAX_NETWORK_ENTRIES) connections += connection else partial = true
            }
        }
        return ConnectionParseResult(recognized, partial, connections.distinct())
    }

    private fun parseEndpoint(value: String, ipv6: Boolean): Pair<String, Int> {
        val parts = value.split(':')
        require(parts.size == 2 && parts[0].length == (if (ipv6) 32 else 8))
        val bytes = parts[0].chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        // Android procfs stores each address word in host little-endian order.
        for (offset in bytes.indices step 4) bytes.reverse(offset, offset + 4)
        val port = parts[1].toInt(16).also { require(it in 0..65535) }
        // This numeric-only conversion never issues DNS requests.
        return requireNotNull(InetAddress.getByAddress(bytes).hostAddress) to port
    }

    private fun tcpState(value: Int): String = when (value) {
        1 -> "ESTABLISHED"
        2 -> "SYN_SENT"
        3 -> "SYN_RECV"
        4 -> "FIN_WAIT1"
        5 -> "FIN_WAIT2"
        6 -> "TIME_WAIT"
        7 -> "CLOSE"
        8 -> "CLOSE_WAIT"
        9 -> "LAST_ACK"
        11 -> "CLOSING"
        12 -> "NEW_SYN_RECV"
        else -> "0x${value.toString(16)}"
    }
}
