package io.github.PctAIGM.procview.network

import io.github.PctAIGM.procview.sampler.AndroidPackageResolver
import io.github.PctAIGM.procview.shizuku.ShizukuCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

data class NetworkApplication(
    val uid: Int,
    val packages: List<String>,
    val labels: List<String>,
    val traffic: UidTraffic?,
    val connections: List<NetworkConnection>,
) {
    fun matches(query: String): Boolean = query.isBlank() ||
        uid.toString().contains(query, true) ||
        (packages + labels).any { it.contains(query, true) } ||
        connections.any { it.destination.contains(query, true) || it.localEndpoint.contains(query, true) }
}

data class NetworkDisplaySnapshot(val snapshot: NetworkSnapshot, val applications: List<NetworkApplication>)

class NetworkRepository(
    private val coordinator: ShizukuCoordinator,
    private val packages: AndroidPackageResolver,
) {
    suspend fun read(): NetworkDisplaySnapshot = withContext(Dispatchers.IO) {
        val service = coordinator.connectedUserService() ?: error("Shizuku disconnected")
        val encoded = service.readNetworkSnapshot()
        require(encoded.length <= MAX_SNAPSHOT_CHARS) { "Network snapshot exceeds IPC bounds" }
        val snapshot = Json.decodeFromString(NetworkSnapshot.serializer(), encoded)
        validate(snapshot)
        val traffic = snapshot.traffic.associateBy(UidTraffic::uid)
        val connections = snapshot.connections.groupBy(NetworkConnection::uid)
        val uids = traffic.keys + connections.keys
        val resolutions = packages.resolveUids(uids)
        NetworkDisplaySnapshot(snapshot, uids.map { uid ->
            val resolved = resolutions[uid]
            NetworkApplication(uid, resolved?.first.orEmpty(), resolved?.second.orEmpty(),
                traffic[uid], connections[uid].orEmpty())
        })
    }

    private fun validate(snapshot: NetworkSnapshot) {
        require(snapshot.sampledAtMillis >= 0 && snapshot.trafficSampledAtMillis >= 0)
        require(snapshot.traffic.size <= MAX_NETWORK_ENTRIES && snapshot.connections.size <= MAX_NETWORK_ENTRIES)
        require(snapshot.traffic.map { it.uid }.distinct().size == snapshot.traffic.size)
        require(snapshot.traffic.all { it.uid >= 0 && it.downloadedBytes >= 0 && it.uploadedBytes >= 0 })
        require(snapshot.connections.all {
            it.uid >= 0 && it.protocol in setOf("TCP", "UDP") &&
                it.localPort in 0..65535 && it.remotePort in 1..65535 &&
                it.localAddress.length <= 64 && it.remoteAddress.length <= 64 && it.state.length <= 32
        })
    }

    private companion object { const val MAX_SNAPSHOT_CHARS = 350_000 }
}
