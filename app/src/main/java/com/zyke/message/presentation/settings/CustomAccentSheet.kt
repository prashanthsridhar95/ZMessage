package com.zyke.message.presentation.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AssistChip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zyke.message.domain.model.AccentPalette
import com.zyke.message.domain.model.ColorMath
import com.zyke.message.presentation.common.FormField
import com.zyke.message.presentation.common.FormSheet
import com.zyke.message.presentation.theme.LocalTrueBlack
import com.zyke.message.presentation.theme.customColorScheme
import kotlin.math.roundToInt

private const val FALLBACK_SEED = 0xFF1A5FB4L

/**
 * Picks the app's own colour: a hue slider, a saturation/brightness pad (with two sliders as the keyboard and
 * screen-reader way to do the same), a #RRGGBB field and a live preview in the current mode. Nothing is applied until
 * Save, so closing the sheet leaves the theme as it was.
 */
@Composable
fun CustomAccentSheet(initial: Long?, onSave: (Long) -> Unit, onReset: () -> Unit, onDismiss: () -> Unit) {
    val start = ColorMath.toHsv(initial ?: FALLBACK_SEED)
    var hue by rememberSaveable { mutableFloatStateOf(start.h.toFloat()) }
    var sat by rememberSaveable { mutableFloatStateOf(start.s.toFloat()) }
    var bri by rememberSaveable { mutableFloatStateOf(start.v.toFloat()) }
    var hexText by rememberSaveable { mutableStateOf(ColorMath.formatHex(ColorMath.fromHsv(hue.toDouble(), sat.toDouble(), bri.toDouble()))) }
    val parsed = ColorMath.parseHex(hexText)

    fun fromControls() {
        hexText = ColorMath.formatHex(ColorMath.fromHsv(hue.toDouble(), sat.toDouble(), bri.toDouble()))
    }

    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val black = dark && LocalTrueBlack.current

    FormSheet(
        title = "Custom colour",
        onDismiss = onDismiss,
        primaryLabel = "Save",
        primaryEnabled = parsed != null,
        onPrimary = { parsed?.let(onSave) },
        secondaryLabel = "Reset to default",
        onSecondary = onReset,
        hint = "Enter the colour as #RRGGBB"
    ) {
        val preview = parsed ?: ColorMath.fromHsv(hue.toDouble(), sat.toDouble(), bri.toDouble())
        PreviewStrip(preview, dark, black)

        LabelledSlider("Hue", "${hue.roundToInt()} degrees", hue, 0f, 360f) { hue = it; fromControls() }
        HueTrack()

        SaturationBrightnessPad(hue, sat, bri) { s, v -> sat = s; bri = v; fromControls() }
        LabelledSlider("Saturation", "${(sat * 100).roundToInt()} percent", sat, 0f, 1f) { sat = it; fromControls() }
        LabelledSlider("Brightness", "${(bri * 100).roundToInt()} percent", bri, 0f, 1f) { bri = it; fromControls() }

        FormField(
            label = "Hex colour",
            value = hexText,
            onChange = { text ->
                hexText = text
                ColorMath.parseHex(text)?.let { argb ->
                    val hsv = ColorMath.toHsv(argb)
                    // Grey and black have no hue of their own: keep the one on screen so the slider does not jump.
                    if (hsv.s > 0.0 && hsv.v > 0.0) hue = hsv.h.toFloat()
                    sat = hsv.s.toFloat(); bri = hsv.v.toFloat()
                }
            },
            placeholder = "#1A5FB4",
            error = if (parsed == null) "Use six digits like #1A5FB4" else null
        )

        TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text("Cancel", fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun ColumnScope.LabelledSlider(label: String, valueText: String, value: Float, from: Float, to: Float, onChange: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.secondary)
            Text(valueText, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = from..to,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                contentDescription = label
                stateDescription = valueText
            }
        )
    }
}

/** The rainbow the hue slider moves along, drawn under it for reference. */
@Composable
private fun HueTrack() {
    val colors = (0..6).map { Color.hsv((it * 60f) % 360f, 1f, 1f) }
    Box(
        Modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(Brush.horizontalGradient(colors))
            .clearAndSetSemantics { }
    )
}

/** A two-way pad: across is saturation, up is brightness. Sliders below do the same without touch. */
@Composable
private fun SaturationBrightnessPad(hue: Float, sat: Float, bri: Float, onChange: (Float, Float) -> Unit) {
    val ring = MaterialTheme.colorScheme.surface
    val ringOuter = MaterialTheme.colorScheme.onSurface
    Canvas(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1.7f)
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
            .pointerInput(Unit) {
                detectTapGestures { p ->
                    onChange((p.x / size.width).coerceIn(0f, 1f), 1f - (p.y / size.height).coerceIn(0f, 1f))
                }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    onChange(
                        (change.position.x / size.width).coerceIn(0f, 1f),
                        1f - (change.position.y / size.height).coerceIn(0f, 1f)
                    )
                }
            }
            .semantics {
                contentDescription = "Saturation and brightness pad"
                stateDescription = "Saturation ${(sat * 100).roundToInt()} percent, brightness ${(bri * 100).roundToInt()} percent. Use the sliders below for precise control."
            }
    ) {
        drawRect(Brush.horizontalGradient(listOf(Color.hsv(hue, 0f, 1f), Color.hsv(hue, 1f, 1f))))
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.hsv(hue, 0f, 0f))))
        val c = Offset(sat * size.width, (1f - bri) * size.height)
        drawCircle(ringOuter, radius = 12.dp.toPx(), center = c, style = Stroke(width = 5.dp.toPx()))
        drawCircle(ring, radius = 12.dp.toPx(), center = c, style = Stroke(width = 2.dp.toPx()))
    }
}

/** A sample button, card, chip and switch in the colours this choice would give in the current mode. */
@Composable
private fun PreviewStrip(seed: Long, dark: Boolean, black: Boolean) {
    val scheme = customColorScheme(seed, dark, black)
    val adjusted = AccentPalette.forSeed(seed, dark, dark && black).adjusted
    val noticeColor = MaterialTheme.colorScheme.onSurfaceVariant
    MaterialTheme(colorScheme = scheme, typography = MaterialTheme.typography) {
        Column(
            Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = false) { contentDescription = "Preview of the colour" },
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(color = scheme.surface, shape = RoundedCornerShape(16.dp), border = androidx.compose.foundation.BorderStroke(1.dp, scheme.outlineVariant)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = {}, modifier = Modifier.heightIn(min = 48.dp)) { Text("Sample") }
                        AssistChip(onClick = {}, label = { Text("Chip") })
                        var on by remember { mutableStateOf(true) }
                        Switch(checked = on, onCheckedChange = { on = it }, modifier = Modifier.semantics { contentDescription = "Sample switch" })
                    }
                    Card(colors = CardDefaults.cardColors(containerColor = scheme.primaryContainer, contentColor = scheme.onPrimaryContainer), modifier = Modifier.fillMaxWidth()) {
                        Text("A card in your colour", Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (adjusted) {
                Text(
                    "Adjusted slightly for readability",
                    style = MaterialTheme.typography.bodySmall,
                    color = noticeColor
                )
            }
        }
    }
}
