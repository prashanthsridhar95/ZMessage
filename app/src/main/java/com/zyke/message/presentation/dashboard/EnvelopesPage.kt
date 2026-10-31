package com.zyke.message.presentation.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zyke.message.R
import com.zyke.message.ZykeMessagesApplication
import com.zyke.message.domain.cycle.CycleRule
import com.zyke.message.domain.envelopes.CarryOver
import com.zyke.message.domain.envelopes.EnvelopeLine
import com.zyke.message.domain.envelopes.Envelopes
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.presentation.common.AmountField
import com.zyke.message.presentation.common.AppButton
import com.zyke.message.presentation.common.AppPage
import com.zyke.message.presentation.common.ButtonSize
import com.zyke.message.presentation.common.ButtonStyle
import com.zyke.message.presentation.common.ChipOption
import com.zyke.message.presentation.common.Chips
import com.zyke.message.presentation.common.FintechCard
import com.zyke.message.presentation.common.FormSheet
import com.zyke.message.presentation.common.LocalAppSnackbar
import com.zyke.message.presentation.common.PickField
import com.zyke.message.presentation.dashboard.components.ShareBar
import com.zyke.message.presentation.format.DisplayFormatters
import com.zyke.message.presentation.home.Danger
import com.zyke.message.presentation.home.EmptyPlan
import com.zyke.message.presentation.home.PageSummary
import com.zyke.message.presentation.home.SectionHeader
import com.zyke.message.presentation.home.StatusPill
import com.zyke.message.presentation.home.Tone
import com.zyke.message.presentation.messages.components.Caption
import com.zyke.message.presentation.messages.components.RowTitle
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.launch

/** The Insights entry: one card that opens the Envelopes page. */
@Composable
internal fun EnvelopesEntryCard(onOpen: () -> Unit) {
    FintechCard(onClick = onOpen) {
        RowTitle("Envelopes")
        Caption("Give each category an amount at the start of the cycle, then watch what is left.", Modifier.padding(top = 2.dp), maxLines = 2)
    }
}

/** Envelope budgets: money assigned per category for this cycle, what was spent, what is left. */
@Composable
internal fun EnvelopesPage(
    messages: List<ClassifiedMessage>,
    cycle: CycleRule,
    today: LocalDate,
    spendable: BigDecimal?,
    onBack: () -> Unit
) {
    val repository = (LocalContext.current.applicationContext as ZykeMessagesApplication).container.envelopes
    val book by repository.book.collectAsState()
    val status = Envelopes.status(book, messages, cycle, today, ZoneId.systemDefault(), spendable)
    val previousStart = cycle.containing(status.cycle.start.minusDays(1)).start
    val canCopy = status.lines.isEmpty() && book.cycles[previousStart]?.assigned?.isNotEmpty() == true
    val snackbar = LocalAppSnackbar.current
    val scope = rememberCoroutineScope()
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var moving by rememberSaveable { mutableStateOf(false) }

    AppPage("Envelopes", onBack) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val unassigned = status.unassigned
            PageSummary(
                label = if (unassigned != null && unassigned.signum() < 0) "Over-assigned" else if (unassigned != null) "Not yet assigned" else "Assigned this cycle",
                value = DisplayFormatters.rupees(unassigned?.abs() ?: status.assignedTotal),
                caption = "${DisplayFormatters.rupees(status.assignedTotal)} in ${DisplayFormatters.count(status.lines.size, "envelope", "envelopes")}" +
                    if (unassigned != null) " · from ${DisplayFormatters.rupees(spendable ?: BigDecimal.ZERO)} spendable" else ""
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppButton("Add envelope", { adding = true }, Modifier.weight(1f), style = ButtonStyle.TONAL)
                if (status.lines.size >= 2) AppButton("Move money", { moving = true }, Modifier.weight(1f), style = ButtonStyle.TONAL)
            }
            if (canCopy) {
                AppButton(
                    "Use last cycle's amounts",
                    { repository.save(Envelopes.startFrom(book, status.cycle.start, previousStart)) },
                    Modifier.fillMaxWidth(), style = ButtonStyle.TONAL
                )
            }
            if (status.lines.isEmpty()) {
                EmptyPlan(
                    icon = R.drawable.ic_goal_24,
                    title = "Budget by envelope",
                    body = "Assign amounts to categories, like Food 8,000 and Fuel 3,000. Spending takes from the envelope and you see what is left.",
                    actionLabel = "Add your first envelope",
                    onAction = { adding = true }
                )
            } else {
                SectionHeader("This cycle", status.lines.size)
                status.lines.forEach { line -> EnvelopeCard(line) { editing = line.category.name } }
            }
        }
    }

    if (adding) {
        var category by rememberSaveable { mutableStateOf<String?>(null) }
        var amount by rememberSaveable { mutableStateOf("") }
        val value = amount.toBigDecimalOrNull()
        val options = MessageCategory.assignable.filter { it !in status.lines.map { l -> l.category } }
        FormSheet(
            title = "Add envelope", onDismiss = { adding = false }, primaryLabel = "Add",
            primaryEnabled = category != null && value != null && value.signum() > 0,
            onPrimary = {
                val picked = options.first { it.displayString == category }
                repository.save(Envelopes.assign(book, status.cycle.start, picked, value!!))
                adding = false
            }
        ) {
            PickField("Category", options.map { it.displayString }, category, { category = it })
            AmountField("Amount for this cycle", amount, { amount = it }, quickAdds = listOf(1_000, 5_000, 10_000))
        }
    }

    status.lines.firstOrNull { it.category.name == editing }?.let { line ->
        var amount by rememberSaveable(line.category.name) { mutableStateOf(line.assigned.stripTrailingZeros().toPlainString()) }
        var carry by rememberSaveable(line.category.name) { mutableStateOf(line.carry) }
        val value = amount.toBigDecimalOrNull()
        FormSheet(
            title = line.category.displayString, onDismiss = { editing = null }, primaryLabel = "Save",
            primaryEnabled = value != null && value.signum() > 0,
            secondaryLabel = "Remove envelope",
            onSecondary = {
                val before = book
                repository.save(Envelopes.assign(book, status.cycle.start, line.category, BigDecimal.ZERO))
                editing = null
                scope.launch {
                    val result = snackbar.showSnackbar("Removed ${line.category.displayString}", actionLabel = "Undo", duration = SnackbarDuration.Long)
                    if (result == SnackbarResult.ActionPerformed) repository.save(before)
                }
            },
            onPrimary = {
                repository.save(Envelopes.setCarry(Envelopes.assign(book, status.cycle.start, line.category, value!!), line.category, carry))
                editing = null
            }
        ) {
            AmountField("Assigned this cycle", amount, { amount = it })
            Chips(CarryOver.entries.map { ChipOption(it.label, it == carry) }, { carry = CarryOver.entries[it] })
        }
    }

    if (moving) {
        var from by rememberSaveable { mutableStateOf<String?>(null) }
        var to by rememberSaveable { mutableStateOf<String?>(null) }
        var amount by rememberSaveable { mutableStateOf("") }
        val value = amount.toBigDecimalOrNull()
        val source = status.lines.firstOrNull { it.category.displayString == from }
        val valid = source != null && to != null && to != from && value != null && value.signum() > 0 && value <= source.left
        FormSheet(
            title = "Move money", onDismiss = { moving = false }, primaryLabel = "Move",
            primaryEnabled = valid,
            hint = if (source != null) "${DisplayFormatters.rupees(source.left.max(BigDecimal.ZERO))} available to move" else null,
            onPrimary = {
                val destination = status.lines.first { it.category.displayString == to }.category
                repository.save(Envelopes.move(book, status, source!!.category, destination, value!!))
                moving = false
                scope.launch {
                    val result = snackbar.showSnackbar("Moved ${DisplayFormatters.rupees(value)}", actionLabel = "Undo", duration = SnackbarDuration.Long)
                    if (result == SnackbarResult.ActionPerformed) repository.save(Envelopes.undoMove(repository.book.value, status.cycle.start))
                }
            }
        ) {
            val names = status.lines.map { it.category.displayString }
            PickField("From", names, from, { from = it })
            PickField("To", names.filter { it != from }, to, { to = it })
            AmountField("Amount", amount, { amount = it })
        }
    }
}

@Composable
private fun EnvelopeCard(line: EnvelopeLine, onClick: () -> Unit) {
    FintechCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                RowTitle(line.category.displayString)
                Caption(
                    "${DisplayFormatters.rupees(line.spent)} of ${DisplayFormatters.rupees(line.available)}" +
                        if (line.carriedIn.signum() > 0) " (incl. ${DisplayFormatters.rupees(line.carriedIn)} carried over)" else "",
                    maxLines = 2
                )
            }
            Text(
                if (line.isOver) "-${DisplayFormatters.rupees(line.left.abs())}" else DisplayFormatters.rupees(line.left),
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                color = if (line.isOver) Danger else MaterialTheme.colorScheme.primary
            )
        }
        ShareBar(line.used.coerceIn(0f, 1f), Modifier.padding(top = 10.dp), color = if (line.isOver) Danger else MaterialTheme.colorScheme.primary)
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (line.isOver) StatusPill("Overspent", Tone.BAD) else StatusPill("On track", Tone.GOOD)
            if (line.carry == CarryOver.CARRY) StatusPill("Carries over", Tone.NEUTRAL)
            if (line.moved.signum() != 0) Caption("moved ${if (line.moved.signum() > 0) "+" else "-"}${DisplayFormatters.rupees(line.moved.abs())}")
        }
    }
}
