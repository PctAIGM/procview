package io.github.PctAIGM.procview.ui.history

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.PctAIGM.procview.R
import io.github.PctAIGM.procview.data.HistoryRepository
import io.github.PctAIGM.procview.data.db.HistoryProcessRow
import io.github.PctAIGM.procview.data.db.HistoryProcessSummary
import io.github.PctAIGM.procview.data.db.SessionEntity
import io.github.PctAIGM.procview.data.db.SessionEventEntity
import io.github.PctAIGM.procview.data.db.SystemSampleEntity
import io.github.PctAIGM.procview.data.db.HistoryTargetSample
import io.github.PctAIGM.procview.export.ExportOptions
import io.github.PctAIGM.procview.export.SessionExportOutcome
import io.github.PctAIGM.procview.export.SessionExporter
import io.github.PctAIGM.procview.export.toExportOptions
import io.github.PctAIGM.procview.model.PinnedTarget
import io.github.PctAIGM.procview.sampler.RetentionReason
import io.github.PctAIGM.procview.ui.live.TrendCard
import io.github.PctAIGM.procview.ui.live.MultiTrendCard
import io.github.PctAIGM.procview.ui.live.TrendSeries
import io.github.PctAIGM.procview.ui.theme.CpuMetricColor
import io.github.PctAIGM.procview.ui.theme.MemoryMetricColor
import io.github.PctAIGM.procview.ui.theme.TemperatureMetricColor
import io.github.PctAIGM.procview.settings.UserSettings
import io.github.PctAIGM.procview.settings.UserSettingsStore
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
internal fun HistoryScreen(
    contentPadding: PaddingValues,
    repository: HistoryRepository,
    exporter: SessionExporter,
    settingsStore: UserSettingsStore,
    pinnedTargets: Set<PinnedTarget>,
    coroutineScope: CoroutineScope,
    modifier: Modifier = Modifier,
) {
    val sessions by remember(repository) { repository.sessions() }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val userSettings by remember(settingsStore) { settingsStore.settings }
        .collectAsStateWithLifecycle(initialValue = UserSettings())
    var selectedSessionId by rememberSaveable { mutableStateOf<String?>(null) }
    val closeDetails = { selectedSessionId = null }
    BackHandler(enabled = selectedSessionId != null, onBack = closeDetails)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .padding(contentPadding),
    ) {
        val wide = maxWidth >= 900.dp && selectedSessionId != null
        Row(modifier = Modifier.fillMaxSize()) {
            SessionList(
                sessions = sessions,
                repository = repository,
                onOpen = { selectedSessionId = it },
                modifier = if (wide) Modifier.weight(0.44f) else Modifier.fillMaxWidth(),
            )
            if (wide) {
                VerticalDivider(modifier = Modifier.fillMaxHeight())
                HistoryDetailPane(
                    sessionId = selectedSessionId!!,
                    repository = repository,
                    exporter = exporter,
                    userSettings = userSettings,
                    pinnedTargets = pinnedTargets,
                    coroutineScope = coroutineScope,
                    onClose = closeDetails,
                    onDeleted = closeDetails,
                    modifier = Modifier.weight(0.56f),
                )
            }
        }
        if (selectedSessionId != null && !wide) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
            ) {
                HistoryDetailPane(
                    sessionId = selectedSessionId!!,
                    repository = repository,
                    exporter = exporter,
                    userSettings = userSettings,
                    pinnedTargets = pinnedTargets,
                    coroutineScope = coroutineScope,
                    onClose = closeDetails,
                    onDeleted = closeDetails,
                )
            }
        }
    }
}

@Composable
private fun SessionList(
    sessions: List<SessionEntity>,
    repository: HistoryRepository,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxHeight(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (sessions.isEmpty()) {
            item(key = "history-empty") { HistoryEmptyCard() }
        } else {
            items(sessions, key = SessionEntity::id) { session ->
                SessionCard(session, repository, onOpen = { onOpen(session.id) })
            }
        }
        item(key = "history-footer") { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun SessionCard(
    session: SessionEntity,
    repository: HistoryRepository,
    onOpen: () -> Unit,
) {
    val estimatedBytes by produceState<Long?>(
        initialValue = null,
        session.id,
        session.status,
        session.lastSampleSequence?.div(SIZE_REFRESH_FRAME_BUCKET),
    ) {
        value = try {
            repository.estimatedSessionBytes(session.id)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        }
    }
    val topProcesses by remember(session.id, repository) {
        repository.topSummaries(session.id)
    }.collectAsStateWithLifecycle(initialValue = emptyList())
    val maximumHeat = listOfNotNull(
        session.maximumBatteryTemperatureDeciC?.let(::formatTemperature),
        session.maximumThermalStatus?.let { thermalStatusText(it) },
    ).joinToString(" · ").ifBlank { "—" }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = session.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stringResource(
                            R.string.history_time_range,
                            formatDateTime(session.startWallTimeMs),
                            session.endWallTimeMs?.let(::formatDateTime) ?: "—",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusBadge(session.status, session.pauseReason)
            }
            Text(
                text = stringResource(
                    R.string.history_duration_and_size,
                    formatDurationMillis(session.elapsedDurationMs),
                    estimatedBytes?.let(::formatBytes) ?: "—",
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            SessionMetricGroup(
                listOf(
                    stringResource(R.string.history_peak_cpu) to
                        formatPercent(session.peakSystemCpuBasisPoints),
                    stringResource(R.string.history_min_available_memory) to
                        formatKilobytes(session.minimumAvailableMemoryKb),
                    stringResource(R.string.history_max_temperature) to maximumHeat,
                ),
            )
            if (topProcesses.isNotEmpty()) {
                Text(
                    text = stringResource(
                        R.string.history_top_processes,
                        topProcesses.joinToString { it.displayName },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun HistoryDetailPane(
    sessionId: String,
    repository: HistoryRepository,
    exporter: SessionExporter,
    userSettings: UserSettings,
    pinnedTargets: Set<PinnedTarget>,
    coroutineScope: CoroutineScope,
    onClose: () -> Unit,
    onDeleted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val session by remember(sessionId) { repository.session(sessionId) }
        .collectAsStateWithLifecycle(initialValue = null)
    val samples by remember(sessionId) { repository.systemSamples(sessionId) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val events by remember(sessionId) { repository.events(sessionId) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    var cursorOffsetMs by rememberSaveable(sessionId) { mutableStateOf(0f) }
    var cursorInitialized by rememberSaveable(sessionId) { mutableStateOf(false) }
    var showEditor by rememberSaveable(sessionId) { mutableStateOf(false) }
    var showDeleteConfirmation by rememberSaveable(sessionId) { mutableStateOf(false) }
    var previewOptions by rememberSaveable(
        sessionId,
        stateSaver = NullableExportOptionsSaver,
    ) { mutableStateOf<ExportOptions?>(null) }
    var pendingExportOptions by rememberSaveable(
        sessionId,
        stateSaver = NullableExportOptionsSaver,
    ) { mutableStateOf<ExportOptions?>(null) }
    var exportOutcome by remember(sessionId) { mutableStateOf<SessionExportOutcome?>(null) }
    var exporting by remember(sessionId) { mutableStateOf(false) }
    var selectedOverlayKeys by rememberSaveable(sessionId) {
        mutableStateOf(emptyList<String>())
    }
    LaunchedEffect(pinnedTargets) {
        val available = pinnedTargets.map(PinnedTarget::stableKey).toSet()
        selectedOverlayKeys = selectedOverlayKeys.filter(available::contains)
    }
    val createDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { target ->
        val options = pendingExportOptions
        if (target == null || options == null) {
            pendingExportOptions = null
            return@rememberLauncherForActivityResult
        }
        exporting = true
        exportOutcome = null
        coroutineScope.launch {
            try {
                exportOutcome = exporter.export(sessionId, target, options)
            } finally {
                exporting = false
                pendingExportOptions = null
            }
        }
    }
    val maximumOffset = maxOf(
        session?.elapsedDurationMs ?: 0L,
        samples.lastOrNull()?.elapsedOffsetMs ?: 0L,
        events.maxOfOrNull(SessionEventEntity::elapsedOffsetMs) ?: 0L,
    ).coerceAtLeast(0L)
    LaunchedEffect(maximumOffset) {
        if (!cursorInitialized && maximumOffset > 0L) {
            cursorOffsetMs = maximumOffset.toFloat()
            cursorInitialized = true
        } else if (cursorOffsetMs > maximumOffset.toFloat()) {
            cursorOffsetMs = maximumOffset.toFloat()
        }
    }
    val gapActive = remember(events, samples, cursorOffsetMs, maximumOffset) {
        isGapActive(events, samples, cursorOffsetMs.toLong()) ||
            isSamplingGap(samples, cursorOffsetMs.toLong(), maximumOffset)
    }
    val selectedSample = remember(samples, cursorOffsetMs, gapActive) {
        if (gapActive) null else nearestSample(samples, cursorOffsetMs.toLong())
    }
    val processRows by produceState<List<HistoryProcessRow>>(
        initialValue = emptyList(),
        sessionId,
        selectedSample?.sequence,
    ) {
        value = selectedSample?.let { sample ->
            try {
                repository.processRowsAt(sessionId, sample.sequence)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                emptyList()
            }
        }.orEmpty()
    }
    val estimatedBytes by produceState<Long?>(
        null,
        sessionId,
        samples.size / SIZE_REFRESH_FRAME_BUCKET,
    ) {
        value = try {
            repository.estimatedSessionBytes(sessionId)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        }
    }
    val sessionValue = session
    if (sessionValue == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.history_session_missing))
                TextButton(onClick = onClose) { Text(stringResource(R.string.action_back)) }
            }
        }
        return
    }
    val cursorFraction = if (maximumOffset > 0L) {
        cursorOffsetMs / maximumOffset.toFloat()
    } else {
        0f
    }
    val chartSamples = remember(samples, maximumOffset) {
        timelineChartSamples(samples, MAX_CHART_POINTS, maximumOffset)
    }
    val cpuPoints = chartSamples.map { it?.cpuPercentBasisPoints?.div(100f) }
    val memoryPoints = chartSamples.map(::memoryUsedPercent)
    val degradedRegions = chartSamples.map { it?.dataSource == "PS_FALLBACK" }
    val degradedRegionDescription = if (degradedRegions.any { it }) {
        stringResource(R.string.history_degraded_regions)
    } else {
        null
    }
    val selectedOverlayTargets = remember(pinnedTargets, selectedOverlayKeys) {
        selectedOverlayKeys.mapNotNull { key ->
            pinnedTargets.firstOrNull { it.stableKey == key }
        }
    }
    val overlaySamples by produceState<Map<String, List<HistoryTargetSample>>>(
        initialValue = emptyMap(),
        sessionId,
        selectedOverlayTargets,
        samples.lastOrNull()?.sequence?.div(SIZE_REFRESH_FRAME_BUCKET),
    ) {
        value = selectedOverlayTargets.associate { target ->
            target.stableKey to try {
                repository.targetSamples(sessionId, target)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                emptyList()
            }
        }
    }
    val sessionIsTerminal = sessionValue.status == "COMPLETED" ||
        sessionValue.status == "INTERRUPTED"
    val sessionTimeRange = stringResource(
        R.string.history_time_range,
        formatDateTime(sessionValue.startWallTimeMs),
        sessionValue.endWallTimeMs?.let(::formatDateTime) ?: "—",
    )

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(key = "history-detail-title") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) {
                    Icon(
                        Icons.Default.ArrowBack,
                        contentDescription = stringResource(R.string.action_back),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = sessionValue.name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stringResource(
                            R.string.history_detail_subtitle,
                            sessionTimeRange,
                            estimatedBytes?.let(::formatBytes) ?: "—",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(
                    onClick = { showEditor = true },
                    enabled = sessionIsTerminal && !exporting,
                ) {
                    Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.action_edit))
                }
                IconButton(
                    onClick = { showDeleteConfirmation = true },
                    enabled = sessionIsTerminal && !exporting,
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(R.string.action_delete),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        item(key = "history-export-actions") {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        previewOptions = userSettings.regularExport.toExportOptions(
                            anonymous = false,
                        )
                    },
                    enabled = sessionIsTerminal && !exporting,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.Download, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.action_export_zip))
                }
                OutlinedButton(
                    onClick = {
                        previewOptions = userSettings.anonymousExport.toExportOptions(
                            anonymous = true,
                        )
                    },
                    enabled = sessionIsTerminal && !exporting,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.PrivacyTip, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.action_export_anonymous))
                }
            }
        }
        if (exporting || exportOutcome != null) {
            item(key = "history-export-status") {
                ExportStatusCard(exporting = exporting, outcome = exportOutcome)
            }
        }
        if (sessionValue.note.isNotBlank()) {
            item(key = "history-note") {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(
                            stringResource(R.string.history_note),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(sessionValue.note, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        item(key = "history-cursor") {
            TimelineCursorCard(
                cursorOffsetMs = cursorOffsetMs,
                maximumOffsetMs = maximumOffset,
                selectedSample = selectedSample,
                gapActive = gapActive,
                onCursorChange = { cursorOffsetMs = it },
            )
        }
        item(key = "history-cpu-chart") {
            TrendCard(
                titleRes = R.string.metric_system_cpu,
                currentValue = formatPercent(selectedSample?.cpuPercentBasisPoints),
                points = cpuPoints,
                color = CpuMetricColor,
                fixedMaximum = 100f,
                cursorFraction = cursorFraction,
                highlightedRegions = degradedRegions,
                highlightedRegionDescription = degradedRegionDescription,
            )
        }
        item(key = "history-memory-chart") {
            TrendCard(
                titleRes = R.string.metric_memory_used,
                currentValue = memoryUsedPercent(selectedSample)?.let(::formatPercentValue) ?: "—",
                points = memoryPoints,
                color = MemoryMetricColor,
                fixedMaximum = 100f,
                cursorFraction = cursorFraction,
                highlightedRegions = degradedRegions,
                highlightedRegionDescription = degradedRegionDescription,
            )
        }
        if (pinnedTargets.isNotEmpty()) {
            item(key = "history-overlay-picker") {
                PinnedOverlayPicker(
                    targets = pinnedTargets,
                    selectedKeys = selectedOverlayKeys,
                    onToggle = { target ->
                        selectedOverlayKeys = if (target.stableKey in selectedOverlayKeys) {
                            selectedOverlayKeys - target.stableKey
                        } else if (selectedOverlayKeys.size < MAX_OVERLAY_SERIES) {
                            selectedOverlayKeys + target.stableKey
                        } else {
                            selectedOverlayKeys
                        }
                    },
                )
            }
        }
        if (selectedOverlayTargets.isNotEmpty()) {
            item(key = "history-overlay-chart") {
                val colors = listOf(CpuMetricColor, MemoryMetricColor, TemperatureMetricColor)
                val chartSequences = chartSamples.map { it?.sequence }
                MultiTrendCard(
                    titleRes = R.string.history_pinned_cpu_overlay,
                    series = selectedOverlayTargets.mapIndexed { index, target ->
                        val valuesBySequence = overlaySamples[target.stableKey]
                            .orEmpty()
                            .associateBy(HistoryTargetSample::sequence)
                        TrendSeries(
                            label = overlayTargetLabel(target),
                            points = chartSequences.map { sequence ->
                                sequence?.let { valuesBySequence[it]?.cpuPercentBasisPoints?.div(100f) }
                            },
                            color = colors[index % colors.size],
                        )
                    },
                    fixedMaximum = null,
                    cursorFraction = cursorFraction,
                )
            }
        }
        selectedSample?.let { sample ->
            item(key = "history-context") { HistoricalContextCard(sample) }
        }
        item(key = "history-events") { EventTimelineCard(events, cursorOffsetMs.toLong()) }
        item(key = "history-process-title") {
            Text(
                text = stringResource(R.string.history_processes_at_cursor),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        if (processRows.isEmpty()) {
            item(key = "history-process-empty") {
                Text(
                    text = stringResource(R.string.history_no_process_sample),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(processRows, key = HistoryProcessRow::identityId) { row ->
                HistoricalProcessCard(row)
            }
        }
        item(key = "history-detail-footer") { Spacer(Modifier.height(8.dp)) }
    }

    if (showEditor) {
        EditSessionDialog(
            session = sessionValue,
            onDismiss = { showEditor = false },
            onSave = { name, note ->
                showEditor = false
                coroutineScope.launch { repository.updateNameAndNote(sessionId, name, note) }
            },
        )
    }
    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text(stringResource(R.string.history_delete_title)) },
            text = { Text(stringResource(R.string.history_delete_body)) },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirmation = false
                        coroutineScope.launch {
                            if (repository.deleteSession(sessionId)) onDeleted()
                        }
                    },
                ) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
    previewOptions?.let { options ->
        ExportPreviewDialog(
            options = options,
            onOptionsChanged = { previewOptions = it },
            onDismiss = { previewOptions = null },
            onContinue = {
                previewOptions = null
                pendingExportOptions = options
                createDocument.launch(exporter.suggestedFileName(sessionValue, options))
            },
        )
    }
}

@Composable
private fun PinnedOverlayPicker(
    targets: Set<PinnedTarget>,
    selectedKeys: List<String>,
    onToggle: (PinnedTarget) -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.history_pinned_overlay_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(R.string.history_pinned_overlay_limit, MAX_OVERLAY_SERIES),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            targets.sortedBy(PinnedTarget::stableKey).forEach { target ->
                val selected = target.stableKey in selectedKeys
                FilterChip(
                    selected = selected,
                    enabled = selected || selectedKeys.size < MAX_OVERLAY_SERIES,
                    onClick = { onToggle(target) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(overlayTargetLabel(target)) },
                )
            }
        }
    }
}

private fun overlayTargetLabel(target: PinnedTarget): String = listOfNotNull(
    target.packageName,
    target.processName,
    target.uid?.let { "UID $it" },
).joinToString(" · ").ifBlank { target.stableKey }

@Composable
private fun ExportPreviewDialog(
    options: ExportOptions,
    onOptionsChanged: (ExportOptions) -> Unit,
    onDismiss: () -> Unit,
    onContinue: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (options.anonymous) R.string.export_preview_anonymous_title
                    else R.string.export_preview_title,
                ),
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.export_preview_entries))
                Text(
                    text = stringResource(
                        if (options.anonymous) R.string.export_preview_anonymous_body
                        else R.string.export_preview_regular_body,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ExportPreviewSwitch(
                    stringResource(R.string.export_include_session_name),
                    options.includeSessionName,
                ) { onOptionsChanged(options.copy(includeSessionName = it)) }
                ExportPreviewSwitch(
                    stringResource(R.string.export_include_note),
                    options.includeNote,
                ) { onOptionsChanged(options.copy(includeNote = it)) }
                ExportPreviewSwitch(
                    stringResource(R.string.export_include_device),
                    options.includeDeviceDetails,
                ) { onOptionsChanged(options.copy(includeDeviceDetails = it)) }
                ExportPreviewSwitch(
                    stringResource(R.string.export_include_absolute_time),
                    options.includeAbsoluteTime,
                ) { onOptionsChanged(options.copy(includeAbsoluteTime = it)) }
                ExportPreviewSwitch(
                    stringResource(R.string.export_include_command_line),
                    options.includeCommandLine,
                ) { onOptionsChanged(options.copy(includeCommandLine = it)) }
                Text(
                    text = stringResource(R.string.export_saf_notice),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(onClick = onContinue) { Text(stringResource(R.string.export_choose_location)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun ExportPreviewSwitch(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.semantics { contentDescription = label },
        )
    }
}

@Composable
private fun ExportStatusCard(
    exporting: Boolean,
    outcome: SessionExportOutcome?,
) {
    val isError = outcome is SessionExportOutcome.Failure ||
        outcome == SessionExportOutcome.SessionMissing
    val text = when {
        exporting -> stringResource(R.string.export_status_running)
        outcome is SessionExportOutcome.Success -> stringResource(
            R.string.export_status_success,
            outcome.systemRows,
            outcome.processRows,
            outcome.eventRows,
        )
        else -> stringResource(R.string.export_status_failed)
    }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isError) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.surface,
        ),
    ) {
        Text(text, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun TimelineCursorCard(
    cursorOffsetMs: Float,
    maximumOffsetMs: Long,
    selectedSample: SystemSampleEntity?,
    gapActive: Boolean,
    onCursorChange: (Float) -> Unit,
) {
    val timelineLabel = stringResource(R.string.history_timeline_cursor)
    val cursorState = if (gapActive) {
        "${formatDurationMillis(cursorOffsetMs.toLong())}. ${stringResource(R.string.history_gap_at_cursor)}"
    } else {
        formatDurationMillis(cursorOffsetMs.toLong())
    }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (gapActive) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.history_timeline_cursor),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = formatDurationMillis(cursorOffsetMs.toLong()),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Slider(
                value = cursorOffsetMs.coerceIn(0f, maximumOffsetMs.toFloat().coerceAtLeast(0f)),
                onValueChange = onCursorChange,
                valueRange = 0f..maximumOffsetMs.toFloat().coerceAtLeast(1f),
                enabled = maximumOffsetMs > 0L,
                modifier = Modifier.semantics {
                    contentDescription = timelineLabel
                    stateDescription = cursorState
                },
            )
            Text(
                text = when {
                    gapActive -> stringResource(R.string.history_gap_at_cursor)
                    selectedSample != null -> stringResource(
                        R.string.history_frame_at_cursor,
                        selectedSample.sequence,
                        selectedSample.samplingIntervalMs,
                    )
                    else -> stringResource(R.string.history_no_sample_at_cursor)
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (gapActive) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

@Composable
private fun HistoricalContextCard(sample: SystemSampleEntity) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Text(
                text = stringResource(R.string.history_system_context),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            ValueRow(
                stringResource(R.string.live_battery_level),
                sample.batteryLevelPercent?.let { "$it%" } ?: "—",
            )
            ValueRow(
                stringResource(R.string.live_battery_temperature),
                sample.batteryTemperatureDeciC?.let(::formatTemperature) ?: "—",
            )
            ValueRow(
                stringResource(R.string.live_charging_state),
                chargingStateText(sample.chargingState),
            )
            ValueRow(
                stringResource(R.string.live_thermal_status),
                thermalStatusText(sample.thermalStatus),
            )
            ValueRow(
                stringResource(R.string.live_screen_state),
                stringResource(if (sample.screenInteractive) R.string.screen_on else R.string.screen_off),
            )
            ValueRow(
                stringResource(R.string.detail_data_source_label),
                stringResource(
                    if (sample.dataSource == "PROCFS") {
                        R.string.data_source_procfs
                    } else {
                        R.string.data_source_ps_fallback
                    },
                ),
            )
            ValueRow(
                stringResource(R.string.history_collection_cost),
                stringResource(R.string.value_milliseconds, sample.collectionDurationMs),
            )
        }
    }
}

@Composable
private fun EventTimelineCard(events: List<SessionEventEntity>, cursorOffsetMs: Long) {
    val nearby = events.sortedBy { abs(it.elapsedOffsetMs - cursorOffsetMs) }.take(6)
        .sortedBy(SessionEventEntity::elapsedOffsetMs)
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.history_events_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (nearby.isEmpty()) {
                Text(
                    text = stringResource(R.string.history_no_events),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                nearby.forEachIndexed { index, event ->
                    if (index > 0) HorizontalDivider()
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = formatDurationMillis(event.elapsedOffsetMs),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = eventLabel(event.type),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoricalProcessCard(row: HistoryProcessRow) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = row.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stringResource(
                            R.string.history_process_identity,
                            row.pid,
                            row.uid?.toString() ?: "—",
                            row.processState,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = row.packageCandidates
                            ?.split('|')
                            ?.filter(String::isNotBlank)
                            ?.joinToString()
                            ?.takeIf(String::isNotBlank)
                            ?: row.packageName
                            ?: row.processName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = row.rank?.let { "#$it" } ?: "—",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            SessionMetricGroup(
                listOf(
                    stringResource(R.string.live_sort_cpu) to
                        formatPercent(row.cpuPercentBasisPoints),
                    stringResource(R.string.live_sort_rss) to formatKilobytes(row.rssKb),
                    stringResource(R.string.live_sort_pss) to formatKilobytes(row.pssKb),
                ),
            )
            Text(
                text = retainedReasonText(row.reasonKept),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EditSessionDialog(
    session: SessionEntity,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var name by rememberSaveable(session.id) { mutableStateOf(session.name) }
    var note by rememberSaveable(session.id) { mutableStateOf(session.note) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.history_edit_title)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(80) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.session_name_label)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(2_000) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.history_note)) },
                    minLines = 3,
                    maxLines = 6,
                )
            }
        },
        confirmButton = {
            Button(onClick = { onSave(name, note) }) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun HistoryEmptyCard() {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                Icons.Default.History,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.history_empty_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.history_empty_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StatusBadge(status: String, pauseReason: String?) {
    val color: Color = when (status) {
        "RUNNING" -> MaterialTheme.colorScheme.tertiary
        "PAUSED", "STARTING" -> MaterialTheme.colorScheme.secondary
        "INTERRUPTED" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text = sessionStatusText(status, pauseReason),
        style = MaterialTheme.typography.labelLarge,
        color = color,
    )
}

@Composable
private fun SessionMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun SessionMetricGroup(values: List<Pair<String, String>>) {
    if (LocalDensity.current.fontScale >= LARGE_FONT_SCALE) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            values.forEach { (label, value) -> SessionMetric(label, value) }
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            values.forEach { (label, value) ->
                SessionMetric(label, value, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ValueRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun sessionStatusText(status: String, pauseReason: String?): String = stringResource(
    when (status) {
        "STARTING" -> R.string.monitor_status_starting
        "RUNNING" -> R.string.monitor_status_running
        "PAUSED" -> when (pauseReason) {
            "SHIZUKU" -> R.string.monitor_status_paused_shizuku
            "STORAGE" -> R.string.monitor_status_paused_storage
            else -> R.string.monitor_status_paused_user
        }
        "COMPLETED" -> R.string.monitor_status_completed
        "INTERRUPTED" -> R.string.monitor_status_interrupted
        else -> R.string.value_unavailable
    },
)

@Composable
private fun chargingStateText(state: String): String = stringResource(
    when (state) {
        "DISCHARGING" -> R.string.charging_discharging
        "CHARGING" -> R.string.charging_charging
        "FULL" -> R.string.charging_full
        else -> R.string.charging_unknown
    },
)

@Composable
private fun thermalStatusText(status: Int?): String = stringResource(
    when (status) {
        0 -> R.string.thermal_none
        1 -> R.string.thermal_light
        2 -> R.string.thermal_moderate
        3 -> R.string.thermal_severe
        4 -> R.string.thermal_critical
        5 -> R.string.thermal_emergency
        6 -> R.string.thermal_shutdown
        else -> R.string.value_unavailable
    },
)

@Composable
private fun eventLabel(type: String): String = stringResource(
    when (type) {
        "SESSION_STARTED" -> R.string.event_session_started
        "FIRST_FRAME" -> R.string.event_first_frame
        "USER_PAUSED" -> R.string.event_user_paused
        "USER_RESUMED" -> R.string.event_user_resumed
        "DATA_GAP_START" -> R.string.event_gap_start
        "DATA_GAP_END" -> R.string.event_gap_end
        "STORAGE_PAUSED" -> R.string.event_storage_paused
        "STORAGE_RESUMED" -> R.string.event_storage_resumed
        "SCREEN_CHANGED" -> R.string.event_screen_changed
        "BATTERY_CHANGED" -> R.string.event_battery_changed
        "THERMAL_CHANGED" -> R.string.event_thermal_changed
        "INTERVAL_CHANGED" -> R.string.event_interval_changed
        "DATA_SOURCE_CHANGED" -> R.string.event_data_source_changed
        "SESSION_COMPLETED" -> R.string.event_session_completed
        "SESSION_INTERRUPTED" -> R.string.event_session_interrupted
        "USER_NOTE" -> R.string.event_user_note
        else -> R.string.event_unknown
    },
)

@Composable
private fun retainedReasonText(reason: Int): String {
    val topCpu = stringResource(R.string.retained_top_cpu)
    val topRss = stringResource(R.string.retained_top_rss)
    val pinned = stringResource(R.string.retained_pinned)
    val detail = stringResource(R.string.retained_detail)
    val values = mutableListOf<String>()
    if (reason and RetentionReason.TOP_CPU != 0) values += topCpu
    if (reason and RetentionReason.TOP_RSS != 0) values += topRss
    if (reason and RetentionReason.PINNED != 0) values += pinned
    if (reason and RetentionReason.DETAIL != 0) values += detail
    return stringResource(R.string.history_retained_because, values.joinToString())
}

private fun isGapActive(
    events: List<SessionEventEntity>,
    samples: List<SystemSampleEntity>,
    offsetMs: Long,
): Boolean {
    var awaitingFirstFrame = false
    var backendGap = false
    var userPause = false
    var storagePause = false
    events.asSequence().filter { it.elapsedOffsetMs <= offsetMs }
        .sortedBy(SessionEventEntity::elapsedOffsetMs)
        .forEach { event ->
            when (event.type) {
                "SESSION_STARTED" -> awaitingFirstFrame = true
                "FIRST_FRAME" -> awaitingFirstFrame = false
                "DATA_GAP_START" -> backendGap = true
                "DATA_GAP_END" -> backendGap = false
                "USER_PAUSED" -> userPause = true
                "USER_RESUMED" -> userPause = false
                "STORAGE_PAUSED" -> storagePause = true
                "STORAGE_RESUMED" -> storagePause = false
                "SESSION_COMPLETED", "SESSION_INTERRUPTED" -> {
                    awaitingFirstFrame = false
                    backendGap = false
                    userPause = false
                    storagePause = false
                }
            }
        }
    if (awaitingFirstFrame && samples.firstOrNull()?.elapsedOffsetMs?.let { it <= offsetMs } == true) {
        awaitingFirstFrame = false
    }
    return awaitingFirstFrame || backendGap || userPause || storagePause
}

private fun isSamplingGap(
    samples: List<SystemSampleEntity>,
    offsetMs: Long,
    timelineEndOffsetMs: Long,
): Boolean {
    val (previous, next) = sampleBounds(samples, offsetMs)
    val previousSample = previous ?: return false
    if (next == null) {
        val expected = previousSample.samplingIntervalMs.coerceAtLeast(1L)
        val tailDuration = timelineEndOffsetMs - previousSample.elapsedOffsetMs
        return tailDuration > expected * 2L &&
            offsetMs > previousSample.elapsedOffsetMs + expected
    }
    if (previousSample.sequence == next.sequence) return false
    val expected = maxOf(previousSample.samplingIntervalMs, next.samplingIntervalMs)
        .coerceAtLeast(1L)
    val actual = next.elapsedOffsetMs - previousSample.elapsedOffsetMs
    if (actual <= expected * 2L) return false
    return offsetMs > previousSample.elapsedOffsetMs + expected &&
        offsetMs < next.elapsedOffsetMs - expected
}

private data class SampleBounds(
    val previous: SystemSampleEntity?,
    val next: SystemSampleEntity?,
)

private fun sampleBounds(samples: List<SystemSampleEntity>, offsetMs: Long): SampleBounds {
    if (samples.isEmpty()) return SampleBounds(null, null)
    val result = samples.binarySearchBy(offsetMs, selector = SystemSampleEntity::elapsedOffsetMs)
    if (result >= 0) {
        val exact = samples[result]
        return SampleBounds(exact, exact)
    }
    val insertionPoint = -result - 1
    return SampleBounds(
        previous = samples.getOrNull(insertionPoint - 1),
        next = samples.getOrNull(insertionPoint),
    )
}

private fun nearestSample(
    samples: List<SystemSampleEntity>,
    offsetMs: Long,
): SystemSampleEntity? {
    val (previous, next) = sampleBounds(samples, offsetMs)
    return when {
        previous == null -> next
        next == null -> previous
        offsetMs - previous.elapsedOffsetMs <= next.elapsedOffsetMs - offsetMs -> previous
        else -> next
    }
}

private fun memoryUsedPercent(sample: SystemSampleEntity?): Float? {
    val total = sample?.memoryTotalKb
    val available = sample?.memoryAvailableKb
    return if (total != null && total > 0L && available != null) {
        ((total - available).coerceAtLeast(0L).toFloat() / total.toFloat()) * 100f
    } else {
        null
    }
}

private fun timelineChartSamples(
    samples: List<SystemSampleEntity>,
    maximum: Int,
    timelineMaximumOffsetMs: Long,
): List<SystemSampleEntity?> {
    if (samples.isEmpty()) return emptyList()
    val maximumOffset = maxOf(
        timelineMaximumOffsetMs,
        samples.last().elapsedOffsetMs,
        1L,
    )
    val minimumInterval = samples.minOf { it.samplingIntervalMs.coerceAtLeast(1L) }
    val desiredPoints = (maximumOffset / minimumInterval + 1L)
        .coerceIn(2L, maximum.toLong())
        .toInt()
    var candidateIndex = 0
    return List(desiredPoints) { index ->
        val target = (maximumOffset * index.toLong()) / (desiredPoints - 1).toLong()
        while (
            candidateIndex < samples.lastIndex &&
            abs(samples[candidateIndex + 1].elapsedOffsetMs - target) <=
            abs(samples[candidateIndex].elapsedOffsetMs - target)
        ) {
            candidateIndex++
        }
        val candidate = samples[candidateIndex]
        val tolerance = (candidate.samplingIntervalMs * 3L) / 2L
        candidate.takeIf { abs(it.elapsedOffsetMs - target) <= tolerance }
    }
}

private fun formatDateTime(wallTimeMs: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(wallTimeMs))

private fun formatDurationMillis(value: Long): String {
    val seconds = value.coerceAtLeast(0L) / 1_000L
    val hours = seconds / 3_600L
    val minutes = (seconds % 3_600L) / 60L
    val remainder = seconds % 60L
    return String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, remainder)
}

private fun formatPercent(value: Int?): String = value?.let {
    String.format(Locale.getDefault(), "%.1f%%", it / 100.0)
} ?: "—"

private fun formatPercentValue(value: Float): String =
    String.format(Locale.getDefault(), "%.1f%%", value)

private fun formatKilobytes(value: Long?): String = value?.let {
    when {
        it >= 1_048_576L -> String.format(Locale.getDefault(), "%.1f GB", it / 1_048_576.0)
        it >= 1_024L -> String.format(Locale.getDefault(), "%.1f MB", it / 1_024.0)
        else -> "$it KB"
    }
} ?: "—"

private fun formatBytes(value: Long): String = when {
    value >= 1_073_741_824L -> String.format(Locale.getDefault(), "%.1f GB", value / 1_073_741_824.0)
    value >= 1_048_576L -> String.format(Locale.getDefault(), "%.1f MB", value / 1_048_576.0)
    value >= 1_024L -> String.format(Locale.getDefault(), "%.1f KB", value / 1_024.0)
    else -> "$value B"
}

private fun formatTemperature(value: Int): String =
    String.format(Locale.getDefault(), "%.1f °C", value / 10.0)

private val NullableExportOptionsSaver = Saver<ExportOptions?, Int>(
    save = { options ->
        if (options == null) {
            NO_EXPORT_OPTIONS
        } else {
            (if (options.anonymous) 1 shl 0 else 0) or
                (if (options.includeSessionName) 1 shl 1 else 0) or
                (if (options.includeNote) 1 shl 2 else 0) or
                (if (options.includeDeviceDetails) 1 shl 3 else 0) or
                (if (options.includeAbsoluteTime) 1 shl 4 else 0) or
                (if (options.includeCommandLine) 1 shl 5 else 0)
        }
    },
    restore = { mask ->
        if (mask == NO_EXPORT_OPTIONS) {
            null
        } else {
            ExportOptions(
                anonymous = mask and (1 shl 0) != 0,
                includeSessionName = mask and (1 shl 1) != 0,
                includeNote = mask and (1 shl 2) != 0,
                includeDeviceDetails = mask and (1 shl 3) != 0,
                includeAbsoluteTime = mask and (1 shl 4) != 0,
                includeCommandLine = mask and (1 shl 5) != 0,
            )
        }
    },
)

private const val MAX_CHART_POINTS = 320
private const val MAX_OVERLAY_SERIES = 3
private const val SIZE_REFRESH_FRAME_BUCKET = 30L
private const val LARGE_FONT_SCALE = 1.5f
private const val NO_EXPORT_OPTIONS = -1
