package com.zyke.message.presentation.networth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.zyke.message.R
import com.zyke.message.ZykeMessagesApplication
import com.zyke.message.domain.accounts.AccountSummary
import com.zyke.message.domain.loans.Loan
import com.zyke.message.domain.loans.LoanKind
import com.zyke.message.domain.loans.LoanStatus
import com.zyke.message.domain.loans.Loans
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.networth.LineSource
import com.zyke.message.domain.networth.NetWorth
import com.zyke.message.domain.networth.NetWorthItem
import com.zyke.message.domain.networth.NetWorthKind
import com.zyke.message.domain.networth.NetWorthLine
import com.zyke.message.presentation.common.AmountField
import com.zyke.message.presentation.common.AppButton
import com.zyke.message.presentation.common.AppPage
import com.zyke.message.presentation.common.ButtonStyle
import com.zyke.message.presentation.common.DateField
import com.zyke.message.presentation.common.DayOfMonthField
import com.zyke.message.presentation.common.FintechCard
import com.zyke.message.presentation.common.FormField
import com.zyke.message.presentation.common.FormSheet
import com.zyke.message.presentation.common.LocalAppSnackbar
import com.zyke.message.presentation.common.PickField
import com.zyke.message.presentation.format.DisplayFormatters
import com.zyke.message.presentation.home.EmptyPlan
import com.zyke.message.presentation.home.PageSummary
import com.zyke.message.presentation.home.ProgressRing
import com.zyke.message.presentation.home.SectionHeader
import com.zyke.message.presentation.home.StatusPill
import com.zyke.message.presentation.home.Tone
import com.zyke.message.presentation.messages.components.Caption
import com.zyke.message.presentation.messages.components.RowTitle
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.launch

private fun shortDate(date: LocalDate): String = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault()).format(date)

private fun plain(value: BigDecimal?): String = value?.stripTrailingZeros()?.toPlainString().orEmpty()

private fun sourceText(line: NetWorthLine): String {
    val asOf = line.asOf?.let { ", as of ${shortDate(it)}" }.orEmpty()
    return when (line.source) {
        LineSource.MESSAGES -> "From your messages$asOf"
        LineSource.ENTERED -> "Entered by you$asOf"
        LineSource.LOANS -> "Worked out from your loan$asOf"
    }
}

/**
 * Net worth: what the person has minus what they owe. Bank balances and card dues are read from the messages; the rest
 * is typed in. Every row says where it came from and how old it is, and the page says plainly that it is a partial view.
 */
@Composable
internal fun NetWorthPage(accounts: List<AccountSummary>, messages: List<ClassifiedMessage>, onBack: () -> Unit) {
    val container = (LocalContext.current.applicationContext as ZykeMessagesApplication).container
    val items by container.netWorth.items.collectAsState()
    val loans by container.loans.loans.collectAsState()
    val today = remember { LocalDate.now() }
    val debits = remember(messages) { Loans.debitsFrom(messages, ZoneId.systemDefault()) }
    val statuses = remember(loans, debits, today) { Loans.statuses(loans, today, debits) }
    val report = remember(items, accounts, statuses, today) { NetWorth.build(items, accounts, statuses, today) }
    var showLoans by rememberSaveable { mutableStateOf(false) }

    AppPage("Net worth", onBack) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            NetWorthContent(
                report = report, loanStatuses = statuses, today = today,
                onSave = { container.netWorth.save(it) }, onRemove = { container.netWorth.remove(it) },
                onOpenLoans = { showLoans = true }, items = items
            )
        }
    }
    if (showLoans) {
        LoansPage(
            statuses = statuses, today = today,
            onSave = { container.loans.save(it) }, onRemove = { container.loans.remove(it) },
            onBack = { showLoans = false }
        )
    }
}

@Composable
private fun NetWorthContent(
    report: com.zyke.message.domain.networth.NetWorthReport,
    items: List<NetWorthItem>,
    loanStatuses: List<LoanStatus>,
    today: LocalDate,
    onSave: (NetWorthItem) -> Unit,
    onRemove: (String) -> Unit,
    onOpenLoans: () -> Unit
) {
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    val snackbar = LocalAppSnackbar.current
    val scope = rememberCoroutineScope()

    PageSummary(
        label = "Net worth",
        value = DisplayFormatters.rupees(report.netWorth),
        caption = "Assets ${DisplayFormatters.rupees(report.totalAssets)} · Liabilities ${DisplayFormatters.rupees(report.totalLiabilities)}"
    )

    FintechCard {
        Caption("A PARTIAL PICTURE")
        Text(
            "Bank balances and card dues come from your messages, and only from accounts that send them. " +
                "Add the rest yourself. Treat this as a rough guide, not a statement.",
            Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary
        )
        if (report.staleCount > 0) {
            StatusPill(
                "${DisplayFormatters.count(report.staleCount, "value is", "values are")} older than ${NetWorth.STALE_DAYS} days",
                Tone.WARN, Modifier.padding(top = 8.dp)
            )
        }
        if (report.unknownCount > 0) {
            StatusPill(
                "${DisplayFormatters.count(report.unknownCount, "row has", "rows have")} no amount yet, so not counted",
                Tone.NEUTRAL, Modifier.padding(top = 8.dp)
            )
        }
    }

    AppButton("Add an asset or debt", { adding = true }, Modifier.fillMaxWidth())
    AppButton(
        if (loanStatuses.isEmpty()) "Add a loan or EMI" else "Loans and EMIs (${loanStatuses.size})",
        onOpenLoans, Modifier.fillMaxWidth(), style = ButtonStyle.TONAL
    )

    SectionHeader("Assets", report.assets.size)
    if (report.assets.isEmpty()) Caption("Nothing yet. Add cash, deposits, funds, stocks, gold or property.", maxLines = 2)
    report.assets.forEach { line -> LineCard(line, onClick = line.itemId?.let { id -> { editing = id } }) }

    SectionHeader("Liabilities", report.liabilities.size)
    if (report.liabilities.isEmpty()) Caption("Nothing yet. Card dues and loans appear here.", maxLines = 2)
    report.liabilities.forEach { line -> LineCard(line, onClick = line.itemId?.let { id -> { editing = id } }) }

    if (adding) {
        NetWorthItemSheet(null, today, onSave = { onSave(it); adding = false }, onRemove = null, onDismiss = { adding = false })
    }
    items.firstOrNull { it.id == editing }?.let { item ->
        NetWorthItemSheet(
            item, today,
            onSave = { onSave(it); editing = null },
            onRemove = {
                onRemove(item.id)
                editing = null
                scope.launch {
                    val result = snackbar.showSnackbar("Deleted ${item.name}", actionLabel = "Undo", duration = SnackbarDuration.Long)
                    if (result == SnackbarResult.ActionPerformed) onSave(item)
                }
            },
            onDismiss = { editing = null }
        )
    }
}

@Composable
private fun LineCard(line: NetWorthLine, onClick: (() -> Unit)?) {
    FintechCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                RowTitle(line.name)
                Caption(line.kindLabel, Modifier.padding(top = 2.dp))
                Caption(sourceText(line), maxLines = 2)
                if (line.note.isNotBlank()) Caption(line.note, maxLines = 3)
                if (line.stale) StatusPill("Older than ${NetWorth.STALE_DAYS} days", Tone.WARN, Modifier.padding(top = 6.dp))
            }
            Spacer(Modifier.width(12.dp))
            Text(
                line.amount?.let { DisplayFormatters.rupees(it) } ?: "Not known",
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, maxLines = 1
            )
        }
    }
}

@Composable
private fun NetWorthItemSheet(item: NetWorthItem?, today: LocalDate, onSave: (NetWorthItem) -> Unit, onRemove: (() -> Unit)?, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(item?.name.orEmpty()) }
    var kindName by rememberSaveable { mutableStateOf((item?.kind ?: NetWorthKind.CASH).name) }
    var amountText by rememberSaveable { mutableStateOf(plain(item?.amount)) }
    var dateText by rememberSaveable { mutableStateOf((item?.asOf ?: today).toString()) }
    var note by rememberSaveable { mutableStateOf(item?.note.orEmpty()) }
    val kind = NetWorthKind.valueOf(kindName)
    val amount = amountText.toBigDecimalOrNull()
    val date = runCatching { LocalDate.parse(dateText) }.getOrNull()

    FormSheet(
        title = if (item == null) "New asset or debt" else "Edit ${item.name}",
        onDismiss = onDismiss,
        primaryLabel = if (item == null) "Add" else "Save changes",
        primaryEnabled = name.isNotBlank() && amount != null && amount.signum() >= 0 && date != null,
        hint = if (name.isBlank()) "Give it a name" else "Enter the amount",
        onPrimary = { onSave(NetWorthItem(item?.id ?: UUID.randomUUID().toString(), name.trim(), kind, amount!!, date!!, note.trim())) },
        secondaryLabel = if (onRemove != null) "Delete" else null,
        onSecondary = onRemove
    ) {
        PickField(
            "Kind", NetWorthKind.values().map { it.label }, kind.label,
            onPick = { picked -> NetWorthKind.values().firstOrNull { it.label == picked }?.let { kindName = it.name } }
        )
        FormField(
            "Name", name, { name = it }, placeholder = "Savings FD, Zerodha holdings, Gold chain",
            capitalization = KeyboardCapitalization.Sentences
        )
        AmountField(if (kind.isLiability) "Amount owed" else "Current value", amountText, { amountText = it })
        DateField(
            "As of", date, { dateText = (it ?: today).toString() },
            presets = listOf("Today" to today), supporting = "The day this amount was true"
        )
        FormField("Note (optional)", note, { note = it }, capitalization = KeyboardCapitalization.Sentences)
    }
}

// ---- loans

/** Loans the person entered, each with how far it has been repaid on schedule and whether its EMIs show in the messages. */
@Composable
private fun LoansPage(
    statuses: List<LoanStatus>,
    today: LocalDate,
    onSave: (Loan) -> Unit,
    onRemove: (String) -> Unit,
    onBack: () -> Unit
) {
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    val snackbar = LocalAppSnackbar.current
    val scope = rememberCoroutineScope()

    AppPage("Loans and EMIs", onBack) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (statuses.isEmpty()) {
                EmptyPlan(
                    icon = R.drawable.ic_wallet_24,
                    title = "Track a loan",
                    body = "Enter a home, vehicle or personal loan to see the EMI, what is left to repay and the next due date.",
                    actionLabel = "Add a loan",
                    onAction = { adding = true }
                )
            } else {
                val open = statuses.filter { !it.isClosed }
                val outstanding = open.fold(BigDecimal.ZERO) { sum, s -> sum + s.outstanding }
                val monthly = open.fold(BigDecimal.ZERO) { sum, s -> sum + s.emi }
                PageSummary(
                    label = "Still to repay (estimate)",
                    value = DisplayFormatters.rupees(outstanding),
                    caption = "${DisplayFormatters.count(open.size, "open loan", "open loans")} · ${DisplayFormatters.rupees(monthly)} in EMIs a month"
                )
                Caption("Worked out from each loan's terms, as if every EMI was paid on time. Your lender's statement is the true figure.", maxLines = 3)
                AppButton("Add a loan", { adding = true }, Modifier.fillMaxWidth(), style = ButtonStyle.TONAL)
                statuses.forEach { status -> LoanCard(status, onClick = { editing = status.loan.id }) }
            }

            if (adding) LoanSheet(null, today, onSave = { onSave(it); adding = false }, onRemove = null, onDismiss = { adding = false })
            statuses.firstOrNull { it.loan.id == editing }?.let { status ->
                LoanSheet(
                    status.loan, today,
                    onSave = { onSave(it); editing = null },
                    onRemove = {
                        val removed = status.loan
                        onRemove(removed.id)
                        editing = null
                        scope.launch {
                            val result = snackbar.showSnackbar("Deleted ${removed.name}", actionLabel = "Undo", duration = SnackbarDuration.Long)
                            if (result == SnackbarResult.ActionPerformed) onSave(removed)
                        }
                    },
                    onDismiss = { editing = null }
                )
            }
        }
    }
}

@Composable
private fun LoanCard(status: LoanStatus, onClick: () -> Unit) {
    val loan = status.loan
    FintechCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProgressRing(status.fraction, if (status.isClosed) Tone.GOOD else Tone.NEUTRAL)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                RowTitle(loan.name)
                Caption("${loan.kind.label} loan · ${DisplayFormatters.rupees(status.emi)} a month", Modifier.padding(top = 2.dp), maxLines = 2)
                Text(
                    if (status.isClosed) "Repaid on schedule" else "${DisplayFormatters.rupees(status.outstanding)} left of ${DisplayFormatters.rupees(loan.principal)}",
                    Modifier.padding(top = 2.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary
                )
            }
        }
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (status.isClosed) {
                StatusPill("Closed", Tone.GOOD)
            } else {
                status.nextDueDate?.let { StatusPill("Next EMI ${shortDate(it)}", Tone.NEUTRAL) }
            }
            when {
                status.dueCount == 0 -> Caption("No EMI due yet")
                status.seenInMessages == 0 -> StatusPill("EMIs not seen in messages", Tone.WARN)
                else -> Caption("${status.seenInMessages} of ${status.dueCount} EMIs seen in messages", maxLines = 2)
            }
        }
        status.schedule.getOrNull(status.dueCount)?.let { next ->
            Caption(
                "Next EMI is ${DisplayFormatters.rupees(next.interest)} interest and ${DisplayFormatters.rupees(next.principal)} principal, by the schedule",
                Modifier.padding(top = 8.dp), maxLines = 3
            )
        }
    }
}

@Composable
private fun LoanSheet(loan: Loan?, today: LocalDate, onSave: (Loan) -> Unit, onRemove: (() -> Unit)?, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(loan?.name.orEmpty()) }
    var kindName by rememberSaveable { mutableStateOf((loan?.kind ?: LoanKind.HOME).name) }
    var principalText by rememberSaveable { mutableStateOf(plain(loan?.principal)) }
    var rateText by rememberSaveable { mutableStateOf(plain(loan?.annualRatePercent)) }
    var monthsText by rememberSaveable { mutableStateOf(loan?.tenureMonths?.toString().orEmpty()) }
    var startText by rememberSaveable { mutableStateOf((loan?.startDate ?: today).toString()) }
    var dayText by rememberSaveable { mutableStateOf((loan?.emiDay ?: today.dayOfMonth).toString()) }
    var emiText by rememberSaveable { mutableStateOf(plain(loan?.emiOverride)) }
    val kind = LoanKind.valueOf(kindName)
    val principal = principalText.toBigDecimalOrNull()
    val rate = rateText.toBigDecimalOrNull()
    val months = monthsText.toIntOrNull()
    val start = runCatching { LocalDate.parse(startText) }.getOrNull()
    val day = dayText.toIntOrNull()
    val override = emiText.toBigDecimalOrNull()?.takeIf { it.signum() > 0 }
    val calculated = if (principal != null && rate != null && months != null && principal.signum() > 0 && months > 0) Loans.emi(principal, rate, months) else null
    val valid = name.isNotBlank() && principal != null && principal.signum() > 0 && rate != null && rate.signum() >= 0 &&
        months != null && months in 1..MAX_MONTHS && start != null && day != null && day in 1..31

    FormSheet(
        title = if (loan == null) "New loan" else "Edit ${loan.name}",
        onDismiss = onDismiss,
        primaryLabel = if (loan == null) "Add loan" else "Save changes",
        primaryEnabled = valid,
        hint = "Fill in the name, amount, rate, months, start date and EMI day",
        onPrimary = {
            onSave(Loan(loan?.id ?: UUID.randomUUID().toString(), name.trim(), kind, principal!!, rate!!, months!!, start!!, override, day!!))
        },
        secondaryLabel = if (onRemove != null) "Delete loan" else null,
        onSecondary = onRemove
    ) {
        FormField("Name", name, { name = it }, placeholder = "HDFC home loan, Car loan", capitalization = KeyboardCapitalization.Sentences)
        PickField(
            "Kind", LoanKind.values().map { it.label }, kind.label,
            onPick = { picked -> LoanKind.values().firstOrNull { it.label == picked }?.let { kindName = it.name } }
        )
        AmountField("Loan amount", principalText, { principalText = it })
        FormField("Interest rate a year (%)", rateText, { rateText = it }, placeholder = "9", keyboardType = KeyboardType.Decimal)
        FormField("Tenure (months)", monthsText, { monthsText = it.filter { c -> c.isDigit() } }, placeholder = "240", keyboardType = KeyboardType.Number)
        DateField("Loan start date", start, { startText = (it ?: today).toString() }, supporting = "The first EMI falls in the following month")
        DayOfMonthField("EMI day of the month", dayText, { dayText = it })
        AmountField(
            "EMI amount (optional)", emiText, { emiText = it },
            supporting = calculated?.let { "Calculated: ${DisplayFormatters.rupees(it)}. Fill in only if your lender's EMI differs." }
        )
    }
}

private const val MAX_MONTHS = 600
