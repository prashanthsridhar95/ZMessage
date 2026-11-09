package com.zyke.message.presentation.trains

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zyke.message.R
import com.zyke.message.domain.train.TrainCardModel
import com.zyke.message.domain.train.TrainStatusKind
import com.zyke.message.presentation.common.Text
import com.zyke.message.presentation.flights.EndpointColumn
import com.zyke.message.presentation.flights.EndpointRow
import com.zyke.message.presentation.flights.rememberStackRoute
import com.zyke.message.presentation.messages.components.Caption
import com.zyke.message.presentation.theme.AppColors

/*
 * The pieces the Home card and the train sheet share, so they read the same as the flight card: the train line (a themed train tile
 * instead of an airline logo, the number never cut, the name cut with an ellipsis if it must be), the route (big station codes, a thin
 * progress line with a little train on it, times in columns) and the status pill. Everything is drawn from a TrainCardModel, which
 * already holds the strings. No animation. Narrow (under 360 dp) or large text turns the route into rows, as for flights; the station
 * names may take two lines.
 */

private const val SIDE_WEIGHT = 0.31f
private const val MIDDLE_WEIGHT = 0.38f
private val LINE_HEIGHT = 32.dp
private const val CITY_LINES = 2

/** The status in words, on a tint of its colour with a dot, so it never relies on colour alone. */
@Composable
internal fun TrainPill(kind: TrainStatusKind, text: String, modifier: Modifier = Modifier) {
    val color = when (kind) {
        TrainStatusKind.SCHEDULED -> AppColors.info
        TrainStatusKind.ON_TIME, TrainStatusKind.AT_STATION, TrainStatusKind.DEPARTED, TrainStatusKind.ARRIVED -> AppColors.success
        TrainStatusKind.RUNNING_LATE, TrainStatusKind.PARTIALLY_CANCELLED -> AppColors.warning
        TrainStatusKind.CANCELLED, TrainStatusKind.DIVERTED -> AppColors.danger
    }
    Row(
        modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.18f)).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, softWrap = false)
    }
}

/** The themed train glyph on a soft tile, where the flight card has an airline logo. */
@Composable
internal fun TrainTile(size: Dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.32f)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(R.drawable.baseline_train_24), contentDescription = null, modifier = Modifier.size(size * 0.6f),
            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary)
        )
    }
}

/**
 * The tile, the train number (never cut) and name (cut with an ellipsis if it must be), and the status pill on the right when there is
 * room. When the screen is narrow or the text large the pill is the caller's to place under this line. [endPadding] keeps the text clear
 * of a button the caller overlays in the corner.
 */
@Composable
internal fun TrainLine(model: TrainCardModel, tileSize: Dp, showPill: Boolean, big: Boolean, endPadding: Dp = 0.dp) {
    Row(Modifier.fillMaxWidth().padding(end = endPadding), verticalAlignment = Alignment.CenterVertically) {
        TrainTile(tileSize)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                model.number, style = (if (big) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleSmall).copy(fontFeatureSettings = "tnum"),
                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, softWrap = false
            )
            Caption(model.name ?: "Train", maxLines = 1)
        }
        if (showPill) {
            Spacer(Modifier.width(8.dp))
            TrainPill(model.kind, model.pill)
        }
    }
}

/** The route: MS ——🚆—— TPJ with times, in columns or (narrow, large text) rows. [big] is the sheet's larger size. */
@Composable
internal fun TrainRouteBlock(model: TrainCardModel, big: Boolean, modifier: Modifier = Modifier, showDuration: Boolean = false) {
    if (rememberStackRoute()) {
        Column(modifier.fillMaxWidth()) {
            EndpointRow(model.origin, big, CITY_LINES)
            TrainRouteLine(model, Modifier.fillMaxWidth().padding(vertical = 6.dp).height(28.dp))
            EndpointRow(model.destination, big, CITY_LINES)
            if (showDuration && model.duration != null) Caption("${model.duration} journey", Modifier.padding(top = 4.dp))
        }
    } else {
        val reserveWas = model.origin.was != null || model.destination.was != null
        Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            EndpointColumn(model.origin, big, reserveWas, end = false, modifier = Modifier.weight(SIDE_WEIGHT), cityLines = CITY_LINES)
            Column(Modifier.weight(MIDDLE_WEIGHT).padding(horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                TrainRouteLine(model, Modifier.fillMaxWidth().height(if (big) 40.dp else LINE_HEIGHT))
                if (showDuration && model.duration != null) Caption(model.duration!!, textAlign = TextAlign.Center)
            }
            EndpointColumn(model.destination, big, reserveWas, end = true, modifier = Modifier.weight(SIDE_WEIGHT), cityLines = CITY_LINES)
        }
    }
}

/**
 * The thin line between the codes: a track, the part travelled in the theme's accent, a dot at each end and a little train where the
 * journey is (not drawn before it starts or after it ends, where the dots say it). Cancelled and diverted get a plain track.
 */
@Composable
private fun TrainRouteLine(model: TrainCardModel, modifier: Modifier) {
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f)
    val done = MaterialTheme.colorScheme.primary
    val surface = MaterialTheme.colorScheme.surface
    val progress = model.progress.coerceIn(0f, 1f)
    val dashed = !model.showTrain
    val description = if (dashed) "No journey progress" else "Journey progress ${(progress * 100).toInt()} percent"
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
        if (!dashed && progress > 0f && progress < 1f) {
            // A small rounded carriage with two windows, centred on the line.
            val w = 18.dp.toPx()
            val h = 12.dp.toPx()
            drawRoundRect(done, Offset(x - w / 2, y - h / 2), Size(w, h), CornerRadius(4.dp.toPx()))
            val window = Size(4.dp.toPx(), 3.dp.toPx())
            drawRoundRect(surface, Offset(x - 5.dp.toPx(), y - 3.dp.toPx()), window, CornerRadius(1.dp.toPx()))
            drawRoundRect(surface, Offset(x + 1.dp.toPx(), y - 3.dp.toPx()), window, CornerRadius(1.dp.toPx()))
        }
    }
}
