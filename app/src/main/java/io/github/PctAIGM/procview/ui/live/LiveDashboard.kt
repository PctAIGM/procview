package io.github.PctAIGM.procview.ui.live

import android.content.pm.PackageManager
import android.os.PowerManager
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import io.github.PctAIGM.procview.R
import io.github.PctAIGM.procview.model.MetricDataSource
import io.github.PctAIGM.procview.model.PinnedTarget
import io.github.PctAIGM.procview.model.ProcessCatalogEntry
import io.github.PctAIGM.procview.model.ProcessKey
import io.github.PctAIGM.procview.model.ProcessMetric
import io.github.PctAIGM.procview.monitor.LiveTimelineFrame
import io.github.PctAIGM.procview.monitor.MetricPeaks
import io.github.PctAIGM.procview.monitor.ChargingState
import io.github.PctAIGM.procview.monitor.MonitorEnvironment
import io.github.PctAIGM.procview.monitor.MonitorRuntimeSnapshot
import io.github.PctAIGM.procview.sampler.ApplicationAggregate
import io.github.PctAIGM.procview.sampler.PinnedTargetMatcher
import io.github.PctAIGM.procview.sampler.ProcessPackageResolution
import io.github.PctAIGM.procview.ui.theme.CpuMetricColor
import io.github.PctAIGM.procview.ui.theme.MemoryMetricColor
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class LiveFilter(@StringRes val label: Int) {
    ALL(R.string.live_filter_all),
    USER(R.string.live_filter_user),
    SYSTEM(R.string.live_filter_system),
    PINNED(R.string.live_filter_pinned),
}

private enum class LiveSort(@StringRes val label: Int) {
    CPU(R.string.live_sort_cpu),
    RSS(R.string.live_sort_rss),
    PSS(R.string.live_sort_pss),
    NAME(R.string.live_sort_name),
}

private data class DisplayApplication(
    val application: ApplicationAggregate,
    val frame: LiveTimelineFrame?,
    val previousFrame: LiveTimelineFrame?,
    val exited: Boolean,
)

@Composable
internal fun LiveDashboard(
    contentPadding: PaddingValues,
    snapshot: MonitorRuntimeSnapshot,
    pinnedTargets: Set<PinnedTarget>,
    onTogglePin: (PinnedTarget) -> Unit,
    onDetailProcessKeysChanged: (Set<ProcessKey>) -> Unit,
    headerContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var selectedFilterName by rememberSaveable { mutableStateOf(LiveFilter.ALL.name) }
    var selectedSortName by rememberSaveable { mutableStateOf(LiveSort.CPU.name) }
    var selectedApplicationId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedPid by rememberSaveable { mutableStateOf<Int?>(null) }
    var selectedStartTime by rememberSaveable { mutableStateOf<Long?>(null) }
    val selectedProcessKey = if (selectedPid != null && selectedStartTime != null) {
        runCatching { ProcessKey(selectedPid!!, selectedStartTime!!) }.getOrNull()
    } else {
        null
    }
    val selectedFilter = LiveFilter.entries.firstOrNull { it.name == selectedFilterName }
        ?: LiveFilter.ALL
    val selectedSort = LiveSort.entries.firstOrNull { it.name == selectedSortName }
        ?: LiveSort.CPU
    val displayApplications = remember(
        snapshot.applications,
        snapshot.catalog,
        snapshot.packageResolutions,
        snapshot.recentFrames,
        pinnedTargets,
        query,
        selectedFilter,
        selectedSort,
    ) {
        buildDisplayApplications(snapshot, pinnedTargets, query, selectedFilter, selectedSort)
    }
    val clearSelection = {
        selectedApplicationId = null
        selectedPid = null
        selectedStartTime = null
    }

    LaunchedEffect(
        selectedApplicationId,
        selectedProcessKey,
        snapshot.applications,
    ) {
        val selectedApplication = snapshot.applications.firstOrNull {
            it.stableId == selectedApplicationId
        }
        val keys = when {
            selectedApplication == null -> emptySet()
            selectedProcessKey != null -> setOf(selectedProcessKey)
            else -> selectedApplication.processes.map(ProcessMetric::key).toSet()
        }
        onDetailProcessKeysChanged(keys)
    }
    DisposableEffect(Unit) {
        onDispose { onDetailProcessKeysChanged(emptySet()) }
    }
    BackHandler(enabled = selectedApplicationId != null, onBack = clearSelection)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .padding(contentPadding),
    ) {
        val showWideDetail = maxWidth >= 900.dp && selectedApplicationId != null
        Row(modifier = Modifier.fillMaxSize()) {
            LiveList(
                snapshot = snapshot,
                pinnedTargets = pinnedTargets,
                displayApplications = displayApplications,
                query = query,
                selectedFilter = selectedFilter,
                selectedSort = selectedSort,
                onQueryChange = { query = it },
                onFilterChange = { selectedFilterName = it.name },
                onSortChange = { selectedSortName = it.name },
                onTogglePin = onTogglePin,
                onOpenApplication = { applicationId ->
                    selectedApplicationId = applicationId
                    selectedPid = null
                    selectedStartTime = null
                },
                headerContent = headerContent,
                modifier = if (showWideDetail) Modifier.weight(0.56f) else Modifier.fillMaxWidth(),
            )
            if (showWideDetail) {
                VerticalDivider(modifier = Modifier.fillMaxHeight())
                ApplicationDetailPane(
                    snapshot = snapshot,
                    applicationId = selectedApplicationId!!,
                    selectedProcessKey = selectedProcessKey,
                    pinnedTargets = pinnedTargets,
                    onTogglePin = onTogglePin,
                    onSelectProcess = { key ->
                        selectedPid = key?.pid
                        selectedStartTime = key?.startTimeTicks
                    },
                    onClose = clearSelection,
                    modifier = Modifier.weight(0.44f),
                )
            }
        }
        if (selectedApplicationId != null && !showWideDetail) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
            ) {
                ApplicationDetailPane(
                    snapshot = snapshot,
                    applicationId = selectedApplicationId!!,
                    selectedProcessKey = selectedProcessKey,
                    pinnedTargets = pinnedTargets,
                    onTogglePin = onTogglePin,
                    onSelectProcess = { key ->
                        selectedPid = key?.pid
                        selectedStartTime = key?.startTimeTicks
                    },
                    onClose = clearSelection,
                )
            }
        }
    }
}

@Composable
private fun LiveList(
    snapshot: MonitorRuntimeSnapshot,
    pinnedTargets: Set<PinnedTarget>,
    displayApplications: List<DisplayApplication>,
    query: String,
    selectedFilter: LiveFilter,
    selectedSort: LiveSort,
    onQueryChange: (String) -> Unit,
    onFilterChange: (LiveFilter) -> Unit,
    onSortChange: (LiveSort) -> Unit,
    onTogglePin: (PinnedTarget) -> Unit,
    onOpenApplication: (String) -> Unit,
    headerContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxHeight(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "header") {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                headerContent()
            }
        }
        item(key = "system-trends") {
            SystemTrendSection(snapshot)
        }
        item(key = "process-toolbar") {
            ProcessToolbar(
                query = query,
                selectedFilter = selectedFilter,
                selectedSort = selectedSort,
                processCount = snapshot.lastFrame?.metrics?.size ?: 0,
                applicationCount = snapshot.applications.size,
                onQueryChange = onQueryChange,
                onFilterChange = onFilterChange,
                onSortChange = onSortChange,
            )
        }
        if (displayApplications.isEmpty()) {
            item(key = "empty-processes") {
                EmptyProcessCard(hasFrame = snapshot.lastFrame != null)
            }
        } else {
            items(
                items = displayApplications,
                key = { item -> "application:${item.application.stableId}" },
            ) { item ->
                ApplicationCard(
                    item = item,
                    pinnedTargets = pinnedTargets,
                    pssStaleAfterNanos = snapshot.preset.pssIntervalMs * 2L * 1_000_000L,
                    onTogglePin = onTogglePin,
                    onOpenApplication = { onOpenApplication(item.application.stableId) },
                )
            }
        }
        item(key = "list-footer") { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun SystemTrendSection(snapshot: MonitorRuntimeSnapshot) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val frames = snapshot.recentFrames.map(LiveTimelineFrame::frame)
    val cpuPoints = frames.map { it.systemCpuPercentBasisPoints?.div(100f) }
    val memoryPoints = frames.map { frame ->
        val total = frame.memoryTotalKb
        val available = frame.memoryAvailableKb
        if (total != null && total > 0 && available != null) {
            ((total - available).coerceAtLeast(0L).toFloat() / total.toFloat()) * 100f
        } else {
            null
        }
    }
    val currentCpu = formatPercent(snapshot.lastFrame?.systemCpuPercentBasisPoints)
    val currentMemory = memoryPoints.lastOrNull()?.let(::formatPercentValue) ?: "—"
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.live_system_overview),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                TextButton(onClick = { expanded = !expanded }) {
                    Text(
                        stringResource(
                            if (expanded) R.string.action_hide_trends else R.string.action_show_trends,
                        ),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OverviewMetric(
                    stringResource(R.string.metric_system_cpu),
                    currentCpu,
                    Modifier.weight(1f),
                )
                OverviewMetric(
                    stringResource(R.string.metric_memory_used),
                    currentMemory,
                    Modifier.weight(1f),
                )
                OverviewMetric(
                    stringResource(R.string.live_battery_level),
                    snapshot.environment.batteryLevelPercent?.let { "$it%" } ?: "—",
                    Modifier.weight(1f),
                )
                OverviewMetric(
                    stringResource(R.string.live_battery_temperature),
                    snapshot.environment.batteryTemperatureDeciC?.let {
                        String.format(Locale.getDefault(), "%.1f°", it / 10.0)
                    } ?: "—",
                    Modifier.weight(1f),
                )
            }
            if (expanded) {
                Text(
                    text = stringResource(
                        R.string.detail_data_source,
                        when (snapshot.lastFrame?.source) {
                            MetricDataSource.PROCFS -> stringResource(R.string.data_source_procfs)
                            MetricDataSource.PS_FALLBACK -> stringResource(R.string.data_source_ps_fallback)
                            null -> stringResource(R.string.value_unavailable)
                        },
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                BoxWithConstraints {
                    if (maxWidth >= 600.dp) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            TrendCard(
                                titleRes = R.string.metric_system_cpu,
                                currentValue = currentCpu,
                                points = cpuPoints,
                                color = CpuMetricColor,
                                fixedMaximum = 100f,
                                modifier = Modifier.weight(1f),
                            )
                            TrendCard(
                                titleRes = R.string.metric_memory_used,
                                currentValue = currentMemory,
                                points = memoryPoints,
                                color = MemoryMetricColor,
                                fixedMaximum = 100f,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            TrendCard(
                                titleRes = R.string.metric_system_cpu,
                                currentValue = currentCpu,
                                points = cpuPoints,
                                color = CpuMetricColor,
                                fixedMaximum = 100f,
                            )
                            TrendCard(
                                titleRes = R.string.metric_memory_used,
                                currentValue = currentMemory,
                                points = memoryPoints,
                                color = MemoryMetricColor,
                                fixedMaximum = 100f,
                            )
                        }
                    }
                }
                EnvironmentSummaryCard(snapshot.environment)
            }
        }
    }
}

@Composable
private fun OverviewMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
private fun EnvironmentSummaryCard(environment: MonitorEnvironment) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Text(
                text = stringResource(R.string.live_device_context),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            DetailValueRow(
                stringResource(R.string.live_battery_level),
                environment.batteryLevelPercent?.let { "$it%" } ?: "—",
            )
            DetailValueRow(
                stringResource(R.string.live_battery_temperature),
                environment.batteryTemperatureDeciC?.let {
                    String.format(Locale.getDefault(), "%.1f °C", it / 10.0)
                } ?: "—",
            )
            DetailValueRow(
                stringResource(R.string.live_charging_state),
                stringResource(
                    when (environment.chargingState) {
                        ChargingState.UNKNOWN -> R.string.charging_unknown
                        ChargingState.DISCHARGING -> R.string.charging_discharging
                        ChargingState.CHARGING -> R.string.charging_charging
                        ChargingState.FULL -> R.string.charging_full
                    },
                ),
            )
            DetailValueRow(
                stringResource(R.string.live_thermal_status),
                thermalStatusText(environment.thermalStatus),
            )
            DetailValueRow(
                stringResource(R.string.live_screen_state),
                stringResource(
                    if (environment.screenInteractive) R.string.screen_on else R.string.screen_off,
                ),
            )
        }
    }
}

@Composable
private fun thermalStatusText(status: Int?): String = stringResource(
    when (status) {
        PowerManager.THERMAL_STATUS_NONE -> R.string.thermal_none
        PowerManager.THERMAL_STATUS_LIGHT -> R.string.thermal_light
        PowerManager.THERMAL_STATUS_MODERATE -> R.string.thermal_moderate
        PowerManager.THERMAL_STATUS_SEVERE -> R.string.thermal_severe
        PowerManager.THERMAL_STATUS_CRITICAL -> R.string.thermal_critical
        PowerManager.THERMAL_STATUS_EMERGENCY -> R.string.thermal_emergency
        PowerManager.THERMAL_STATUS_SHUTDOWN -> R.string.thermal_shutdown
        else -> R.string.value_unavailable
    },
)

@Composable
private fun ProcessToolbar(
    query: String,
    selectedFilter: LiveFilter,
    selectedSort: LiveSort,
    processCount: Int,
    applicationCount: Int,
    onQueryChange: (String) -> Unit,
    onFilterChange: (LiveFilter) -> Unit,
    onSortChange: (LiveSort) -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(
            modifier = Modifier.padding(vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.live_processes_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(
                        R.string.live_process_count,
                        applicationCount,
                        processCount,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                singleLine = true,
                label = { Text(stringResource(R.string.live_search_label)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = if (query.isNotEmpty()) {
                    {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(
                                Icons.Default.Clear,
                                contentDescription = stringResource(R.string.action_clear_search),
                            )
                        }
                    }
                } else {
                    null
                },
            )
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(LiveFilter.entries, key = LiveFilter::name) { filter ->
                    FilterChip(
                        selected = selectedFilter == filter,
                        onClick = { onFilterChange(filter) },
                        label = { Text(stringResource(filter.label)) },
                    )
                }
            }
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(LiveSort.entries, key = LiveSort::name) { sort ->
                    FilterChip(
                        selected = selectedSort == sort,
                        onClick = { onSortChange(sort) },
                        label = {
                            Text(
                                text = stringResource(sort.label),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ApplicationCard(
    item: DisplayApplication,
    pinnedTargets: Set<PinnedTarget>,
    pssStaleAfterNanos: Long,
    onTogglePin: (PinnedTarget) -> Unit,
    onOpenApplication: () -> Unit,
) {
    val application = item.application
    val applicationTitle = if (application.isSharedUid && application.primaryPackage == null) {
        stringResource(R.string.live_shared_uid)
    } else {
        application.displayName
    }
    val catalog = item.frame?.catalog ?: item.previousFrame?.catalog.orEmpty()
    val resolutions = item.frame?.packageResolutions
        ?: item.previousFrame?.packageResolutions.orEmpty()
    val appTarget = remember(application.stableId, catalog, resolutions) {
        PinnedTargetMatcher.targetForApplication(application, catalog, resolutions)
    }
    val catalogByKey = remember(catalog) { catalog.associateBy(ProcessCatalogEntry::key) }
    val resolutionByKey = remember(resolutions) {
        resolutions.associateBy(ProcessPackageResolution::key)
    }
    val hasPinnedProcess = remember(
        pinnedTargets,
        application.processes,
        catalogByKey,
        resolutionByKey,
    ) {
        application.processes.any { metric ->
            val entry = catalogByKey[metric.key] ?: return@any false
            pinnedTargets.any { target ->
                with(PinnedTargetMatcher) {
                    target.matches(entry, resolutionByKey[metric.key])
                }
            }
        }
    }
    val appTargetPinned = appTarget != null && appTarget in pinnedTargets
    val context = LocalContext.current
    val collector = application.primaryPackage == context.packageName
    val pssSampledAt = application.processes
        .mapNotNull(ProcessMetric::pssSampleElapsedRealtimeNanos)
        .minOrNull()
    val pssStale = pssSampledAt != null && item.frame?.frame?.elapsedRealtimeNanos?.let {
        (it - pssSampledAt).coerceAtLeast(0L) > pssStaleAfterNanos
    } == true
    val pssAgeSeconds = pssSampledAt?.let { sampledAt ->
        item.frame?.frame?.elapsedRealtimeNanos?.minus(sampledAt)?.coerceAtLeast(0L)
            ?.div(1_000_000_000L)
    }

    Card(
        onClick = onOpenApplication,
        enabled = !item.exited,
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (item.exited) 0.48f else 1f),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PackageIcon(
                    packageName = application.primaryPackage,
                    fallbackText = applicationTitle,
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.padding(end = 38.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = applicationTitle,
                            modifier = Modifier.weight(1f, fill = false),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (collector) {
                            Text(
                                text = stringResource(R.string.live_collector_badge),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        if (item.exited) {
                            Text(
                                text = stringResource(R.string.live_exited_badge),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    val identityLabel = application.primaryPackage
                        ?: application.packageCandidates.joinToString().ifBlank {
                            stringResource(R.string.live_native_process)
                        }
                    Text(
                        text = stringResource(
                            R.string.live_application_secondary,
                            identityLabel,
                            application.processes.size,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(7.dp))
                    MetricStrip(application, pssStale, pssAgeSeconds)
                }
            }
            IconButton(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(40.dp),
                onClick = { appTarget?.let(onTogglePin) },
                enabled = appTarget != null && !item.exited,
            ) {
                Icon(
                    modifier = Modifier.size(21.dp),
                    imageVector = if (appTargetPinned || hasPinnedProcess) {
                        Icons.Filled.Star
                    } else {
                        Icons.Outlined.StarBorder
                    },
                    contentDescription = stringResource(
                        if (appTargetPinned) R.string.action_unpin else R.string.action_pin,
                    ),
                    tint = if (appTargetPinned || hasPinnedProcess) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

@Composable
private fun MetricStrip(
    application: ApplicationAggregate,
    pssStale: Boolean,
    pssAgeSeconds: Long?,
) {
    val metrics = listOf(
        CompactMetricValue(
            label = stringResource(R.string.live_sort_cpu),
            value = formatPercent(application.cpuPercentBasisPoints),
            supplemental = if (application.cpuPercentBasisPoints != null && !application.cpuComplete) {
                stringResource(R.string.metric_partial_short)
            } else {
                null
            },
        ),
        CompactMetricValue(
            label = stringResource(R.string.live_sort_rss),
            value = formatKilobytes(application.rssKb),
            supplemental = if (application.rssKb != null && !application.rssComplete) {
                stringResource(R.string.metric_partial_short)
            } else {
                null
            },
        ),
        CompactMetricValue(
            label = stringResource(R.string.live_sort_pss),
            value = formatKilobytes(application.pssKb),
            supplemental = listOfNotNull(
                pssAgeSeconds?.let { stringResource(R.string.live_pss_age_value, it) },
                if (application.pssKb != null && !application.pssComplete) {
                    stringResource(R.string.metric_partial_short)
                } else {
                    null
                },
            ).joinToString(" · ").ifBlank { null },
            dimmed = pssStale,
        ),
    )
    CompactMetricLayout(
        metrics = metrics,
        stacked = LocalDensity.current.fontScale >= LARGE_FONT_SCALE,
    )
}

@Composable
internal fun CompactMetricLayout(
    metrics: List<CompactMetricValue>,
    stacked: Boolean,
) {
    require(metrics.size == 3) { "CPU, RSS, and PSS metrics are required" }
    if (stacked) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            metrics.forEach { metric ->
                CompactMetric(
                    label = metric.label,
                    value = metric.value,
                    supplemental = metric.supplemental,
                    dimmed = metric.dimmed,
                )
            }
        }
    } else {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                metrics.take(2).forEach { metric ->
                    CompactMetric(
                        label = metric.label,
                        value = metric.value,
                        supplemental = metric.supplemental,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            metrics.last().let { metric ->
                CompactMetric(
                    label = metric.label,
                    value = metric.value,
                    supplemental = metric.supplemental,
                    dimmed = metric.dimmed,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

internal data class CompactMetricValue(
    val label: String,
    val value: String,
    val supplemental: String? = null,
    val dimmed: Boolean = false,
)

@Composable
private fun CompactMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    supplemental: String? = null,
    dimmed: Boolean = false,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = if (dimmed) {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        supplemental?.let {
            Text(
                text = "· $it",
                modifier = Modifier.weight(1f, fill = false),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ProcessChildRow(
    metric: ProcessMetric,
    entry: ProcessCatalogEntry?,
    resolution: ProcessPackageResolution?,
    exited: Boolean,
    pinnedTargets: Set<PinnedTarget>,
    onTogglePin: (PinnedTarget) -> Unit,
    onOpen: () -> Unit,
) {
    val target = remember(entry, resolution) {
        entry?.let { PinnedTargetMatcher.targetForProcess(it, resolution) }
    }
    val pinned = target != null && target in pinnedTargets
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (exited) 0.48f else 1f)
            .clickable(enabled = !exited, onClick = onOpen)
            .padding(start = 66.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry?.processName ?: stringResource(R.string.live_pid_value, metric.key.pid),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(
                    R.string.live_process_secondary,
                    metric.key.pid,
                    metric.state.toString(),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(formatPercent(metric.cpuPercentBasisPoints), style = MaterialTheme.typography.bodySmall)
            Text(
                formatKilobytes(metric.rssKb),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(
            onClick = { target?.let(onTogglePin) },
            enabled = target != null && !exited,
        ) {
            Icon(
                imageVector = if (pinned) Icons.Filled.Star else Icons.Outlined.StarBorder,
                contentDescription = stringResource(
                    if (pinned) R.string.action_unpin else R.string.action_pin,
                ),
                tint = if (pinned) {
                    MaterialTheme.colorScheme.tertiary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

@Composable
private fun ApplicationDetailPane(
    snapshot: MonitorRuntimeSnapshot,
    applicationId: String,
    selectedProcessKey: ProcessKey?,
    pinnedTargets: Set<PinnedTarget>,
    onTogglePin: (PinnedTarget) -> Unit,
    onSelectProcess: (ProcessKey?) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val latestLiveFrame = snapshot.recentFrames.asReversed().firstOrNull { frame ->
        frame.applications.any { it.stableId == applicationId }
    }
    val applicationIsLive = snapshot.applications.any { it.stableId == applicationId }
    val application = snapshot.applications.firstOrNull { it.stableId == applicationId }
        ?: latestLiveFrame?.applications?.firstOrNull { it.stableId == applicationId }
    if (application == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.detail_no_longer_available))
                TextButton(onClick = onClose) { Text(stringResource(R.string.action_back)) }
            }
        }
        return
    }
    val applicationTitle = if (application.isSharedUid && application.primaryPackage == null) {
        stringResource(R.string.live_shared_uid)
    } else {
        application.displayName
    }
    val catalog = if (applicationIsLive) {
        snapshot.catalog
    } else {
        latestLiveFrame?.catalog.orEmpty()
    }
    val resolutions = if (applicationIsLive) {
        snapshot.packageResolutions
    } else {
        latestLiveFrame?.packageResolutions.orEmpty()
    }
    val catalogByKey = catalog.associateBy(ProcessCatalogEntry::key)
    val resolutionByKey = resolutions.associateBy(ProcessPackageResolution::key)
    val selectedMetric = selectedProcessKey?.let { key ->
        application.processes.firstOrNull { it.key == key }
            ?: snapshot.recentFrames.asReversed().asSequence()
                .mapNotNull { frame ->
                    frame.applications.firstOrNull { it.stableId == applicationId }
                        ?.processes?.firstOrNull { it.key == key }
                }
                .firstOrNull()
    }
    val selectedEntry = selectedProcessKey?.let { catalogByKey[it] }
        ?: selectedProcessKey?.let { key ->
            snapshot.recentFrames.asReversed().asSequence()
                .mapNotNull { frame -> frame.catalog.firstOrNull { it.key == key } }
                .firstOrNull()
        }
    val selectedResolution = selectedProcessKey?.let { resolutionByKey[it] }
        ?: selectedProcessKey?.let { key ->
            snapshot.recentFrames.asReversed().asSequence()
                .mapNotNull { frame ->
                    frame.packageResolutions.firstOrNull { it.key == key }
                }
                .firstOrNull()
        }
    val selectedProcessIsLive = selectedProcessKey == null ||
        (applicationIsLive && application.processes.any { it.key == selectedProcessKey })
    val detailSubtitle = when {
        selectedProcessKey == null && applicationIsLive ->
            stringResource(R.string.detail_application_summary)
        selectedProcessKey == null -> listOf(
            stringResource(R.string.detail_application_summary),
            stringResource(R.string.live_exited_badge),
        ).joinToString(" · ")
        selectedEntry == null -> stringResource(R.string.detail_no_longer_available)
        selectedProcessIsLive -> selectedEntry.processName
        else -> listOf(
            selectedEntry.processName,
            stringResource(R.string.live_exited_badge),
        ).joinToString(" · ")
    }
    val target = if (selectedProcessKey != null) {
        selectedEntry?.let { entry ->
            PinnedTargetMatcher.targetForProcess(entry, selectedResolution)
        }
    } else {
        PinnedTargetMatcher.targetForApplication(application, catalog, resolutions)
    }
    val targetPinned = target != null && target in pinnedTargets
    val timeline = snapshot.recentFrames
    val cpuPoints = timeline.map { frame ->
        metricFor(frame, applicationId, selectedProcessKey)?.cpuPercentBasisPoints?.div(100f)
    }
    val rssPoints = timeline.map { frame ->
        metricFor(frame, applicationId, selectedProcessKey)?.rssKb?.div(1024f)
    }
    val pssPoints = timeline.map { frame ->
        metricFor(frame, applicationId, selectedProcessKey)?.pssKb?.div(1024f)
    }
    val currentCpu = if (selectedProcessKey == null) {
        application.cpuPercentBasisPoints
    } else {
        selectedMetric?.cpuPercentBasisPoints
    }
    val currentRss = if (selectedProcessKey == null) application.rssKb else selectedMetric?.rssKb
    val pssSampledAt = if (selectedProcessKey != null) {
        selectedMetric?.pssSampleElapsedRealtimeNanos
    } else {
        application.processes.mapNotNull(ProcessMetric::pssSampleElapsedRealtimeNanos).minOrNull()
    }
    val pssStale = pssSampledAt != null && snapshot.lastFrame?.elapsedRealtimeNanos?.let {
        (it - pssSampledAt).coerceAtLeast(0L) >
            snapshot.preset.pssIntervalMs * 2L * 1_000_000L
    } == true

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(key = "detail-title") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) {
                    Icon(
                        Icons.Default.ArrowBack,
                        contentDescription = stringResource(R.string.action_back),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = applicationTitle,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = detailSubtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(
                    onClick = { target?.let(onTogglePin) },
                    enabled = target != null,
                ) {
                    Icon(
                        imageVector = if (targetPinned) {
                            Icons.Filled.Star
                        } else {
                            Icons.Outlined.StarBorder
                        },
                        contentDescription = stringResource(
                            if (targetPinned) R.string.action_unpin else R.string.action_pin,
                        ),
                        tint = if (targetPinned) {
                            MaterialTheme.colorScheme.tertiary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
        if (selectedProcessKey != null) {
            item(key = "detail-app-summary-action") {
                TextButton(onClick = { onSelectProcess(null) }) {
                    Text(stringResource(R.string.detail_show_application_summary))
                }
            }
        }
        item(key = "detail-cpu-chart") {
            TrendCard(
                titleRes = R.string.live_sort_cpu,
                currentValue = formatAggregateValue(
                    formatted = formatPercent(currentCpu),
                    hasValue = currentCpu != null,
                    complete = selectedProcessKey != null || application.cpuComplete,
                ),
                points = cpuPoints,
                color = CpuMetricColor,
                fixedMaximum = 100f,
            )
        }
        item(key = "detail-rss-chart") {
            TrendCard(
                titleRes = R.string.detail_rss_megabytes,
                currentValue = formatAggregateValue(
                    formatted = formatKilobytes(currentRss),
                    hasValue = currentRss != null,
                    complete = selectedProcessKey != null || application.rssComplete,
                ),
                points = rssPoints,
                color = MemoryMetricColor,
            )
        }
        item(key = "detail-pss-chart") {
            TrendCard(
                titleRes = R.string.detail_pss_megabytes,
                currentValue = formatPssWithAge(
                    selectedProcess = selectedProcessKey != null,
                    selectedMetric = selectedMetric,
                    application = application,
                    snapshot = snapshot,
                ),
                points = pssPoints,
                color = if (pssStale) {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                } else {
                    MemoryMetricColor
                },
            )
        }
        item(key = "detail-peaks") {
            val peaks = if (selectedProcessKey == null) {
                snapshot.applicationPeaks[applicationId]
            } else {
                snapshot.processPeaks[selectedProcessKey]
            }
            DetailPeakCard(peaks)
        }
        if (selectedEntry != null) {
            item(key = "detail-metadata") {
                ProcessMetadataCard(
                    entry = selectedEntry,
                    resolution = selectedResolution,
                    metric = selectedMetric,
                    snapshot = snapshot,
                )
            }
        }
        item(key = "detail-process-heading") {
            Text(
                text = stringResource(R.string.detail_processes_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        items(
            items = application.processes,
            key = { "detail-process:${it.key.pid}:${it.key.startTimeTicks}" },
        ) { metric ->
            ProcessChildRow(
                metric = metric,
                entry = catalogByKey[metric.key],
                resolution = resolutionByKey[metric.key],
                exited = !applicationIsLive,
                pinnedTargets = pinnedTargets,
                onTogglePin = onTogglePin,
                onOpen = { onSelectProcess(metric.key) },
            )
        }
        item(key = "detail-source") {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    DetailValueRow(
                        stringResource(R.string.detail_data_source_label),
                        when (latestLiveFrame?.frame?.source) {
                            MetricDataSource.PROCFS -> stringResource(R.string.data_source_procfs)
                            MetricDataSource.PS_FALLBACK -> stringResource(R.string.data_source_ps_fallback)
                            null -> "—"
                        },
                    )
                    DetailValueRow(
                        stringResource(R.string.detail_last_sample_time),
                        latestLiveFrame?.frame?.wallTimeMillis?.let(::formatDateTime) ?: "—",
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailPeakCard(peaks: MetricPeaks?) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.detail_session_peaks),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            DetailValueRow(
                stringResource(R.string.live_sort_cpu),
                formatPercent(peaks?.cpuPercentBasisPoints),
            )
            DetailValueRow(
                stringResource(R.string.live_sort_rss),
                formatKilobytes(peaks?.rssKb),
            )
            DetailValueRow(
                stringResource(R.string.live_sort_pss),
                formatKilobytes(peaks?.pssKb),
            )
        }
    }
}

@Composable
private fun ProcessMetadataCard(
    entry: ProcessCatalogEntry,
    resolution: ProcessPackageResolution?,
    metric: ProcessMetric?,
    snapshot: MonitorRuntimeSnapshot,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Text(
                text = stringResource(R.string.detail_process_metadata),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            DetailValueRow(stringResource(R.string.detail_pid), entry.key.pid.toString())
            DetailValueRow(stringResource(R.string.detail_uid), entry.uid?.toString() ?: "—")
            DetailValueRow(stringResource(R.string.detail_ppid), entry.parentPid.toString())
            DetailValueRow(
                stringResource(R.string.detail_process_state),
                metric?.state?.toString() ?: "—",
            )
            DetailValueRow(
                stringResource(R.string.detail_start_ticks),
                entry.key.startTimeTicks.toString(),
            )
            DetailValueRow(
                stringResource(R.string.detail_package),
                resolution?.packageCandidates?.joinToString().orEmpty().ifBlank { "—" },
            )
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = stringResource(R.string.detail_process_name),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(entry.processName, style = MaterialTheme.typography.bodyMedium)
            }
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = stringResource(R.string.detail_command_line),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = entry.commandLine.ifBlank { "—" },
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
            }
            val age = metric?.pssSampleElapsedRealtimeNanos?.let { sampledAt ->
                snapshot.lastFrame?.elapsedRealtimeNanos?.minus(sampledAt)?.coerceAtLeast(0L)
                    ?.div(1_000_000_000L)
            }
            DetailValueRow(
                stringResource(R.string.detail_pss_age),
                age?.let { stringResource(R.string.value_seconds, it) } ?: "—",
            )
        }
    }
}

@Composable
private fun DetailValueRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            modifier = Modifier.weight(1.4f),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun EmptyProcessCard(hasFrame: Boolean) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Default.Memory,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(
                    if (hasFrame) R.string.live_no_filter_results else R.string.live_no_frames,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PackageIcon(packageName: String?, fallbackText: String) {
    val packageManager = LocalContext.current.packageManager
    val bitmap by rememberPackageIcon(packageManager, packageName)
    if (bitmap != null) {
        Image(
            bitmap = bitmap!!,
            contentDescription = null,
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp)),
            contentScale = ContentScale.Crop,
        )
    } else {
        Surface(
            modifier = Modifier.size(40.dp),
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = fallbackText.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@Composable
private fun rememberPackageIcon(
    packageManager: PackageManager,
    packageName: String?,
) = produceState<ImageBitmap?>(initialValue = null, packageManager, packageName) {
    value = if (packageName == null) {
        null
    } else {
        withContext(Dispatchers.IO) {
            runCatching {
                packageManager.getApplicationIcon(packageName)
                    .toBitmap(width = 96, height = 96)
                    .asImageBitmap()
            }.getOrNull()
        }
    }
}

private fun buildDisplayApplications(
    snapshot: MonitorRuntimeSnapshot,
    pinnedTargets: Set<PinnedTarget>,
    query: String,
    filter: LiveFilter,
    sort: LiveSort,
): List<DisplayApplication> {
    val currentFrame = snapshot.recentFrames.lastOrNull()
    val previousFrame = snapshot.recentFrames.dropLast(1).lastOrNull()
    val currentApplications = snapshot.applications
    val currentIds = currentApplications.map(ApplicationAggregate::stableId).toSet()
    val previousById = previousFrame?.applications.orEmpty()
        .associateBy(ApplicationAggregate::stableId)
    val normalizedQuery = query.trim().lowercase(Locale.ROOT)
    val currentCatalogByKey = if (normalizedQuery.isEmpty()) {
        emptyMap()
    } else {
        (currentFrame?.catalog ?: snapshot.catalog).associateBy(ProcessCatalogEntry::key)
    }
    val previousCatalogByKey = if (normalizedQuery.isEmpty()) {
        emptyMap()
    } else {
        previousFrame?.catalog.orEmpty().associateBy(ProcessCatalogEntry::key)
    }
    val currentPinnedKeys = if (filter == LiveFilter.PINNED) {
        PinnedTargetMatcher.matchingKeys(
            pinnedTargets,
            currentFrame?.catalog ?: snapshot.catalog,
            currentFrame?.packageResolutions ?: snapshot.packageResolutions,
        )
    } else {
        emptySet()
    }
    val previousPinnedKeys = if (filter == LiveFilter.PINNED && previousFrame != null) {
        PinnedTargetMatcher.matchingKeys(
            pinnedTargets,
            previousFrame.catalog,
            previousFrame.packageResolutions,
        )
    } else {
        emptySet()
    }
    val currentItems = currentApplications.map { application ->
        DisplayApplication(
            application = application,
            frame = currentFrame,
            previousFrame = previousFrame,
            exited = false,
        )
    }
    val exitedItems = previousById.values
        .filter { it.stableId !in currentIds }
        .map { application ->
            DisplayApplication(
                application = application,
                frame = previousFrame,
                previousFrame = null,
                exited = true,
            )
        }
    return (currentItems + exitedItems)
        .asSequence()
        .filter { item ->
            item.matchesQuery(
                normalizedQuery,
                if (item.exited) previousCatalogByKey else currentCatalogByKey,
            )
        }
        .filter { item ->
            item.matchesFilter(
                filter,
                if (item.exited) previousPinnedKeys else currentPinnedKeys,
            )
        }
        .sortedWith(displayComparator(sort))
        .toList()
}

private fun DisplayApplication.matchesQuery(
    query: String,
    catalogByKey: Map<ProcessKey, ProcessCatalogEntry>,
): Boolean {
    if (query.isEmpty()) return true
    val application = application
    if (application.displayName.lowercase(Locale.ROOT).contains(query)) return true
    if (application.primaryPackage?.lowercase(Locale.ROOT)?.contains(query) == true) return true
    if (application.packageCandidates.any { it.lowercase(Locale.ROOT).contains(query) }) return true
    return application.processes.any { metric ->
        metric.key.pid.toString().contains(query) ||
            catalogByKey[metric.key]?.processName?.lowercase(Locale.ROOT)?.contains(query) == true
    }
}

private fun DisplayApplication.matchesFilter(
    filter: LiveFilter,
    pinnedKeys: Set<ProcessKey>,
): Boolean = when (filter) {
    LiveFilter.ALL -> true
    LiveFilter.USER -> !application.isSystem && !application.isNative
    LiveFilter.SYSTEM -> application.isSystem || application.isNative
    LiveFilter.PINNED -> application.processes.any { it.key in pinnedKeys }
}

private fun displayComparator(sort: LiveSort): Comparator<DisplayApplication> {
    val stableTie = compareBy<DisplayApplication, String>(String.CASE_INSENSITIVE_ORDER) {
        it.application.displayName
    }.thenBy { it.application.stableId }
    return when (sort) {
        LiveSort.CPU -> compareByDescending<DisplayApplication> {
            it.application.cpuPercentBasisPoints ?: -1
        }.then(stableTie)
        LiveSort.RSS -> compareByDescending<DisplayApplication> {
            it.application.rssKb ?: -1L
        }.then(stableTie)
        LiveSort.PSS -> compareByDescending<DisplayApplication> {
            it.application.pssKb ?: -1L
        }.then(stableTie)
        LiveSort.NAME -> stableTie
    }
}

private fun metricFor(
    frame: LiveTimelineFrame,
    applicationId: String,
    processKey: ProcessKey?,
): MetricProjection? {
    val application = frame.applications.firstOrNull { it.stableId == applicationId } ?: return null
    if (processKey == null) {
        return MetricProjection(
            cpuPercentBasisPoints = application.cpuPercentBasisPoints
                .takeIf { application.cpuComplete },
            rssKb = application.rssKb.takeIf { application.rssComplete },
            pssKb = application.pssKb.takeIf { application.pssComplete },
        )
    }
    val process = application.processes.firstOrNull { it.key == processKey } ?: return null
    return MetricProjection(
        cpuPercentBasisPoints = process.cpuPercentBasisPoints,
        rssKb = process.rssKb,
        pssKb = process.pssKb,
    )
}

private data class MetricProjection(
    val cpuPercentBasisPoints: Int?,
    val rssKb: Long?,
    val pssKb: Long?,
)

private const val LARGE_FONT_SCALE = 1.5f

private fun formatPercent(value: Int?): String = value?.let {
    String.format(Locale.getDefault(), "%.1f%%", it / 100.0)
} ?: "—"

private fun formatPercentValue(value: Float): String =
    String.format(Locale.getDefault(), "%.1f%%", value)

private fun formatKilobytes(value: Long?): String = value?.let {
    when {
        it >= 1024L * 1024L -> String.format(Locale.getDefault(), "%.1f GB", it / 1_048_576.0)
        it >= 1024L -> String.format(Locale.getDefault(), "%.1f MB", it / 1024.0)
        else -> "$it KB"
    }
} ?: "—"

private fun formatDateTime(wallTimeMillis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
        .format(Date(wallTimeMillis))

@Composable
private fun formatPssWithAge(
    selectedProcess: Boolean,
    selectedMetric: ProcessMetric?,
    application: ApplicationAggregate,
    snapshot: MonitorRuntimeSnapshot,
): String {
    val value = if (selectedProcess) selectedMetric?.pssKb else application.pssKb
    value ?: return "—"
    val sampledAt = if (selectedProcess) {
        selectedMetric?.pssSampleElapsedRealtimeNanos
    } else {
        application.processes.mapNotNull(ProcessMetric::pssSampleElapsedRealtimeNanos).minOrNull()
    }
    val ageSeconds = sampledAt?.let { sampled ->
        snapshot.lastFrame?.elapsedRealtimeNanos?.minus(sampled)?.coerceAtLeast(0L)
            ?.div(1_000_000_000L)
    }
    val formatted = if (ageSeconds == null) {
        formatKilobytes(value)
    } else {
        "${formatKilobytes(value)} · ${stringResource(R.string.value_seconds, ageSeconds)}"
    }
    return formatAggregateValue(
        formatted = formatted,
        hasValue = true,
        complete = selectedProcess || application.pssComplete,
    )
}

@Composable
private fun formatAggregateValue(
    formatted: String,
    hasValue: Boolean,
    complete: Boolean,
): String = if (!hasValue || complete) {
    formatted
} else {
    stringResource(R.string.metric_partial_value, formatted)
}
