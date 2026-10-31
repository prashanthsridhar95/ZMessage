package com.zyke.message.presentation.messages.components

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zyke.message.domain.categories.CategoryKey
import com.zyke.message.domain.categories.CategoryOptions
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

    AppBottomSheet(onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MerchantChip(
                    item.merchant.logo, imageKey = item.merchant.imageKey, name = item.merchant.name, description = title, size = 52.dp,
                    modifier = if (pickPicture != null) Modifier.clip(RoundedCornerShape(13.dp)).clickable(onClickLabel = "Change picture", onClick = pickPicture) else Modifier
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        modifier = Modifier.heading(),
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1
                    )
                    Caption("${DateFormatters.detailTimestamp(message.date)} · ${item.category.displayString}", maxLines = 1)
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
                        DetailRow("Category", if (item.categoryOwn) item.category.displayString else "Same as $title") { categoryScope = true }
                        DetailRow("Nickname", if (item.nicknameOwn) item.merchant.name else "None") { nameScope = true }
                        if (pickThisPicture != null) {
                            DetailRow("Picture", if (thisHasPicture) "Your picture" else "Same as $title", onClick = pickThisPicture)
                            if (thisHasPicture) DetailRow("Remove picture", null) { imageStore?.removeChosen(thisMessageKey) }
                        }
                    }
                }
                SettingsGroup(if (content is MessageContent.Transaction) "Every payment to $title" else "About $title") {
                    if (canTag) {
                        DetailRow(
                            "Category",
                            when {
                                item.categoryOwn -> "Not set"
                                else -> item.category.displayString + if (item.categoryOverridden) "" else " · auto"
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
                            ledger.setExcluded(item.id, item.setAside != com.zyke.message.domain.ledger.SetAside.EXCLUDED)
                        }
                    }
                    if (!manual && item.setAside != com.zyke.message.domain.ledger.SetAside.EXCLUDED) {
                        if (item.setAside == com.zyke.message.domain.ledger.SetAside.TRANSFER) {
                            DetailRow("Not a transfer", null) { ledger.markTransfer(item, false) }
                        } else {
                            DetailRow("This is a transfer between my accounts", null) { ledger.markTransfer(item, true) }
                        }
                    }
                    if (item.setAside == com.zyke.message.domain.ledger.SetAside.TRANSFER) GoalLinkRow(item)
                    if (manual) DetailRow("Delete this entry", null) { ledger.deleteEntry(item.id); onDismiss() }
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

    categoryScope?.let { forTransaction -> CategorySheet(item, actions, forTransaction) { categoryScope = null } }
    nameScope?.let { forTransaction -> NicknameSheet(item, title, actions, forTransaction) { nameScope = null } }
}

/** A titled card of rows, for settings that belong together. */
@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
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
    Row(
        Modifier.fillMaxWidth().fullWidthClickable(role = Role.Switch, onClickLabel = label, onClick = onToggle).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary, maxLines = 1)
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** How the categories are grouped on the sheet, so one is found by where it belongs and not by reading the whole list. */
private val CATEGORY_GROUPS = listOf(
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
private fun CategorySheet(item: ClassifiedMessage, actions: FeedActions, forTransaction: Boolean, onDismiss: () -> Unit) {
    // What is in effect for the thing being filed: this one transaction's own choice, or its sender's or receiver's.
    val chosenHere = if (forTransaction) item.categoryOwn else item.categoryOverridden && !item.categoryOwn
    val suggestion = item.suggestedCategory?.takeIf { !item.categoryOverridden && it != item.category }
    val set: (MessageCategory?) -> Unit = { category ->
        if (forTransaction) actions.onSetMessageCategory(item.id, category) else actions.onSetCategory(item.id, category)
    }
    val available = CategoryOptions.forMessage(item)
    var query by rememberSaveable { mutableStateOf("") }
    val matches = available.filter { it.displayString.contains(query.trim(), ignoreCase = true) }

    fun choose(category: MessageCategory) {
        com.zyke.message.data.diag.DevLog.d("ui") { "category sheet: chose $category id=${item.id} forTransaction=$forTransaction alreadyChosen=${chosenHere && item.category == category}" }
        // Choosing the one already chosen takes the choice away.
        set(if (chosenHere && item.category == category) null else category)
        onDismiss()
    }

    AppBottomSheet(onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column {
                Text(
                    if (forTransaction) "Category for this transaction" else "Category for ${CategoryKey.label(item)}",
                    Modifier.heading(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary
                )
                Caption(
                    if (forTransaction) "Other payments to ${CategoryKey.label(item)} are not changed" else "Every payment to ${CategoryKey.label(item)}",
                    Modifier.padding(top = 2.dp), maxLines = 2
                )
            }

            FormField(label = "Find a category", value = query, onChange = { query = it }, placeholder = "Type to search")

            if (query.isBlank()) {
                // Where it is now, and what the app thinks it is.
                val now = item.category.takeIf { chosenHere }
                if (now != null || (!forTransaction && suggestion != null)) {
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
                        Chips(inGroup.map { ChipOption(it.displayString, chosenHere && item.category == it) }, onSelect = { choose(inGroup[it]) })
                    }
                }
                // Anything this payment can be filed under that the groups above do not hold.
                val rest = available.filter { it !in shown }
                if (rest.isNotEmpty()) {
                    FormGroup("More") {
                        Chips(rest.map { ChipOption(it.displayString, chosenHere && item.category == it) }, onSelect = { choose(rest[it]) })
                    }
                }
            } else if (matches.isEmpty()) {
                Caption("Nothing matches \"${query.trim()}\"", maxLines = 2)
            } else {
                Chips(matches.map { ChipOption(it.displayString, chosenHere && item.category == it) }, onSelect = { choose(matches[it]) })
            }
        }
    }
}

/** Giving the sender a nickname, in the app's one form shape. */
@Composable
private fun NicknameSheet(item: ClassifiedMessage, shownName: String, actions: FeedActions, forTransaction: Boolean, onDismiss: () -> Unit) {
    // A name for this transaction, or for the sender or receiver: each is kept and cleared on its own.
    val named = if (forTransaction) item.nicknameOwn else item.merchant.renamed && !item.nicknameOwn
    var name by rememberSaveable { mutableStateOf(if (named) shownName else "") }
    val container = (androidx.compose.ui.platform.LocalContext.current.applicationContext as com.zyke.message.ZykeMessagesApplication).container
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val nicknames by container.nicknames.nicknames.collectAsState()
    val categories by container.categoryOverrides.overrides.collectAsState()
    val pictureKeys by container.imageStore.keysWithImage.collectAsState()

    // What the thing being named is filed under: this one payment, or its sender or receiver.
    val targetKey = if (forTransaction) CategoryKey.messageKey(item.id) else CategoryKey.of(item)
    // Names already given, best fit to what is typed first, never counting this thing's own current name.
    val suggestions = androidx.compose.runtime.remember(name, nicknames, targetKey) {
        com.zyke.message.domain.nickname.NicknameSuggestions.suggest(name, nicknames.filterKeys { it != targetKey })
    }
    // If the name is one already used, what it already has, which this one will take too.
    val inherited = androidx.compose.runtime.remember(name, nicknames, categories, pictureKeys, targetKey) {
        targetKey?.let { com.zyke.message.domain.nickname.NicknameSuggestions.inherit(name, it, nicknames, categories, pictureKeys) }
    }

    FormSheet(
        title = if (forTransaction) "Name this transaction" else "Name ${CategoryKey.label(item)}",
        onDismiss = onDismiss,
        primaryLabel = "Save",
        primaryEnabled = name.isNotBlank(),
        onPrimary = {
            com.zyke.message.data.diag.DevLog.d("ui") { "name sheet: save id=${item.id} forTransaction=$forTransaction nameLength=${name.length} inherits=${inherited != null}" }
            actions.onRename(item.id, name, forTransaction)
            // A name that is already in use brings its category and picture along, unless this one has its own already.
            if (inherited != null && targetKey != null) {
                inherited.category?.let { category ->
                    if (container.categoryOverrides.overrides.value[targetKey] == null) container.categoryOverrides.set(targetKey, category)
                }
                inherited.imageKey?.let { source ->
                    if (targetKey !in container.imageStore.keysWithImage.value) scope.launch { container.imageStore.copyChosen(source, targetKey) }
                }
            }
            onDismiss()
        },
        secondaryLabel = if (named) "Remove this name" else null,
        onSecondary = {
            com.zyke.message.data.diag.DevLog.d("ui") { "name sheet: remove id=${item.id} forTransaction=$forTransaction" }
            actions.onRename(item.id, "", forTransaction)
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
        inherited?.let { details ->
            val parts = listOfNotNull(details.category?.let { "the category ${it.displayString}" }, details.imageKey?.let { "its picture" })
            Caption("\"${name.trim()}\" is already in use: this will also take ${parts.joinToString(" and ")}.", maxLines = 3)
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
