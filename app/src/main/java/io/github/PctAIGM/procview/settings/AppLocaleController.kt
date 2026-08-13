package io.github.PctAIGM.procview.settings

import android.app.LocaleManager
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import java.util.Locale

object AppLocaleController {
    private const val PREFERENCES_NAME = "procview_locale_bootstrap"
    private const val LANGUAGE_KEY = "language"

    fun wrap(context: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return context
        val preference = remembered(context)
        val languageTag = preference.languageTag ?: return context
        val locale = Locale.forLanguageTag(languageTag)
        val configuration = Configuration(context.resources.configuration).apply {
            setLocales(LocaleList(locale))
        }
        return ContextWrapper(context.createConfigurationContext(configuration))
    }

    fun applyPlatformLocale(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val localeManager = context.getSystemService(LocaleManager::class.java)
        val languageTag = remembered(context).languageTag
        val requested = if (languageTag == null) LocaleList.getEmptyLocaleList()
        else LocaleList.forLanguageTags(languageTag)
        if (localeManager.applicationLocales != requested) {
            localeManager.applicationLocales = requested
        }
    }

    fun remember(context: Context, preference: LanguagePreference) {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(LANGUAGE_KEY, preference.name)
            .apply()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            applyPlatformLocale(context)
        } else {
            applyLegacyLocaleInPlace(context, preference)
        }
    }

    @Suppress("DEPRECATION")
    private fun applyLegacyLocaleInPlace(
        context: Context,
        preference: LanguagePreference,
    ) {
        val locales = preference.languageTag?.let(LocaleList::forLanguageTags)
            ?: Resources.getSystem().configuration.locales
        val resources = context.resources
        val configuration = Configuration(resources.configuration).apply { setLocales(locales) }
        resources.updateConfiguration(configuration, resources.displayMetrics)
    }

    private fun remembered(context: Context): LanguagePreference {
        val value = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getString(LANGUAGE_KEY, null)
        return LanguagePreference.entries.firstOrNull { it.name == value }
            ?: LanguagePreference.SYSTEM
    }
}
