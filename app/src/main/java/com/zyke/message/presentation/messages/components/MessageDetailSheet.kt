package com.zyke.message.presentation.messages.components

import com.zyke.message.presentation.common.AppBottomSheet
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
    val title = if (item.merchant.logo != MerchantLogo.PERSON) item.merchant.displayName else SenderKey.displayAddress(message.sender)

    // skipPartiallyExpanded: the sheet is exactly as tall as its content (up to the screen), instead of
    // opening at a preset half height that hides the rest behind a drag.
    AppBottomSheet(onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MerchantChip(item.merchant.logo, description = title, size = 52.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
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

            // The one action that matters most for this kind of message.
            val billLink = (content as? MessageContent.CardBill)?.paymentLink
            when {
                content is MessageContent.Otp && content.code.isNotEmpty() ->
                    AppButton("Copy code", { actions.onCopyOtp(item.id, content.code) }, Modifier.fillMaxWidth())
                billLink != null ->
                    AppButton("View / pay bill", { actions.onOpenLink(billLink) }, Modifier.fillMaxWidth())
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

            if (!inConversation) {
                LinkButton("More from $title", onMoreFromSender)
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

/** File this sender / receiver under a category; it applies to every message with them and to the dashboard. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryPicker(item: ClassifiedMessage, actions: FeedActions) {
    if (item.content is MessageContent.Otp) return
    if (CategoryKey.of(item) == null) return

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Caption("CATEGORY FOR ${CategoryKey.label(item).uppercase()}", maxLines = 2)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            MessageCategory.assignable.forEach { category ->
                FilterChip(
                    selected = item.category == category,
                    onClick = {
                        // Tapping the chosen one goes back to the automatic guess.
                        val clear = item.categoryOverridden && item.category == category
                        actions.onSetCategory(item.id, if (clear) null else category)
                    },
                    label = { Text(category.displayString) }
                )
            }
        }
        Caption(
            if (item.categoryOverridden) "Chosen by you. Tap it again to go back to automatic." else "Automatic. Pick one to change it.",
            maxLines = 2
        )
    }
}
