package com.zyke.message.presentation.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zyke.message.ZykeMessagesApplication
import com.zyke.message.domain.categories.CategoryKey
import com.zyke.message.domain.categories.Rules
import com.zyke.message.domain.categories.SimilarSenders
import com.zyke.message.domain.categories.TaughtRule
import com.zyke.message.domain.categories.Tokens
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.presentation.common.AppButton
import com.zyke.message.presentation.common.ButtonSize
import com.zyke.message.presentation.common.ButtonStyle
import com.zyke.message.presentation.common.EmptyState
import com.zyke.message.presentation.common.FormField
import com.zyke.message.presentation.common.FormSheet
import com.zyke.message.presentation.common.LocalAppSnackbar
import com.zyke.message.presentation.common.PickField
import com.zyke.message.presentation.common.rememberLedgerActions
import com.zyke.message.presentation.dashboard.components.SectionCard
import com.zyke.message.presentation.messages.components.Caption
import com.zyke.message.presentation.messages.components.RowTitle

/** "Your rules": what the person has taught the app about senders, receivers and single payments, to look at, change or forget. */
@Composable
internal fun RulesSection() {
    val container = (LocalContext.current.applicationContext as ZykeMessagesApplication).container
    val messages by remember(container) { container.observeMessages() }.collectAsStateWithLifecycle(emptyList<ClassifiedMessage>())
    val overrides by container.categoryOverrides.overrides.collectAsStateWithLifecycle()
    val nicknames by container.nicknames.nicknames.collectAsStateWithLifecycle()
    val pictures by container.imageStore.keysWithImage.collectAsStateWithLifecycle()
    val words by container.tokenRules.rules.collectAsStateWithLifecycle()
    val actions = rememberLedgerActions()
    val view = remember(messages, overrides, nicknames, pictures, words) { Rules.describe(messages, overrides, nicknames, pictures, words) }
    val suggestions = remember(overrides, words) { Rules.suggestTokenRules(overrides, words) }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }

    if (view.isEmpty && suggestions.isEmpty()) {
        EmptyState("Nothing taught yet", "When you file a payment under a category or give a sender a name, it shows up here.")
        return
    }
    suggestions.forEach { s ->
        SectionCard("Suggested rule") {
            RowTitle("Anything with \"${Rules.titleCase(s.token)}\" under ${s.category.displayString}?")
            Caption("You filed ${s.receivers} different receivers with that word there.", maxLines = 2)
            AppButton("Add rule", { container.tokenRules.set(s.token, s.category) }, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT)
        }
    }
    if (view.rules.isNotEmpty()) SectionCard("Senders and receivers") {
        view.rules.forEach { rule -> RuleRow(rule, { editing = rule.key }, {
            val old = rule
            container.categoryOverrides.set(rule.key, null); container.nicknames.set(rule.key, null)
            actions.withUndo("Rule forgotten") {
                old.category?.let { container.categoryOverrides.set(old.key, it) }
                old.nickname?.let { container.nicknames.set(old.key, it) }
            }
        }) }
    }
    if (view.tokenRules.isNotEmpty()) SectionCard("Word rules") {
        view.tokenRules.forEach { row ->
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    RowTitle("Anything with \"${Rules.titleCase(row.token)}\"")
                    Caption("${row.category.displayString} · ${count(row.affects)}", maxLines = 2)
                }
                AppButton("Delete", {
                    container.tokenRules.set(row.token, null)
                    actions.withUndo("Rule forgotten") { container.tokenRules.set(row.token, row.category) }
                }, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT)
            }
        }
    }
    if (view.singles.isNotEmpty()) SectionCard("Single payments") {
        view.singles.forEach { rule -> RuleRow(rule, { editing = rule.key }, {
            container.categoryOverrides.set(rule.key, null); container.nicknames.set(rule.key, null)
            actions.withUndo("Rule forgotten") {
                rule.category?.let { container.categoryOverrides.set(rule.key, it) }
                rule.nickname?.let { container.nicknames.set(rule.key, it) }
            }
        }) }
    }

    val open = (view.rules + view.singles).firstOrNull { it.key == editing }
    if (open != null) EditRuleSheet(
        rule = open,
        onSave = { category, name ->
            if (open.category != null && category != null) container.categoryOverrides.set(open.key, category)
            if (open.nickname != null && !name.isNullOrBlank()) container.nicknames.set(open.key, name)
            editing = null
        },
        onDismiss = { editing = null }
    )
}

private fun count(n: Int) = if (n == 1) "1 message" else "$n messages"

@Composable
private fun RuleRow(rule: TaughtRule, onEdit: () -> Unit, onDelete: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        RowTitle(rule.label)
        val parts = buildList {
            rule.category?.let { add(it.displayString) }
            rule.nickname?.let { add("Called $it") }
            if (rule.hasPicture) add("Has a picture")
            add(count(rule.affects))
        }
        Caption(parts.joinToString(" · "), maxLines = 3)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppButton("Edit", onEdit, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT)
            AppButton("Delete", onDelete, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT)
        }
    }
}

@Composable
private fun EditRuleSheet(rule: TaughtRule, onSave: (MessageCategory?, String?) -> Unit, onDismiss: () -> Unit) {
    var category by rememberSaveable { mutableStateOf(rule.category) }
    var name by rememberSaveable { mutableStateOf(rule.nickname.orEmpty()) }
    val categories = MessageCategory.assignable
    FormSheet(
        title = rule.label,
        onDismiss = onDismiss,
        primaryLabel = "Save",
        primaryEnabled = rule.nickname == null || name.isNotBlank(),
        onPrimary = { onSave(category, name) }
    ) {
        if (rule.category != null) PickField(
            "Category", categories.map { it.displayString }, category?.displayString,
            onPick = { picked -> category = categories.firstOrNull { it.displayString == picked } }
        )
        if (rule.nickname != null) FormField("Name", name, { name = it })
    }
}

/**
 * Watches for the person filing a sender / receiver under a category and, when others alike are still unfiled (or
 * their choices now repeat), offers the one tap to do the same for them. A snackbar, so nothing blocks and it goes by itself.
 */
@Composable
fun LearningOffers() {
    val container = (LocalContext.current.applicationContext as ZykeMessagesApplication).container
    val snackbar = LocalAppSnackbar.current
    val overrides by container.categoryOverrides.overrides.collectAsStateWithLifecycle()
    val messages by remember(container) { container.observeMessages() }.collectAsStateWithLifecycle(emptyList<ClassifiedMessage>())
    val latest by rememberUpdatedState(messages)
    var seen by remember { mutableStateOf<Map<String, MessageCategory>?>(null) }
    LaunchedEffect(overrides) {
        val before = seen
        seen = overrides
        if (before == null) return@LaunchedEffect
        val added = overrides.filter { (key, category) -> !key.startsWith("msg:") && before[key] != category }
        if (added.size != 1) return@LaunchedEffect
        val (key, category) = added.entries.single()
        val chosen = latest.firstOrNull { CategoryKey.of(it) == key } ?: return@LaunchedEffect
        val similar = SimilarSenders.find(chosen, latest, overrides)
        if (similar.isNotEmpty()) {
            val names = similar.take(2).joinToString(", ") { it.label }
            val more = if (similar.size > 2) " and ${similar.size - 2} more" else ""
            val result = snackbar.showSnackbar("Also file $names$more under ${category.displayString}?", actionLabel = "File them", duration = SnackbarDuration.Long)
            if (result == SnackbarResult.ActionPerformed) similar.forEach { container.categoryOverrides.set(it.key, category) }
            return@LaunchedEffect
        }
        val words = Tokens.of(key).toSet()
        val rule = Rules.suggestTokenRules(overrides, container.tokenRules.rules.value).firstOrNull { it.category == category && it.token in words }
        if (rule != null) {
            val result = snackbar.showSnackbar(
                "Anything with \"${Rules.titleCase(rule.token)}\" under ${category.displayString}?", actionLabel = "Add rule", duration = SnackbarDuration.Long
            )
            if (result == SnackbarResult.ActionPerformed) container.tokenRules.set(rule.token, rule.category)
        }
    }
}
