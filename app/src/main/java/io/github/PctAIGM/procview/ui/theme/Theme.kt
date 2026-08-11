package io.github.PctAIGM.procview.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val FixedLightColors = lightColorScheme(
    primary = IosBlueLight,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD9E9FF),
    onPrimaryContainer = Color(0xFF002C5E),
    secondary = Color(0xFF5856D6),
    tertiary = Color(0xFFFF9500),
    background = LightBackground,
    onBackground = LightText,
    surface = LightSurface,
    onSurface = LightText,
    surfaceVariant = Color(0xFFE8E8ED),
    onSurfaceVariant = LightSecondaryText,
    outline = LightSeparator,
    error = Color(0xFFD70015),
)

private val FixedDarkColors = darkColorScheme(
    primary = IosBlueDark,
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF003E7C),
    onPrimaryContainer = Color(0xFFD6E9FF),
    secondary = Color(0xFF5E5CE6),
    tertiary = Color(0xFFFF9F0A),
    background = DarkBackground,
    onBackground = DarkText,
    surface = DarkSurface,
    onSurface = DarkText,
    surfaceVariant = Color(0xFF3A3A3C),
    onSurfaceVariant = DarkSecondaryText,
    outline = DarkSeparator,
    error = Color(0xFFFF453A),
)

@Composable
fun ProcViewTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if (darkTheme) {
        FixedDarkColors
    } else {
        FixedLightColors
    }

    MaterialTheme(
        colorScheme = colors,
        typography = ProcViewTypography,
        shapes = ProcViewShapes,
        content = content,
    )
}
