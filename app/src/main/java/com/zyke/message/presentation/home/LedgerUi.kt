package com.zyke.message.presentation.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.zyke.message.domain.ledger.ManualEntry
import com.zyke.message.domain.ledger.ManualKind
import com.zyke.message.domain.ledger.ReviewQueue
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.presentation.common.AmountField
import com.zyke.message.presentation.common.AppButton
import com.zyke.message.presentation.common.AppPage
import com.zyke.message.presentation.common.ButtonSize
import com.zyke.message.presentation.common.ButtonStyle
import com.zyke.message.presentation.common.Choice
import com.zyke.message.presentation.common.DateField
import com.zyke.message.presentation.common.FintechCard
import com.zyke.message.presentation.common.FormField
import com.zyke.message.presentation.common.FormSheet
import com.zyke.message.presentation.common.PickField
import com.zyke.message.presentation.common.SegmentedChoice
import com.zyke.message.presentation.format.DisplayFormatters
import com.zyke.message.presentation.messages.components.Caption
import com.zyke.message.presentation.messages.components.RowTitle
import java.time.LocalDate

private val SPEND_SUGGESTIONS = listOf("Cash spend", "Auto", "Tea and snacks", "Vegetables", "Tip", "Parking", "Pocket money")
private val INCOME_SUGGESTIONS = listOf("Cash received", "Freelance", "Rent received", "Gift", "Refund")
private val TRANSFER_SUGGESTIONS = listOf("Cash withdrawal", "To savings", "To my other account", "Cash deposit")

/**
 * Money the person records by hand: cash spent, a payment that sent no message, income received another way, or a
 * transfer between their own accounts. It is then read like any other payment: in lists, totals, limits and the calendar.
 */
@Composable
internal fun ManualEntrySheet(today: LocalDate, onSave: (ManualEntry) -> Unit, onDismiss: () -> Unit) {
    var kind by rememberSaveable { mutableStateOf(ManualKind.EXPENSE) }
    var amount by rememberSaveable { mutableStateOf("") }
    var title by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    var dateText by rememberSaveable { mutableStateOf(today.toString()) }
    var categoryName by rememberSaveable { mutableStateOf<String?>(null) }
    var account by rememberSaveable { mutableStateOf("Cash") }
    val value = amount.toBigDecimalOrNull()
    val date = runCatching { LocalDate.parse(dateText) }.getOrDefault(today)
    val categories = MessageCategory.assignable
    val chosen = categories.firstOrNull { it.name == categoryName }

    FormSheet(
        title = "Add an entry",
        subtitle = "Money that did not come in a message",
        onDismiss = onDismiss,
        primaryLabel = "Save entry",
        primaryEnabled = value != null && value.signum() > 0 && title.isNotBlank(),
        hint = if (value == null || value.signum() <= 0) "Enter the amount" else "Say what it was for",
        onPrimary = {
            onSave(
                ManualEntry(
                    id = 0, kind = kind, amount = value!!, title = title.trim(),
                    category = if (kind == ManualKind.TRANSFER) MessageCategory.OTHERS else chosen ?: MessageCategory.OTHERS,
                    date = date, note = note.trim(), account = account
                )
            )
        }
    ) {
        SegmentedChoice(ManualKind.entries.map { Choice(it, it.label) }, kind, { kind = it })
        AmountField("Amount", amount, { amount = it }, quickAdds = listOf(100, 500, 1_000))
        FormField(
            label = when (kind) {
                ManualKind.EXPENSE -> "Paid to, or for"
                ManualKind.INCOME -> "Received from"
                ManualKind.TRANSFER -> "What moved"
            },
            value = title, onChange = { title = it },
            placeholder = when (kind) {
                ManualKind.EXPENSE -> "e.g. Auto, vegetables"
                ManualKind.INCOME -> "e.g. Freelance work"
                ManualKind.TRANSFER -> "e.g. Cash withdrawal"
            },
            suggestions = when (kind) {
                ManualKind.EXPENSE -> SPEND_SUGGESTIONS
                ManualKind.INCOME -> INCOME_SUGGESTIONS
                ManualKind.TRANSFER -> TRANSFER_SUGGESTIONS
            },
            capitalization = KeyboardCapitalization.Sentences
        )
        DateField("Date", date, { dateText = (it ?: date).toString() }, presets = listOf("Today" to today, "Yesterday" to today.minusDays(1)))
        if (kind != ManualKind.TRANSFER) {
            PickField(
                "Category (optional)", categories.map { it.displayString }, chosen?.displayString,
                onPick = { picked -> categoryName = categories.firstOrNull { it.displayString == picked }?.name },
                placeholder = "Choose a category"
            )
        }
        PickField("Account", listOf("Cash", "Bank account", "Card", "UPI"), account, onPick = { account = it })
        FormField(label = "Note (optional)", value = note, onChange = { note = it }, placeholder = "Anything to remember it by")
        if (kind == ManualKind.TRANSFER) {
            Caption("A transfer between your own accounts is not counted as income or spending.", maxLines = 3)
        }
    }
}

/** One card on Home: how many things the app would like the person to look at. */
@Composable
internal fun ReviewCard(count: Int, onOpen: () -> Unit) {
    FintechCard(onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Caption("NEEDS REVIEW")
                Text(
                    DisplayFormatters.count(count, "thing", "things") + " to look at",
                    Modifier.padding(top = 4.dp), style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary
                )
                Caption("Possible transfers, messages not understood, payments with no category", maxLines = 2)
            }
            Text("›", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.secondary)
        }
    }
}

/** What the app is unsure of, each with the one thing to do about it. Nothing here is decided until the person says so. */
@Composable
internal fun ReviewPage(
    review: ReviewQueue,
    onLink: (debitId: Long, creditId: Long) -> Unit,
    onNotTransfer: (String) -> Unit,
    onOpen: (Long) -> Unit,
    onBack: () -> Unit
) {
    AppPage("Needs review", onBack) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (review.isEmpty) {
                com.zyke.message.presentation.common.EmptyState("All clear", "Nothing needs a look right now. Anything the app is unsure of will appear here.")
            }
            if (review.transfers.isNotEmpty()) {
                SectionHeader("Possible transfers", review.transfers.size)
                review.transfers.forEach { candidate ->
                    val debit = candidate.debit.content as MessageContent.Transaction
                    val credit = candidate.credit.content as MessageContent.Transaction
                    FintechCard {
                        Text(
                            DisplayFormatters.rupees(candidate.amount), style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary
                        )
                        Caption(
                            "Left ${debit.bankName.displayString} ••${debit.accountNumber.takeLast(4)} and arrived in ${credit.bankName.displayString} ••${credit.accountNumber.takeLast(4)}",
                            Modifier.padding(top = 2.dp), maxLines = 3
                        )
                        Caption("If it is your own money moving, it is not income or spending.", Modifier.padding(top = 2.dp), maxLines = 2)
                        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AppButton("It is a transfer", { onLink(candidate.debit.id, candidate.credit.id) }, Modifier.weight(1f), size = ButtonSize.COMPACT)
                            AppButton("Not a transfer", { onNotTransfer(candidate.key) }, Modifier.weight(1f), style = ButtonStyle.OUTLINE, size = ButtonSize.COMPACT)
                        }
                    }
                }
            }
            if (review.uncategorised.isNotEmpty()) {
                SectionHeader("Payments with no category", review.uncategorised.size)
                review.uncategorised.forEach { message ->
                    val tx = message.content as MessageContent.Transaction
                    FintechCard(onClick = { onOpen(message.id) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                RowTitle(message.merchant.name)
                                Caption("File it under a category")
                            }
                            Text(DisplayFormatters.rupees(tx.amount), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
            if (review.unclear.isNotEmpty()) {
                SectionHeader("Messages not understood", review.unclear.size)
                review.unclear.forEach { message ->
                    FintechCard(onClick = { onOpen(message.id) }) {
                        RowTitle(message.merchant.name)
                        Caption(message.message.body.replace(Regex("\\s+"), " ").trim(), Modifier.padding(top = 2.dp), maxLines = 2)
                    }
                }
                Caption("Share a parsing report from Settings so these can be taught.", maxLines = 2)
            }
        }
    }
}
