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
    onDismiss: () -> Unit
) {
    val message = item.message
    val content = item.content
    val facts = item.detailFacts()
    val title = if (item.merchant.renamed || item.merchant.logo != MerchantLogo.PERSON) item.merchant.displayName else SenderKey.displayAddress(message.sender)
    val sender = SenderKey.displayAddress(message.sender)
    val canTag = content !is MessageContent.Otp && CategoryKey.of(item) != null
    var choosingCategory by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }
    val pickPicture = rememberPicturePicker(item)
    val imageStore = LocalImageStore.current
    val hiddenLogos by (imageStore?.hiddenLogos ?: kotlinx.coroutines.flow.MutableStateFlow(emptySet<String>())).collectAsState()

    AppBottomSheet(onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MerchantChip(item.merchant.logo, imageKey = item.merchant.imageKey, name = item.merchant.name, description = title, size = 52.dp)
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

            if (content !is MessageContent.Otp) {
                SettingsGroup("About $title") {
                    if (canTag) {
                        DetailRow("Category", item.category.displayString + if (item.categoryOverridden) "" else " · auto") { choosingCategory = true }
                        DetailRow("Nickname", if (item.merchant.renamed) item.merchant.name else "None") { naming = true }
                        if (pickPicture != null) {
                            val pictureValue = when {
                                item.merchant.imageKey != null -> "Your picture"
                                imageStore != null && imageStore.hasLogo(item.merchant.name) && item.merchant.name.trim().lowercase() !in hiddenLogos -> "Brand logo"
                                else -> "Default"
                            }
                            DetailRow("Picture", pictureValue, onClick = pickPicture)
                            RemovePictureRow(item)
                        }
                    }
                    SwitchRow("Mute $sender", item.isMuted) { actions.onToggleMute(item.id) }
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

    if (choosingCategory) CategorySheet(item, actions) { choosingCategory = false }
    if (naming) NicknameSheet(item, title, actions) { naming = false }
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

/** Every category in a list, the current one ticked, with the automatic guess offered first when it differs. */
@Composable
private fun CategorySheet(item: ClassifiedMessage, actions: FeedActions, onDismiss: () -> Unit) {
    val suggestion = item.suggestedCategory?.takeIf { !item.categoryOverridden && it != item.category }
    AppBottomSheet(onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text(
                "Category for ${CategoryKey.label(item)}",
                Modifier.padding(bottom = 8.dp).heading(),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary
            )
            suggestion?.let {
                CategoryRow("Looks like ${it.displayString}", current = false) {
                    actions.onSetCategory(item.id, it)
                    onDismiss()
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            }
            MessageCategory.assignable.forEach { category ->
                val current = item.category == category
                CategoryRow(category.displayString, current) {
                    // Choosing the current one goes back to the automatic guess.
                    val clear = item.categoryOverridden && current
                    actions.onSetCategory(item.id, if (clear) null else category)
                    onDismiss()
                }
            }
        }
    }
}

@Composable
private fun CategoryRow(label: String, current: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().fullWidthClickable(onClickLabel = "File under $label", onClick = onClick).padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
            color = MaterialTheme.colorScheme.primary
        )
        if (current) Text("\u2713", color = MaterialTheme.colorScheme.primary)
    }
}

/** Giving the sender a nickname, in the app's one form shape. */
@Composable
private fun NicknameSheet(item: ClassifiedMessage, shownName: String, actions: FeedActions, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(if (item.merchant.renamed) shownName else "") }
    FormSheet(
        title = "Nickname",
        onDismiss = onDismiss,
        primaryLabel = "Save",
        primaryEnabled = name.isNotBlank(),
        onPrimary = { actions.onRename(item.id, name); onDismiss() },
        secondaryLabel = if (item.merchant.renamed) "Use the original name" else null,
        onSecondary = { actions.onRename(item.id, ""); onDismiss() }
    ) {
        FormField("Name", name, { name = it }, placeholder = CategoryKey.label(item), supporting = "Shown wherever ${CategoryKey.label(item)} appears")
    }
}

/**
 * Choosing a picture for this sender / receiver from the person's own photos; no permission is needed. The
 * sheet's round picture is the button. Null where pictures are not available.
 */
@Composable
private fun rememberPicturePicker(item: ClassifiedMessage): (() -> Unit)? {
    val store = LocalImageStore.current ?: return null
    val key = CategoryKey.of(item) ?: return null
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                if (!store.saveChosen(key, uri)) Toast.makeText(context, "Couldn't use that image", Toast.LENGTH_SHORT).show()
            }
        }
    }
    return { launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
}

/**
 * Takes a picture away from this sender: the one chosen, or a brand logo that turns out to be wrong. A hidden logo
 * can be brought back from the same row.
 */
@Composable
private fun RemovePictureRow(item: ClassifiedMessage) {
    val store = LocalImageStore.current ?: return
    val key = CategoryKey.of(item) ?: return
    val hidden by store.hiddenLogos.collectAsState()
    val name = item.merchant.name
    when {
        item.merchant.imageKey != null -> DetailRow("Remove picture", null) { store.removeChosen(key) }
        store.hasLogo(name) && !store.isHidden(name) -> DetailRow("This logo is wrong", null) { store.hideLogo(name) }
        store.isHidden(name) && hidden.isNotEmpty() -> DetailRow("Show the logo again", null) { store.showLogo(name) }
    }
}
