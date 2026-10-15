package io.github.PctAIGM.procview.network

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class NetworkParsersTest {
    @Test
    fun uidTotalsCombineInterfacesAndForegroundWithoutCountingTagsOrDebugSets() {
        val result = NetworkParsers.parseNetstats("""
            Xt stats:
              History since boot:
                ident=[wifi] uid=-1 set=ALL tag=0x0
                  st=1 rb=99999 tb=99999
            UID stats:
              Pending bytes: 200
              History since boot:
                ident=[wifi] uid=10001 set=DEFAULT tag=0x0
                  NetworkStatsHistory: bucketDuration=7200
                    st=1 rb=100 rp=1 tb=20 tp=1
                    st=2 rb=50 rp=1 tb=10 tp=1
                ident=[mobile] uid=10001 set=FOREGROUND tag=0x0
                    st=1 rb=200 tb=30
                ident=[wifi] uid=10001 set=DBG_VPN_IN tag=0x0
                    st=1 rb=5000 tb=5000
                ident=[wifi] uid=10001 set=DEFAULT tag=0xf00
                    st=1 rb=8000 tb=8000
              Complete history:
                ident=[wifi] uid=10001 set=DEFAULT tag=0x0
                    st=1 rb=30000 tb=30000
            UID tag stats:
              History since boot:
                ident=[wifi] uid=10001 set=DEFAULT tag=0x0
                    st=1 rb=40000 tb=40000
        """.trimIndent())
        assertEquals(NetworkReadStatus.AVAILABLE, result.status)
        assertEquals(listOf(UidTraffic(10001, 350, 60)), result.entries)
    }

    @Test
    fun unsupportedAndEmptySupportedOutputsAreDistinct() {
        assertEquals(NetworkReadStatus.UNSUPPORTED, NetworkParsers.parseNetstats("Permission Denial").status)
        val empty = NetworkParsers.parseNetstats("UID stats:\n  History since boot:\n")
        assertEquals(NetworkReadStatus.AVAILABLE, empty.status)
        assertTrue(empty.entries.isEmpty())
        // AOSP --full selects persisted history instead of the since-boot recorder.
        val complete = NetworkParsers.parseNetstats("UID stats:\n  Complete history:\n" +
            "ident=[wifi] uid=10001 set=DEFAULT tag=0x0\nst=1 rb=123 tb=456")
        assertEquals(NetworkReadStatus.UNSUPPORTED, complete.status)
        assertTrue(complete.entries.isEmpty())
    }

    @Test
    fun malformedIdentityAndUnknownAccountingSetArePartial() {
        for (identity in listOf("uid=overflow set=DEFAULT tag=0x0", "uid=10001 tag=0x0",
            "uid=10001 set=NEW_ROM_SET tag=0x0")) {
            val parsed = NetworkParsers.parseNetstats("UID stats:\n  History since boot:\n" +
                "ident=[wifi] $identity\nst=1 rb=100 tb=10")
            assertEquals(NetworkReadStatus.PARTIAL, parsed.status)
            assertTrue(parsed.entries.isEmpty())
        }
    }

    @Test
    fun snapshotAtMaximumEntryCountFitsBoundedJsonIpc() {
        val snapshot = NetworkSnapshot(1234567890123, 1234567890123,
            NetworkReadStatus.AVAILABLE, NetworkReadStatus.AVAILABLE,
            (0 until MAX_NETWORK_ENTRIES).map { UidTraffic(10000 + it, Long.MAX_VALUE, Long.MAX_VALUE) },
            (0 until MAX_NETWORK_ENTRIES).map {
                NetworkConnection(10000 + it, "TCP", "ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff", 65535,
                    "ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff", 65535, "ESTABLISHED")
            })
        val encoded = Json.encodeToString(NetworkSnapshot.serializer(), snapshot)
        assertTrue(encoded.length < 350_000)
        // Binder strings are UTF-16; leave ample room for concurrent process-frame replies.
        assertTrue(encoded.length * 2 < 512 * 1024)
    }

    @Test
    fun omissionMalformedAndOverflowCountersArePartial() {
        for (body in listOf("(omitting 10 buckets)\nst=1 rb=1 tb=1", "st=1 rb=1", "st=1 rb=-1 tb=1",
            "st=1 rb=${Long.MAX_VALUE} tb=1\nst=2 rb=1 tb=1")) {
            val parsed = NetworkParsers.parseNetstats("UID stats:\n  History since boot:\n" +
                "    ident=[wifi] uid=10001 set=DEFAULT tag=0x0\n$body")
            assertEquals(body, NetworkReadStatus.PARTIAL, parsed.status)
            assertTrue(parsed.entries.all { it.downloadedBytes >= 0 && it.uploadedBytes >= 0 })
        }
    }

    @Test
    fun ipv4PortsUidsAndSocketStatesDecodeCorrectly() {
        val result = NetworkParsers.parseSockets(header + "\n" + socket("0100007F:C350", "08080808:01BB", "01", 10123), "TCP", false)
        assertTrue(result.recognized)
        assertFalse(result.partial)
        val value = result.entries.single()
        assertEquals("127.0.0.1:50000", value.localEndpoint)
        assertEquals("8.8.8.8:443", value.destination)
        assertEquals(10123, value.uid)
        assertEquals("ESTABLISHED", value.state)
    }

    @Test
    fun ipv6ReversesEachWordAndBracketsEndpoint() {
        val parsed = NetworkParsers.parseSockets(header + "\n" +
            socket("00000000000000000000000001000000:C350", "B80D0120000000000000000001000000:01BB", "01", 10001), "TCP", true)
        val connection = parsed.entries.single()
        assertEquals("0:0:0:0:0:0:0:1", connection.localAddress)
        assertEquals("[2001:db8:0:0:0:0:0:1]:443", connection.destination)
    }

    @Test
    fun connectedUdpIncludedButUnconnectedUdpAndTcpListenersAreExcluded() {
        val connected = socket("0100007F:C350", "01010101:0035", "01", 10001)
        val unconnected = socket("00000000:0035", "00000000:0000", "07", 10002)
        val listener = socket("00000000:01BB", "00000000:0000", "0A", 10003)
        val parsed = NetworkParsers.parseSockets("$header\n$connected\n$unconnected\n$listener", "UDP", false)
        assertEquals(1, parsed.entries.size)
        assertEquals("1.1.1.1:53", parsed.entries.single().destination)
        assertEquals("UDP", parsed.entries.single().state)
    }

    @Test
    fun malformedSocketRecordsDoNotMasqueradeAsCompleteEmptySample() {
        val unknown = NetworkParsers.parseSockets("Permission denied", "TCP", false)
        assertFalse(unknown.recognized)
        val bad = NetworkParsers.parseSockets("$header\n0: invalid", "TCP", false)
        assertTrue(bad.partial)
        val empty = NetworkParsers.parseSockets(header, "TCP", false)
        assertTrue(empty.recognized)
        assertFalse(empty.partial)
    }

    @Test
    fun entryCapsReportPartialAndKeepLargestTrafficUids() {
        val histories = (0..MAX_NETWORK_ENTRIES).joinToString("\n") {
            "ident=[wifi] uid=${10000 + it} set=DEFAULT tag=0x0\nst=1 rb=$it tb=0"
        }
        val traffic = NetworkParsers.parseNetstats("UID stats:\n  History since boot:\n$histories")
        assertEquals(NetworkReadStatus.PARTIAL, traffic.status)
        assertEquals(MAX_NETWORK_ENTRIES, traffic.entries.size)
        assertEquals(10000 + MAX_NETWORK_ENTRIES, traffic.entries.first().uid)
        val sockets = (0..MAX_NETWORK_ENTRIES).joinToString("\n") {
            socket("0100007F:${(1024 + it).toString(16)}", "08080808:01BB", "01", 10001)
        }
        val connections = NetworkParsers.parseSockets("$header\n$sockets", "TCP", false)
        assertTrue(connections.partial)
        assertEquals(MAX_NETWORK_ENTRIES, connections.entries.size)
    }

    @Test
    fun typedSnapshotJsonRoundTripsAndAppSearchIncludesEndpoints() {
        val connection = NetworkConnection(10001, "TCP", "127.0.0.1", 1234, "1.1.1.1", 443, "ESTABLISHED")
        val snapshot = NetworkSnapshot(100, 90, NetworkReadStatus.AVAILABLE, NetworkReadStatus.PARTIAL,
            listOf(UidTraffic(10001, 1024, 256)), listOf(connection))
        assertEquals(snapshot, Json.decodeFromString(NetworkSnapshot.serializer(),
            Json.encodeToString(NetworkSnapshot.serializer(), snapshot)))
        val app = NetworkApplication(10001, listOf("org.example.app"), listOf("Example"), snapshot.traffic.single(), listOf(connection))
        for (query in listOf("EXAMPLE", "10001", "1.1.1.1", "443")) assertTrue(app.matches(query))
        assertFalse(app.matches("does-not-match"))
    }

    private fun socket(local: String, remote: String, state: String, uid: Int): String =
        "0: $local $remote $state 00000000:00000000 00:00000000 00000000 $uid 0 123456"

    private val header = "sl local_address rem_address st tx_queue rx_queue tr tm->when retrnsmt uid timeout inode"
}
