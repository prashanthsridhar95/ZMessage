package com.zyke.message.presentation.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.zyke.message.ZykeMessagesApplication
import com.zyke.message.domain.categories.CategoryKey
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.nickname.BulkEditText
import com.zyke.message.domain.nickname.CategoryChoice
import com.zyke.message.domain.nickname.NamingEdits
import com.zyke.message.domain.nickname.NicknameProfiles
import com.zyke.message.domain.nickname.NicknameSuggestions
import com.zyke.message.domain.nickname.label
import com.zyke.message.presentation.common.Choice
import com.zyke.message.presentation.common.ChipOption
import com.zyke.message.presentation.common.Chips
import com.zyke.message.presentation.common.FormField
import com.zyke.message.presentation.common.FormGroup
import com.zyke.message.presentation.common.FormSheet
import com.zyke.message.presentation.common.LedgerActions
import com.zyke.message.presentation.common.LocalCustomCategories
import com.zyke.message.presentation.common.SegmentedChoice
import com.zyke.message.presentation.common.SuggestionRow
import com.zyke.message.presentation.messages.components.CATEGORY_GROUPS
import com.zyke.message.presentation.messages.components.Caption
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** What a bulk edit sets. */
internal enum class BulkMode(val title: String) {
    NAME("Set nickname"), CATEGORY("Set category"), BOTH("Name and category");

    val setsName: Boolean get() = this != CATEGORY
    val setsCategory: Boolean get() = this != NAME
}

/**
 * Names and / or files the picked payments in one step. The name can bring a category along (as anywhere else); a
 * category picked here wins. The scope says whether only these payments change or their payees too, so future payments
 * follow. One Undo restores everything.
 */
@Composable
internal fun BulkEditSheet(
    mode: BulkMode,
    picked: List<ClassifiedMessage>,
    ledger: LedgerActions,
    appScope: CoroutineScope,
    onApplied: () -> Unit,
    onDismiss: () -> Unit
) {
    val container = (LocalContext.current.applicationContext as ZykeMessagesApplication).container
    val nicknames by container.nicknames.nicknames.collectAsState()
    val pictureKeys by container.imageStore.keysWithImage.collectAsState()
    val messages by remember(container) { container.observeMessages() }.collectAsState(initial = emptyList())
    val customs = LocalCustomCategories.current

    var name by rememberSaveable { mutableStateOf("") }
    // "b:FOOD" for a built-in category, "c:<id>" for one of the person's own.
    var chosenKey by rememberSaveable { mutableStateOf<String?>(null) }
    var wholePayee by rememberSaveable { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }

    val chosen = remember(chosenKey, customs) {
        chosenKey?.let { k ->
            when {
                k.startsWith("c:") -> CategoryChoice.custom(k.removePrefix("c:")).takeIf { c -> customs.any { it.id == c.customId } }
                else -> MessageCategory.entries.firstOrNull { it.name == k.removePrefix("b:") }?.let { CategoryChoice.of(it) }
            }
        }
    }
    val pickedIds = remember(picked) { picked.mapTo(HashSet()) { it.id } }
    val payeeKeys = remember(picked) { picked.mapNotNull { CategoryKey.of(it) }.distinct() }
    val others = remember(messages, payeeKeys, pickedIds) {
        val wanted = payeeKeys.toHashSet()
        messages.count { it.id !in pickedIds && CategoryKey.of(it) in wanted }
    }
    val targets = remember(picked, wholePayee, messages) {
        if (!wholePayee) picked.map { NamingEdits.Target(CategoryKey.messageKey(it.id), setOf(it.id)) }
        else {
            val byPayee = HashMap<String, MutableSet<Long>>()
            val singles = ArrayList<NamingEdits.Target>()
            val payeeOf = picked.associate { it.id to CategoryKey.of(it) }
            for (m in picked) {
                val k = payeeOf[m.id]
                if (k == null) singles += NamingEdits.Target(CategoryKey.messageKey(m.id), setOf(m.id)) else byPayee.getOrPut(k) { HashSet() } += m.id
            }
            for (m in messages) CategoryKey.of(m)?.let { k -> byPayee[k]?.add(m.id) }
            byPayee.map { (k, ids) -> NamingEdits.Target(k, ids) } + singles
        }
    }
    val newName = if (mode.setsName) name.trim() else ""
    val profile = remember(newName, chosen, nicknames, messages, pictureKeys, targets) {
        if (newName.isEmpty() || chosen != null) null
        else NicknameProfiles.profile(
            newName, nicknames, messages, pictureKeys,
            excludeIds = targets.flatMapTo(HashSet()) { it.messageIds }, excludeKeys = targets.map { it.key }.toSet()
        )
    }
    val inheritedLabel = profile?.category?.takeIf { !it.isEmpty }?.label(customs).orEmpty()
    val categoryLabel = if (mode.setsCategory && chosen != null) chosen.label(customs) else inheritedLabel
    val preview = BulkEditText.preview(picked.size, newName, categoryLabel, fromName = categoryLabel.isNotEmpty() && categoryLabel == inheritedLabel && !(mode.setsCategory && chosen != null))
    val suggestions = remember(name, nicknames) { NicknameSuggestions.suggest(name, nicknames) }
    val ready = (!mode.setsName || newName.isNotEmpty()) && (!mode.setsCategory || chosen != null)

    FormSheet(
        title = mode.title,
        subtitle = if (picked.size == 1) "1 payment selected" else "${picked.size} payments selected",
        onDismiss = onDismiss,
        asPage = true,
        primaryLabel = "Apply",
        primaryEnabled = ready,
        hint = if (mode == BulkMode.BOTH) "Give a name and choose a category" else null,
        onPrimary = {
            com.zyke.message.data.diag.DevLog.d("ledger") {
                "bulk edit: mode=$mode payments=${picked.size} targets=${targets.size} wholePayee=$wholePayee name=${newName.isNotEmpty()} category=${chosen != null}"
            }
            ledger.edit(
                NamingEdits.Request(targets, newName.takeIf { mode.setsName }, chosen.takeIf { mode.setsCategory }),
                messages, pictureKeys, customs,
                copyPicture = { from, to -> appScope.launch { container.imageStore.copyChosen(from, to) } },
                removePicture = { container.imageStore.removeChosen(it) },
                summary = BulkEditText.done(picked.size)
            )
            onApplied()
        }
    ) {
        if (mode.setsName) {
            FormField(
                "Nickname", name, { name = it }, placeholder = "e.g. Bajaj SIP",
                capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences
            )
            if (suggestions.isNotEmpty()) {
                FormGroup(if (name.isBlank()) "Names you have used" else "Names you have used that fit") {
                    SuggestionRow(suggestions.map { it.name }) { name = it }
                }
            }
        }
        if (mode.setsCategory) {
            FormGroup("Category") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CATEGORY_GROUPS.forEach { (title, members) ->
                        val inGroup = members.filter { it in MessageCategory.assignable }
                        if (inGroup.isNotEmpty()) {
                            Caption(title)
                            Chips(
                                inGroup.map { ChipOption(it.displayString, chosen?.category == it) },
                                onSelect = { i -> val k = "b:${inGroup[i].name}"; chosenKey = if (chosenKey == k) null else k }
                            )
                        }
                    }
                    Caption("Your categories")
                    Chips(
                        customs.map { ChipOption(it.name, chosen?.customId == it.id) } + ChipOption("＋ Add category", false),
                        onSelect = { i ->
                            if (i < customs.size) { val k = "c:${customs[i].id}"; chosenKey = if (chosenKey == k) null else k } else adding = true
                        }
                    )
                }
            }
        }
        FormGroup("Apply to") {
            SegmentedChoice(
                listOf(Choice(false, "Only these"), Choice(true, "And future payments")),
                wholePayee, { wholePayee = it }
            )
            Caption(
                if (!wholePayee) "Only the ${picked.size} selected ${if (picked.size == 1) "payment" else "payments"} change."
                else "Their ${payeeKeys.size} ${if (payeeKeys.size == 1) "payee" else "payees"} change too: $others other ${if (others == 1) "payment" else "payments"} and everything that arrives later.",
                maxLines = 3
            )
        }
        Caption(preview, maxLines = 4)
        if (profile?.imageKey != null) Caption("\"$newName\" is already in use: this will also take its picture.", maxLines = 3)
    }
    if (adding) {
        com.zyke.message.presentation.common.CategoryEditSheet(
            existing = null, onDismiss = { adding = false },
            onSaved = { made -> chosenKey = "c:${made.id}"; adding = false }
        )
    }
}
