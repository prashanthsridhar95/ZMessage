package com.zyke.message.presentation.messages.components

import com.zyke.message.presentation.common.AdaptiveSheet
import com.zyke.message.presentation.common.linksIn
import com.zyke.message.presentation.common.LocalOpenLink
import android.net.Uri
import com.zyke.message.presentation.common.FormField
import com.zyke.message.presentation.common.FormSheet
import com.zyke.message.presentation.common.FintechCard
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.Switch
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.AlertDialog
import com.zyke.message.presentation.common.fullWidthClickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import com.zyke.message.presentation.common.heading
import com.zyke.message.presentation.common.ChipOption
import com.zyke.message.presentation.common.Chips
import com.zyke.message.presentation.common.FormGroup
import com.zyke.message.presentation.common.rememberPicturePicker
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.zyke.message.presentation.common.LocalImageStore
import kotlinx.coroutines.launch
import com.zyke.message.presentation.common.AppBottomSheet
import com.zyke.message.presentation.common.addToCalendar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import com.zyke.message.presentation.common.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zyke.message.domain.categories.CategoryKey
import com.zyke.message.domain.categories.CategoryOptions
import com.zyke.message.domain.categories.CustomCategories
import com.zyke.message.domain.model.CustomCategory
import com.zyke.message.domain.nickname.label
import com.zyke.message.domain.conversation.SenderKey
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MerchantLogo
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.presentation.common.AppButton
import com.zyke.message.presentation.common.ButtonSize
import com.zyke.message.presentation.common.ButtonStyle
import com.zyke.message.presentation.common.LinkButton
import com.zyke.message.presentation.format.DateFormatters
import com.zyke.message.presentation.format.DisplayFormatters
import com.zyke.message.presentation.format.detailFacts
import com.zyke.message.presentation.format.displayName

/**
 * One message, in full, in a bottom sheet. It reads top to bottom: who and when, what was understood, the one action
 * that matters, the original text, then two quiet lists of settings, one for the sender and one for this message,
 * each a row with its current value. Nothing here is a loose button; a row opens what it names.
 */
@Composable
internal fun MessageDetailSheet(
    item: ClassifiedMessage,
    isPinned: Boolean,
    isArchived: Boolean,
    inConversation: Boolean,
    actions: FeedActions,
    onMoreFromSender: () -> Unit,
    onDismiss: () -> Unit,
    /** Other messages that look like the same payment, offered for merging. */
    mergeCandidates: List<ClassifiedMessage> = emptyList()
) {
    val message = item.message
    val content = item.content
    val facts = item.detailFacts()
    val title = if (item.merchant.renamed || item.merchant.logo != MerchantLogo.PERSON) item.merchant.displayName else SenderKey.displayAddress(message.sender)
    val sender = SenderKey.displayAddress(message.sender)
    val canTag = content !is MessageContent.Otp && CategoryKey.of(item) != null
    // Which of the two the open sheet is for: this one transaction, or the sender or receiver it is with.
    var categoryScope by remember { mutableStateOf<Boolean?>(null) }
    var nameScope by remember { mutableStateOf<Boolean?>(null) }
    val pickPicture = CategoryKey.of(item)?.let { rememberPicturePicker(it, CategoryKey.label(item)) }
    val imageStore = LocalImageStore.current
    val pictureKeys by (imageStore?.keysWithImage ?: kotlinx.coroutines.flow.MutableStateFlow(emptySet<String>())).collectAsState()
    val thisMessageKey = CategoryKey.messageKey(item.id)
    val senderHasPicture = CategoryKey.of(item)?.let { it in pictureKeys } == true
    val thisHasPicture = thisMessageKey in pictureKeys
    // The same chooser, for this one payment rather than everything from its sender or receiver.
    val pickThisPicture = if (canTag && content is MessageContent.Transaction) rememberPicturePicker(thisMessageKey, title) else null
    val hiddenLogos by (imageStore?.hiddenLogos ?: kotlinx.coroutines.flow.MutableStateFlow(emptySet<String>())).collectAsState()

    AdaptiveSheet(title, onDismiss, asPage = true) {
        Column(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MerchantChip(
                    item.merchant.logo, imageKey = item.merchant.imageKey, name = item.merchant.name, description = title, size = 52.dp,
                    modifier = if (pickPicture != null) Modifier.clip(RoundedCornerShape(13.dp)).clickable(onClickLabel = "Change picture", onClick = pickPicture) else Modifier
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Caption("${DateFormatters.detailTimestamp(message.date)} · ${com.zyke.message.presentation.common.categoryText(item)}", maxLines = 1)
                }
            }

            if (facts.isNotEmpty()) {
                FintechCard {
                    facts.forEachIndexed { index, fact ->
                        if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Caption(fact.label, Modifier.weight(1f))
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = fact.value,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.secondary,
                                textAlign = TextAlign.End,
                                maxLines = 2
                            )
                        }
                    }
                }
            }

            // The one action that matters most for this kind of message.
            val billLink = (content as? MessageContent.CardBill)?.paymentLink
            val openLink = LocalOpenLink.current
            when {
                content is MessageContent.Otp && content.code.isNotEmpty() ->
                    AppButton("Copy code", { actions.onCopyOtp(item.id, content.code) }, Modifier.fillMaxWidth())
                billLink != null ->
                    AppButton("View / pay bill", { openLink(billLink) }, Modifier.fillMaxWidth())
                content is MessageContent.Life && content.date != null && !content.isDone -> {
                    val context = LocalContext.current
                    AppButton(
                        "Add to calendar",
                        {
                            if (!addToCalendar(context, content)) {
                                Toast.makeText(context, "No calendar app found", Toast.LENGTH_SHORT).show()
                            }
                        },
                        Modifier.fillMaxWidth()
                    )
                }
            }
            val links = remember(message.body) { linksIn(message.body).filter { it != billLink }.take(3) }
            links.forEach { link ->
                AppButton(
                    "Open ${Uri.parse(link).host?.removePrefix("www.") ?: "link"}",
                    { openLink(link) },
                    Modifier.fillMaxWidth(),
                    style = ButtonStyle.TONAL
                )
            }
            val split = actions.onSplit
            if (split != null && content is MessageContent.Transaction && !content.isReceived) {
                AppButton("Split with friends", { split(item.id) }, Modifier.fillMaxWidth(), style = ButtonStyle.TONAL)
            }

            FintechCard {
                Caption("MESSAGE")
                SelectionContainer {
                    Text(
                        text = message.body,
                        modifier = Modifier.padding(top = 6.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
                if (item.isModelParsed) Caption("Read by on-device AI", Modifier.padding(top = 8.dp))
            }

            MergedMessages(item, actions.onUnmerge)
            MergeSuggestions(item, mergeCandidates, actions.onRequestMerge)

            if (content !is MessageContent.Otp) {
                // A payment can be filed and named on its own, apart from everything else paid to the same receiver.
                if (canTag && content is MessageContent.Transaction) {
                    SettingsGroup("This transaction") {
                        DetailRow("Category", if (item.categoryOwn) com.zyke.message.presentation.common.categoryText(item) else "Same as $title") { categoryScope = true }
                        DetailRow("Nickname", if (item.nicknameOwn) item.merchant.name else "None") { nameScope = true }
                        if (pickThisPicture != null) {
                            DetailRow("Picture", if (thisHasPicture) "Your picture" else "Same as $title", onClick = pickThisPicture)
                            if (thisHasPicture) DetailRow("Remove picture", null) { imageStore?.removeChosen(thisMessageKey) }
                        }
                    }
                }
                if (content is MessageContent.Transaction) PaymentNotesGroup(item)
                SettingsGroup(if (content is MessageContent.Transaction) "Every payment to $title" else "About $title") {
                    if (canTag) {
                        DetailRow(
                            "Category",
                            when {
                                item.categoryOwn -> "Not set"
                                else -> com.zyke.message.presentation.common.categoryText(item) + if (item.categoryOverridden) "" else " · auto"
                            }
                        ) { categoryScope = false }
                        DetailRow("Nickname", if (item.merchant.renamed && !item.nicknameOwn) item.merchant.name else "None") { nameScope = false }
                        if (pickPicture != null) {
                            val pictureValue = when {
                                senderHasPicture -> "Your picture"
                                imageStore != null && imageStore.hasLogo(item.merchant.name) && item.merchant.name.trim().lowercase() !in hiddenLogos -> "Brand logo"
                                else -> "Default"
                            }
                            DetailRow("Picture", pictureValue, onClick = pickPicture)
                            RemovePictureRow(item, senderHasPicture)
                        }
                    }
                    SwitchRow("Mute $sender", item.isMuted) { actions.onToggleMute(item.id) }
                }
            }

            if (content is MessageContent.Transaction) {
                // Where this payment stands in the income and spending totals.
                val ledger = com.zyke.message.presentation.common.rememberLedgerActions()
                val manual = com.zyke.message.domain.ledger.ManualEntries.isManual(item)
                SettingsGroup("In your totals") {
                    Caption(
                        com.zyke.message.domain.ledger.CountedReason.explain(item),
                        maxLines = 3
                    )
                    if (item.setAside != com.zyke.message.domain.ledger.SetAside.TRANSFER) {
                        SwitchRow("Leave out of totals", item.setAside == com.zyke.message.domain.ledger.SetAside.EXCLUDED) {
                            ledger.toggleExcluded(item.id)
                        }
                    }
                    if (!manual && item.setAside != com.zyke.message.domain.ledger.SetAside.EXCLUDED) {
                        if (item.setAside == com.zyke.message.domain.ledger.SetAside.TRANSFER) {
                            DetailRow("Not a transfer", null) { ledger.markTransfer(item, false) }
                        } else {
                            DetailRow("This is a transfer between my accounts", null) { ledger.markTransfer(item, true) }
                        }
                    }
                    if (!manual && item.setAside == null) {
                        // A debit the app took for paying a credit card's bill, or may have: the person has the last word.
                        val cardBill = item.cardBill
                        if (com.zyke.message.domain.ledger.CardBillPayments.isCountedAsBillPayment(item)) {
                            DetailRow("This is not a card bill payment", null) { ledger.answerCardBill(listOf(item), false, false) }
                        } else if (cardBill != null && cardBill.kind == com.zyke.message.domain.ledger.CardBillKind.SUSPECTED) {
                            Caption(cardBill.reason, maxLines = 2)
                            DetailRow("Yes, this paid my credit card bill", null) { ledger.answerCardBill(listOf(item), true, true) }
                        }
                    }
                    if (!manual && item.setAside == null) SamePaymentRows(item, ledger)
                    if (item.setAside == com.zyke.message.domain.ledger.SetAside.TRANSFER) GoalLinkRow(item)
                    if (manual) DetailRow("Delete this entry", null) { ledger.deleteEntry(item.id); onDismiss() }
                }
            } else if (item.samePayment != null) {
                // A gateway's confirmation the app linked to a bank debit: it says so, and the person can undo it.
                val ledger = com.zyke.message.presentation.common.rememberLedgerActions()
                SettingsGroup("In your totals") {
                    Caption(com.zyke.message.domain.ledger.CountedReason.explain(item), maxLines = 3)
                    SamePaymentRows(item, ledger)
                }
            }

            SettingsGroup("This message") {
                if (!isArchived) SwitchRow("Pinned", isPinned) { actions.onTogglePin(item.id) }
                DetailRow(if (isArchived) "Restore" else "Archive", null) {
                    if (isArchived) actions.onUnarchive(item.id) else actions.onArchive(item.id)
                    if (isArchived) onDismiss()
                }
            }

            if (!inConversation) {
                LinkButton("More from $title", onMoreFromSender)
            }
        }
    }

    val namingLedger = com.zyke.message.presentation.common.rememberLedgerActions()
    val namingScope = rememberCoroutineScope()
    categoryScope?.let { forTransaction -> CategorySheet(item, actions, forTransaction, namingLedger) { categoryScope = null } }
    nameScope?.let { forTransaction -> NicknameSheet(item, title, forTransaction, namingLedger, namingScope) { nameScope = null } }
}

/**
 * What the person can say about a text the app linked to another message as the same payment (a gateway's or a merchant's
 * confirmation of a bank debit): that it is, when the app was only unsure, or that it is a payment of its own.
 */
@Composable
private fun SamePaymentRows(item: ClassifiedMessage, ledger: com.zyke.message.presentation.common.LedgerActions) {
    val same = item.samePayment ?: return
    if (same.kind == com.zyke.message.domain.ledger.SamePaymentKind.SUSPECTED) {
        Caption(same.reason, maxLines = 2)
        DetailRow("Yes, same payment", null) { ledger.answerSamePayment(listOf(item), true, false) }
    }
    DetailRow("This is a separate payment", null) { ledger.answerSamePayment(listOf(item), false, false) }
}

/** A titled card of rows, for settings that belong together. */
@Composable
internal fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, Modifier.padding(start = 4.dp), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary, maxLines = 1)
        FintechCard(content = content)
    }
}

/** A setting: its name on the left, its current value and a chevron on the right; a tap opens it. */
@Composable
private fun DetailRow(label: String, value: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().fullWidthClickable(role = Role.Button, onClickLabel = label, onClick = onClick).padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary, maxLines = 1)
        if (value != null) {
            Spacer(Modifier.width(12.dp))
            Text(value, style = MaterialTheme.typography.bodyMedium, color = mutedColor(), maxLines = 1)
        }
        Text("\u203A", Modifier.padding(start = 8.dp), color = mutedColor())
    }
}

/** A setting that is on or off, switched in place. */
@Composable
private fun SwitchRow(label: String, checked: Boolean, onToggle: () -> Unit) {
    val tick = com.zyke.message.presentation.common.rememberTick()
    // Shown at once on a tap; follows the real value again whenever that arrives or changes.
    var shown by remember(checked) { mutableStateOf(checked) }
    Row(
        Modifier.fillMaxWidth().fullWidthClickable(role = Role.Switch, onClickLabel = label, onClick = { tick(); shown = !shown; onToggle() }).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary, maxLines = 1)
        Switch(checked = shown, onCheckedChange = null)
    }
}

/** How the categories are grouped on the sheet, so one is found by where it belongs and not by reading the whole list. */
internal val CATEGORY_GROUPS = listOf(
    "Everyday" to listOf(MessageCategory.FOOD, MessageCategory.GROCERIES, MessageCategory.SHOPPING, MessageCategory.FUEL, MessageCategory.TRAVEL, MessageCategory.ENTERTAINMENT, MessageCategory.UPI_PAYMENT),
    "Bills and services" to listOf(MessageCategory.UTILITIES, MessageCategory.CREDIT_CARDS, MessageCategory.PAY_LATER, MessageCategory.NETWORK_CARRIERS, MessageCategory.INSURANCE, MessageCategory.FASTAG, MessageCategory.COURIER, MessageCategory.LOYALTY_PROGRAMMES),
    "Money" to listOf(MessageCategory.INVESTMENTS, MessageCategory.SAVINGS, MessageCategory.SALARY),
    "People and life" to listOf(MessageCategory.HEALTH, MessageCategory.EDUCATION, MessageCategory.FAMILY, MessageCategory.CHARITY)
)

/**
 * Filing a payment: the one in effect and the app's own guess come first, then the categories in groups, each a tap
 * away. Typing in the search box narrows them all to the ones that match. Tapping the one already chosen takes the choice away.
 */
@Composable
private fun CategorySheet(
    item: ClassifiedMessage, actions: FeedActions, forTransaction: Boolean,
    ledger: com.zyke.message.presentation.common.LedgerActions, onDismiss: () -> Unit
) {
    // What is in effect for the thing being filed: this one transaction's own choice, or its sender's or receiver's.
    val chosenHere = if (forTransaction) item.categoryOwn else item.categoryOverridden && !item.categoryOwn
    val suggestion = item.suggestedCategory?.takeIf { !item.categoryOverridden && it != item.category }
    val container = (androidx.compose.ui.platform.LocalContext.current.applicationContext as com.zyke.message.ZykeMessagesApplication).container
    val customs = com.zyke.message.presentation.common.LocalCustomCategories.current
    // What this choice is filed under: this one payment, or its sender or receiver.
    val fileKey = if (forTransaction) CategoryKey.messageKey(item.id) else CategoryKey.of(item)
    val customHere = item.customCategoryId.takeIf { chosenHere }
    // Other payments that carry the same nickname: the choice can be carried to them too.
    val nicknames by container.nicknames.nicknames.collectAsState()
    val messages by remember(container) { container.observeMessages() }.collectAsState(initial = emptyList())
    val nick = item.merchant.name.takeIf { item.merchant.renamed }
    val coveredIds = remember(messages, fileKey, forTransaction) {
        if (forTransaction || fileKey == null) setOf(item.id) else messages.filter { CategoryKey.of(it) == fileKey }.mapTo(HashSet()) { it.id }
    }
    val sameName = remember(nick, nicknames, messages, fileKey, coveredIds) {
        if (nick == null || fileKey == null) emptyList()
        else com.zyke.message.domain.nickname.NicknameProfiles.holders(nick, nicknames, messages, coveredIds, setOf(fileKey))
    }
    val sameNameCount = sameName.sumOf { it.messageIds.size }
    // On for a sender or receiver (the whole name moves together), off for one payment (the person has not asked for more).
    var alsoSameName by rememberSaveable { mutableStateOf(!forTransaction) }
    fun fileSameName(choice: com.zyke.message.domain.nickname.CategoryChoice) {
        if (!alsoSameName || sameName.isEmpty() || nick == null) return
        ledger.edit(
            com.zyke.message.domain.nickname.NamingEdits.Request(
                sameName.map { com.zyke.message.domain.nickname.NamingEdits.Target(it.key, it.messageIds) }, category = choice, inherit = false
            ),
            messages, emptySet(), customs,
            summary = "Also filed $sameNameCount other \"$nick\" ${if (sameNameCount == 1) "payment" else "payments"} under ${choice.label(customs)}"
        )
    }
    val set: (MessageCategory?) -> Unit = { category ->
        // A built-in choice (or taking the choice away) replaces any category of the person's own for the same thing.
        fileKey?.let { container.customCategories.assign(it, null) }
        if (forTransaction) actions.onSetMessageCategory(item.id, category) else actions.onSetCategory(item.id, category)
    }
    fun chooseCustom(category: CustomCategory) {
        com.zyke.message.data.diag.DevLog.d("category") { "category sheet: chose custom ${category.id} id=${item.id} forTransaction=$forTransaction alreadyChosen=${customHere == category.id}" }
        val key = fileKey ?: return
        if (customHere == category.id) {
            container.customCategories.assign(key, null)
        } else {
            // One thing is filed one way: the built-in choice for the same key is cleared so the two never compete.
            if (forTransaction) actions.onSetMessageCategory(item.id, null) else actions.onSetCategory(item.id, null)
            container.customCategories.assign(key, category.id)
            fileSameName(com.zyke.message.domain.nickname.CategoryChoice.custom(category.id))
        }
    }
    var adding by remember { mutableStateOf(false) }
    val available = CategoryOptions.forMessage(item)
    var query by rememberSaveable { mutableStateOf("") }
    val matches = available.filter { it.displayString.contains(query.trim(), ignoreCase = true) }
    val customMatches = customs.filter { it.name.contains(query.trim(), ignoreCase = true) }

    fun choose(category: MessageCategory) {
        com.zyke.message.data.diag.DevLog.d("ui") { "category sheet: chose $category id=${item.id} forTransaction=$forTransaction alreadyChosen=${chosenHere && item.category == category}" }
        // Choosing the one already chosen takes the choice away.
        val removing = chosenHere && customHere == null && item.category == category
        set(if (removing) null else category)
        if (!removing) fileSameName(com.zyke.message.domain.nickname.CategoryChoice.of(category))
        onDismiss()
    }

    AdaptiveSheet(if (forTransaction) "Category for this transaction" else "Category for ${CategoryKey.label(item)}", onDismiss, asPage = true) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column {
                Caption(
                    if (forTransaction) "Other payments to ${CategoryKey.label(item)} are not changed" else "Every payment to ${CategoryKey.label(item)}",
                    Modifier.padding(top = 2.dp), maxLines = 2
                )
            }

            if (sameNameCount > 0 && nick != null) {
                SwitchRow("Also file the $sameNameCount other \"$nick\" ${if (sameNameCount == 1) "payment" else "payments"} here", alsoSameName) { alsoSameName = !alsoSameName }
            }

            FormField(label = "Find a category", value = query, onChange = { query = it }, placeholder = "Type to search")

            if (query.isBlank()) {
                // Where it is now, and what the app thinks it is.
                val now = item.category.takeIf { chosenHere && customHere == null }
                val nowCustom = customs.firstOrNull { it.id == customHere }
                if (nowCustom != null) {
                    FormGroup("Now") {
                        Chips(listOf(ChipOption(com.zyke.message.presentation.common.lookText(CustomCategories.look(MessageCategory.OTHERS, nowCustom.id, customs)), true)), onSelect = { chooseCustom(nowCustom) })
                    }
                } else if (now != null || (!forTransaction && suggestion != null)) {
                    FormGroup("Now") {
                        Chips(
                            buildList {
                                now?.let { add(ChipOption(it.displayString, true)) }
                                if (!forTransaction) suggestion?.let { add(ChipOption("Looks like ${it.displayString}", false)) }
                            },
                            onSelect = { index ->
                                if (now != null && index == 0) choose(now)
                                else suggestion?.let { choose(it) }
                            }
                        )
                    }
                }
                val shown = HashSet<MessageCategory>()
                CATEGORY_GROUPS.forEach { (title, members) ->
                    val inGroup = members.filter { it in available }
                    if (inGroup.isEmpty()) return@forEach
                    shown += inGroup
                    FormGroup(title) {
                        Chips(inGroup.map { ChipOption(it.displayString, chosenHere && customHere == null && item.category == it) }, onSelect = { choose(inGroup[it]) })
                    }
                }
                FormGroup("Your categories") {
                    Chips(
                        customs.map { ChipOption(com.zyke.message.presentation.common.lookText(CustomCategories.look(MessageCategory.OTHERS, it.id, customs)), customHere == it.id) } +
                            ChipOption("\uFF0B Add category", false),
                        onSelect = { i -> if (i < customs.size) { chooseCustom(customs[i]); onDismiss() } else adding = true }
                    )
                }
                // Anything this payment can be filed under that the groups above do not hold.
                val rest = available.filter { it !in shown }
                if (rest.isNotEmpty()) {
                    FormGroup("More") {
                        Chips(rest.map { ChipOption(it.displayString, chosenHere && customHere == null && item.category == it) }, onSelect = { choose(rest[it]) })
                    }
                }
            } else if (matches.isEmpty() && customMatches.isEmpty()) {
                Caption("Nothing matches \"${query.trim()}\"", maxLines = 2)
                Chips(listOf(ChipOption("\uFF0B Add \"${CustomCategories.tidy(query)}\" as a category", false)), onSelect = { adding = true })
            } else {
                if (customMatches.isNotEmpty()) Chips(
                    customMatches.map { ChipOption(com.zyke.message.presentation.common.lookText(CustomCategories.look(MessageCategory.OTHERS, it.id, customs)), customHere == it.id) },
                    onSelect = { chooseCustom(customMatches[it]); onDismiss() }
                )
                Chips(matches.map { ChipOption(it.displayString, chosenHere && customHere == null && item.category == it) }, onSelect = { choose(matches[it]) })
            }
        }
    }
    if (adding) {
        com.zyke.message.presentation.common.CategoryEditSheet(
            existing = null,
            onDismiss = { adding = false },
            onSaved = { made -> chooseCustom(made); adding = false; onDismiss() }
        )
    }
}

/** Giving the sender a nickname, in the app's one form shape. */
@Composable
private fun NicknameSheet(
    item: ClassifiedMessage,
    shownName: String,
    forTransaction: Boolean,
    ledger: com.zyke.message.presentation.common.LedgerActions,
    appScope: kotlinx.coroutines.CoroutineScope,
    onDismiss: () -> Unit
) {
    // A name for this transaction, or for the sender or receiver: each is kept and cleared on its own.
    val named = if (forTransaction) item.nicknameOwn else item.merchant.renamed && !item.nicknameOwn
    var name by rememberSaveable { mutableStateOf(if (named) shownName else "") }
    // The category a name already has is taken by default; this switches that off before saving.
    var keepCategory by rememberSaveable { mutableStateOf(false) }
    val container = (androidx.compose.ui.platform.LocalContext.current.applicationContext as com.zyke.message.ZykeMessagesApplication).container
    val nicknames by container.nicknames.nicknames.collectAsState()
    val pictureKeys by container.imageStore.keysWithImage.collectAsState()
    val messages by remember(container) { container.observeMessages() }.collectAsState(initial = emptyList())
    val customs = com.zyke.message.presentation.common.LocalCustomCategories.current

    // What the thing being named is filed under: this one payment, or its sender or receiver.
    val targetKey = if (forTransaction) CategoryKey.messageKey(item.id) else CategoryKey.of(item)
    val coveredIds = androidx.compose.runtime.remember(messages, targetKey, forTransaction) {
        if (forTransaction || targetKey == null) setOf(item.id) else messages.filter { CategoryKey.of(it) == targetKey }.mapTo(HashSet()) { it.id }
    }
    // Names already given, best fit to what is typed first, never counting this thing's own current name.
    val suggestions = androidx.compose.runtime.remember(name, nicknames, targetKey) {
        com.zyke.message.domain.nickname.NicknameSuggestions.suggest(name, nicknames.filterKeys { it != targetKey })
    }
    // If the name is one already used, what its other payments have: the category most of them are filed under, and a picture.
    val profile = androidx.compose.runtime.remember(name, nicknames, messages, pictureKeys, targetKey, coveredIds) {
        targetKey?.let {
            com.zyke.message.domain.nickname.NicknameProfiles.profile(name, nicknames, messages, pictureKeys, coveredIds, setOf(it))
        }
    }
    val inheritedCategory = profile?.category?.takeIf { !it.isEmpty }

    FormSheet(
        title = if (forTransaction) "Name this transaction" else "Name ${CategoryKey.label(item)}",
        onDismiss = onDismiss,
        primaryLabel = "Save",
        primaryEnabled = name.isNotBlank(),
        onPrimary = {
            com.zyke.message.data.diag.DevLog.d("ui") { "name sheet: save id=${item.id} forTransaction=$forTransaction nameLength=${name.length} inherits=${profile != null && !keepCategory}" }
            if (targetKey != null) {
                ledger.edit(
                    com.zyke.message.domain.nickname.NamingEdits.Request(
                        listOf(com.zyke.message.domain.nickname.NamingEdits.Target(targetKey, coveredIds)), name, inherit = !keepCategory
                    ),
                    messages, pictureKeys, customs,
                    copyPicture = { from, to -> appScope.launch { container.imageStore.copyChosen(from, to) } },
                    removePicture = { container.imageStore.removeChosen(it) }
                )
            }
            onDismiss()
        },
        secondaryLabel = if (named) "Remove this name" else null,
        onSecondary = {
            com.zyke.message.data.diag.DevLog.d("ui") { "name sheet: remove id=${item.id} forTransaction=$forTransaction" }
            if (targetKey != null) {
                ledger.edit(
                    com.zyke.message.domain.nickname.NamingEdits.Request(
                        listOf(com.zyke.message.domain.nickname.NamingEdits.Target(targetKey, coveredIds)), "", inherit = false
                    ),
                    messages, pictureKeys, customs, summary = "Name removed"
                )
            }
            onDismiss()
        }
    ) {
        FormField(
            "Name", name, { name = it }, placeholder = CategoryKey.label(item),
            supporting = if (forTransaction) "Only this payment is shown with this name" else "Shown wherever ${CategoryKey.label(item)} appears",
            capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences
        )
        if (suggestions.isNotEmpty()) {
            FormGroup(if (name.isBlank()) "Names you have used" else "Names you have used that fit") {
                com.zyke.message.presentation.common.SuggestionRow(
                    suggestions.map { if (it.uses > 1) "${it.name} \u00B7 ${it.uses}" else it.name }
                ) { picked -> name = suggestions.first { (if (it.uses > 1) "${it.name} \u00B7 ${it.uses}" else it.name) == picked }.name }
            }
        }
        if (inheritedCategory != null && profile != null) {
            val inheritedLabel = inheritedCategory.label(customs)
            val current = CustomCategories.label(item, customs)
            FormGroup("Category") {
                Chips(
                    listOf(ChipOption("Use $inheritedLabel", !keepCategory), ChipOption("Keep $current", keepCategory)),
                    onSelect = { keepCategory = it == 1 }
                )
                Caption(
                    "from ${profile.transactions} other \"${name.trim()}\" ${if (profile.transactions == 1) "payment" else "payments"}",
                    maxLines = 2
                )
            }
        }
        if (profile?.imageKey != null) {
            Caption("\"${name.trim()}\" is already in use: this will also take its picture.", maxLines = 3)
        }
    }
}

/**
 * Takes a picture away from this sender: the one chosen, or a brand logo that turns out to be wrong. A hidden logo
 * can be brought back from the same row.
 */
@Composable
private fun RemovePictureRow(item: ClassifiedMessage, hasOwnPicture: Boolean) {
    val store = LocalImageStore.current ?: return
    val key = CategoryKey.of(item) ?: return
    val hidden by store.hiddenLogos.collectAsState()
    val name = item.merchant.name
    when {
        hasOwnPicture -> DetailRow("Remove picture", null) { store.removeChosen(key) }
        store.hasLogo(name) && !store.isHidden(name) -> DetailRow("This logo is wrong", null) { store.hideLogo(name) }
        store.isHidden(name) && hidden.isNotEmpty() -> DetailRow("Show the logo again", null) { store.showLogo(name) }
    }
}
