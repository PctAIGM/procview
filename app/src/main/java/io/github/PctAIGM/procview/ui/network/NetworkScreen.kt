package io.github.PctAIGM.procview.ui.network

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.PctAIGM.procview.R
import io.github.PctAIGM.procview.network.NetworkApplication
import io.github.PctAIGM.procview.network.NetworkDisplaySnapshot
import io.github.PctAIGM.procview.network.NetworkReadStatus
import io.github.PctAIGM.procview.network.NetworkRepository
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private data class NetworkUiState(val data: NetworkDisplaySnapshot? = null, val failed: Boolean = false)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NetworkScreen(
    contentPadding: PaddingValues,
    repository: NetworkRepository,
    backendReady: Boolean,
    onSetup: () -> Unit,
) {
    var monitoring by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf("download") }
    var selectedUid by rememberSaveable { mutableStateOf<Int?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val state by produceState(NetworkUiState(), monitoring, backendReady, lifecycleOwner) {
        value = NetworkUiState()
        if (monitoring && backendReady) {
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (isActive) {
                    value = try {
                        NetworkUiState(repository.read())
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Exception) {
                        NetworkUiState(failed = true)
                    }
                    delay(5_000L)
                }
            }
        }
    }
    val snapshot = state.data?.snapshot
    val applications = state.data?.applications.orEmpty().filter { it.matches(query.trim()) }
        .sortedWith(compareByDescending<NetworkApplication> {
            when (sort) {
                "upload" -> it.traffic?.uploadedBytes?.toDouble() ?: -1.0
                "connections" -> it.connections.size.toDouble()
                else -> it.traffic?.downloadedBytes?.toDouble() ?: -1.0
            }
        }.thenBy { it.uid })
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(contentPadding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.network_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.network_description), style = MaterialTheme.typography.bodySmall)
                    if (backendReady) {
                        Button(onClick = { monitoring = !monitoring; selectedUid = null }) {
                            Text(stringResource(if (monitoring) R.string.network_stop else R.string.network_start))
                        }
                    } else {
                        Text(stringResource(R.string.network_setup_required))
                        Button(onClick = onSetup) { Text(stringResource(R.string.network_setup)) }
                    }
                }
            }
        }
        if (monitoring && backendReady) {
            item {
                when {
                    state.failed -> Text(stringResource(R.string.network_failed), color = MaterialTheme.colorScheme.error)
                    snapshot == null -> CircularProgressIndicator()
                    else -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.network_updated, formatTime(snapshot.sampledAtMillis)))
                        Text(stringResource(R.string.network_traffic_updated, formatTime(snapshot.trafficSampledAtMillis)),
                            style = MaterialTheme.typography.bodySmall)
                        StatusText(stringResource(R.string.network_traffic), snapshot.trafficStatus)
                        StatusText(stringResource(R.string.network_connections), snapshot.connectionStatus)
                        Text(stringResource(R.string.network_scope), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(stringResource(R.string.network_search)) },
                )
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listOf("download", "upload", "connections")) { key ->
                        FilterChip(
                            selected = sort == key, onClick = { sort = key },
                            label = { Text(stringResource(when (key) {
                                "upload" -> R.string.network_upload
                                "connections" -> R.string.network_connections
                                else -> R.string.network_download
                            })) },
                        )
                    }
                }
            }
            if (snapshot != null && applications.isEmpty()) {
                item { Text(stringResource(R.string.network_empty)) }
            }
            items(applications, key = { it.uid }) { app ->
                Card(onClick = { selectedUid = app.uid }) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(app.labels.joinToString(" / ").ifBlank { "UID ${app.uid}" },
                            style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("UID ${app.uid}" + app.packages.joinToString(", ").let { if (it.isBlank()) "" else " · $it" },
                            style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (app.packages.size > 1) Text(stringResource(R.string.network_shared_uid),
                            color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(stringResource(R.string.network_download_value, formatBytes(app.traffic?.downloadedBytes)))
                            Text(stringResource(R.string.network_upload_value, formatBytes(app.traffic?.uploadedBytes)))
                        }
                        Text(stringResource(R.string.network_connection_count, app.connections.size))
                        app.connections.firstOrNull()?.let { Text("${it.protocol} ${it.destination}",
                            fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                        TextButton(onClick = { selectedUid = app.uid }) { Text(stringResource(R.string.network_details)) }
                    }
                }
            }
        }
    }
    val selected = state.data?.applications?.firstOrNull { it.uid == selectedUid }
    if (selected != null && monitoring && backendReady) {
        ModalBottomSheet(onDismissRequest = { selectedUid = null }) {
            LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Text(selected.labels.joinToString(" / ").ifBlank { "UID ${selected.uid}" },
                        style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.network_endpoint_note), style = MaterialTheme.typography.bodySmall)
                }
                if (selected.connections.isEmpty()) item {
                    Text(stringResource(if (snapshot?.connectionStatus == NetworkReadStatus.UNAVAILABLE)
                        R.string.network_address_unavailable else R.string.network_no_connections))
                }
                items(selected.connections) { connection ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${connection.protocol} · ${connection.state}", style = MaterialTheme.typography.labelLarge)
                        Text(connection.destination, fontFamily = FontFamily.Monospace)
                        Text(stringResource(R.string.network_local_address, connection.localEndpoint),
                            fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusText(label: String, status: NetworkReadStatus) {
    Text("$label · " + stringResource(when (status) {
        NetworkReadStatus.AVAILABLE -> R.string.network_available
        NetworkReadStatus.PARTIAL -> R.string.network_partial
        NetworkReadStatus.UNAVAILABLE -> R.string.network_unavailable
        NetworkReadStatus.UNSUPPORTED -> R.string.network_unsupported
    }), color = if (status == NetworkReadStatus.AVAILABLE) MaterialTheme.colorScheme.onSurfaceVariant
        else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
}

private fun formatTime(millis: Long): String = DateFormat.getTimeInstance().format(Date(millis))

private fun formatBytes(value: Long?): String {
    if (value == null) return "—"
    val units = listOf("B", "KiB", "MiB", "GiB", "TiB", "PiB", "EiB")
    var amount = value.toDouble()
    var unit = 0
    while (amount >= 1024 && unit < units.lastIndex) { amount /= 1024; unit++ }
    return if (unit == 0) "$value B" else String.format(Locale.getDefault(), "%.1f %s", amount, units[unit])
}
