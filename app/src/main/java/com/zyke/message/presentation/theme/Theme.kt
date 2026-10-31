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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.zyke.message.domain.model.AccentColor
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

/** True when the theme is pure black, so cards and dividers can add the edge that a faint tint no longer provides. */
val LocalTrueBlack = androidx.compose.runtime.compositionLocalOf { false }

private fun accentScheme(accent: AccentColor, dark: Boolean): ColorScheme {
    val primary = Color(if (dark) accent.darkPrimary else accent.lightPrimary)
    val onPrimary = Color(if (dark) accent.darkOnPrimary else accent.lightOnPrimary)
    return if (dark) {
        darkColorScheme(
            primary = primary, onPrimary = onPrimary, secondary = primary, tertiary = primary,
            primaryContainer = lerp(Color.Black, primary, 0.3f), onPrimaryContainer = Color(0xFFF2F2F2),
            surfaceTint = primary
        )
    } else {
        lightColorScheme(
            primary = primary, onPrimary = onPrimary, secondary = primary, tertiary = primary,
            primaryContainer = lerp(Color.White, primary, 0.18f), onPrimaryContainer = Color(0xFF1C1B1F),
            surfaceTint = primary
        )
    }
}

/** Pure #000 for the background and surfaces (OLED); raised containers stay just off black so they still read. */
private fun ColorScheme.pureBlack(): ColorScheme = copy(
    background = Color.Black, surface = Color.Black, surfaceDim = Color.Black, surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF0B0B0B), surfaceContainer = Color(0xFF111111),
    surfaceContainerHigh = Color(0xFF181818), surfaceContainerHighest = Color(0xFF202020),
    surfaceVariant = Color(0xFF181818)
)

@Composable
fun ZykeMessageTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = true,
    trueBlack: Boolean = false,
    accent: AccentColor = AccentColor.DEFAULT,
    content: @Composable () -> Unit
) {
    val base = when {
        // Only the default follows the phone's wallpaper colours; a chosen accent is fixed.
        accent == AccentColor.DEFAULT && dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        accent != AccentColor.DEFAULT -> accentScheme(accent, darkTheme)
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    val black = trueBlack && darkTheme
    val colorScheme = if (black) base.pureBlack() else base

    CompositionLocalProvider(LocalTrueBlack provides black) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AppTypography,
            content = content
        )
    }
}

/** A row of colour dots for choosing the accent; the chosen one is ringed and ticked. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AccentSwatches(selected: AccentColor, onSelect: (AccentColor) -> Unit, modifier: Modifier = Modifier) {
    val dark = isSystemInDarkTheme() || LocalTrueBlack.current ||
        MaterialTheme.colorScheme.background.luminance() < 0.5f
    androidx.compose.foundation.layout.FlowRow(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AccentColor.entries.forEach { accent ->
            val chosen = accent == selected
            val fill = Color(if (dark) accent.darkPrimary else accent.lightPrimary)
            val onFill = Color(if (dark) accent.darkOnPrimary else accent.lightOnPrimary)
            Box(
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(fill)
                    .border(if (chosen) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                    .selectable(selected = chosen, role = Role.RadioButton, onClick = { onSelect(accent) })
                    .semantics { contentDescription = accent.label },
                contentAlignment = Alignment.Center
            ) {
                if (chosen) Text("\u2713", color = onFill, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
        }
    }
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
