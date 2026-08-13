package io.github.PctAIGM.procview.ui.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.PctAIGM.procview.BuildConfig
import io.github.PctAIGM.procview.R
import io.github.PctAIGM.procview.data.HistoryRepository
import io.github.PctAIGM.procview.data.StorageHealth
import io.github.PctAIGM.procview.data.StorageMonitor
import io.github.PctAIGM.procview.data.db.SessionEntity
import io.github.PctAIGM.procview.diagnostics.DiagnosticsExporter
import io.github.PctAIGM.procview.model.CapabilityReport
import io.github.PctAIGM.procview.model.PinnedTarget
import io.github.PctAIGM.procview.model.PinnedTargetKind
import io.github.PctAIGM.procview.monitor.SamplingPreset
import io.github.PctAIGM.procview.settings.ExportDefaults
import io.github.PctAIGM.procview.settings.ExportDefaultField
import io.github.PctAIGM.procview.settings.LanguagePreference
import io.github.PctAIGM.procview.settings.PalettePreference
import io.github.PctAIGM.procview.settings.ThemePreference
import io.github.PctAIGM.procview.settings.UserSettings
import io.github.PctAIGM.procview.settings.UserSettingsStore
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private enum class SettingsPage {
    OVERVIEW,
    SAMPLING_STORAGE,
    APPEARANCE_LANGUAGE,
    EXPORT_PRIVACY,
    DIAGNOSTICS,
    ABOUT,
}

@Composable
internal fun SettingsScreen(
    contentPadding: PaddingValues,
    settingsStore: UserSettingsStore,
    historyRepository: HistoryRepository,
    storageMonitor: StorageMonitor,
    diagnosticsExporter: DiagnosticsExporter,
    pinnedTargets: Set<PinnedTarget>,
    capabilityReport: CapabilityReport?,
    diagnosticsContent: @Composable () -> Unit,
    onRemovePin: (PinnedTarget) -> Unit,
    onOpenHistory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var pageName by rememberSaveable { mutableStateOf(SettingsPage.OVERVIEW.name) }
    val page = SettingsPage.entries.firstOrNull { it.name == pageName } ?: SettingsPage.OVERVIEW
    BackHandler(enabled = page != SettingsPage.OVERVIEW) {
        pageName = SettingsPage.OVERVIEW.name
    }
    val settings by remember(settingsStore) { settingsStore.settings }
        .collectAsStateWithLifecycle(initialValue = UserSettings())
    val sessions by remember(historyRepository) { historyRepository.sessions() }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var diagnosticExportRunning by remember { mutableStateOf(false) }
    var diagnosticExportSucceeded by remember { mutableStateOf<Boolean?>(null) }
    val createDiagnosticDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { target ->
        val report = capabilityReport
        if (target == null) return@rememberLauncherForActivityResult
        if (report == null) {
            runCatching { context.contentResolver.delete(target, null, null) }
            diagnosticExportSucceeded = false
            return@rememberLauncherForActivityResult
        }
        diagnosticExportRunning = true
        diagnosticExportSucceeded = null
        scope.launch {
            try {
                diagnosticExportSucceeded = diagnosticsExporter.export(target, report)
            } finally {
                diagnosticExportRunning = false
            }
        }
    }
    val storage by produceState(
        initialValue = StorageHealth.Empty,
        settings.storageWarningMegabytes,
        sessions.size,
    ) {
        while (isActive) {
            value = try {
                storageMonitor.snapshot(settings.storageWarningMegabytes)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                StorageHealth.Empty
            }
            delay(STORAGE_REFRESH_MS)
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (page == SettingsPage.OVERVIEW) {
            item("settings-overview") {
                SettingsSection(title = stringResource(R.string.settings_categories_title)) {
                    SettingsPage.entries.drop(1).forEachIndexed { index, destination ->
                        if (index > 0) HorizontalDivider()
                        SettingsNavigationRow(
                            title = stringResource(destination.titleResource()),
                            summary = stringResource(destination.summaryResource()),
                            onClick = { pageName = destination.name },
                        )
                    }
                }
            }
        } else {
            item("settings-page-header") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { pageName = SettingsPage.OVERVIEW.name }) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                    Text(
                        text = stringResource(page.titleResource()),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
        if (page == SettingsPage.SAMPLING_STORAGE) item("settings-storage-warning") {
            StorageWarningCard(storage)
        }
        if (page == SettingsPage.SAMPLING_STORAGE) item("settings-sampling") {
            SettingsSection(
                title = stringResource(R.string.settings_sampling_title),
                description = stringResource(R.string.settings_sampling_description),
            ) {
                SamplingPreset.entries.forEach { preset ->
                    FilterChip(
                        selected = settings.samplingPreset == preset,
                        onClick = { scope.launch { settingsStore.setSamplingPreset(preset) } },
                        modifier = Modifier.fillMaxWidth(),
                        label = {
                            Column(Modifier.padding(vertical = 5.dp)) {
                                Text(stringResource(preset.titleResource()))
                                Text(
                                    text = stringResource(
                                        R.string.session_preset_cadence,
                                        preset.foregroundIntervalMs / 1_000.0,
                                        preset.backgroundIntervalMs / 1_000.0,
                                        preset.pssIntervalMs / 1_000,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        },
                    )
                }
                Text(
                    text = stringResource(R.string.settings_wake_lock_notice),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (page == SettingsPage.APPEARANCE_LANGUAGE) item("settings-appearance") {
            SettingsSection(title = stringResource(R.string.settings_appearance_title)) {
                ChoiceRow(
                    label = stringResource(R.string.settings_theme_title),
                    choices = ThemePreference.entries,
                    selected = settings.theme,
                    choiceLabel = { stringResource(it.labelResource()) },
                    onSelected = { value -> scope.launch { settingsStore.setTheme(value) } },
                )
                HorizontalDivider()
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_dynamic_color),
                    body = stringResource(R.string.settings_dynamic_color_body),
                    checked = settings.palette == PalettePreference.DYNAMIC,
                    onCheckedChange = { enabled ->
                        scope.launch {
                            settingsStore.setPalette(
                                if (enabled) PalettePreference.DYNAMIC
                                else PalettePreference.FIXED,
                            )
                        }
                    },
                )
            }
        }
        if (page == SettingsPage.APPEARANCE_LANGUAGE) item("settings-language") {
            SettingsSection(title = stringResource(R.string.settings_language_title)) {
                ChoiceRow(
                    label = stringResource(R.string.settings_language_description),
                    choices = LanguagePreference.entries,
                    selected = settings.language,
                    choiceLabel = { stringResource(it.labelResource()) },
                    onSelected = { value ->
                        scope.launch {
                            settingsStore.setLanguage(value)
                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                                context.findActivity()?.recreate()
                            }
                        }
                    },
                )
            }
        }
        if (page == SettingsPage.SAMPLING_STORAGE) item("settings-pins") {
            SettingsSection(
                title = stringResource(R.string.settings_pins_title),
                description = if (pinnedTargets.isEmpty()) {
                    stringResource(R.string.settings_pins_empty)
                } else {
                    stringResource(R.string.settings_pins_count, pinnedTargets.size)
                },
            ) {
                pinnedTargets.sortedBy(PinnedTarget::stableKey).forEachIndexed { index, target ->
                    if (index > 0) HorizontalDivider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.PushPin, contentDescription = null)
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 12.dp),
                        ) {
                            Text(
                                text = targetLabel(target),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = targetKindLabel(target.kind),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { onRemovePin(target) }) {
                            Icon(
                                Icons.Outlined.DeleteOutline,
                                contentDescription = stringResource(R.string.action_unpin),
                            )
                        }
                    }
                }
            }
        }
        if (page == SettingsPage.SAMPLING_STORAGE) item("settings-storage") {
            SettingsSection(
                title = stringResource(R.string.settings_storage_title),
                description = stringResource(
                    R.string.settings_storage_total,
                    formatBytes(storage.databaseBytes),
                ),
            ) {
                var thresholdDraft by remember(settings.storageWarningMegabytes) {
                    mutableFloatStateOf(settings.storageWarningMegabytes.toFloat())
                }
                Text(
                    text = stringResource(
                        R.string.settings_storage_threshold,
                        thresholdDraft.toInt(),
                    ),
                    style = MaterialTheme.typography.labelLarge,
                )
                val storageThresholdDescription = stringResource(
                    R.string.settings_storage_threshold,
                    thresholdDraft.toInt(),
                )
                Slider(
                    value = thresholdDraft,
                    onValueChange = { thresholdDraft = (it / 100f).toInt() * 100f },
                    onValueChangeFinished = {
                        scope.launch {
                            settingsStore.setStorageWarningMegabytes(thresholdDraft.toInt())
                        }
                    },
                    valueRange = UserSettings.MIN_STORAGE_WARNING_MB.toFloat()..
                        UserSettings.MAX_STORAGE_WARNING_MB.toFloat(),
                    steps = ((UserSettings.MAX_STORAGE_WARNING_MB -
                        UserSettings.MIN_STORAGE_WARNING_MB) / 100) - 1,
                    modifier = Modifier.semantics {
                        contentDescription = storageThresholdDescription
                    },
                )
                Text(
                    text = stringResource(R.string.settings_storage_device_floor),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (sessions.isEmpty()) {
                    Text(
                        stringResource(R.string.history_empty_title),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    sessions.forEachIndexed { index, session ->
                        if (index > 0) HorizontalDivider()
                        StorageSessionRow(session, historyRepository, onOpenHistory)
                    }
                }
                TextButton(onClick = onOpenHistory, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.settings_manage_sessions))
                    Icon(Icons.Outlined.ChevronRight, contentDescription = null)
                }
            }
        }
        if (page == SettingsPage.EXPORT_PRIVACY) item("settings-export") {
            SettingsSection(
                title = stringResource(R.string.settings_export_defaults_title),
                description = stringResource(R.string.settings_export_defaults_body),
            ) {
                ExportDefaultsEditor(
                    title = stringResource(R.string.settings_regular_export),
                    value = settings.regularExport,
                    onChange = { field, enabled ->
                        scope.launch { settingsStore.setRegularExportField(field, enabled) }
                    },
                )
                HorizontalDivider()
                ExportDefaultsEditor(
                    title = stringResource(R.string.settings_anonymous_export),
                    value = settings.anonymousExport,
                    onChange = { field, enabled ->
                        scope.launch { settingsStore.setAnonymousExportField(field, enabled) }
                    },
                )
                Text(
                    text = stringResource(R.string.settings_anonymous_invariant),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (page == SettingsPage.DIAGNOSTICS) item("settings-diagnostics-tools") {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                diagnosticsContent()
            }
        }
        if (page == SettingsPage.DIAGNOSTICS) item("settings-diagnostics") {
            SettingsSection(
                title = stringResource(R.string.settings_diagnostics_title),
                description = capabilityReport?.let {
                    if (it.metricCoverageReferenceCount > 0) {
                        stringResource(
                            R.string.settings_diagnostics_available,
                            (it.metricCoverage * 100).toInt(),
                            it.metricCoverageReferenceCount,
                        )
                    } else {
                        stringResource(R.string.settings_diagnostics_unverified)
                    }
                } ?: stringResource(R.string.settings_diagnostics_unavailable),
            ) {
                capabilityReport?.let { report ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                createDiagnosticDocument.launch(
                                    diagnosticsExporter.suggestedFileName(),
                                )
                            },
                            enabled = !diagnosticExportRunning,
                        ) {
                            Icon(Icons.Outlined.Download, contentDescription = null)
                            Spacer(Modifier.size(8.dp))
                            Text(stringResource(R.string.settings_export_diagnostics))
                        }
                        if (diagnosticExportRunning) {
                            Text(stringResource(R.string.settings_diagnostics_exporting))
                        } else if (diagnosticExportSucceeded != null) {
                            Text(
                                stringResource(
                                    if (diagnosticExportSucceeded == true) {
                                        R.string.settings_diagnostics_exported
                                    } else {
                                        R.string.settings_diagnostics_export_failed
                                    },
                                ),
                                color = if (diagnosticExportSucceeded == true) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                            )
                        }
                    }
                }
            }
        }
        if (page == SettingsPage.EXPORT_PRIVACY) item("settings-privacy") {
            SettingsSection(
                title = stringResource(R.string.settings_privacy_title),
                description = stringResource(R.string.settings_privacy_body),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Outlined.Info, contentDescription = null)
                    Text(
                        text = stringResource(R.string.settings_no_internet),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        if (page == SettingsPage.ABOUT) item("settings-about") {
            SettingsSection(title = stringResource(R.string.settings_page_about)) {
                Text(
                    text = stringResource(R.string.settings_no_internet),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider()
                Text(
                    text = stringResource(R.string.settings_dependencies),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(
                        R.string.settings_version,
                        BuildConfig.VERSION_NAME,
                        BuildConfig.VERSION_CODE,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item("settings-footer") { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable
private fun SettingsNavigationRow(
    title: String,
    summary: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = summary,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(Icons.Outlined.ChevronRight, contentDescription = null, modifier = Modifier.size(20.dp))
    }
}

private fun SettingsPage.titleResource(): Int = when (this) {
    SettingsPage.OVERVIEW -> R.string.nav_settings
    SettingsPage.SAMPLING_STORAGE -> R.string.settings_page_sampling_storage
    SettingsPage.APPEARANCE_LANGUAGE -> R.string.settings_page_appearance_language
    SettingsPage.EXPORT_PRIVACY -> R.string.settings_page_export_privacy
    SettingsPage.DIAGNOSTICS -> R.string.settings_page_diagnostics
    SettingsPage.ABOUT -> R.string.settings_page_about
}

private fun SettingsPage.summaryResource(): Int = when (this) {
    SettingsPage.OVERVIEW -> R.string.settings_categories_title
    SettingsPage.SAMPLING_STORAGE -> R.string.settings_page_sampling_storage_summary
    SettingsPage.APPEARANCE_LANGUAGE -> R.string.settings_page_appearance_language_summary
    SettingsPage.EXPORT_PRIVACY -> R.string.settings_page_export_privacy_summary
    SettingsPage.DIAGNOSTICS -> R.string.settings_page_diagnostics_summary
    SettingsPage.ABOUT -> R.string.settings_page_about_summary
}

@Composable
private fun SettingsSection(
    title: String,
    description: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            description?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            content()
        }
    }
}

@Composable
private fun <T> ChoiceRow(
    label: String,
    choices: List<T>,
    selected: T,
    choiceLabel: @Composable (T) -> String,
    onSelected: (T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        choices.forEach { choice ->
            FilterChip(
                selected = choice == selected,
                onClick = { onSelected(choice) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(choiceLabel(choice)) },
            )
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    body: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            body?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.semantics { contentDescription = title },
        )
    }
}

@Composable
private fun ExportDefaultsEditor(
    title: String,
    value: ExportDefaults,
    onChange: (ExportDefaultField, Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        SettingsSwitchRow(
            stringResource(R.string.export_include_session_name),
            checked = value.includeSessionName,
            onCheckedChange = { onChange(ExportDefaultField.SESSION_NAME, it) },
        )
        SettingsSwitchRow(
            stringResource(R.string.export_include_note),
            checked = value.includeNote,
            onCheckedChange = { onChange(ExportDefaultField.NOTE, it) },
        )
        SettingsSwitchRow(
            stringResource(R.string.export_include_device),
            checked = value.includeDeviceDetails,
            onCheckedChange = { onChange(ExportDefaultField.DEVICE_DETAILS, it) },
        )
        SettingsSwitchRow(
            stringResource(R.string.export_include_absolute_time),
            checked = value.includeAbsoluteTime,
            onCheckedChange = { onChange(ExportDefaultField.ABSOLUTE_TIME, it) },
        )
        SettingsSwitchRow(
            stringResource(R.string.export_include_command_line),
            checked = value.includeCommandLine,
            onCheckedChange = { onChange(ExportDefaultField.COMMAND_LINE, it) },
        )
    }
}

@Composable
private fun StorageWarningCard(storage: StorageHealth) {
    if (!storage.thresholdReached && !storage.deviceLow) return
    val message = when {
        storage.deviceLow -> stringResource(
            R.string.settings_device_storage_warning,
            storage.availablePercent,
        )
        else -> stringResource(
            R.string.settings_app_storage_warning,
            formatBytes(storage.databaseBytes),
            formatBytes(storage.warningBytes),
        )
    }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Storage, contentDescription = null, modifier = Modifier.size(24.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun StorageSessionRow(
    session: SessionEntity,
    repository: HistoryRepository,
    onOpenHistory: () -> Unit,
) {
    val bytes by produceState<Long?>(null, session.id, session.status) {
        val terminal = session.status == "COMPLETED" || session.status == "INTERRUPTED"
        while (isActive) {
            value = try {
                repository.estimatedSessionBytes(session.id)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                null
            }
            if (terminal) break
            delay(SESSION_SIZE_REFRESH_MS)
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(session.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                bytes?.let(::formatBytes) ?: "—",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onOpenHistory) { Text(stringResource(R.string.action_manage)) }
    }
}

private fun targetLabel(target: PinnedTarget): String = listOfNotNull(
    target.packageName,
    target.processName,
    target.uid?.let { "UID $it" },
).joinToString(" · ").ifBlank { target.stableKey }

@Composable
private fun targetKindLabel(kind: PinnedTargetKind): String = stringResource(
    when (kind) {
        PinnedTargetKind.PACKAGE -> R.string.settings_pin_kind_application
        PinnedTargetKind.PACKAGE_PROCESS -> R.string.settings_pin_kind_process
        PinnedTargetKind.UID -> R.string.settings_pin_kind_shared_uid
        PinnedTargetKind.COMMAND_UID -> R.string.settings_pin_kind_native_process
    },
)

private fun formatBytes(value: Long): String = when {
    value >= 1_073_741_824L -> String.format(Locale.getDefault(), "%.2f GB", value / 1_073_741_824.0)
    value >= 1_048_576L -> String.format(Locale.getDefault(), "%.1f MB", value / 1_048_576.0)
    value >= 1_024L -> String.format(Locale.getDefault(), "%.1f KB", value / 1_024.0)
    else -> "$value B"
}

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun SamplingPreset.titleResource(): Int = when (this) {
    SamplingPreset.FINE -> R.string.session_preset_fine
    SamplingPreset.BALANCED -> R.string.session_preset_balanced
    SamplingPreset.POWER_SAVER -> R.string.session_preset_power_saver
}

private fun ThemePreference.labelResource(): Int = when (this) {
    ThemePreference.SYSTEM -> R.string.settings_follow_system
    ThemePreference.LIGHT -> R.string.settings_light
    ThemePreference.DARK -> R.string.settings_dark
}

private fun LanguagePreference.labelResource(): Int = when (this) {
    LanguagePreference.SYSTEM -> R.string.settings_follow_system
    LanguagePreference.SIMPLIFIED_CHINESE -> R.string.settings_language_chinese
    LanguagePreference.ENGLISH -> R.string.settings_language_english
}

private const val STORAGE_REFRESH_MS = 5_000L
private const val SESSION_SIZE_REFRESH_MS = 30_000L
