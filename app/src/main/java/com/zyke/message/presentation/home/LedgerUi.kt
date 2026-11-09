package com.zyke.message.presentation.home

import androidx.compose.foundation.layout.Arrangement
import com.zyke.message.presentation.format.FormatCache
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import com.zyke.message.presentation.common.toggleRow
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import com.zyke.message.presentation.common.Text
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
import com.zyke.message.domain.ledger.OverdueBill
import com.zyke.message.domain.ledger.ReviewQueue
import com.zyke.message.domain.ledger.ReviewQueues
import com.zyke.message.domain.ledger.ReviewSelection
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
import com.zyke.message.presentation.common.fullWidthClickable
import com.zyke.message.presentation.common.FormField
import com.zyke.message.presentation.common.FormSheet
import com.zyke.message.presentation.common.PickField
import com.zyke.message.presentation.common.SegmentedChoice
import com.zyke.message.presentation.format.DisplayFormatters
import com.zyke.message.presentation.messages.components.Caption
import com.zyke.message.presentation.messages.components.RowTitle
import kotlinx.coroutines.launch
import java.time.LocalDate

private val SPEND_SUGGESTIONS = listOf("Cash spend", "Auto", "Tea and snacks", "Vegetables", "Tip", "Parking", "Pocket money")
private val INCOME_SUGGESTIONS = listOf("Cash received", "Freelance", "Rent received", "Gift", "Refund")
private val TRANSFER_SUGGESTIONS = listOf("Cash withdrawal", "To savings", "To my other account", "Cash deposit")

/**
 * Money the person records by hand: cash spent, a payment that sent no message, income received another way, or a
 * transfer between their own accounts. It is then read like any other payment: in lists, totals, limits and the calendar.
 */
@Composable
internal fun ManualEntrySheet(today: LocalDate, onSave: (ManualEntry) -> Unit, onDismiss: () -> Unit, initial: EntryRequest? = null, onAddFlight: (() -> Unit)? = null) {
    var kind by rememberSaveable { mutableStateOf(initial?.kind ?: ManualKind.EXPENSE) }
    var amount by rememberSaveable { mutableStateOf(initial?.amount?.toPlainString().orEmpty()) }
    var title by rememberSaveable { mutableStateOf(initial?.title.orEmpty()) }
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
        asPage = true,
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
        // Not money: a flight to track. Quiet, at the end of the form, so the sheet stays what it was.
        if (onAddFlight != null) AppButton("Adding a flight instead?", onAddFlight, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT)
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
                Caption("Bills, possible duplicates and transfers, large payments, messages not understood", maxLines = 2)
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
    onBack: () -> Unit,
    /** Folds the second message into the first as one payment. */
    onMerge: (primaryId: Long, duplicateId: Long) -> Unit = { _, _ -> },
    onUnmerge: (duplicateId: Long) -> Unit = {},
    /** The person says the bill was paid; [onUndoPaid] takes that back. */
    onMarkPaid: (OverdueBill) -> Unit = {},
    onUndoPaid: (OverdueBill) -> Unit = {},
    /** The person shows the app how to read a message it did not understand. */
    onTeach: (com.zyke.message.domain.teach.LearnedTemplate) -> Unit = {},
    taught: List<TaughtFormat> = emptyList(),
    onDeleteTaught: (TaughtFormat) -> Unit = {},
    /** The person still uses a subscription they had marked as cancelled. */
    onStillUsing: (com.zyke.message.domain.insights.CancelledCharge) -> Unit = {}
) {
    val snackbar = com.zyke.message.presentation.common.LocalAppSnackbar.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var teaching by androidx.compose.runtime.remember { mutableStateOf<com.zyke.message.domain.model.ClassifiedMessage?>(null) }
    teaching?.let { message ->
        TeachSheet(message, onSave = { onTeach(it); teaching = null }, onDismiss = { teaching = null })
    }
    // Picking several payments: which are picked survives rotation; leaving the page ends it.
    var picking by rememberSaveable { mutableStateOf(false) }
    var pickedIds by rememberSaveable { mutableStateOf(emptyList<Long>()) }
    var lastSection by rememberSaveable { mutableStateOf(SECTION_UNUSUAL) }
    var bulk by rememberSaveable { mutableStateOf<String?>(null) }
    val selectable = androidx.compose.runtime.remember(review) { ReviewSelection.selectable(review) }
    val selectableIds = androidx.compose.runtime.remember(selectable) { selectable.map { it.id } }
    // Payments that have left the list (handled elsewhere, or by the bulk edit) drop out of the choice.
    val picked = androidx.compose.runtime.remember(pickedIds, selectableIds) { ReviewSelection.prune(pickedIds, selectableIds) }
    androidx.compose.runtime.LaunchedEffect(picked) { if (picked != pickedIds) pickedIds = picked }
    val ledger = com.zyke.message.presentation.common.rememberLedgerActions()
    val cardBillIds = androidx.compose.runtime.remember(review) { review.cardBills.mapTo(HashSet()) { it.id } }
    // Payments to edit in bulk are the unusual and uncategorised ones; the card-bill question has its own two answers.
    val samePaymentIds = androidx.compose.runtime.remember(review) { review.samePayments.mapTo(HashSet()) { it.id } }
    val pickedMessages = androidx.compose.runtime.remember(picked, selectable, cardBillIds, samePaymentIds) { selectable.filter { it.id in picked && it.id !in cardBillIds && it.id !in samePaymentIds } }
    val pickedCardBills = androidx.compose.runtime.remember(picked, review) { review.cardBills.filter { it.id in picked } }
    val pickedSamePayments = androidx.compose.runtime.remember(picked, review) { review.samePayments.filter { it.id in picked } }
    // "Remember my answer for this sender" is on unless the person turns it off.
    var rememberSender by rememberSaveable { mutableStateOf(true) }
    // "Always treat payments to the same payee like this" is on unless the person turns it off.
    var alwaysSame by rememberSaveable { mutableStateOf(true) }
    val sectionIds = when (lastSection) {
        SECTION_UNUSUAL -> review.unusual.map { it.id }
        SECTION_CARDBILL -> review.cardBills.map { it.id }
        SECTION_SAME -> review.samePayments.map { it.id }
        else -> review.uncategorised.map { it.id }
    }
    fun endPicking() { picking = false; pickedIds = emptyList() }
    fun toggle(id: Long, section: String) {
        lastSection = section
        picking = true
        pickedIds = ReviewSelection.toggle(picked, id)
    }
    bulk?.let { modeName ->
        val mode = BulkMode.entries.firstOrNull { it.name == modeName }
        if (mode != null && pickedMessages.isNotEmpty()) {
            BulkEditSheet(
                mode, pickedMessages, ledger, scope,
                onApplied = { bulk = null; endPicking() },
                onDismiss = { bulk = null }
            )
        }
    }
    AppPage(
        "Needs review", onBack,
        footer = if (picking) {
            {
                SelectionBar(
                    count = picked.size,
                    sectionTitle = when (lastSection) {
                        SECTION_UNUSUAL -> "unusually large payments"
                        SECTION_CARDBILL -> "possible credit card bill payments"
                        SECTION_SAME -> "texts that may repeat a payment"
                        else -> "payments with no category"
                    },
                    cardBillMode = lastSection == SECTION_CARDBILL,
                    onCardBillAnswer = { isBill -> ledger.answerCardBill(pickedCardBills, isBill, alwaysSame); endPicking() },
                    cardBillCount = pickedCardBills.size,
                    samePaymentMode = lastSection == SECTION_SAME,
                    onSamePaymentAnswer = { same -> ledger.answerSamePayment(pickedSamePayments, same, rememberSender); endPicking() },
                    samePaymentCount = pickedSamePayments.size,
                    sectionFull = ReviewSelection.allPicked(picked, sectionIds),
                    onSelectSection = { pickedIds = ReviewSelection.selectAll(picked, sectionIds) },
                    onClear = { pickedIds = emptyList() },
                    onAction = { bulk = it.name },
                    onDone = { endPicking() }
                )
            }
        } else null
    ) {
        // Back leaves selection mode first, and the page only after that.
        androidx.activity.compose.BackHandler(enabled = picking) { endPicking() }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (review.isEmpty) {
                com.zyke.message.presentation.common.EmptyState("All clear", "Nothing needs a look right now. Anything the app is unsure of will appear here.")
            }
            if (review.cardBills.isNotEmpty()) {
                SectionHeader("Credit card bill payments?", review.cardBills.size)
                Caption("These stay in your spending until you say. A card bill payment is not counted: the card's swipes already were.", maxLines = 3)
                if (!picking) SelectHint { lastSection = SECTION_CARDBILL; picking = true }
                androidx.compose.foundation.layout.Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleRow(alwaysSame, androidx.compose.ui.semantics.Role.Checkbox) { alwaysSame = it },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { androidx.compose.material3.Checkbox(checked = alwaysSame, onCheckedChange = null) }
                    Text("Remember my answer for the same payee", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                }
                review.cardBills.forEach { message ->
                    val tx = message.content as MessageContent.Transaction
                    PickableCard(
                        message, picking, message.id in picked, { toggle(message.id, SECTION_CARDBILL) }, { onOpen(message.id) },
                        caption = stampOf(message) + " \u00B7 " + (message.cardBill?.reason ?: "May have paid a credit card bill"),
                        amount = DisplayFormatters.rupees(tx.amount)
                    ) {
                        com.zyke.message.presentation.common.ResponsiveRow(Modifier.fillMaxWidth().padding(top = 10.dp), spacing = 8.dp) {
                            AppButton("Yes, card bill", { ledger.answerCardBill(listOf(message), true, alwaysSame) }, Modifier.cell(), size = ButtonSize.COMPACT)
                            AppButton("No, spending", { ledger.answerCardBill(listOf(message), false, alwaysSame) }, Modifier.cell(), style = ButtonStyle.OUTLINE, size = ButtonSize.COMPACT)
                        }
                    }
                }
            }
            if (review.samePayments.isNotEmpty()) {
                SectionHeader("Same payment?", review.samePayments.size)
                Caption("One payment often sends several texts. Both are counted until you say, so one payment is not counted twice.", maxLines = 3)
                if (!picking) SelectHint { lastSection = SECTION_SAME; picking = true }
                androidx.compose.foundation.layout.Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleRow(rememberSender, androidx.compose.ui.semantics.Role.Checkbox) { rememberSender = it },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { androidx.compose.material3.Checkbox(checked = rememberSender, onCheckedChange = null) }
                    Text("Remember my answer for this sender", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                }
                review.samePayments.forEach { message ->
                    val tx = message.content as MessageContent.Transaction
                    val link = message.samePayment
                    PickableCard(
                        message, picking, message.id in picked, { toggle(message.id, SECTION_SAME) }, { onOpen(message.id) },
                        caption = stampOf(message) + " \u00B7 " + (link?.reason ?: "May repeat another payment"),
                        amount = DisplayFormatters.rupees(tx.amount)
                    ) {
                        com.zyke.message.presentation.common.ResponsiveRow(Modifier.fillMaxWidth().padding(top = 10.dp), spacing = 8.dp) {
                            AppButton("Yes, same payment", { ledger.answerSamePayment(listOf(message), true, rememberSender) }, Modifier.cell(), size = ButtonSize.COMPACT)
                            AppButton("No, separate", { ledger.answerSamePayment(listOf(message), false, rememberSender) }, Modifier.cell(), style = ButtonStyle.OUTLINE, size = ButtonSize.COMPACT)
                        }
                    }
                }
            }
            if (review.bills.isNotEmpty()) {
                SectionHeader("Bills that need a look", review.bills.size)
                review.bills.forEach { bill ->
                    FintechCard {
                        RowTitle(bill.label)
                        Text(
                            DisplayFormatters.rupees(bill.amount), Modifier.padding(top = 2.dp), style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary
                        )
                        Caption("Was this bill paid? It was due on ${DueFormat.format(bill.dueDate)} and no payment message was found.", Modifier.padding(top = 2.dp), maxLines = 3)
                        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AppButton("Yes, mark as paid", {
                                onMarkPaid(bill)
                                scope.launch {
                                    val result = snackbar.showSnackbar("Marked ${bill.label} as paid", actionLabel = "Undo", duration = androidx.compose.material3.SnackbarDuration.Long)
                                    if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) onUndoPaid(bill)
                                }
                            }, Modifier.weight(1f), size = ButtonSize.COMPACT)
                            AppButton("Open message", { onOpen(bill.message.id) }, Modifier.weight(1f), style = ButtonStyle.OUTLINE, size = ButtonSize.COMPACT)
                        }
                    }
                }
            }
            if (review.cancelledCharges.isNotEmpty()) {
                SectionHeader("Charged after you cancelled", review.cancelledCharges.size)
                review.cancelledCharges.forEach { charge ->
                    val amount = DisplayFormatters.rupees(charge.amount)
                    FintechCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                RowTitle(charge.name)
                                Caption(
                                    stampOf(charge.message) + " \u00B7 you marked it cancelled on ${CancelledFormat.format(charge.cancelledOn)}. Was this expected?",
                                    maxLines = 3
                                )
                            }
                            com.zyke.message.presentation.messages.components.AmountText(amount, MaterialTheme.colorScheme.error)
                        }
                        ActionButtons(
                            listOf(
                                RowAction("Open message", ButtonStyle.PRIMARY) { onOpen(charge.message.id) },
                                RowAction("Still using it", ButtonStyle.OUTLINE) { onStillUsing(charge) },
                                RowAction("Looks fine", ButtonStyle.OUTLINE) { onNotTransfer(charge.reviewKey) }
                            ),
                            Modifier.padding(top = 10.dp)
                        )
                    }
                }
            }
            if (review.duplicates.isNotEmpty()) {
                SectionHeader("Possible duplicates", review.duplicates.size)
                review.duplicates.forEach { candidate ->
                    FintechCard {
                        Text(
                            DisplayFormatters.rupees(candidate.amount), style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary
                        )
                        Caption("Tap a line to read its message", Modifier.padding(top = 2.dp))
                        TransferLeg("First", candidate.first, onOpen)
                        TransferLeg("Second", candidate.second, onOpen)
                        Caption("If it is one payment, counting it twice overstates your spending.", Modifier.padding(top = 8.dp), maxLines = 2)
                        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AppButton("Same payment", {
                                onMerge(candidate.first.id, candidate.second.id)
                                scope.launch {
                                    val result = snackbar.showSnackbar("Merged into one payment", actionLabel = "Undo", duration = androidx.compose.material3.SnackbarDuration.Long)
                                    if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) onUnmerge(candidate.second.id)
                                }
                            }, Modifier.weight(1f), size = ButtonSize.COMPACT)
                            AppButton("Different payments", { onNotTransfer(candidate.key) }, Modifier.weight(1f), style = ButtonStyle.OUTLINE, size = ButtonSize.COMPACT)
                        }
                    }
                }
            }
            if (review.transfers.isNotEmpty()) {
                SectionHeader("Possible transfers", review.transfers.size)
                review.transfers.forEach { candidate ->
                    FintechCard {
                        Text(
                            DisplayFormatters.rupees(candidate.amount), style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary
                        )
                        Caption("Tap a line to read its message", Modifier.padding(top = 2.dp))
                        // Each leg: when it happened, which account, and a tap to read the message itself.
                        TransferLeg("Left", candidate.debit, onOpen)
                        TransferLeg("Arrived", candidate.credit, onOpen)
                        Caption("If it is your own money moving, it is not income or spending.", Modifier.padding(top = 8.dp), maxLines = 2)
                        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AppButton("It is a transfer", { onLink(candidate.debit.id, candidate.credit.id) }, Modifier.weight(1f), size = ButtonSize.COMPACT)
                            AppButton("Not a transfer", { onNotTransfer(candidate.key) }, Modifier.weight(1f), style = ButtonStyle.OUTLINE, size = ButtonSize.COMPACT)
                        }
                    }
                }
            }
            if (review.unusual.isNotEmpty()) {
                SectionHeader("Unusually large payments", review.unusual.size)
                if (!picking) SelectHint { lastSection = SECTION_UNUSUAL; picking = true }
                review.unusual.forEach { message ->
                    val tx = message.content as MessageContent.Transaction
                    PickableCard(
                        message, picking, message.id in picked, { toggle(message.id, SECTION_UNUSUAL) }, { onOpen(message.id) },
                        caption = stampOf(message) + " \u00B7 far more than you usually pay. Is this right?",
                        amount = DisplayFormatters.rupees(tx.amount)
                    ) {
                        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AppButton("Open message", { onOpen(message.id) }, Modifier.weight(1f), size = ButtonSize.COMPACT)
                            AppButton("Looks right", { onNotTransfer(ReviewQueues.unusualKey(message)) }, Modifier.weight(1f), style = ButtonStyle.OUTLINE, size = ButtonSize.COMPACT)
                        }
                    }
                }
            }
            if (review.uncategorised.isNotEmpty()) {
                SectionHeader("Payments with no category", review.uncategorised.size)
                if (!picking) SelectHint { lastSection = SECTION_UNCATEGORISED; picking = true }
                review.uncategorised.forEach { message ->
                    val tx = message.content as MessageContent.Transaction
                    PickableCard(
                        message, picking, message.id in picked, { toggle(message.id, SECTION_UNCATEGORISED) }, { onOpen(message.id) },
                        caption = stampOf(message) + " \u00B7 file it under a category",
                        amount = DisplayFormatters.rupees(tx.amount)
                    ) {}
                }
            }
            if (review.unclear.isNotEmpty()) {
                SectionHeader("Messages not understood", review.unclear.size)
                review.unclear.forEach { message ->
                    FintechCard(onClick = { teaching = message }) {
                        RowTitle(message.merchant.name)
                        Caption(stampOf(message), Modifier.padding(top = 2.dp))
                        Caption(message.message.body.replace(Regex("\\s+"), " ").trim(), Modifier.padding(top = 2.dp), maxLines = 2)
                        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AppButton("Teach the app", { teaching = message }, Modifier.weight(1f), size = ButtonSize.COMPACT)
                            AppButton("Open message", { onOpen(message.id) }, Modifier.weight(1f), style = ButtonStyle.OUTLINE, size = ButtonSize.COMPACT)
                        }
                    }
                }
                Caption("Tap one to show the app where the amount is. It then reads that kind of message on its own.", maxLines = 2)
            }
            TaughtFormats(taught, onDeleteTaught)
        }
    }
}

private val DueFormat = FormatCache.pattern("d MMM yyyy", java.util.Locale.getDefault())
private val CancelledFormat = FormatCache.pattern("d MMM", java.util.Locale.getDefault())

private val StampFormat = FormatCache.pattern("d MMM yyyy, h:mm a", java.util.Locale.getDefault())

/** When the message arrived: the day and the time. */
private fun stampOf(message: com.zyke.message.domain.model.ClassifiedMessage): String =
    StampFormat.format(java.time.Instant.ofEpochMilli(message.message.date).atZone(java.time.ZoneId.systemDefault()))

/** One side of a possible transfer: what it was, when, in which account, tappable to read the message. */
@Composable
private fun TransferLeg(label: String, message: com.zyke.message.domain.model.ClassifiedMessage, onOpen: (Long) -> Unit) {
    val tx = message.content as MessageContent.Transaction
    val account = listOf(tx.bankName.displayString, "\u2022\u2022" + tx.accountNumber.takeLast(4)).filter { it.isNotBlank() }.joinToString(" ")
    Row(
        Modifier.fillMaxWidth().fullWidthClickable(onClickLabel = "Read the message", onClick = { onOpen(message.id) }).padding(top = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("$label \u00B7 $account", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            Caption(stampOf(message) + (message.merchant.name.takeIf { it.isNotBlank() && tx.payee.isNotBlank() }?.let { " \u00B7 $it" } ?: ""), maxLines = 2)
        }
        Text("\u203A", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.secondary)
    }
}

private const val SECTION_UNUSUAL = "unusual"
private const val SECTION_UNCATEGORISED = "uncategorised"
private const val SECTION_CARDBILL = "cardbill"
private const val SECTION_SAME = "samepayment"

/** The way in to selection mode for people who do not long-press. */
@Composable
private fun SelectHint(onSelect: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Caption("Touch and hold a payment to pick several", Modifier.weight(1f), maxLines = 2)
        androidx.compose.material3.TextButton(onClick = onSelect, modifier = Modifier.heightIn(min = 48.dp)) { Text("Select") }
    }
}

/**
 * A payment in the review list. Touch and hold picks it; while picking, a tap picks or drops it and a checkbox shows
 * which are picked. [actions] (the card's own buttons) are hidden while picking.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PickableCard(
    message: com.zyke.message.domain.model.ClassifiedMessage,
    picking: Boolean,
    picked: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    caption: String,
    amount: String,
    actions: @Composable () -> Unit
) {
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp)
    Box(
        Modifier
            .clip(shape)
            .combinedClickable(
                onClickLabel = if (picking) (if (picked) "Drop from selection" else "Add to selection") else "Open message",
                onClick = { if (picking) onToggle() else onOpen() },
                onLongClickLabel = "Pick several",
                onLongClick = onToggle
            )
            .then(
                if (picking) Modifier.semantics(mergeDescendants = true) {
                    role = Role.Checkbox
                    toggleableState = ToggleableState(picked)
                } else Modifier
            )
    ) {
        FintechCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (picking) {
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        androidx.compose.material3.Checkbox(checked = picked, onCheckedChange = null)
                    }
                }
                Column(Modifier.weight(1f)) {
                    RowTitle(message.merchant.name)
                    Caption(caption, maxLines = 2)
                }
                Text(amount, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            }
            if (!picking) actions()
        }
    }
}

/** Pinned under the list while payments are picked: how many, and what to do with them. Wraps for large type. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SelectionBar(
    count: Int,
    sectionTitle: String,
    sectionFull: Boolean,
    onSelectSection: () -> Unit,
    onClear: () -> Unit,
    onAction: (BulkMode) -> Unit,
    onDone: () -> Unit,
    cardBillMode: Boolean = false,
    cardBillCount: Int = 0,
    onCardBillAnswer: (Boolean) -> Unit = {},
    samePaymentMode: Boolean = false,
    samePaymentCount: Int = 0,
    onSamePaymentAnswer: (Boolean) -> Unit = {}
) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "$count selected",
            Modifier.semantics { liveRegion = LiveRegionMode.Polite; contentDescription = ReviewSelection.announcement(count) },
            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary
        )
        androidx.compose.foundation.layout.FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (cardBillMode) {
                com.zyke.message.presentation.common.ResponsiveRow(Modifier.fillMaxWidth(), spacing = 8.dp) {
                    AppButton("Yes, card bill payments", { onCardBillAnswer(true) }, Modifier.cell(), size = ButtonSize.COMPACT, enabled = cardBillCount > 0)
                    AppButton("No, spending", { onCardBillAnswer(false) }, Modifier.cell(), style = ButtonStyle.OUTLINE, size = ButtonSize.COMPACT, enabled = cardBillCount > 0)
                }
            } else if (samePaymentMode) {
                com.zyke.message.presentation.common.ResponsiveRow(Modifier.fillMaxWidth(), spacing = 8.dp) {
                    AppButton("Yes, same payment", { onSamePaymentAnswer(true) }, Modifier.cell(), size = ButtonSize.COMPACT, enabled = samePaymentCount > 0)
                    AppButton("No, separate", { onSamePaymentAnswer(false) }, Modifier.cell(), style = ButtonStyle.OUTLINE, size = ButtonSize.COMPACT, enabled = samePaymentCount > 0)
                }
            } else BulkMode.entries.forEach { mode ->
                AppButton(mode.title, { onAction(mode) }, size = ButtonSize.COMPACT, enabled = count > 0)
            }
        }
        androidx.compose.foundation.layout.FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AppButton(
                "Select all in this section", onSelectSection, style = ButtonStyle.OUTLINE, size = ButtonSize.COMPACT, enabled = !sectionFull,
                modifier = Modifier.semantics { contentDescription = "Select all $sectionTitle" }
            )
            AppButton("Clear", onClear, style = ButtonStyle.OUTLINE, size = ButtonSize.COMPACT, enabled = count > 0)
            AppButton("Done", onDone, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT)
        }
    }
}
