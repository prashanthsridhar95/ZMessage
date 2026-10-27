package com.zyke.message.presentation.home

import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zyke.message.domain.life.Journey
import com.zyke.message.domain.life.Journeys
import com.zyke.message.domain.life.TravelMode
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.presentation.common.AppBottomSheet
import com.zyke.message.presentation.common.AppButton
import com.zyke.message.presentation.common.ButtonSize
import com.zyke.message.presentation.common.ButtonStyle
import com.zyke.message.presentation.common.addToCalendar
import com.zyke.message.presentation.common.heading
import com.zyke.message.presentation.common.rememberReduceMotion
import com.zyke.message.presentation.messages.components.Caption
import com.zyke.message.presentation.messages.components.RowTitle
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.pow
import kotlinx.coroutines.launch

private const val LEAD_DAYS = 7L
private const val MS_PER_MINUTE = 60_000L
private const val MINUTES_PER_HOUR = 60L
private const val HOURS_PER_DAY = 24L

/**
 * A trip as a small living scene: a train, a plane, a bus or a cab travels along its way as departure draws near.
 * Drag across the scene to see the journey at any point; tap the vehicle and it hops. Below it, how long is left and
 * what the message says about the trip.
 */
@Composable
internal fun JourneySheet(message: ClassifiedMessage, onOpenMessage: () -> Unit, onDismiss: () -> Unit) {
    val life = message.content as? MessageContent.Life ?: return
    val journey = remember(message.id) { Journeys.of(message.message.body, message.message.sender) }
    val zone = ZoneId.systemDefault()
    val now = rememberNowMillis()
    val departure = life.date?.atTime(life.time ?: LocalTime.of(DEFAULT_HOUR, 0))?.atZone(zone)?.toInstant()?.toEpochMilli()
    val start = departure?.minus(LEAD_DAYS * HOURS_PER_DAY * MINUTES_PER_HOUR * MS_PER_MINUTE)
    val real = if (departure != null && start != null) ((now - start).toFloat() / (departure - start)).coerceIn(0f, 1f) else 0.5f
    var scrub by remember { mutableStateOf<Float?>(null) }
    val shown by animateFloatAsState(scrub ?: real, spring(stiffness = Spring.StiffnessLow), label = "journey")
    val context = LocalContext.current

    AppBottomSheet(onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column {
                Caption(journey.mode.label.uppercase())
                Text(
                    journey.route ?: life.title,
                    Modifier.heading(),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                if (journey.route != null) Caption(life.title)
            }

            JourneyScene(journey.mode, shown, onScrub = { scrub = it }, Modifier.fillMaxWidth().height(SCENE_HEIGHT))
            Caption("Drag to scrub · tap to hop")

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val dateText = life.date?.let { date ->
                    DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault()).format(date) +
                        (life.time?.let { " · " + DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()).format(it) } ?: "")
                }
                dateText?.let { RowTitle(it) }
                Caption(countdown(departure, now, life.status))
                life.reference?.let { reference ->
                    SelectionContainer { Caption("Reference $reference") }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (life.date != null && !life.isDone) {
                    AppButton(
                        "Add to calendar",
                        {
                            if (!addToCalendar(context, life)) Toast.makeText(context, "No calendar app found", Toast.LENGTH_SHORT).show()
                        },
                        style = ButtonStyle.TONAL, size = ButtonSize.COMPACT
                    )
                }
                AppButton("Open message", onOpenMessage, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT)
            }
        }
    }
}

private const val DEFAULT_HOUR = 9
private val SCENE_HEIGHT = 190.dp

private fun countdown(departure: Long?, now: Long, status: String): String {
    if (departure == null) return status
    val left = departure - now
    if (left <= 0) return "$status · under way or done"
    val minutes = left / MS_PER_MINUTE
    val days = minutes / (MINUTES_PER_HOUR * HOURS_PER_DAY)
    val hours = (minutes / MINUTES_PER_HOUR) % HOURS_PER_DAY
    val mins = minutes % MINUTES_PER_HOUR
    val parts = buildList {
        if (days > 0) add("${days}d")
        if (days > 0 || hours > 0) add("${hours}h")
        add("${mins}m")
    }
    return "$status · leaves in ${parts.joinToString(" ")}"
}

/** The scene itself; [progress] is how far along the way the vehicle is, 0 to 1. */
@Composable
private fun JourneyScene(mode: TravelMode, progress: Float, onScrub: (Float?) -> Unit, modifier: Modifier = Modifier) {
    val reduceMotion = rememberReduceMotion()
    val transition = rememberInfiniteTransition(label = "scene")
    val loop by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(LOOP_MS, easing = LinearEasing), RepeatMode.Restart), label = "loop"
    )
    val t = if (reduceMotion) 0f else loop
    val hop = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    val ink = MaterialTheme.colorScheme.primary
    val surface = MaterialTheme.colorScheme.surface
    val soft = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    val faint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)

    Canvas(
        modifier
            .clip(RoundedCornerShape(24.dp))
            .semantics { contentDescription = "${mode.label} on its way, ${(progress * 100).toInt()} percent of the way to departure" }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { onScrub((it.x / size.width).coerceIn(0f, 1f)) },
                    onDragEnd = { onScrub(null) },
                    onDragCancel = { onScrub(null) }
                ) { change, _ ->
                    change.consume()
                    onScrub((change.position.x / size.width).coerceIn(0f, 1f))
                }
            }
            .pointerInput(Unit) {
                detectTapGestures {
                    scope.launch {
                        hop.animateTo(1f, tween(HOP_UP_MS))
                        hop.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
                    }
                }
            }
    ) {
        val u = 1.dp.toPx()
        val w = size.width
        val h = size.height
        val ground = h * GROUND_AT
        val lift = hop.value * HOP_HEIGHT * u

        drawRect(Brush.verticalGradient(listOf(ink.copy(alpha = 0.14f), ink.copy(alpha = 0.02f))))
        clouds(t, w, h, u, soft)

        when (mode) {
            TravelMode.TRAIN -> train(progress, t, w, ground, u, lift, ink, surface, soft)
            TravelMode.FLIGHT -> plane(progress, t, w, h, ground, u, lift, ink, soft)
            TravelMode.BUS -> road(progress, t, w, h, ground, u, lift, ink, surface, soft, wide = true)
            TravelMode.CAB, TravelMode.OTHER -> road(progress, t, w, h, ground, u, lift, ink, surface, soft, wide = false)
            TravelMode.HOTEL -> hotel(progress, t, w, h, ground, u, ink, surface, faint)
        }
    }
}

private const val LOOP_MS = 1600
private const val HOP_UP_MS = 90
private const val HOP_HEIGHT = 14f
private const val GROUND_AT = 0.78f

private fun DrawScope.clouds(t: Float, w: Float, h: Float, u: Float, color: Color) {
    repeat(3) { i ->
        val speed = 0.05f + i * 0.03f
        val x = (((i * 0.37f) + 1f - t * speed) % 1f) * (w + 120 * u) - 60 * u
        val y = h * (0.16f + i * 0.11f)
        val s = (1f - i * 0.18f) * u
        drawCircle(color, 11 * s, Offset(x, y))
        drawCircle(color, 15 * s, Offset(x + 14 * s, y - 5 * s))
        drawCircle(color, 11 * s, Offset(x + 28 * s, y))
    }
}

private fun DrawScope.wheel(center: Offset, radius: Float, spin: Float, ink: Color, hub: Color) {
    drawCircle(ink, radius, center)
    drawCircle(hub, radius * 0.45f, center)
    rotate(spin * 360f, center) {
        drawLine(ink, Offset(center.x - radius * 0.4f, center.y), Offset(center.x + radius * 0.4f, center.y), strokeWidth = radius * 0.18f)
    }
}

// ---- train

private fun DrawScope.train(progress: Float, t: Float, w: Float, ground: Float, u: Float, lift: Float, ink: Color, sky: Color, soft: Color) {
    val trainWidth = 190 * u
    val pad = 16 * u
    val x = pad + progress * (w - 2 * pad - trainWidth)
    val top = ground - 36 * u - lift

    // The rail, and sleepers sliding back so the ground seems to rush past.
    drawLine(soft, Offset(0f, ground), Offset(w, ground), strokeWidth = 3 * u)
    var sleeper = -((t * 28f) % 14f) * u
    while (sleeper < w) {
        drawRect(soft, Offset(sleeper, ground + 3 * u), Size(6 * u, 4 * u))
        sleeper += 14 * u
    }

    translate(x, top) {
        // smoke
        repeat(3) { k ->
            val p = (t + k / 3f) % 1f
            drawCircle(ink.copy(alpha = (1f - p) * 0.28f), (3f + p * 7f) * u, Offset(10 * u - p * 24 * u, -p * 28 * u))
        }
        // engine
        drawRoundRect(ink, Offset(0f, 8 * u), Size(62 * u, 26 * u), CornerRadius(7 * u))
        drawRect(ink, Offset(6 * u, 0f), Size(8 * u, 10 * u))
        drawRoundRect(sky, Offset(42 * u, 13 * u), Size(14 * u, 10 * u), CornerRadius(2 * u))
        // coaches
        repeat(2) { c ->
            val left = 66 * u + c * 58 * u
            drawRoundRect(ink.copy(alpha = 0.88f), Offset(left, 10 * u), Size(54 * u, 24 * u), CornerRadius(6 * u))
            repeat(4) { win -> drawRoundRect(sky, Offset(left + 6 * u + win * 12 * u, 15 * u), Size(8 * u, 8 * u), CornerRadius(2 * u)) }
            drawLine(ink, Offset(left - 4 * u, 30 * u), Offset(left, 30 * u), strokeWidth = 2 * u)
        }
        listOf(14f, 40f, 82f, 108f, 140f, 166f).forEach { wx -> wheel(Offset(wx * u, 36 * u), 5 * u, t, ink, sky) }
    }
}

// ---- plane

private fun DrawScope.plane(progress: Float, t: Float, w: Float, h: Float, ground: Float, u: Float, lift: Float, ink: Color, soft: Color) {
    val p0 = Offset(24 * u, ground - 6 * u)
    val p1 = Offset(w / 2, -h * 0.18f)
    val p2 = Offset(w - 24 * u, ground - 6 * u)
    fun at(s: Float) = Offset(
        (1 - s).pow(2) * p0.x + 2 * (1 - s) * s * p1.x + s * s * p2.x,
        (1 - s).pow(2) * p0.y + 2 * (1 - s) * s * p1.y + s * s * p2.y
    )

    // the way ahead, dotted
    for (i in 0..TRAJECTORY_DOTS) {
        val s = i / TRAJECTORY_DOTS.toFloat()
        drawCircle(if (s <= progress) ink.copy(alpha = 0.35f) else soft, 2 * u, at(s))
    }
    drawCircle(ink.copy(alpha = 0.5f), 4 * u, p0)
    drawCircle(soft, 4 * u, p2)
    drawLine(soft, Offset(0f, ground), Offset(w, ground), strokeWidth = 2 * u)

    val s = progress.coerceIn(0.001f, 0.999f)
    val position = at(s).let { Offset(it.x, it.y - lift) }
    val tangent = Offset(
        2 * (1 - s) * (p1.x - p0.x) + 2 * s * (p2.x - p1.x),
        2 * (1 - s) * (p1.y - p0.y) + 2 * s * (p2.y - p1.y)
    )
    val angle = Math.toDegrees(atan2(tangent.y, tangent.x).toDouble()).toFloat()
    val bob = kotlin.math.sin(t * 2 * Math.PI.toFloat()) * 1.5f * u

    translate(position.x, position.y + bob) {
        rotate(angle, Offset.Zero) {
            drawRoundRect(ink, Offset(-18 * u, -4 * u), Size(36 * u, 8 * u), CornerRadius(4 * u))
            val wing = Path().apply {
                moveTo(-2 * u, 0f); lineTo(-12 * u, -15 * u); lineTo(-6 * u, -15 * u); lineTo(7 * u, 0f); close()
            }
            val wingLow = Path().apply {
                moveTo(-2 * u, 0f); lineTo(-12 * u, 15 * u); lineTo(-6 * u, 15 * u); lineTo(7 * u, 0f); close()
            }
            val tail = Path().apply {
                moveTo(-15 * u, -3 * u); lineTo(-20 * u, -11 * u); lineTo(-12 * u, -3 * u); close()
            }
            drawPath(wing, ink)
            drawPath(wingLow, ink.copy(alpha = 0.8f))
            drawPath(tail, ink)
            repeat(4) { drawCircle(Color.White.copy(alpha = 0.85f), 1.2f * u, Offset(2 * u + it * 5 * u, -1 * u)) }
        }
    }
}

private const val TRAJECTORY_DOTS = 28

// ---- bus and cab

private fun DrawScope.road(
    progress: Float, t: Float, w: Float, h: Float, ground: Float, u: Float, lift: Float,
    ink: Color, sky: Color, soft: Color, wide: Boolean
) {
    drawRect(soft, Offset(0f, ground), Size(w, h - ground))
    var dash = -((t * 40f) % 40f) * u
    while (dash < w) {
        drawRoundRect(sky.copy(alpha = 0.9f), Offset(dash, ground + (h - ground) * 0.45f), Size(22 * u, 3 * u), CornerRadius(2 * u))
        dash += 40 * u
    }

    val vehicleWidth = (if (wide) 100f else 66f) * u
    val pad = 16 * u
    val x = pad + progress * (w - 2 * pad - vehicleWidth)
    val bodyHeight = (if (wide) 38f else 22f) * u
    val top = ground - bodyHeight - 6 * u - lift

    translate(x, top) {
        if (wide) {
            drawRoundRect(ink, Offset.Zero, Size(vehicleWidth, bodyHeight), CornerRadius(8 * u))
            repeat(5) { drawRoundRect(sky, Offset(8 * u + it * 15 * u, 6 * u), Size(11 * u, 11 * u), CornerRadius(2 * u)) }
            drawRoundRect(sky, Offset(vehicleWidth - 16 * u, 6 * u), Size(10 * u, 24 * u), CornerRadius(2 * u))
            wheel(Offset(24 * u, bodyHeight), 6 * u, t, ink, sky)
            wheel(Offset(vehicleWidth - 26 * u, bodyHeight), 6 * u, t, ink, sky)
        } else {
            val roof = Path().apply {
                moveTo(14 * u, 6 * u); lineTo(22 * u, -8 * u); lineTo(46 * u, -8 * u); lineTo(54 * u, 6 * u); close()
            }
            drawPath(roof, ink.copy(alpha = 0.9f))
            drawRoundRect(ink, Offset(0f, 4 * u), Size(vehicleWidth, bodyHeight), CornerRadius(7 * u))
            drawRoundRect(sky, Offset(24 * u, -5 * u), Size(10 * u, 8 * u), CornerRadius(2 * u))
            drawRoundRect(sky, Offset(37 * u, -5 * u), Size(10 * u, 8 * u), CornerRadius(2 * u))
            wheel(Offset(15 * u, bodyHeight + 4 * u), 5.5f * u, t, ink, sky)
            wheel(Offset(vehicleWidth - 15 * u, bodyHeight + 4 * u), 5.5f * u, t, ink, sky)
        }
    }
}

// ---- stay

private fun DrawScope.hotel(progress: Float, t: Float, w: Float, h: Float, ground: Float, u: Float, ink: Color, sky: Color, faint: Color) {
    drawLine(faint, Offset(0f, ground), Offset(w, ground), strokeWidth = 2 * u)
    val buildingWidth = 110 * u
    val buildingHeight = ground - 18 * u
    val left = (w - buildingWidth) / 2
    drawRoundRect(ink, Offset(left, ground - buildingHeight), Size(buildingWidth, buildingHeight), CornerRadius(6 * u))
    val columns = 4
    val rows = 5
    val lit = (progress * columns * rows).toInt()
    repeat(rows) { row ->
        repeat(columns) { col ->
            val index = row * columns + col
            val on = index < lit || (index == lit && t > 0.5f)
            drawRoundRect(
                if (on) Color(0xFFFFD66B) else sky.copy(alpha = 0.35f),
                Offset(left + 12 * u + col * 24 * u, ground - buildingHeight + 14 * u + row * 24 * u),
                Size(14 * u, 14 * u), CornerRadius(2 * u)
            )
        }
    }
    drawRoundRect(sky, Offset(left + buildingWidth / 2 - 8 * u, ground - 20 * u), Size(16 * u, 20 * u), CornerRadius(3 * u))
}
