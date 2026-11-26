package com.zyke.message.presentation.ai

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zyke.message.R
import com.zyke.message.domain.ai.AskSuggestions
import com.zyke.message.presentation.common.AppButton
import com.zyke.message.presentation.common.ButtonSize
import com.zyke.message.presentation.common.ButtonStyle
import com.zyke.message.presentation.common.LinkButton
import com.zyke.message.presentation.common.PrivateAmount
import com.zyke.message.presentation.common.Text
import com.zyke.message.presentation.common.tappable
import com.zyke.message.presentation.format.FormatCache
import com.zyke.message.presentation.messages.components.Caption
import com.zyke.message.presentation.motion.Motion
import com.zyke.message.presentation.motion.rememberMotion
import com.zyke.message.presentation.theme.AppShapes
import com.zyke.message.presentation.theme.Spacing
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

private const val SHOWN_PAYMENTS = 3
private const val SHORT_QUESTION = 28
private val PersonShape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 6.dp)
private val ZykeShape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 6.dp, bottomEnd = 20.dp)
private val AvatarSize = 28.dp
private val TimeFormat = FormatCache.pattern("h:mm a", Locale.getDefault())

/** What a tap inside a Zyke bubble can do: ask something, open a payment, go to Search or to the AI settings. */
internal class ChatActions(
    val ask: (String) -> Unit,
    val open: (Long) -> Unit,
    val search: (String) -> Unit,
    val setup: () -> Unit,
    val normalSearch: () -> Unit
)

/** The person's message: right, in the container tone, a tighter corner at the bottom right. Spoken as "You said". */
@Composable
internal fun PersonBubble(text: String, maxWidth: Dp) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Box(
            Modifier.widthIn(max = maxWidth).clip(PersonShape).background(MaterialTheme.colorScheme.primaryContainer)
                .semantics { contentDescription = "You said" }
                .padding(horizontal = Spacing.lg, vertical = Spacing.md)
        ) {
            SelectionContainer {
                Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    }
}

/** Zyke's reply: left, in the surface container tone, a small avatar when [avatar], and the time quietly underneath. Spoken as "Zyke said". */
@Composable
internal fun ZykeBubble(reply: Reply, id: Long, at: Long, avatar: Boolean, newest: Boolean, maxWidth: Dp, actions: ChatActions) {
    ZykeRow(avatar) {
        Column(Modifier.widthIn(max = maxWidth), horizontalAlignment = Alignment.Start) {
            Column(
                Modifier.clip(ZykeShape).background(MaterialTheme.colorScheme.surfaceContainer)
                    .semantics { contentDescription = "Zyke said" }
                    .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                when (reply) {
                    is Reply.Answer -> AnswerContent(reply, id, newest, actions)
                    is Reply.NotUnderstood -> NotUnderstoodContent(reply, newest, actions)
                    is Reply.Problem -> {
                        ZykeText(reply.text, newest)
                        AppButton("Try again", { actions.ask(reply.question) }, Modifier.fillMaxWidth(), style = ButtonStyle.OUTLINE, size = ButtonSize.COMPACT)
                    }
                    Reply.NotReady -> NotReadyContent(newest, actions)
                }
            }
            Caption(TimeFormat.format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())), Modifier.padding(start = Spacing.sm, top = Spacing.xs))
        }
    }
}

/** While the model reads the question: "Thinking…" in words, three dots that appear once (instant when motion is reduced), and how to stop. */
@Composable
internal fun ThinkingBubble(maxWidth: Dp) {
    ZykeRow(avatar = true) {
        Column(
            Modifier.widthIn(max = maxWidth).clip(ZykeShape).background(MaterialTheme.colorScheme.surfaceContainer)
                .semantics { contentDescription = "Zyke is thinking" }
                .padding(horizontal = Spacing.lg, vertical = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            Text(
                "Thinking…", Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface
            )
            ThinkingDots()
            Caption("Working it out on this phone. Tap stop to cancel.", maxLines = 3)
        }
    }
}

/** Three dots that fade in one after another, once. Not a loop; with reduced motion or Lite they are simply there. */
@Composable
private fun ThinkingDots() {
    val motion = rememberMotion()
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    Row(Modifier.padding(vertical = Spacing.xs).clearAndSetSemantics { }, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        repeat(3) { index ->
            val level by animateFloatAsState(
                if (shown) 1f else 0.2f, motion.tween<Float>(Motion.STANDARD_MS, delayMs = index * Motion.QUICK_MS), label = "dot"
            )
            Box(Modifier.size(8.dp).graphicsLayer { this.alpha = level }.background(MaterialTheme.colorScheme.onSurfaceVariant, CircleShape))
        }
    }
}

@Composable
private fun ZykeRow(avatar: Boolean, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start, verticalAlignment = Alignment.Top) {
        if (avatar) ZykeAvatar() else Spacer(Modifier.width(AvatarSize))
        Spacer(Modifier.width(Spacing.sm))
        content()
    }
}

/** The Zyke mark in a small round tonal disc. Decoration: the bubble beside it says who is speaking. */
@Composable
internal fun ZykeAvatar(size: Dp = AvatarSize) {
    Box(
        Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer).clearAndSetSemantics { },
        contentAlignment = Alignment.Center
    ) {
        Image(
            painterResource(R.drawable.zyke_icon), contentDescription = "Zyke", Modifier.size(size * 0.65f),
            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onPrimaryContainer)
        )
    }
}

/** One paragraph of Zyke's, selectable. The newest one is a live region, so a screen reader reads the answer as it arrives. */
@Composable
private fun ZykeText(text: String, newest: Boolean) {
    SelectionContainer {
        Text(
            text, if (newest) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier,
            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun AnswerContent(reply: Reply.Answer, id: Long, newest: Boolean, actions: ChatActions) {
    reply.figure?.let { figure ->
        if (reply.isCount) Text(figure, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        else PrivateAmount(figure, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, maxLines = 1, fit = true)
    }
    ZykeText(reply.sentence, newest)
    Caption("Read as: " + reply.understood.joinToString(" · "), maxLines = 3)
    if (reply.example) Caption("Example only: made up, not from your messages.", maxLines = 3)
    var showAll by rememberSaveable(id) { mutableStateOf(false) }
    val shown = if (showAll) reply.payments else reply.payments.take(SHOWN_PAYMENTS)
    shown.forEach { line -> PaymentRow(line, actions.open) }
    if (reply.payments.size > SHOWN_PAYMENTS) {
        AppButton(
            if (showAll) "Show fewer" else "See all ${reply.payments.size}", { showAll = !showAll },
            Modifier.fillMaxWidth(), style = ButtonStyle.OUTLINE, size = ButtonSize.COMPACT
        )
    }
}

@Composable
private fun PaymentRow(line: PaymentLine, onOpen: (Long) -> Unit) {
    val id = line.messageId
    val tap = if (id != null) Modifier.tappable(onClick = { onOpen(id) }, role = Role.Button, onClickLabel = "Open the payment to ${line.name}") else Modifier
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).then(tap), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(line.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
            Caption(line.day)
        }
        if (line.amount.isNotEmpty()) {
            PrivateAmount(line.amount, Modifier.padding(start = Spacing.sm), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

@Composable
private fun NotUnderstoodContent(reply: Reply.NotUnderstood, newest: Boolean, actions: ChatActions) {
    ZykeText("I couldn't read that as a question about your spending. Try one of these:", newest)
    Caption(reply.reason, maxLines = 3)
    AskSuggestions.starters.take(SHOWN_PAYMENTS).forEach { starter -> ChatChip(starter) { actions.ask(starter) } }
    val shortQuestion = if (reply.question.length > SHORT_QUESTION) reply.question.take(SHORT_QUESTION).trimEnd() + "…" else reply.question
    AppButton("Search messages for “$shortQuestion”", { actions.search(reply.question) }, Modifier.fillMaxWidth(), size = ButtonSize.COMPACT)
}

/** The model is not there: say so plainly, and offer the two ways on. */
@Composable
internal fun NotReadyContent(newest: Boolean, actions: ChatActions) {
    ZykeText("The on-device model is not ready yet, so I can't work that out.", newest)
    LinkButton("Set up on-device AI", actions.setup)
    AppButton("Use normal search", actions.normalSearch, Modifier.fillMaxWidth(), style = ButtonStyle.OUTLINE, size = ButtonSize.COMPACT)
}

/** A suggestion: one tap asks it. 48 dp tall, tonal, named as a button. */
@Composable
internal fun ChatChip(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.heightIn(min = 48.dp).clip(AppShapes.control).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f))
            .tappable(onClick = onClick, enabled = enabled, role = Role.Button)
            .padding(horizontal = Spacing.lg),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium,
            color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
