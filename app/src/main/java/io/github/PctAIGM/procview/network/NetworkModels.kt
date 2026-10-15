package io.github.PctAIGM.procview.network

import kotlinx.serialization.Serializable

@Serializable
enum class NetworkReadStatus { AVAILABLE, PARTIAL, UNAVAILABLE, UNSUPPORTED }

@Serializable
data class UidTraffic(val uid: Int, val downloadedBytes: Long, val uploadedBytes: Long)

@Serializable
data class NetworkConnection(
    val uid: Int,
    val protocol: String,
    val localAddress: String,
    val localPort: Int,
    val remoteAddress: String,
    val remotePort: Int,
    val state: String,
) {
    val destination: String get() = endpoint(remoteAddress, remotePort)
    val localEndpoint: String get() = endpoint(localAddress, localPort)
    private fun endpoint(address: String, port: Int): String =
        if (':' in address) "[$address]:$port" else "$address:$port"
}

/** UID-wide counters must never be attributed to an individual socket or shared-UID package. */
@Serializable
data class NetworkSnapshot(
    val sampledAtMillis: Long,
    val trafficSampledAtMillis: Long,
    val trafficStatus: NetworkReadStatus,
    val connectionStatus: NetworkReadStatus,
    val traffic: List<UidTraffic> = emptyList(),
    val connections: List<NetworkConnection> = emptyList(),
)

internal const val MAX_NETWORK_ENTRIES = 512
