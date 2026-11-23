package com.lingocast.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LingoLightColorScheme = lightColorScheme(
    primary = Color(0xFF1D4E89),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD5E4FF),
    onPrimaryContainer = Color(0xFF001C3B),
    secondary = Color(0xFF0E6B64),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFA2F2E8),
    onSecondaryContainer = Color(0xFF00201D),
    tertiary = Color(0xFF8C5000),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDCBA),
    onTertiaryContainer = Color(0xFF2D1600),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF7F9FC),
    onBackground = Color(0xFF171C23),
    surface = Color(0xFFF7F9FC),
    onSurface = Color(0xFF171C23),
    surfaceVariant = Color(0xFFE0E5EE),
    onSurfaceVariant = Color(0xFF424751),
    outline = Color(0xFF727782),
    outlineVariant = Color(0xFFC2C7D2),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF1F4FA),
    surfaceContainer = Color(0xFFEBEFF6),
    surfaceContainerHigh = Color(0xFFE4E9F2),
    surfaceContainerHighest = Color(0xFFDEE3EC)
)

private val LingoDarkColorScheme = darkColorScheme(
    primary = Color(0xFFA7C8FF),
    onPrimary = Color(0xFF003061),
    primaryContainer = Color(0xFF00468A),
    onPrimaryContainer = Color(0xFFD5E4FF),
    secondary = Color(0xFF86D5CB),
    onSecondary = Color(0xFF003733),
    secondaryContainer = Color(0xFF00504A),
    onSecondaryContainer = Color(0xFFA2F2E8),
    tertiary = Color(0xFFFFB86B),
    onTertiary = Color(0xFF4A2800),
    tertiaryContainer = Color(0xFF6B3B00),
    onTertiaryContainer = Color(0xFFFFDCBA),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0F1319),
    onBackground = Color(0xFFE2E6EE),
    surface = Color(0xFF0F1319),
    onSurface = Color(0xFFE2E6EE),
    surfaceVariant = Color(0xFF424751),
    onSurfaceVariant = Color(0xFFC2C7D2),
    outline = Color(0xFF8C919C),
    outlineVariant = Color(0xFF424751),
    surfaceContainerLowest = Color(0xFF0A0D12),
    surfaceContainerLow = Color(0xFF171B22),
    surfaceContainer = Color(0xFF1B2028),
    surfaceContainerHigh = Color(0xFF252A34),
    surfaceContainerHighest = Color(0xFF303641)
)

enum class ThemeModePreference {
    SYSTEM, LIGHT, DARK
}

@Composable
fun LingoCastTheme(
    themeMode: ThemeModePreference = ThemeModePreference.SYSTEM,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        ThemeModePreference.SYSTEM -> isSystemInDarkTheme()
        ThemeModePreference.LIGHT -> false
        ThemeModePreference.DARK -> true
    }

    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> LingoDarkColorScheme
        else -> LingoLightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = LingoTypography,
        shapes = LingoShapes,
        content = content
    )
}
