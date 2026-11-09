package com.zyke.message.presentation.flights

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zyke.message.domain.flight.FlightCardModel
import com.zyke.message.domain.flight.FlightEndpoint
import com.zyke.message.domain.flight.FlightStatusKind
import com.zyke.message.domain.flight.TimeShift
import com.zyke.message.presentation.common.LocalResponsive
import com.zyke.message.presentation.common.Text
import com.zyke.message.presentation.messages.components.AirlineLogo
import com.zyke.message.presentation.messages.components.Caption
import com.zyke.message.presentation.messages.components.mutedColor
import com.zyke.message.presentation.theme.AppColors

/*
 * The pieces the Home card and the flight sheet share, so they read the same: the airline line, the route (big codes, a thin
 * progress line with the plane on it, times that line up in columns) and the status pill. Everything is drawn from a
 * FlightCardModel, which already holds the strings. No animation anywhere. Narrow (under 360 dp) or large text (1.3 and up)
 * turns the route into rows: code and time on one line, city below, the line between them.
 */

private val LINE_HEIGHT = 32.dp
private val PLANE_SIZE = 18.dp
private const val SIDE_WEIGHT = 0.31f
private const val MIDDLE_WEIGHT = 0.38f

/** Tabular digits, so times in a column line up and do not jiggle as they change. */
@Composable
private fun tabular(style: TextStyle): TextStyle = style.copy(fontFeatureSettings = "tnum")

/** The status in words, on a tint of its colour with a dot, so it never relies on colour alone. */
@Composable
internal fun StatusPill(kind: FlightStatusKind, modifier: Modifier = Modifier) {
    val color = when (kind) {
        FlightStatusKind.SCHEDULED, FlightStatusKind.CHECK_IN -> AppColors.info
        FlightStatusKind.BOARDING, FlightStatusKind.DEPARTED, FlightStatusKind.IN_AIR, FlightStatusKind.LANDED -> AppColors.success
        FlightStatusKind.DELAYED -> AppColors.warning
        FlightStatusKind.CANCELLED, FlightStatusKind.DIVERTED -> AppColors.danger
    }
    Row(
        modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.18f)).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(kind.label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, softWrap = false)
    }
}

/**
 * Logo, flight code (never cut) and airline name (cut with an ellipsis if it must be), and the status pill on the right when there
 * is room. When the screen is narrow or the text large the pill is the caller's to place under this line. [endPadding] keeps the
 * text clear of a button the caller overlays in the corner.
 */
@Composable
internal fun AirlineLine(model: FlightCardModel, logoSize: Dp, showPill: Boolean, big: Boolean, endPadding: Dp = 0.dp) {
    Row(Modifier.fillMaxWidth().padding(end = endPadding), verticalAlignment = Alignment.CenterVertically) {
        AirlineLogo(model.airlineCode, size = logoSize)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                model.flightCode, style = tabular(if (big) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleSmall),
                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, softWrap = false
            )
            Caption(model.airlineName, maxLines = 1)
        }
        if (showPill) {
            Spacer(Modifier.width(8.dp))
            StatusPill(model.kind)
        }
    }
}

/** True when the route should be rows, not columns: narrow phone or large text. */
@Composable
internal fun rememberStackRoute(): Boolean = LocalResponsive.current.stackRows

@Composable
private fun timeColor(shift: TimeShift, hasTime: Boolean): Color = when {
    !hasTime -> mutedColor()
    shift == TimeShift.LATER -> AppColors.warning
    shift == TimeShift.EARLIER -> AppColors.success
    else -> MaterialTheme.colorScheme.onSurface
}

/** A column of the route: the code, the time under it, the struck-through schedule when it moved, and the city. */
@Composable
internal fun EndpointColumn(e: FlightEndpoint, big: Boolean, reserveWas: Boolean, end: Boolean, modifier: Modifier, cityLines: Int = 1) {
    val align = if (end) Alignment.End else Alignment.Start
    val textAlign = if (end) TextAlign.End else TextAlign.Start
    Column(modifier, horizontalAlignment = align) {
        Text(
            e.code, style = if (big) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, softWrap = false, textAlign = textAlign
        )
        Text(
            e.time ?: "—", style = tabular(if (big) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium),
            fontWeight = FontWeight.SemiBold, color = timeColor(e.shift, e.time != null), maxLines = 1, softWrap = false, textAlign = textAlign
        )
        // Both columns keep this line when either has a struck time, so the cities below line up.
        if (reserveWas) {
            Text(
                e.was ?: " ", style = tabular(MaterialTheme.typography.bodySmall), color = mutedColor(),
                textDecoration = if (e.was != null) TextDecoration.LineThrough else null, maxLines = 1, softWrap = false, textAlign = textAlign
            )
        }
        if (e.city != null) Caption(e.city!!, maxLines = cityLines, textAlign = textAlign)
    }
}

/** One end of the route as a row, for the stacked form: code and time on one line, city (and the struck schedule) under. */
@Composable
internal fun EndpointRow(e: FlightEndpoint, big: Boolean, cityLines: Int = 1) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            Text(
                e.code, style = if (big) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, softWrap = false
            )
            Text(
                e.time ?: "—", style = tabular(if (big) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium),
                fontWeight = FontWeight.SemiBold, color = timeColor(e.shift, e.time != null), maxLines = 1, softWrap = false
            )
        }
        if (e.city != null || e.was != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { if (e.city != null) Caption(e.city!!, maxLines = cityLines) }
                if (e.was != null) {
                    Text(e.was!!, style = tabular(MaterialTheme.typography.bodySmall), color = mutedColor(), textDecoration = TextDecoration.LineThrough, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

/** The route: DEL ——✈—— MAA with times, in columns or (narrow, large text) rows. [big] is the sheet's larger size. */
@Composable
internal fun RouteBlock(model: FlightCardModel, big: Boolean, modifier: Modifier = Modifier, showDuration: Boolean = false) {
    if (rememberStackRoute()) {
        Column(modifier.fillMaxWidth()) {
            EndpointRow(model.origin, big)
            RouteLine(model, Modifier.fillMaxWidth().padding(vertical = 6.dp).height(28.dp))
            EndpointRow(model.destination, big)
            if (showDuration && model.duration != null) Caption("${model.duration} flight", Modifier.padding(top = 4.dp))
        }
    } else {
        val reserveWas = model.origin.was != null || model.destination.was != null
        Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            EndpointColumn(model.origin, big, reserveWas, end = false, modifier = Modifier.weight(SIDE_WEIGHT))
            Column(Modifier.weight(MIDDLE_WEIGHT).padding(horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                RouteLine(model, Modifier.fillMaxWidth().height(if (big) 40.dp else LINE_HEIGHT))
                if (showDuration && model.duration != null) Caption(model.duration!!, textAlign = TextAlign.Center)
            }
            EndpointColumn(model.destination, big, reserveWas, end = true, modifier = Modifier.weight(SIDE_WEIGHT))
        }
    }
}

/**
 * The thin line between the codes: a track, the part flown in the theme's accent, a dot at each end and the plane where the
 * flight is (parked at the start before it leaves, at the end once it has landed). Cancelled and diverted flights get no plane.
 */
@Composable
private fun RouteLine(model: FlightCardModel, modifier: Modifier) {
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f)
    val done = MaterialTheme.colorScheme.primary
    val surface = MaterialTheme.colorScheme.surface
    val progress = model.progress.coerceIn(0f, 1f)
    val dashed = model.kind == FlightStatusKind.CANCELLED || model.kind == FlightStatusKind.DIVERTED
    val description = if (dashed) "No flight progress" else "Flight progress ${(progress * 100).toInt()} percent"
    Canvas(modifier.then(Modifier.semantics { contentDescription = description })) {
        val y = size.height / 2
        val r = 4.dp.toPx()
        val startX = r + 1.dp.toPx()
        val endX = size.width - startX
        val stroke = 2.dp.toPx()
        drawLine(track, Offset(startX, y), Offset(endX, y), strokeWidth = stroke, cap = StrokeCap.Round)
        val x = startX + (endX - startX) * progress
        if (progress > 0f && !dashed) drawLine(done, Offset(startX, y), Offset(x, y), strokeWidth = stroke, cap = StrokeCap.Round)
        drawCircle(if (dashed) track else done, r, Offset(startX, y))
        if (progress >= 1f && !dashed) drawCircle(done, r, Offset(endX, y))
        else {
            drawCircle(surface, r, Offset(endX, y))
            drawCircle(track, r, Offset(endX, y), style = Stroke(1.5.dp.toPx()))
        }
        if (model.showPlane) {
            val side = PLANE_SIZE.toPx()
            val left = x - when {
                progress <= 0f -> 0f
                progress >= 1f -> side
                else -> side / 2
            }
            drawPath(planePath(left, y - side / 2, side), done)
        }
    }
}

/** A small plane pointing right, in a unit square placed at ([left], [top]) and [side] wide. */
private fun planePath(left: Float, top: Float, side: Float): Path {
    val points = listOf(
        1f to .5f, .58f to .62f, .40f to 1f, .34f to .97f, .42f to .60f, .18f to .62f, .10f to .78f, .05f to .76f,
        .10f to .52f, .05f to .28f, .10f to .26f, .18f to .42f, .42f to .40f, .34f to .03f, .40f to 0f, .58f to .38f
    )
    return Path().apply {
        points.forEachIndexed { i, (px, py) ->
            val x = left + px * side
            val y = top + py * side
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }
}
