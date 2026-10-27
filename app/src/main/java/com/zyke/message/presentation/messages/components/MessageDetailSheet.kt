package com.zyke.message.presentation.messages.components

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
 * One message, in full, in a bottom sheet: who and when, the numbers we understood, the original text
 * (selectable), and the actions that make sense for this kind of message. The most useful action is
 * the single filled button; the rest step down.
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

    // skipPartiallyExpanded: the sheet is exactly as tall as its content (up to the screen), instead of
    // opening at a preset half height that hides the rest behind a drag.
    AppBottomSheet(onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            val pickPicture = rememberPicturePicker(item)
            Row(verticalAlignment = Alignment.CenterVertically) {
                MerchantChip(
                    item.merchant.logo, imageKey = item.merchant.imageKey, name = item.merchant.name, description = title, size = 52.dp,
                    modifier = if (pickPicture != null) {
                        Modifier.clip(RoundedCornerShape(13.dp)).clickable(onClickLabel = "Change picture", onClick = pickPicture)
                    } else {
                        Modifier
                    }
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
                    Caption("${DateFormatters.detailTimestamp(message.date)} · ${item.category.displayString}", maxLines = 2)
                }
            }

            if (facts.isNotEmpty()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f), RoundedCornerShape(16.dp))
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    facts.forEachIndexed { index, fact ->
                        if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
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

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Caption("MESSAGE")
                SelectionContainer {
                    Text(
                        text = message.body,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }

            CategoryPicker(item, actions)

            RemovePicture(item)

            if (item.isModelParsed) {
                Caption("Read by on-device AI")
            }

            // The one action that matters most for this kind of message.
            val billLink = (content as? MessageContent.CardBill)?.paymentLink
            when {
                content is MessageContent.Otp && content.code.isNotEmpty() ->
                    AppButton("Copy code", { actions.onCopyOtp(item.id, content.code) }, Modifier.fillMaxWidth())
                billLink != null ->
                    AppButton("View / pay bill", { actions.onOpenLink(billLink) }, Modifier.fillMaxWidth())
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

            val split = actions.onSplit
            if (split != null && content is MessageContent.Transaction && !content.isReceived) {
                AppButton("Split with friends", { split(item.id) }, Modifier.fillMaxWidth(), style = ButtonStyle.TONAL)
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!isArchived) {
                    AppButton(
                        text = if (isPinned) "Unpin" else "Pin",
                        onClick = { actions.onTogglePin(item.id) },
                        modifier = Modifier.weight(1f),
                        style = ButtonStyle.OUTLINE,
                        size = ButtonSize.COMPACT
                    )
                }
                AppButton(
                    text = if (isArchived) "Restore" else "Archive",
                    onClick = {
                        if (isArchived) actions.onUnarchive(item.id) else actions.onArchive(item.id)
                        if (isArchived) onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                    style = ButtonStyle.OUTLINE,
                    size = ButtonSize.COMPACT
                )
            }

            if (content !is MessageContent.Otp && CategoryKey.of(item) != null) {
                RenameSender(item, title, actions)
            }

            if (content !is MessageContent.Otp) {
                val sender = SenderKey.displayAddress(message.sender)
                AppButton(
                    text = if (item.isMuted) "Unmute $sender" else "Mute $sender",
                    onClick = { actions.onToggleMute(item.id) },
                    modifier = Modifier.fillMaxWidth(),
                    style = ButtonStyle.TEXT,
                    size = ButtonSize.COMPACT
                )
                if (!item.isMuted) {
                }
            }

            if (!inConversation) {
                LinkButton("More from $title", onMoreFromSender)
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

/**
 * The category this sender / receiver is filed under, with a button to change it. The full list is another
 * sheet, so this one stays short.
 */
@Composable
private fun CategoryPicker(item: ClassifiedMessage, actions: FeedActions) {
    if (item.content is MessageContent.Otp) return
    if (CategoryKey.of(item) == null) return
    var choosing by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Caption("CATEGORY FOR ${CategoryKey.label(item).uppercase()}", maxLines = 2)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                item.category.displayString,
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            AppButton("Change", { choosing = true }, style = ButtonStyle.TONAL, size = ButtonSize.COMPACT)
        }
        val suggestion = item.suggestedCategory
        if (suggestion != null && !item.categoryOverridden) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Looks like ${suggestion.displayString}",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
                AppButton("Apply", { actions.onSetCategory(item.id, suggestion) }, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT)
            }
        }
        Caption(
            if (item.categoryOverridden) "Chosen by you"
            else "Picked automatically",
            maxLines = 2
        )
    }

    if (choosing) {
        AppBottomSheet({ choosing = false }) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
                Text(
                    "Category for ${CategoryKey.label(item)}",
                    Modifier.padding(bottom = 8.dp),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                MessageCategory.assignable.forEach { category ->
                    val current = item.category == category
                    Row(
                        Modifier.fillMaxWidth()
                            .fullWidthClickable(onClickLabel = "File under ${category.displayString}") {
                                // Choosing the current one goes back to the automatic guess.
                                val clear = item.categoryOverridden && current
                                actions.onSetCategory(item.id, if (clear) null else category)
                                choosing = false
                            }
                            .padding(vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            category.displayString,
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (current) Text("\u2713", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
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
 * can be brought back from the same place.
 */
@Composable
private fun RemovePicture(item: ClassifiedMessage) {
    val store = LocalImageStore.current ?: return
    val key = CategoryKey.of(item) ?: return
    val hidden by store.hiddenLogos.collectAsState()
    val name = item.merchant.name
    when {
        item.merchant.imageKey != null ->
            AppButton("Remove picture", { store.removeChosen(key) }, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT)
        store.hasLogo(name) && !store.isHidden(name) ->
            AppButton("This logo is wrong", { store.hideLogo(name) }, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT)
        store.isHidden(name) && hidden.isNotEmpty() ->
            AppButton("Show the logo again", { store.showLogo(name) }, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT)
    }
}

/** A nickname for this sender / receiver, shown everywhere their name appears. */
@Composable
private fun RenameSender(item: ClassifiedMessage, shownName: String, actions: FeedActions) {
    var renaming by remember { mutableStateOf(false) }
    AppButton(
        text = if (item.merchant.renamed) "Change nickname" else "Give a nickname",
        onClick = { renaming = true },
        modifier = Modifier.fillMaxWidth(),
        style = ButtonStyle.TEXT,
        size = ButtonSize.COMPACT
    )
    if (renaming) {
        var name by rememberSaveable { mutableStateOf(if (item.merchant.renamed) shownName else "") }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Nickname for ${CategoryKey.label(item)}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Nickname") })
                    Caption("Shown wherever this name appears")
                }
            },
            confirmButton = {
                AppButton(
                    "Save",
                    { actions.onRename(item.id, name); renaming = false },
                    style = ButtonStyle.TEXT, size = ButtonSize.COMPACT, enabled = name.isNotBlank()
                )
            },
            dismissButton = {
                if (item.merchant.renamed) {
                    AppButton("Use original", { actions.onRename(item.id, ""); renaming = false }, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT)
                } else {
                    AppButton("Cancel", { renaming = false }, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT)
                }
            }
        )
    }
}

