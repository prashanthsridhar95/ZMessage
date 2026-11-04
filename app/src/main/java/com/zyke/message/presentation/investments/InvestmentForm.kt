package com.zyke.message.presentation.investments

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.zyke.message.domain.cards.CardCatalog
import com.zyke.message.domain.investments.Compounding
import com.zyke.message.domain.investments.Holding
import com.zyke.message.domain.investments.HoldingCheck
import com.zyke.message.domain.investments.InvestmentKind
import com.zyke.message.domain.investments.InvestmentMath
import com.zyke.message.domain.investments.Payout
import com.zyke.message.domain.investments.Terms
import com.zyke.message.presentation.common.ChipOption
import com.zyke.message.presentation.common.Chips
import com.zyke.message.presentation.common.DateField
import com.zyke.message.presentation.common.DayOfMonthField
import com.zyke.message.presentation.common.FormField
import com.zyke.message.presentation.common.FormGroup
import com.zyke.message.presentation.common.FormSheet
import com.zyke.message.presentation.common.PickField
import com.zyke.message.presentation.common.Text
import com.zyke.message.presentation.format.FormatCache
import com.zyke.message.presentation.home.FintechPreviewCard
import com.zyke.message.presentation.messages.components.Caption
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Locale
import java.util.UUID

private fun plain(v: BigDecimal?): String = v?.stripTrailingZeros()?.toPlainString().orEmpty()

private fun day(date: LocalDate): String = FormatCache.pattern("d MMM yyyy", Locale.getDefault()).format(date)

private val AMOUNT_CHARS = { text: String -> text.filter { it.isDigit() || it == '.' } }

/** An amount in rupees with an inline error. */
@Composable
internal fun MoneyField(label: String, value: String, onChange: (String) -> Unit, error: String? = null, supporting: String? = null) {
    FormField(
        label, value, { onChange(AMOUNT_CHARS(it)) },
        prefix = "₹", keyboardType = KeyboardType.Decimal, large = true, supporting = supporting, error = error
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, supporting: String? = null) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
            if (supporting != null) Caption(supporting, maxLines = 3)
        }
        Switch(checked, null)
    }
}

/**
 * Adds or edits an investment. The fields follow the kind: a deposit asks for its terms and shows what it will be worth, a
 * fund, share or gold holding asks what is invested and what it is worth now. [initial] null means a new one of [startKind].
 * [renewal] makes the sheet read as a renewal (the draft is a new holding).
 */
@Composable
internal fun InvestmentFormSheet(
    initial: Holding?,
    startKind: InvestmentKind,
    institutions: List<String>,
    today: LocalDate,
    onSave: (Holding) -> Unit,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)? = null,
    renewal: Boolean = false
) {
    val t0 = initial?.terms ?: Terms()
    var kindName by rememberSaveable { mutableStateOf((initial?.kind ?: startKind).name) }
    var name by rememberSaveable { mutableStateOf(initial?.name.orEmpty()) }
    var institution by rememberSaveable { mutableStateOf(initial?.institution.orEmpty()) }
    var investedText by rememberSaveable { mutableStateOf(plain(initial?.invested?.takeIf { it.signum() > 0 })) }
    var currentText by rememberSaveable { mutableStateOf(plain(initial?.currentValue)) }
    var asOfText by rememberSaveable { mutableStateOf((initial?.valueAsOf ?: today).toString()) }
    var startText by rememberSaveable {
        mutableStateOf((initial?.startDate ?: if ((initial?.kind ?: startKind).isDeposit) today else null)?.toString().orEmpty())
    }
    var notes by rememberSaveable { mutableStateOf(initial?.notes.orEmpty()) }
    var receipt by rememberSaveable { mutableStateOf(initial?.receiptNo.orEmpty()) }
    var rateText by rememberSaveable { mutableStateOf(plain(initial?.terms?.ratePercent?.takeIf { it.signum() > 0 })) }
    var compoundingName by rememberSaveable { mutableStateOf(t0.compounding.name) }
    // Tenure is entered in months or years; or the maturity date is entered and the tenure is worked out.
    val startTenure = initial?.let { InvestmentMath.tenureMonths(it) }
    var byDate by rememberSaveable { mutableStateOf(initial != null && t0.tenureMonths == null && t0.maturityDate != null) }
    var inYears by rememberSaveable { mutableStateOf(startTenure != null && startTenure % 12 == 0) }
    var tenureText by rememberSaveable {
        mutableStateOf(startTenure?.let { if (it % 12 == 0) (it / 12).toString() else it.toString() } ?: if (initial == null) "12" else "")
    }
    var maturityText by rememberSaveable { mutableStateOf(t0.maturityDate?.toString().orEmpty()) }
    var givenText by rememberSaveable { mutableStateOf(plain(t0.maturityAmountGiven)) }
    var payoutName by rememberSaveable { mutableStateOf(t0.payout.name) }
    var leads by rememberSaveable { mutableStateOf(t0.remindLeadDays.sortedDescending().joinToString(",")) }
    var installmentText by rememberSaveable { mutableStateOf(plain(t0.monthlyInstallment)) }
    var dayText by rememberSaveable { mutableStateOf(t0.installmentDay?.toString().orEmpty()) }
    var paidText by rememberSaveable { mutableStateOf(t0.installmentsPaidOverride?.toString().orEmpty()) }
    var rdReminder by rememberSaveable { mutableStateOf(t0.installmentReminder) }

    val kind = InvestmentKind.valueOf(kindName)
    val start = runCatching { LocalDate.parse(startText) }.getOrNull()
    val maturity = runCatching { LocalDate.parse(maturityText) }.getOrNull()
    val asOf = runCatching { LocalDate.parse(asOfText) }.getOrNull() ?: today
    val tenureMonths = tenureText.toIntOrNull()?.let { if (inYears) it * 12 else it }
    val leadSet = leads.split(',').mapNotNull { it.trim().toIntOrNull() }.toSet()

    val draft = Holding(
        id = initial?.id ?: UUID.randomUUID().toString(), kind = kind, name = name.trim(), institution = institution.trim(),
        invested = if (kind == InvestmentKind.RECURRING_DEPOSIT) BigDecimal.ZERO else investedText.toBigDecimalOrNull() ?: BigDecimal.ZERO,
        currentValue = currentText.toBigDecimalOrNull(), valueAsOf = if (currentText.isBlank()) null else asOf,
        startDate = start, notes = notes.trim(), receiptNo = receipt.trim(),
        terms = if (!kind.isDeposit) Terms() else Terms(
            ratePercent = rateText.toBigDecimalOrNull() ?: BigDecimal.ZERO,
            compounding = Compounding.valueOf(compoundingName),
            tenureMonths = if (byDate) null else tenureMonths,
            maturityDate = if (byDate) maturity else null,
            maturityAmountGiven = givenText.toBigDecimalOrNull(),
            payout = if (kind == InvestmentKind.FIXED_DEPOSIT) Payout.valueOf(payoutName) else Payout.CUMULATIVE,
            monthlyInstallment = installmentText.toBigDecimalOrNull(),
            installmentDay = dayText.toIntOrNull(),
            installmentsPaidOverride = paidText.toIntOrNull(),
            remindLeadDays = leadSet, installmentReminder = rdReminder
        ),
        status = initial?.status ?: com.zyke.message.domain.investments.HoldingStatus.ACTIVE,
        closedOn = initial?.closedOn, closedAmount = initial?.closedAmount, history = initial?.history.orEmpty(), renewedFrom = initial?.renewedFrom
    )
    val errors = HoldingCheck.validate(draft, today)
    // Only what the person has started typing is called wrong; an empty field is explained by the hint instead.
    fun shown(field: String, text: String): String? = errors[field]?.takeIf { text.isNotBlank() }
    val valid = errors.isEmpty()

    FormSheet(
        title = when {
            renewal -> "Renew ${initial?.name.orEmpty()}"
            initial == null -> "Add investment"
            else -> "Edit ${initial.name}"
        },
        onDismiss = onDismiss,
        primaryLabel = when {
            renewal -> "Renew"
            initial == null -> "Add"
            else -> "Save changes"
        },
        primaryEnabled = valid,
        hint = errors.values.firstOrNull() ?: "Fill in the details",
        onPrimary = {
            var out = draft
            val entered = draft.currentValue
            if (!kind.isDeposit && entered != null) {
                val changed = initial == null || initial.currentValue != entered || initial.valueAsOf != draft.valueAsOf
                if (changed) out = InvestmentMath.withValue(draft, entered, asOf)
            }
            onSave(out)
        },
        secondaryLabel = if (onDelete != null) "Delete" else null,
        onSecondary = onDelete,
        asPage = false
    ) {
        if (initial == null || renewal) {
            FormGroup("What is it?") {
                val kinds = InvestmentKind.entries
                Chips(kinds.map { ChipOption(it.label, it == kind) }, onSelect = { kindName = kinds[it].name })
            }
        }
        FormField(
            "Name", name, { name = it }, error = shown("name", name),
            placeholder = when (kind) {
                InvestmentKind.FIXED_DEPOSIT -> "Society FD, Bank FD 2026"
                InvestmentKind.RECURRING_DEPOSIT -> "Monthly RD"
                InvestmentKind.MUTUAL_FUND -> "Index fund, Parag Parikh Flexi Cap"
                InvestmentKind.STOCKS -> "Zerodha holdings"
                InvestmentKind.GOLD -> "Gold coins, Sovereign gold bond"
                else -> "What you call it"
            },
            capitalization = KeyboardCapitalization.Sentences
        )
        FormField(
            if (kind.isDeposit) "Bank or fund society" else "Institution or fund house (optional)", institution, { institution = it },
            suggestions = (institutions + CardCatalog.banks).distinct(), capitalization = KeyboardCapitalization.Words,
            placeholder = if (kind.isDeposit) "Where you put the money" else "AMC, broker or shop"
        )

        if (kind.isDeposit) {
            if (kind == InvestmentKind.RECURRING_DEPOSIT) {
                MoneyField("Monthly installment", installmentText, { installmentText = it }, shown("installment", installmentText))
                DayOfMonthField("Installment day of the month", dayText, { dayText = it }, supporting = "Leave unset to use the start date's day")
            } else {
                MoneyField("Amount invested", investedText, { investedText = it }, shown("invested", investedText))
            }
            DateField("Start date", start, { startText = it?.toString().orEmpty() }, presets = listOf("Today" to today), supporting = errors["startDate"])
            FormField(
                "Interest rate a year (%)", rateText, { rateText = it.filter { c -> c.isDigit() || c == '.' } }, placeholder = "7.25",
                keyboardType = KeyboardType.Decimal, error = shown("rate", rateText)
            )
            PickField(
                "Interest is added", Compounding.entries.map { it.label }, Compounding.valueOf(compoundingName).label,
                onPick = { picked -> Compounding.entries.firstOrNull { it.label == picked }?.let { compoundingName = it.name } },
                supporting = "Banks usually compound every quarter"
            )
            FormGroup("How long?") {
                Chips(listOf(ChipOption("Tenure", !byDate), ChipOption("Maturity date", byDate)), onSelect = { byDate = it == 1 })
                if (byDate) {
                    DateField("Maturity date", maturity, { maturityText = it?.toString().orEmpty() }, supporting = errors["maturity"] ?: errors["tenure"])
                } else {
                    Chips(listOf(ChipOption("Months", !inYears), ChipOption("Years", inYears)), onSelect = { inYears = it == 1 })
                    FormField(
                        if (inYears) "Tenure (years)" else "Tenure (months)", tenureText, { tenureText = it.filter { c -> c.isDigit() } },
                        keyboardType = KeyboardType.Number, error = shown("tenure", tenureText)
                    )
                }
            }
            MoneyField(
                "Maturity amount as per receipt (optional)", givenText, { givenText = it }, shown("maturityGiven", givenText),
                supporting = "Fund societies often have their own terms: enter the amount from your receipt to use it"
            )
            if (kind == InvestmentKind.FIXED_DEPOSIT) {
                FormGroup("Interest") {
                    Chips(
                        Payout.entries.map { ChipOption(it.label, it.name == payoutName) },
                        onSelect = { payoutName = Payout.entries[it].name }
                    )
                }
            }
            FormGroup("Remind me before it matures") {
                val options = Terms.LEAD_OPTIONS
                Chips(
                    options.map { ChipOption(if (it == 0) "On the day" else "$it days before", it in leadSet) },
                    onSelect = { i ->
                        val lead = options[i]
                        leads = (if (lead in leadSet) leadSet - lead else leadSet + lead).sortedDescending().joinToString(",")
                    }
                )
                Caption("A reminder at 9:00 on each of these days", maxLines = 2)
            }
            if (kind == InvestmentKind.RECURRING_DEPOSIT) {
                SwitchRow("Remind me of each installment", rdReminder, { rdReminder = it }, "Two days before the installment day")
                FormField(
                    "Installments already paid (optional)", paidText, { paidText = it.filter { c -> c.isDigit() } },
                    keyboardType = KeyboardType.Number, supporting = "Counted from the start date unless you enter it"
                )
            }
            DepositPreview(draft, today)
        } else {
            MoneyField("Amount invested", investedText, { investedText = it }, shown("invested", investedText))
            MoneyField("Current value", currentText, { currentText = it }, shown("currentValue", currentText), supporting = "What it is worth today")
            DateField("Value as of", asOf, { asOfText = (it ?: today).toString() }, presets = listOf("Today" to today))
            DateField(
                "Started on (optional)", start, { startText = it?.toString().orEmpty() }, clearable = true,
                supporting = "With a date, the yearly growth rate is shown"
            )
        }
        FormField("Receipt or certificate number (optional)", receipt, { receipt = it }, capitalization = KeyboardCapitalization.Characters)
        FormField(
            "Notes (optional)", notes, { notes = it }, capitalization = KeyboardCapitalization.Sentences, singleLine = false,
            placeholder = if (kind.isDeposit) "Nominee, folio, branch" else "Units, NAV, folio"
        )
    }
}

/** "Matures on 12 Mar 2027 · ₹1,07,186 · interest ₹7,186 · 128 days to go", live as the terms are typed. */
@Composable
private fun DepositPreview(h: Holding, today: LocalDate) {
    val maturity = InvestmentMath.maturityDate(h)
    val value = InvestmentMath.maturityValue(h)
    if (maturity == null) return
    val days = InvestmentMath.daysToMaturity(h, today) ?: 0
    val interest = InvestmentMath.interestAtMaturity(h)
    val given = h.terms.maturityAmountGiven != null && h.terms.payout == Payout.CUMULATIVE || (h.kind == InvestmentKind.RECURRING_DEPOSIT && h.terms.maturityAmountGiven != null)
    val text = buildString {
        append("Matures on ${day(maturity)}")
        if (h.kind == InvestmentKind.RECURRING_DEPOSIT) {
            if (value != null) append(" · maturity ${money(value)}")
            InvestmentMath.totalDeposit(h)?.let { append(" · total deposited ${money(it)}") }
        } else {
            if (value != null) append(" · ${money(value)}")
        }
        if (interest != null && interest.signum() > 0) append(" · interest ${money(interest)}")
        append(
            when {
                days > 1 -> " · $days days to go"
                days == 1L -> " · tomorrow"
                days == 0L -> " · today"
                else -> " · ${-days} days ago"
            }
        )
    }
    FintechPreviewCard(text, if (given) "From your receipt" else "Estimated from the rate and tenure")
}
