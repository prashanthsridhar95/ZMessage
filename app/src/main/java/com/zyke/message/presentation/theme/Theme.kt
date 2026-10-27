package com.zyke.message.presentation.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.zyke.message.R

private val DarkColorScheme = darkColorScheme(
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80
)

private val LightColorScheme = lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40

    /* Other default colors to override
    background = Color(0xFFFFFBFE),
    surface = Color(0xFFFFFBFE),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color(0xFF1C1B1F),
    onSurface = Color(0xFF1C1B1F),
    */
)

@Composable
fun ZykeMessageTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content
    )
}

private val AppFontFamily = FontFamily(
    Font(R.font.plus_jakarta_sans_regular, FontWeight.Normal),
    Font(R.font.plus_jakarta_sans_medium, FontWeight.Medium),
    Font(R.font.plus_jakarta_sans_bold, FontWeight.Bold)
)

/**
 * Material's own type scale (sizes, weights, line heights) with only the font family swapped.
 * Replacing a style with a bare `TextStyle(fontFamily = ...)` would throw those sizes away and
 * leave every heading at body size.
 */
private val DefaultType = Typography()

val AppTypography = Typography(
    displayLarge = DefaultType.displayLarge.copy(fontFamily = AppFontFamily),
    displayMedium = DefaultType.displayMedium.copy(fontFamily = AppFontFamily),
    displaySmall = DefaultType.displaySmall.copy(fontFamily = AppFontFamily),
    headlineLarge = DefaultType.headlineLarge.copy(fontFamily = AppFontFamily),
    headlineMedium = DefaultType.headlineMedium.copy(fontFamily = AppFontFamily),
    headlineSmall = DefaultType.headlineSmall.copy(fontFamily = AppFontFamily),
    titleLarge = DefaultType.titleLarge.copy(fontFamily = AppFontFamily),
    titleMedium = DefaultType.titleMedium.copy(fontFamily = AppFontFamily),
    titleSmall = DefaultType.titleSmall.copy(fontFamily = AppFontFamily),
    bodyLarge = DefaultType.bodyLarge.copy(fontFamily = AppFontFamily),
    bodyMedium = DefaultType.bodyMedium.copy(fontFamily = AppFontFamily),
    bodySmall = DefaultType.bodySmall.copy(fontFamily = AppFontFamily),
    labelLarge = DefaultType.labelLarge.copy(fontFamily = AppFontFamily),
    labelMedium = DefaultType.labelMedium.copy(fontFamily = AppFontFamily),
    labelSmall = DefaultType.labelSmall.copy(fontFamily = AppFontFamily)
)
