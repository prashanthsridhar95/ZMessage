package com.zyke.message.presentation.dashboard.components

import com.zyke.message.presentation.common.CardHeader
import com.zyke.message.presentation.theme.Spacing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.zyke.message.domain.plan.RunwayStatus
import com.zyke.message.domain.plan.SalaryRunway
import com.zyke.message.domain.plan.SalaryRunways
import com.zyke.message.presentation.common.FintechCard
import com.zyke.message.presentation.format.FormatCache
import com.zyke.message.presentation.messages.components.Caption
import java.time.LocalDate
import java.util.Locale

/** "the 1st", "the 22nd", "the 13th". */
internal fun dayWords(date: LocalDate): String {
    val n = date.dayOfMonth
    val suffix = when {
        n in 11..13 -> "th"
        n % 10 == 1 -> "st"
        n % 10 == 2 -> "nd"
        n % 10 == 3 -> "rd"
        else -> "th"
    }
    return "the $n$suffix"
}

/** The strip on a card of its own, for when there is no forecast to put it in. */
@Composable
internal fun SalaryStripCard(runway: SalaryRunway, modifier: Modifier = Modifier) {
    FintechCard(modifier) {
        CardHeader("Your plan's money")
        SalaryStrip(runway, Modifier.padding(top = Spacing.sm))
    }
}

/**
 * The pay cycle as a row of small day cells: days gone are filled, today is outlined, the day the plan's money is
 * projected to run out is in the error colour, and the payday is in the primary colour. Under it, the sentence.
 */
@Composable
internal fun SalaryStrip(runway: SalaryRunway, modifier: Modifier = Modifier) {
    val caption = remember(runway) { SalaryRunways.caption(runway, ::dayWords) }
    val days = remember(runway) { runway.days }
    val dayFormat = FormatCache.pattern("d MMM", Locale.getDefault())
    val scheme = MaterialTheme.colorScheme
    val gone = scheme.onSurface.copy(alpha = 0.38f)
    val covered = scheme.onSurface.copy(alpha = 0.14f)
    val short = scheme.error.copy(alpha = 0.22f)
    // A local copy: a property of another module cannot be smart-cast.
    val runsOut = runway.runsOutOn

    Column(modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = caption }) {
        Canvas(Modifier.fillMaxWidth().height(22.dp).clearAndSetSemantics { }) {
            val gap = 2.dp.toPx()
            val cell = ((size.width - gap * (days.size - 1)) / days.size).coerceAtLeast(1f)
            val radius = CornerRadius(cell / 3f)
            days.forEachIndexed { index, day ->
                val x = index * (cell + gap)
                val isToday = day == runway.today
                val isRunOut = runsOut != null && day == runsOut
                val isPayday = day == runway.payday
                val color = when {
                    isPayday -> scheme.primary
                    isRunOut -> scheme.error
                    day.isBefore(runway.today) -> gone
                    runsOut != null && day.isAfter(runsOut) -> short
                    else -> covered
                }
                // The marked days stand a little taller than the rest.
                val tall = isToday || isRunOut || isPayday
                val top = if (tall) 0f else size.height * 0.2f
                val height = size.height - top
                drawRoundRect(color, Offset(x, top), Size(cell, height), radius)
                if (isToday) {
                    drawRoundRect(scheme.primary, Offset(x, 0f), Size(cell, size.height), radius, style = Stroke(1.5.dp.toPx()))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Caption(dayFormat.format(runway.cycleStart))
            if (runway.status == RunwayStatus.RUNS_OUT_EARLY && runsOut != null) {
                Caption("Runs out ${dayFormat.format(runsOut)}", color = scheme.error)
            }
            Caption(dayFormat.format(runway.payday))
        }
        Caption(caption, Modifier.padding(top = 4.dp), maxLines = 3)
    }
}
