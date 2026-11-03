package com.zyke.message.presentation.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zyke.message.R
import com.zyke.message.ZykeMessagesApplication
import com.zyke.message.domain.insights.MergeAliases
import com.zyke.message.domain.insights.MergeSuggestion
import com.zyke.message.domain.insights.RecurringGroups
import com.zyke.message.domain.insights.RecurringMerge
import com.zyke.message.domain.insights.RecurringMerges
import com.zyke.message.domain.insights.RecurringNames
import com.zyke.message.domain.insights.Subscription
import com.zyke.message.domain.nickname.NicknameSuggestions
import com.zyke.message.presentation.common.AppButton
import com.zyke.message.presentation.common.ButtonSize
import com.zyke.message.presentation.common.ButtonStyle
import com.zyke.message.presentation.common.Choice
import com.zyke.message.presentation.common.FintechCard
import com.zyke.message.presentation.common.FormField
import com.zyke.message.presentation.common.FormGroup
import com.zyke.message.presentation.common.FormSheet
import com.zyke.message.presentation.common.PickField
import com.zyke.message.presentation.common.SegmentedChoice
import com.zyke.message.presentation.common.SuggestionRow
import com.zyke.message.presentation.common.Text
import com.zyke.message.presentation.common.ordinalDay
import com.zyke.message.presentation.common.privateSpeech
import com.zyke.message.presentation.format.DisplayFormatters
import com.zyke.message.presentation.format.FormatCache
import com.zyke.message.presentation.home.HomeContract.Intent
import com.zyke.message.presentation.home.HomeContract.State
import com.zyke.message.presentation.messages.components.Caption
import com.zyke.message.presentation.messages.components.mutedColor
import java.util.Locale

private val DayMonth = FormatCache.pattern("d MMM", Locale.getDefault())

/** A name with "#2 of 4" when its payee has several alike entries. */
internal fun Subscription.fullName(): String = (title + (if (suffix.isEmpty()) "" else " $suffix")).trim()

/** "Also seen as: A, B" for a merged entry. */
internal fun Subscription.alsoSeenCaption(): String = "Also seen as: " + alsoSeenAs.joinToString(", ")

/** One entry of a row's "more" menu. */
internal class MenuEntry(val label: String, val onClick: () -> Unit)

/** The small 48dp "more" button of a row, opening a menu of [entries]. */
@Composable
internal fun RowMenu(name: String, entries: List<MenuEntry>) {
    var open by remember { mutableStateOf(false) }
    androidx.compose.foundation.layout.Box {
        IconButton(onClick = { open = true }, Modifier.size(48.dp)) {
            Icon(painterResource(R.drawable.baseline_more_horiz_24), contentDescription = "More for $name")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            entries.forEach { e ->
                DropdownMenuItem(text = { Text(e.label) }, onClick = { open = false; e.onClick() }, modifier = Modifier.heightIn(min = 48.dp))
            }
        }
    }
}

/** What a page can do with merges; the sheets it needs are shown by [rememberMergeActions]. */
internal class MergeActions(
    /** Opens the "Merge with…" picker for the entry with this key. */
    val mergeWith: (String) -> Unit,
    /** Opens the merge sheet for these entries straight away. */
    val mergeKeys: (List<String>) -> Unit,
    val unmerge: (Subscription) -> Unit,
    val rename: (String) -> Unit
) {
    /** The menu entries a row of [sub] gets; every entry can be renamed, merged and (via [extra]) grouped. */
    fun menuFor(sub: Subscription, extra: List<MenuEntry> = emptyList(), onSelect: (() -> Unit)? = null): List<MenuEntry> = buildList {
        add(MenuEntry("Rename") { rename(sub.key) })
        add(MenuEntry("Merge with…") { mergeWith(sub.key) })
        if (sub.isMerged) add(MenuEntry("Unmerge") { unmerge(sub) })
        addAll(extra)
        if (onSelect != null) add(MenuEntry("Select several", onSelect))
    }
}

/**
 * Holds the open merge picker / sheet / rename sheet of a page, shows them, and returns what the rows call. A merge
 * is one write and one Undo.
 */
@Composable
internal fun rememberMergeActions(state: State, onIntent: (Intent) -> Unit): MergeActions {
    val handler = rememberPlanActionHandler(onIntent)
    var keys by rememberSaveable { mutableStateOf<List<String>?>(null) }
    var choose by rememberSaveable { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf<String?>(null) }
    val pool = remember(state.subscriptionOverview) { state.subscriptionOverview.lines.map { it.subscription }.distinctBy { it.key } }
    val merges = state.recurringMerges
    val names = state.recurringNames

    fun write(next: List<RecurringMerge>, previous: List<RecurringMerge>, text: String) {
        onIntent(Intent.SetRecurringMerges(next))
        handler.announce(text) { onIntent(Intent.SetRecurringMerges(previous)) }
    }

    keys?.let { initial ->
        MergeSheet(
            pool = pool, initial = initial, choose = choose,
            onMerge = { added, text ->
                keys = null
                write(RecurringMerges.merged(merges, added), merges, text)
            },
            onDismiss = { keys = null }
        )
    }
    renaming?.let { key ->
        val sub = pool.firstOrNull { it.key == key }
        if (sub != null) {
            val hasName = sub.alias.isNotEmpty() || state.recurringNames.containsKey(key)
            RenameSheet(
                sub, hasName,
                onSave = { name ->
                    renaming = null
                    val trimmed = name.trim()
                    // Back to the payee's own name is a reset, not a name that happens to match.
                    val next = if (trimmed == sub.payee && !sub.isMerged) RecurringNames.reset(names, RecurringGroups.keysOf(sub)) else RecurringNames.set(names, key, trimmed)
                    onIntent(Intent.SetRecurringNames(next))
                    handler.announce("Renamed to $trimmed") { onIntent(Intent.SetRecurringNames(names)) }
                },
                onReset = {
                    renaming = null
                    onIntent(Intent.SetRecurringNames(RecurringNames.reset(names, RecurringGroups.keysOf(sub))))
                    // A merged entry may carry the name it was merged under: that goes too.
                    val clearMerge = sub.isMerged && merges.any { key in it.allKeys && it.displayName != null }
                    if (clearMerge) onIntent(Intent.SetRecurringMerges(RecurringMerges.renamed(merges, key, "", System.currentTimeMillis())))
                    handler.announce("Name reset to ${sub.payee}") {
                        onIntent(Intent.SetRecurringNames(names))
                        if (clearMerge) onIntent(Intent.SetRecurringMerges(merges))
                    }
                },
                onDismiss = { renaming = null }
            )
        } else renaming = null
    }

    return remember(pool, merges, names, onIntent, handler) {
        MergeActions(
            mergeWith = { key -> keys = listOf(key); choose = true },
            mergeKeys = { list -> keys = list; choose = false },
            unmerge = { sub ->
                write(RecurringMerges.unmerged(merges, sub.key), merges, "Unmerged ${sub.title}")
            },
            rename = { renaming = it }
        )
    }
}

@Composable
private fun RenameSheet(sub: Subscription, hasName: Boolean, onSave: (String) -> Unit, onReset: () -> Unit, onDismiss: () -> Unit) {
    val container = (LocalContext.current.applicationContext as ZykeMessagesApplication).container
    val nicknames by container.nicknames.nicknames.collectAsState()
    var name by rememberSaveable { mutableStateOf(sub.title) }
    val suggestions = remember(name, nicknames) { NicknameSuggestions.suggest(name, nicknames) }
    FormSheet(
        title = "Rename", subtitle = if (sub.isMerged) "Merged · ${sub.nameCount} names" else "Shown as ${sub.fullName()}", onDismiss = onDismiss,
        primaryLabel = "Save", primaryEnabled = name.isNotBlank(), onPrimary = { onSave(name) },
        secondaryLabel = if (hasName) "Reset name" else null, onSecondary = if (hasName) onReset else null
    ) {
        FormField("Name", name, { name = it }, capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences)
        if (suggestions.isNotEmpty()) FormGroup("Names you have used") { SuggestionRow(suggestions.map { it.name }) { name = it } }
        if (sub.isMerged) Caption(sub.alsoSeenCaption(), maxLines = 4)
        else Caption("Paid to ${sub.payee}. A name you choose replaces \"#2 of 4\" in the lists.", maxLines = 3)
    }
}

private const val CUSTOM = -1

/**
 * Merging entries that are one payment under several names. First (when opened from a row's menu) a picker lists the
 * other entries, likely twins first; then the sheet asks which name to keep and previews that the payment counts once.
 * With entries from two sides (a bank's name and several fund names) it can pair them up one to one.
 */
@Composable
internal fun MergeSheet(
    pool: List<Subscription>,
    initial: List<String>,
    choose: Boolean,
    onMerge: (List<RecurringMerge>, String) -> Unit,
    onDismiss: () -> Unit
) {
    var picking by rememberSaveable { mutableStateOf(choose) }
    var selected by rememberSaveable { mutableStateOf(initial) }
    // Entries that were merged or removed while the sheet was open drop out.
    val chosen = remember(pool, selected) { selected.mapNotNull { k -> pool.firstOrNull { it.key == k } } }
    if (picking) {
        val origin = pool.firstOrNull { it.key == initial.firstOrNull() }
        MergePicker(
            origin, pool, selected,
            onToggle = { key -> selected = if (key in selected) selected - key else selected + key },
            onNext = { picking = false },
            onDismiss = onDismiss
        )
    } else {
        MergeConfirm(chosen, onMerge, onDismiss)
    }
}

@Composable
private fun MergePicker(origin: Subscription?, pool: List<Subscription>, selected: List<String>, onToggle: (String) -> Unit, onNext: () -> Unit, onDismiss: () -> Unit) {
    val others = remember(pool, origin) { pool.filter { it.key != origin?.key } }
    val likely = remember(others, origin) { if (origin == null) emptyList() else RecurringMerges.likelySame(origin, others) }
    val rest = remember(others, likely) { others.filter { o -> likely.none { it.key == o.key } } }
    FormSheet(
        title = "Merge with…",
        subtitle = origin?.let { "Pick the entries that are the same payment as ${it.fullName()}" },
        onDismiss = onDismiss,
        asPage = true,
        primaryLabel = "Next",
        primaryEnabled = selected.size >= 2,
        hint = "Pick at least one other entry",
        onPrimary = onNext
    ) {
        if (others.isEmpty()) Caption("There are no other repeating payments to merge with.", maxLines = 3)
        if (likely.isNotEmpty()) {
            FormGroup("Likely the same SIP") {
                likely.forEach { s -> androidx.compose.runtime.key(s.key) { PickRow(s, s.key in selected) { onToggle(s.key) } } }
            }
        }
        if (rest.isNotEmpty()) {
            FormGroup(if (likely.isNotEmpty()) "Other entries" else "Entries") {
                rest.forEach { s -> androidx.compose.runtime.key(s.key) { PickRow(s, s.key in selected) { onToggle(s.key) } } }
            }
        }
    }
}

@Composable
private fun PickRow(sub: Subscription, picked: Boolean, onToggle: () -> Unit) {
    val spoken = privateSpeech("${sub.fullName()}, ${DisplayFormatters.rupees(sub.amount)}, last charged ${DayMonth.format(sub.lastPaid)}")
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .clickable(role = Role.Checkbox, onClickLabel = if (picked) "Drop from merge" else "Add to merge", onClick = onToggle)
            .semantics(mergeDescendants = true) { contentDescription = spoken; toggleableState = ToggleableState(picked) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = picked, onCheckedChange = null, modifier = Modifier.size(48.dp))
        Column(Modifier.weight(1f)) {
            Text(sub.fullName(), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface, maxLines = 2)
            Caption("Last charged ${DayMonth.format(sub.lastPaid)}")
        }
        Spacer(Modifier.width(8.dp))
        Text(DisplayFormatters.rupees(sub.amount), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary, maxLines = 1)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MergeConfirm(entries: List<Subscription>, onMerge: (List<RecurringMerge>, String) -> Unit, onDismiss: () -> Unit) {
    val container = (LocalContext.current.applicationContext as ZykeMessagesApplication).container
    val nicknames by container.nicknames.nicknames.collectAsState()
    val sides = remember(entries) { RecurringMerges.sides(entries) }
    val canPair = entries.size > 2 && sides.first.isNotEmpty() && sides.second.isNotEmpty()
    var pairMode by rememberSaveable { mutableStateOf(canPair && sides.first.size > 1 && sides.second.size > 1) }
    val pairing = pairMode && canPair
    // The right-hand partner of each left-hand entry, in order ("" = none).
    var partners by rememberSaveable {
        mutableStateOf(RecurringMerges.autoPair(sides.first, sides.second).let { pairs -> sides.first.map { l -> pairs.firstOrNull { it.left.key == l.key }?.right?.key.orEmpty() } })
    }
    val partnerKeys = remember(partners, sides) { sides.first.indices.map { partners.getOrNull(it).orEmpty() } }

    // A single group: which name to keep (an entry's own, or one typed).
    val brandIndex = remember(entries) { entries.indexOfFirst { !MergeAliases.looksLikeAggregator(it.payee) }.coerceAtLeast(0) }
    var nameChoice by rememberSaveable { mutableStateOf(brandIndex) }
    var typed by rememberSaveable { mutableStateOf("") }
    val suggestions = remember(typed, nicknames) { NicknameSuggestions.suggest(typed, nicknames) }
    val keepIndex = if (nameChoice == CUSTOM || nameChoice in entries.indices) nameChoice else brandIndex
    val primary = if (keepIndex == CUSTOM) entries.getOrNull(brandIndex) else entries.getOrNull(keepIndex)
    val customName = typed.trim()

    val pairs = remember(partnerKeys, sides) {
        sides.first.mapIndexedNotNull { i, l -> sides.second.firstOrNull { it.key == partnerKeys[i] }?.let { RecurringMerges.Pairing(l, it) } }
    }
    val ready = if (pairing) pairs.isNotEmpty() else entries.size >= 2 && primary != null && (keepIndex != CUSTOM || customName.isNotEmpty())

    FormSheet(
        title = "Merge", subtitle = "${entries.size} entries",
        onDismiss = onDismiss, asPage = true,
        primaryLabel = if (pairing) "Merge ${pairs.size} pairs" else "Merge",
        primaryEnabled = ready,
        hint = if (pairing) "Pair at least one" else "Type a name or pick one to keep",
        onPrimary = {
            val now = System.currentTimeMillis()
            if (pairing) {
                val added = pairs.map { RecurringMerges.build(it.right, listOf(it.left), null, now) }
                onMerge(added, "Merged ${pairs.size * 2} entries")
            } else {
                val p = primary ?: return@FormSheet
                val others = entries.filter { it.key != p.key }
                onMerge(listOf(RecurringMerges.build(p, others, customName.takeIf { keepIndex == CUSTOM }, now)), "Merged ${entries.size} entries")
            }
        }
    ) {
        Caption("Use this when the same payment is reported under two names (for example your bank and your fund house).", maxLines = 4)
        if (canPair) {
            SegmentedChoice(listOf(Choice(false, "One payment"), Choice(true, "Pair them up")), pairMode, { pairMode = it })
        }
        if (pairing) {
            PairingSection(sides.first, sides.second, partnerKeys) { i, key ->
                val next = partnerKeys.toMutableList()
                // A partner taken by another entry swaps places, so no two entries share one.
                val taker = if (key.isEmpty()) -1 else next.indexOf(key)
                if (taker >= 0 && taker != i) next[taker] = next[i]
                next[i] = key
                partners = next
            }
            val left = sides.first.size - pairs.size
            val leftover = left + (sides.second.size - pairs.size)
            if (leftover > 0) Caption("$leftover ${if (leftover == 1) "entry stays" else "entries stay"} as they are.", maxLines = 2)
            PairPreview(pairs)
        } else {
            FormGroup("Which name to keep") {
                Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    entries.forEachIndexed { i, s ->
                        androidx.compose.runtime.key(s.key) { NameChoiceRow(s.fullName(), keepIndex == i) { nameChoice = i } }
                    }
                    NameChoiceRow("Type a name", keepIndex == CUSTOM) { nameChoice = CUSTOM }
                }
                if (keepIndex == CUSTOM) {
                    FormField("Name", typed, { typed = it }, placeholder = "e.g. Mahindra Manulife SIP", capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences)
                    if (suggestions.isNotEmpty()) SuggestionRow(suggestions.map { it.name }) { typed = it }
                }
            }
            if (primary != null) SinglePreview(primary, entries.filter { it.key != primary.key })
        }
    }
}

@Composable
private fun NameChoiceRow(label: String, picked: Boolean, onPick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .clickable(role = Role.RadioButton, onClickLabel = "Keep $label", onClick = onPick)
            .semantics(mergeDescendants = true) { stateDescription = if (picked) "Selected" else "Not selected" },
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = picked, onClick = null, modifier = Modifier.size(48.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 2)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SinglePreview(primary: Subscription, others: List<Subscription>) {
    val p = RecurringMerges.preview(primary, others)
    val cadence = if (primary.cadence == com.zyke.message.domain.insights.Cadence.MONTHLY) "monthly" else primary.cadence.label.lowercase()
    FintechCard {
        Text(
            "Will count as one ${DisplayFormatters.rupees(p.amount)} $cadence payment · ${DisplayFormatters.rupees(p.yearly)} a year (not ${DisplayFormatters.rupees(p.wouldBeYearly)})",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface
        )
        if (p.amountsDiffer) {
            FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusPill("Amounts differ", Tone.WARN)
            }
            Caption("The amount of ${primary.fullName()} is used, once.", Modifier.padding(top = 4.dp), maxLines = 2)
        }
    }
}

@Composable
private fun PairPreview(pairs: List<RecurringMerges.Pairing>) {
    if (pairs.isEmpty()) return
    val yearly = pairs.fold(java.math.BigDecimal.ZERO) { sum, p -> sum + RecurringMerges.preview(p.right, listOf(p.left)).yearly }
    val would = pairs.fold(java.math.BigDecimal.ZERO) { sum, p -> sum + RecurringMerges.preview(p.right, listOf(p.left)).wouldBeYearly }
    FintechCard {
        Text(
            "${pairs.size} ${if (pairs.size == 1) "pair" else "pairs"} will each count once: ${DisplayFormatters.rupees(yearly)} a year (not ${DisplayFormatters.rupees(would)})",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface
        )
        if (pairs.any { RecurringMerges.preview(it.right, listOf(it.left)).amountsDiffer }) {
            StatusPill("Amounts differ", Tone.WARN, Modifier.padding(top = 8.dp))
        }
        Caption("Each pair keeps the second name.", Modifier.padding(top = 4.dp), maxLines = 2)
    }
}

@Composable
private fun PairingSection(left: List<Subscription>, right: List<Subscription>, partnerKeys: List<String>, onChange: (Int, String) -> Unit) {
    val none = "No partner"
    // Names can repeat (two funds with one name): a number tells them apart in the list.
    val labels = remember(right) {
        val seen = HashMap<String, Int>()
        right.map { r ->
            val n = (seen[r.fullName()] ?: 0) + 1
            seen[r.fullName()] = n
            if (right.count { it.fullName() == r.fullName() } > 1) "${r.fullName()} ($n)" else r.fullName()
        }
    }
    FormGroup("Proposed pairs") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            left.forEachIndexed { i, l ->
                androidx.compose.runtime.key(l.key) {
                    val current = right.indexOfFirst { it.key == partnerKeys.getOrNull(i) }
                    PickField(
                        label = l.fullName() + " ↔",
                        options = listOf(none) + labels,
                        selected = if (current >= 0) labels[current] else none,
                        onPick = { picked ->
                            val idx = labels.indexOf(picked)
                            onChange(i, if (idx >= 0) right[idx].key else "")
                        }
                    )
                }
            }
        }
    }
}

/** The question "is this the same payment?" for each suggestion, with Merge and No. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MergeSuggestionCard(
    suggestions: List<MergeSuggestion>,
    onMerge: (MergeSuggestion) -> Unit,
    onNo: (MergeSuggestion) -> Unit
) {
    FintechCard {
        suggestions.forEachIndexed { index, s ->
            androidx.compose.runtime.key(s.id) {
                val a = s.first
                val b = s.second
                val aliasFirst = MergeAliases.looksLikeAggregator(b.payee) && !MergeAliases.looksLikeAggregator(a.payee)
                val x = if (aliasFirst) b else a
                val y = if (aliasFirst) a else b
                val text = "${x.fullName()} and ${y.fullName()} both charge ${DisplayFormatters.rupees(y.amount)} around the ${ordinalDay(y.lastPaid.dayOfMonth)} — the same SIP?"
                if (index > 0) Spacer(Modifier.height(12.dp))
                Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                FlowRow(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppButton("Merge", { onMerge(s) }, style = ButtonStyle.TONAL, size = ButtonSize.COMPACT)
                    AppButton("No, keep separate", { onNo(s) }, style = ButtonStyle.OUTLINE, size = ButtonSize.COMPACT)
                }
            }
        }
    }
}

/** Pinned under the list while entries are picked: Merge (one payment, two names) and Group (different payments, one provider). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MergeSelectionBar(
    count: Int,
    canMerge: Boolean,
    canGroup: Boolean,
    onMerge: () -> Unit,
    onGroup: () -> Unit,
    onClear: () -> Unit,
    onDone: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "$count selected",
            Modifier.semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite; contentDescription = if (count == 1) "1 entry selected" else "$count entries selected" },
            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary
        )
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AppButton("Merge", onMerge, size = ButtonSize.COMPACT, enabled = canMerge)
            AppButton("Group", onGroup, style = ButtonStyle.TONAL, size = ButtonSize.COMPACT, enabled = canGroup)
            AppButton("Clear", onClear, style = ButtonStyle.OUTLINE, size = ButtonSize.COMPACT, enabled = count > 0)
            AppButton("Done", onDone, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT)
        }
        Caption("Merge: one payment reported under two names", maxLines = 2)
        Caption("Group: different payments to the same provider", maxLines = 2)
    }
}
