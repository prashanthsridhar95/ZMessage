package com.zyke.message.presentation.common

import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics

/**
 * One tap target for a row that holds a checkbox or switch and its label: the whole row toggles, and TalkBack reads the label,
 * the role and "on" / "off" (or "checked"). The box or switch inside is drawn with `onCheckedChange = null`.
 */
fun Modifier.toggleRow(value: Boolean, role: Role = Role.Checkbox, onValueChange: (Boolean) -> Unit): Modifier =
    toggleable(value = value, role = role, onValueChange = onValueChange)

/** Marks a title as a heading, so screen-reader users can jump between sections. */
fun Modifier.heading(): Modifier = semantics { heading() }

/**
 * True when decorative motion should be skipped: the person has turned system animations off ("Remove animations" /
 * animator scale 0), or lite mode is on (see [LocalLite]): every screen that already honours reduced motion then
 * also drops its looping and decorative animation on a slow phone.
 */
@Composable
fun rememberReduceMotion(): Boolean {
    val lite = LocalLite.current
    val resolver = LocalContext.current.contentResolver
    val system = remember {
        runCatching { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
    }
    return lite || system
}

private const val LARGE_FONT_SCALE = 1.3f

/** One more line for text when the person has made system text large, so it wraps instead of being cut off. */
@Composable
fun scaledLines(base: Int): Int = if (LocalDensity.current.fontScale > LARGE_FONT_SCALE) base + 1 else base

/**
 * True while a screen reader that explores by touch (TalkBack) is on. A gesture such as "pull down to reveal" cannot be done
 * there, so a screen shows a plain button for it as well.
 */
@Composable
fun rememberScreenReaderOn(): Boolean {
    val context = LocalContext.current
    val manager = remember { context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager }
    var on by remember { mutableStateOf(manager?.isTouchExplorationEnabled == true) }
    DisposableEffect(manager) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { on = it }
        runCatching { manager?.addTouchExplorationStateChangeListener(listener) }
        onDispose { runCatching { manager?.removeTouchExplorationStateChangeListener(listener) } }
    }
    return on
}
