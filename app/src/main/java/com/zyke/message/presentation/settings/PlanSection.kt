package com.zyke.message.presentation.settings

import com.zyke.message.presentation.common.fullWidthClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.zyke.message.domain.plan.MoneyPlan
import com.zyke.message.domain.plan.PlanItem
import com.zyke.message.domain.plan.PlanItemKind
import com.zyke.message.domain.plan.SalaryPattern
import com.zyke.message.presentation.common.AppButton
import com.zyke.message.presentation.common.FormSheet
import com.zyke.message.presentation.common.FormField
import com.zyke.message.presentation.common.AmountField
import com.zyke.message.presentation.common.ButtonSize
import com.zyke.message.presentation.common.ButtonStyle
import com.zyke.message.presentation.common.limitColors
import com.zyke.message.presentation.dashboard.components.SectionCard
import com.zyke.message.presentation.format.DisplayFormatters
import com.zyke.message.presentation.messages.components.Caption
import com.zyke.message.presentation.messages.components.RowTitle
import java.math.BigDecimal
import java.util.UUID

private const val MAX_DAY = 31

/**
 * What the person tells the app about their month: salary, regular outflows (parents, charity), investments and
 * how much to save. Everything is approximate and optional. It stays on the phone and feeds the plan card on the
 * Money tab.
 */
@Composable
internal fun PlanSection(
    plan: MoneyPlan,
    spotted: SalaryPattern?,
    onSalary: (amount: String, day: String) -> Unit,
    onSavingsGoal: (String) -> Unit,
    onSaveItem: (PlanItem) -> Unit,
    onDeleteItem: (String) -> Unit
) {
    var editSalary by remember { mutableStateOf(false) }
    var editGoal by remember { mutableStateOf(false) }
    var editingItem by remember { mutableStateOf<PlanItem?>(null) }
    var addingKind by remember { mutableStateOf<PlanItemKind?>(null) }
    val colors = limitColors()

    SectionCard("Money plan") {
        Caption("Roughly what comes in and goes out each month")

        // Salary: the messages come first; what is entered here is the fallback.
        if (spotted != null) {
            val range = if (spotted.low.compareTo(spotted.high) != 0) {
                ", lately ${DisplayFormatters.rupees(spotted.low)} to ${DisplayFormatters.rupees(spotted.high)}"
            } else {
                ""
            }
            PlanRow(
                title = "Salary found in your messages",
                value = DisplayFormatters.rupees(spotted.typical),
                detail = "Around day ${spotted.typicalDay}$range. Used instead of the amount below.",
                onClick = { editSalary = true }
            )
        }
        PlanRow(
            title = if (spotted != null) "Salary to use if none is found" else "Monthly salary",
            value = plan.salary?.let { DisplayFormatters.rupees(it) } ?: "Not set",
            detail = plan.salaryDay?.let { "Usually arrives on day $it" },
            onClick = { editSalary = true }
        )

        // Regular outflows and investments
        listOf(PlanItemKind.OUTFLOW to plan.outflows, PlanItemKind.INVESTMENT to plan.investments).forEach { (kind, items) ->
            HorizontalDivider(Modifier.padding(top = 12.dp))
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (kind == PlanItemKind.OUTFLOW) "Regular outflows" else "Investments",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                AppButton("Add", { addingKind = kind }, style = ButtonStyle.TONAL, size = ButtonSize.COMPACT)
            }
            if (items.isEmpty()) {
                Caption(
                    if (kind == PlanItemKind.OUTFLOW) "Parents, charity, rent, a loan" else "A SIP, a deposit, a policy",
                    Modifier.padding(top = 4.dp),
                    maxLines = 2
                )
            }
            items.forEach { item ->
                PlanRow(
                    title = item.name,
                    value = DisplayFormatters.rupees(item.amount),
                    detail = item.dayOfMonth?.let { "Around day $it" },
                    onClick = { editingItem = item }
                )
            }
        }

        // Savings
        HorizontalDivider(Modifier.padding(top = 12.dp))
        PlanRow(
            title = "Save each month",
            value = plan.savingsGoal?.let { DisplayFormatters.rupees(it) } ?: "Not set",
            detail = null,
            onClick = { editGoal = true },
            modifier = Modifier.padding(top = 4.dp)
        )

        // What is left
        val salary = spotted?.typical ?: plan.salary
        if (salary != null) {
            val left = salary -plan.outflows.sumOf { it.amount } - plan.investments.sumOf { it.amount } - (plan.savingsGoal ?: BigDecimal.ZERO)
            val fits = left.signum() >= 0
            Text(
                if (fits) {
                    "After all of this, about ${DisplayFormatters.rupees(left)} a month is yours to spend."
                } else {
                    "This plan is ${DisplayFormatters.rupees(left.negate())} more than the salary."
                },
                Modifier.padding(top = 16.dp),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (fits) colors.good else colors.bad
            )
        }
    }

    if (editSalary) {
        SalaryDialog(
            amount = plan.salary,
            day = plan.salaryDay,
            onSave = { amount, day -> onSalary(amount, day); editSalary = false },
            onDismiss = { editSalary = false }
        )
    }
    if (editGoal) {
        AmountDialog(
            title = "Save each month",
            label = "Amount to put aside",
            initial = plan.savingsGoal,
            onSave = { onSavingsGoal(it); editGoal = false },
            onDismiss = { editGoal = false }
        )
    }
    addingKind?.let { kind ->
        PlanItemDialog(
            kind = kind,
            initial = null,
            onSave = { onSaveItem(it); addingKind = null },
            onDelete = null,
            onDismiss = { addingKind = null }
        )
    }
    editingItem?.let { item ->
        PlanItemDialog(
            kind = item.kind,
            initial = item,
            onSave = { onSaveItem(it); editingItem = null },
            onDelete = { onDeleteItem(item.id); editingItem = null },
            onDismiss = { editingItem = null }
        )
    }
}

@Composable
private fun PlanRow(title: String, value: String, detail: String?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .fullWidthClickable(role = Role.Button, onClickLabel = "Edit $title", onClick = onClick)
            .padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            RowTitle(title)
            detail?.let { Caption(it) }
        }
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun SalaryDialog(amount: BigDecimal?, day: Int?, onSave: (String, String) -> Unit, onDismiss: () -> Unit) {
    var amountText by rememberSaveable { mutableStateOf(amount?.stripTrailingZeros()?.toPlainString().orEmpty()) }
    var dayText by rememberSaveable { mutableStateOf(day?.toString().orEmpty()) }
    val dayValid = dayText.isBlank() || dayText.toIntOrNull() in 1..MAX_DAY

    FormSheet(
        title = "Monthly salary",
        onDismiss = onDismiss,
        primaryLabel = "Save salary",
        primaryEnabled = dayValid,
        onPrimary = { onSave(amountText, dayText) }
    ) {
        AmountField(
            "Amount that reaches your account, roughly", amountText, { amountText = it },
            supporting = "Leave blank to clear it", quickAdds = listOf(5000, 10000, 25000)
        )
        FormField(
            label = "Day it arrives (optional)",
            value = dayText,
            onChange = { dayText = it.filter(Char::isDigit).take(2) },
            placeholder = "e.g. 1",
            supporting = if (dayValid) "Your months can start from this day" else "A day from 1 to 31",
            keyboardType = KeyboardType.Number
        )
    }
}

@Composable
private fun AmountDialog(title: String, label: String, initial: BigDecimal?, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial?.stripTrailingZeros()?.toPlainString().orEmpty()) }
    FormSheet(
        title = title,
        onDismiss = onDismiss,
        primaryLabel = "Save",
        primaryEnabled = true,
        onPrimary = { onSave(text) }
    ) {
        AmountField(label, text, { text = it }, supporting = "Leave blank to clear it", quickAdds = listOf(1000, 5000, 10000))
    }
}

@Composable
private fun PlanItemDialog(
    kind: PlanItemKind,
    initial: PlanItem?,
    onSave: (PlanItem) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit
) {
    var name by rememberSaveable { mutableStateOf(initial?.name.orEmpty()) }
    var amount by rememberSaveable { mutableStateOf(initial?.amount?.stripTrailingZeros()?.toPlainString().orEmpty()) }
    var day by rememberSaveable { mutableStateOf(initial?.dayOfMonth?.toString().orEmpty()) }
    val parsed = amount.toBigDecimalOrNull()?.takeIf { it.signum() > 0 }
    val dayValue = day.toIntOrNull()?.takeIf { it in 1..MAX_DAY }
    val dayValid = day.isBlank() || dayValue != null

    FormSheet(
        title = if (initial == null) "Add ${kind.label.lowercase()}" else kind.label,
        onDismiss = onDismiss,
        primaryLabel = "Save",
        primaryEnabled = name.isNotBlank() && parsed != null && dayValid,
        onPrimary = { onSave(PlanItem(initial?.id ?: UUID.randomUUID().toString(), kind, name.trim(), parsed!!, dayValue)) },
        secondaryLabel = if (onDelete != null) "Remove" else null,
        onSecondary = onDelete
    ) {
        FormField(
            label = "Name",
            value = name,
            onChange = { name = it.take(MAX_NAME) },
            placeholder = if (kind == PlanItemKind.OUTFLOW) "e.g. Parents, Charity" else "e.g. Index fund SIP"
        )
        AmountField("Amount each month", amount, { amount = it }, quickAdds = listOf(500, 1000, 5000))
        FormField(
            label = "Usual day of the month (optional)",
            value = day,
            onChange = { day = it.filter(Char::isDigit).take(2) },
            placeholder = "e.g. 5",
            supporting = if (dayValid) "Helps spot the payment when it happens" else "A day from 1 to 31",
            keyboardType = KeyboardType.Number
        )
    }
}

private const val MAX_NAME = 30
