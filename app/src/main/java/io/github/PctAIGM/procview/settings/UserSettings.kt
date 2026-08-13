package io.github.PctAIGM.procview.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.PctAIGM.procview.monitor.SamplingPreset
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

enum class ThemePreference {
    SYSTEM,
    LIGHT,
    DARK,
}

enum class PalettePreference {
    FIXED,
    DYNAMIC,
}

enum class LanguagePreference(val languageTag: String?) {
    SYSTEM(null),
    SIMPLIFIED_CHINESE("zh-CN"),
    ENGLISH("en"),
}

data class ExportDefaults(
    val includeSessionName: Boolean,
    val includeNote: Boolean,
    val includeDeviceDetails: Boolean,
    val includeAbsoluteTime: Boolean,
    val includeCommandLine: Boolean,
)

enum class ExportDefaultField {
    SESSION_NAME,
    NOTE,
    DEVICE_DETAILS,
    ABSOLUTE_TIME,
    COMMAND_LINE,
}

data class UserSettings(
    val samplingPreset: SamplingPreset = SamplingPreset.BALANCED,
    val theme: ThemePreference = ThemePreference.SYSTEM,
    val palette: PalettePreference = PalettePreference.FIXED,
    val language: LanguagePreference = LanguagePreference.SYSTEM,
    val storageWarningMegabytes: Int = DEFAULT_STORAGE_WARNING_MB,
    val regularExport: ExportDefaults = ExportDefaults(
        includeSessionName = true,
        includeNote = true,
        includeDeviceDetails = true,
        includeAbsoluteTime = true,
        includeCommandLine = true,
    ),
    val anonymousExport: ExportDefaults = ExportDefaults(
        includeSessionName = false,
        includeNote = false,
        includeDeviceDetails = false,
        includeAbsoluteTime = false,
        includeCommandLine = false,
    ),
) {
    companion object {
        const val DEFAULT_STORAGE_WARNING_MB = 500
        const val MIN_STORAGE_WARNING_MB = 100
        const val MAX_STORAGE_WARNING_MB = 10_000
    }
}

private val Context.procViewSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "procview_settings",
)

class UserSettingsStore(context: Context) {
    private val applicationContext = context.applicationContext
    private val dataStore = applicationContext.procViewSettingsDataStore

    val settings: Flow<UserSettings> = dataStore.data
        .catch { failure ->
            if (failure is IOException) emit(emptyPreferences()) else throw failure
        }
        .map(::decode)

    suspend fun setSamplingPreset(value: SamplingPreset) = update(Keys.samplingPreset, value.name)

    suspend fun setTheme(value: ThemePreference) = update(Keys.theme, value.name)

    suspend fun setPalette(value: PalettePreference) = update(Keys.palette, value.name)

    suspend fun setLanguage(value: LanguagePreference) {
        dataStore.edit { it[Keys.language] = value.name }
        AppLocaleController.remember(applicationContext, value)
    }

    suspend fun setStorageWarningMegabytes(value: Int) {
        dataStore.edit {
            it[Keys.storageWarningMegabytes] = value.coerceIn(
                UserSettings.MIN_STORAGE_WARNING_MB,
                UserSettings.MAX_STORAGE_WARNING_MB,
            )
        }
    }

    suspend fun setRegularExportField(field: ExportDefaultField, enabled: Boolean) =
        dataStore.edit { preferences ->
            preferences[regularExportKey(field)] = enabled
        }

    suspend fun setAnonymousExportField(field: ExportDefaultField, enabled: Boolean) =
        dataStore.edit { preferences ->
            preferences[anonymousExportKey(field)] = enabled
        }

    private fun regularExportKey(field: ExportDefaultField): Preferences.Key<Boolean> = when (field) {
        ExportDefaultField.SESSION_NAME -> Keys.regularName
        ExportDefaultField.NOTE -> Keys.regularNote
        ExportDefaultField.DEVICE_DETAILS -> Keys.regularDevice
        ExportDefaultField.ABSOLUTE_TIME -> Keys.regularAbsoluteTime
        ExportDefaultField.COMMAND_LINE -> Keys.regularCommandLine
    }

    private fun anonymousExportKey(field: ExportDefaultField): Preferences.Key<Boolean> =
        when (field) {
            ExportDefaultField.SESSION_NAME -> Keys.anonymousName
            ExportDefaultField.NOTE -> Keys.anonymousNote
            ExportDefaultField.DEVICE_DETAILS -> Keys.anonymousDevice
            ExportDefaultField.ABSOLUTE_TIME -> Keys.anonymousAbsoluteTime
            ExportDefaultField.COMMAND_LINE -> Keys.anonymousCommandLine
        }

    private suspend fun update(key: Preferences.Key<String>, value: String) {
        dataStore.edit { it[key] = value }
    }

    private fun decode(preferences: Preferences): UserSettings {
        val defaults = UserSettings()
        return UserSettings(
            samplingPreset = enumValueOrDefault(
                preferences[Keys.samplingPreset],
                defaults.samplingPreset,
            ),
            theme = enumValueOrDefault(preferences[Keys.theme], defaults.theme),
            palette = enumValueOrDefault(preferences[Keys.palette], defaults.palette),
            language = enumValueOrDefault(preferences[Keys.language], defaults.language),
            storageWarningMegabytes = preferences[Keys.storageWarningMegabytes]
                ?.coerceIn(
                    UserSettings.MIN_STORAGE_WARNING_MB,
                    UserSettings.MAX_STORAGE_WARNING_MB,
                ) ?: defaults.storageWarningMegabytes,
            regularExport = ExportDefaults(
                includeSessionName = preferences[Keys.regularName]
                    ?: defaults.regularExport.includeSessionName,
                includeNote = preferences[Keys.regularNote]
                    ?: defaults.regularExport.includeNote,
                includeDeviceDetails = preferences[Keys.regularDevice]
                    ?: defaults.regularExport.includeDeviceDetails,
                includeAbsoluteTime = preferences[Keys.regularAbsoluteTime]
                    ?: defaults.regularExport.includeAbsoluteTime,
                includeCommandLine = preferences[Keys.regularCommandLine]
                    ?: defaults.regularExport.includeCommandLine,
            ),
            anonymousExport = ExportDefaults(
                includeSessionName = preferences[Keys.anonymousName]
                    ?: defaults.anonymousExport.includeSessionName,
                includeNote = preferences[Keys.anonymousNote]
                    ?: defaults.anonymousExport.includeNote,
                includeDeviceDetails = preferences[Keys.anonymousDevice]
                    ?: defaults.anonymousExport.includeDeviceDetails,
                includeAbsoluteTime = preferences[Keys.anonymousAbsoluteTime]
                    ?: defaults.anonymousExport.includeAbsoluteTime,
                includeCommandLine = preferences[Keys.anonymousCommandLine]
                    ?: defaults.anonymousExport.includeCommandLine,
            ),
        )
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String?, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == value } ?: fallback

    private object Keys {
        val samplingPreset = stringPreferencesKey("sampling_preset")
        val theme = stringPreferencesKey("theme")
        val palette = stringPreferencesKey("palette")
        val language = stringPreferencesKey("language")
        val storageWarningMegabytes = intPreferencesKey("storage_warning_megabytes")
        val regularName = booleanPreferencesKey("regular_export_name")
        val regularNote = booleanPreferencesKey("regular_export_note")
        val regularDevice = booleanPreferencesKey("regular_export_device")
        val regularAbsoluteTime = booleanPreferencesKey("regular_export_absolute_time")
        val regularCommandLine = booleanPreferencesKey("regular_export_command_line")
        val anonymousName = booleanPreferencesKey("anonymous_export_name")
        val anonymousNote = booleanPreferencesKey("anonymous_export_note")
        val anonymousDevice = booleanPreferencesKey("anonymous_export_device")
        val anonymousAbsoluteTime = booleanPreferencesKey("anonymous_export_absolute_time")
        val anonymousCommandLine = booleanPreferencesKey("anonymous_export_command_line")
    }
}
