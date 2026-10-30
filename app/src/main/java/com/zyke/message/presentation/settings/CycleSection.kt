package com.zyke.message.presentation.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.zyke.message.domain.cycle.CycleMode
import com.zyke.message.domain.dashboard.DateRange
import com.zyke.message.domain.plan.MoneyPlan
import com.zyke.message.domain.plan.SalaryPattern
import com.zyke.message.presentation.dashboard.components.SectionCard
import com.zyke.message.presentation.messages.components.Caption
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Where a "month" starts. Graphs, limits, the daily budget and the report card all follow it, so for a salaried
 * person a month can run from one salary credit to the day before the next.
 */
@Composable
internal fun CycleSection(
    plan: MoneyPlan,
    spotted: SalaryPattern?,
    current: DateRange?,
    onChange: (CycleMode, String) -> Unit
) {
    var day by rememberSaveable(plan.cycleDay) { mutableStateOf(plan.cycleDay?.toString().orEmpty()) }
    val format = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())

    SectionCard("Month starts", summary = plan.cycleMode.label) {
        Caption(current?.let { "Now: ${format.format(it.start)} \u2013 ${format.format(it.endInclusive)}" } ?: "", Modifier.padding(bottom = 10.dp))
        com.zyke.message.presentation.common.ChoiceCards(
            choices = CycleMode.entries.map { mode ->
                com.zyke.message.presentation.common.Choice(
                    mode, mode.label,
                    when (mode) {
                        CycleMode.SALARY -> {
                            val found = spotted?.typicalDay ?: plan.salaryDayIn(java.time.YearMonth.now())
                            if (found != null) "From one salary to the next, around day $found" else "Needs a salary and its day"
                        }
                        CycleMode.CALENDAR -> "The 1st to the last day of each month"
                        CycleMode.DAY -> "A day of the month that you choose"
                    }
                )
            },
            selected = plan.cycleMode,
            onSelect = { mode -> onChange(mode, day) }
        )
        if (plan.cycleMode == CycleMode.DAY) {
            com.zyke.message.presentation.common.DayOfMonthField(
                "Day of the month your months start", day,
                onChange = { picked ->
                    day = picked
                    onChange(CycleMode.DAY, day)
                },
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}
