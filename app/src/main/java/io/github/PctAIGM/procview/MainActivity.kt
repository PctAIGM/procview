package io.github.PctAIGM.procview

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.PctAIGM.procview.settings.AppLocaleController
import io.github.PctAIGM.procview.settings.PalettePreference
import io.github.PctAIGM.procview.settings.ThemePreference
import io.github.PctAIGM.procview.settings.UserSettings
import io.github.PctAIGM.procview.ui.ProcViewApp
import io.github.PctAIGM.procview.ui.theme.ProcViewTheme

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocaleController.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val application = applicationContext as ProcViewApplication
            val settings = application.userSettingsStore.settings.collectAsStateWithLifecycle(
                initialValue = UserSettings(),
            ).value
            val systemDark = isSystemInDarkTheme()
            val darkTheme = when (settings.theme) {
                ThemePreference.SYSTEM -> systemDark
                ThemePreference.LIGHT -> false
                ThemePreference.DARK -> true
            }
            SideEffect {
                val systemBarStyle = if (darkTheme) {
                    SystemBarStyle.dark(Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                }
                enableEdgeToEdge(
                    statusBarStyle = systemBarStyle,
                    navigationBarStyle = systemBarStyle,
                )
            }
            ProcViewTheme(
                darkTheme = darkTheme,
                dynamicColor = settings.palette == PalettePreference.DYNAMIC,
            ) {
                ProcViewApp()
            }
        }
    }
}
