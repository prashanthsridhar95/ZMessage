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
        Caption(
            "Choose where your month begins. Graphs, limits, the daily budget and the report card all follow it." +
                (current?.let { " Right now: ${format.format(it.start)} – ${format.format(it.endInclusive)}." } ?: ""),
            maxLines = 4
        )
        CycleMode.entries.forEach { mode ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectable(selected = plan.cycleMode == mode, role = Role.RadioButton, onClick = { onChange(mode, day) })
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = plan.cycleMode == mode, onClick = null)
                Column(Modifier.padding(start = 12.dp)) {
                    Text(mode.label)
                    if (mode == CycleMode.SALARY) {
                        val found = spotted?.typicalDay ?: plan.salaryDay
                        Caption(if (found != null) "Your salary arrives around day $found" else "Add a salary and its day to use this", maxLines = 2)
                    }
                }
            }
        }
        if (plan.cycleMode == CycleMode.DAY) {
            OutlinedTextField(
                value = day,
                onValueChange = { input ->
                    day = input.filter { it.isDigit() }.take(2)
                    onChange(CycleMode.DAY, day)
                },
                singleLine = true,
                label = { Text("Day of the month (1-31)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}
