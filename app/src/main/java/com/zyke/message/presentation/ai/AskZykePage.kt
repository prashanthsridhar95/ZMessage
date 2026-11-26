package com.zyke.message.presentation.ai

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zyke.message.R
import com.zyke.message.domain.ai.AskSuggestions
import com.zyke.message.domain.cycle.CycleRule
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.presentation.common.LocalOpenSearch
import com.zyke.message.presentation.common.LocalOpenSettings
import com.zyke.message.presentation.common.AppPage
import com.zyke.message.presentation.common.SearchRequest
import com.zyke.message.presentation.common.Text
import com.zyke.message.presentation.common.heading
import com.zyke.message.presentation.messages.components.Caption
import com.zyke.message.presentation.motion.rememberHaptics
import com.zyke.message.presentation.motion.rememberMotion
import com.zyke.message.presentation.settings.SettingsDeepLink
import com.zyke.message.presentation.theme.Spacing

private const val BUBBLE_SHARE = 0.85f
private const val SETTINGS_AI_PAGE = "AI"
private const val MAX_INPUT_LINES = 4
private val FieldShape = RoundedCornerShape(24.dp)

/**
 * Ask Zyke: a page of its own for asking about your money, laid out like a chat. The person's questions are bubbles on the right and
 * Zyke's answers bubbles on the left. The model only reads each question (and, for "and last month?", the request before it); the
 * figures in every answer are worked out by the app from the messages. The chat is kept in memory for this run of the app and never saved.
 *
 * @param onOpenMessage opens one payment's detail; the page stays, and the detail opens over it
 */
@Composable
fun AskZykePage(messages: List<ClassifiedMessage>, cycle: CycleRule, onOpenMessage: (Long) -> Unit, onClose: () -> Unit) {
    val ai = rememberAi()
    val chat = AskChatSession.chat
    val demo = ai.demo
    val openSearch = LocalOpenSearch.current
    val openSettings = LocalOpenSettings.current
    val haptics = rememberHaptics()
    var draft by rememberSaveable { mutableStateOf("") }
    val send: (String) -> Unit = { text ->
        haptics.tick()
        chat.send(text, ai, messages, cycle)
    }
    val actions = ChatActions(
        ask = send,
        open = onOpenMessage,
        search = { question -> SearchRequest.pending = question; onClose(); openSearch() },
        setup = { SettingsDeepLink.pending = SETTINGS_AI_PAGE; onClose(); openSettings() },
        normalSearch = { onClose(); openSearch() }
    )
    LaunchedEffect(demo) {
        chat.enter(demo)
        chat.queued?.let { question -> chat.queued = null; send(question) }
    }
    AppPage(
        title = "Ask Zyke", onBack = onClose, scrollable = false,
        actions = { ChatMenu(canClear = chat.entries.isNotEmpty() && !demo, onClear = { chat.clear(); draft = "" }) }
    ) {
        ChatPane(demo, actions)
        InputBar(
            draft, { draft = it }, chat.thinking, demo,
            onSend = { text -> send(text); draft = "" }, onStop = chat::stop
        )
    }
}

@Composable
private fun ChatMenu(canClear: Boolean, onClear: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Box(
            Modifier.size(56.dp).clickable(role = Role.Button, onClickLabel = "Open the chat menu") { open = true },
            contentAlignment = Alignment.Center
        ) {
            Image(
                painterResource(R.drawable.ic_more_vert_24), contentDescription = "Chat menu",
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary)
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Clear chat") }, enabled = canClear, onClick = { open = false; onClear() })
            DropdownMenuItem(text = { Text("Chats are not saved") }, enabled = false, onClick = { })
        }
    }
}

@Composable
private fun ColumnScope.ChatPane(demo: Boolean, actions: ChatActions) {
    val chat = AskChatSession.chat
    val entries = chat.entries
    val thinking = chat.thinking
    val motion = rememberMotion()
    val listState = rememberLazyListState()
    val count = entries.size
    // The newest question goes to the top with its answer under it; while thinking, the question stays in view.
    LaunchedEffect(count, thinking) {
        if (count > 0) {
            val last = entries[count - 1]
            val target = if (last is ChatEntry.Replied && count >= 2) count - 2 else count - 1
            if (motion.reduced) listState.scrollToItem(target) else listState.animateScrollToItem(target)
        }
    }
    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
        if (count == 0 && !thinking) {
            EmptyChat(demo, actions)
        } else {
            val bubbleMax = (maxWidth - Spacing.lg * 2) * BUBBLE_SHARE
            LazyColumn(
                Modifier.fillMaxSize(), listState,
                contentPadding = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                itemsIndexed(entries, key = { _, entry -> entry.id }) { index, entry ->
                    when (entry) {
                        is ChatEntry.Said -> PersonBubble(entry.text, bubbleMax)
                        is ChatEntry.Replied -> ZykeBubble(
                            entry.reply, entry.id, entry.at,
                            avatar = index == 0 || entries[index - 1] is ChatEntry.Said,
                            newest = index == count - 1 && !thinking, maxWidth = bubbleMax, actions = actions
                        )
                    }
                }
                if (thinking) item(key = "thinking") { ThinkingBubble(bubbleMax) }
            }
        }
    }
}

/** Before the first question: a greeting, what it can and cannot do, and four things to tap. Scrolls when the screen is small or the text large. */
@Composable
private fun EmptyChat(demo: Boolean, actions: ChatActions) {
    val ready = rememberAiReady()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalArrangement = Arrangement.Center
    ) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            ZykeAvatar(size = 56.dp)
            Text("Ask Zyke", Modifier.heading(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Text(
                "Ask anything about your money. It all stays on this phone.", textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Caption(
                "Zyke only reads your question. The figures are worked out by the app from your messages. Chats are not saved.",
                textAlign = TextAlign.Center, maxLines = 4
            )
        }
        Spacer(Modifier.height(Spacing.xl))
        if (!ready && !demo) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) { NotReadyContent(newest = false, actions = actions) }
            Spacer(Modifier.height(Spacing.md))
        }
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            AskSuggestions.starters.forEach { starter -> ChatChip(starter, enabled = ready) { actions.ask(starter) } }
        }
    }
}

/** The text box pinned above the keyboard and the navigation bar: a rounded field that grows to four lines, and a send button that becomes stop while Zyke is thinking. */
@Composable
private fun InputBar(draft: String, onDraft: (String) -> Unit, thinking: Boolean, demo: Boolean, onSend: (String) -> Unit, onStop: () -> Unit) {
    val ink = MaterialTheme.colorScheme.onSurface
    val canSend = draft.isNotBlank() && !thinking && !demo
    val submit = { if (canSend) onSend(draft.trim()) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.Bottom
    ) {
        BasicTextField(
            draft, onDraft,
            Modifier.weight(1f).semantics { contentDescription = "Ask Zyke" },
            enabled = !demo,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = ink),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { submit() }),
            maxLines = MAX_INPUT_LINES,
            decorationBox = { inner ->
                Box(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(FieldShape).background(ink.copy(alpha = 0.07f))
                        .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (draft.isEmpty()) {
                        Text(
                            if (demo) "Examples only in the demo" else "Ask Zyke", style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1
                        )
                    }
                    inner()
                }
            }
        )
        SendButton(thinking = thinking, enabled = canSend, onSend = { submit() }, onStop = onStop)
    }
}

/** Send, off while there is nothing to send; while Zyke is thinking the same place is Stop. Always 48 dp, named for a screen reader. */
@Composable
private fun SendButton(thinking: Boolean, enabled: Boolean, onSend: () -> Unit, onStop: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val active = thinking || enabled
    val fill = if (active) scheme.primary else scheme.onSurface.copy(alpha = 0.08f)
    val ink: Color = if (active) scheme.onPrimary else scheme.onSurface.copy(alpha = 0.38f)
    Box(
        Modifier.size(48.dp).clip(CircleShape).background(fill)
            .clickable(enabled = active, role = Role.Button, onClickLabel = if (thinking) "Stop" else "Send") { if (thinking) onStop() else onSend() },
        contentAlignment = Alignment.Center
    ) {
        Image(
            painterResource(if (thinking) R.drawable.ic_stop_24 else R.drawable.ic_send_24),
            contentDescription = if (thinking) "Stop" else "Send", Modifier.size(22.dp), colorFilter = ColorFilter.tint(ink)
        )
    }
}
