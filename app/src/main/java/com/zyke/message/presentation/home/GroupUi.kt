package com.zyke.message.presentation.home

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import com.zyke.message.presentation.motion.AppearVisibility
import com.zyke.message.presentation.motion.pressFeedback
import com.zyke.message.presentation.theme.AppShapes
import com.zyke.message.presentation.theme.Spacing
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zyke.message.ZykeMessagesApplication
import com.zyke.message.domain.insights.GroupSuggestion
import com.zyke.message.domain.insights.RecurringGroup
import com.zyke.message.domain.insights.RecurringGroups
import com.zyke.message.domain.insights.RecurringListItem
import com.zyke.message.domain.insights.Subscription
import com.zyke.message.domain.insights.SubscriptionLine
import com.zyke.message.domain.insights.SubscriptionState
import com.zyke.message.domain.nickname.NicknameSuggestions
import com.zyke.message.presentation.common.AdaptiveSheet
import com.zyke.message.presentation.common.AppButton
import com.zyke.message.presentation.common.ButtonSize
import com.zyke.message.presentation.common.ButtonStyle
import com.zyke.message.presentation.common.FintechCard
import com.zyke.message.presentation.common.FormField
import com.zyke.message.presentation.common.FormGroup
import com.zyke.message.presentation.common.FormSheet
import com.zyke.message.presentation.common.SuggestionRow
import com.zyke.message.presentation.common.Text
import com.zyke.message.presentation.common.privateSpeech
import com.zyke.message.presentation.common.rememberReduceMotion
import com.zyke.message.presentation.format.DisplayFormatters
import com.zyke.message.presentation.format.FormatCache
import com.zyke.message.presentation.home.HomeContract.Intent
import com.zyke.message.presentation.home.HomeContract.State
import com.zyke.message.presentation.messages.components.Caption
import com.zyke.message.presentation.messages.components.mutedColor
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

private val DayMonthShort = FormatCache.pattern("d MMM", Locale.getDefault())

private fun plural(count: Int, one: String, many: String) = if (count == 1) "1 $one" else "$count $many"

/** "4 SIPs" / "1 payment". */
internal fun RecurringListItem.Group<*>.countLabel(): String {
    val unit = RecurringGroups.unitName(subscriptions)
    return plural(count, unit, unit + "s")
}

/** What a page can do with groups; the sheets they need are shown by [rememberGroupActions]. */
internal class GroupActions(
    /** Opens the group sheet with these entries picked, adding to the group with this id (null: a new group), with a suggested name. */
    val open: (keys: List<String>, groupId: String?, name: String?) -> Unit,
    val renameGroup: (String) -> Unit,
    val ungroup: (String) -> Unit,
    val cancelAll: (String) -> Unit,
    val remove: (Subscription) -> Unit
) {
    /** The entry-menu items a row gets: "Group with…" outside a group, "Remove from group" inside one. */
    fun entryMenu(sub: Subscription, grouped: Boolean): List<MenuEntry> =
        if (grouped) listOf(MenuEntry("Remove from group") { remove(sub) }) else listOf(MenuEntry("Group with…") { open(listOf(sub.key), null, null) })

    fun groupMenu(group: RecurringGroup, canCancel: Boolean): List<MenuEntry> = buildList {
        add(MenuEntry("Rename group") { renameGroup(group.id) })
        add(MenuEntry("Add entries…") { open(emptyList(), group.id, null) })
        if (canCancel) add(MenuEntry("Cancel all in this group") { cancelAll(group.id) })
        add(MenuEntry("Ungroup") { ungroup(group.id) })
    }
}

/**
 * Holds the open group sheet, group rename sheet and cancel-all confirmation of a page, shows them, and returns what
 * the rows call. Every change is one write of the whole list and one Undo.
 */
@Composable
internal fun rememberGroupActions(state: State, onIntent: (Intent) -> Unit, onSaved: () -> Unit = {}): GroupActions {
    val handler = rememberPlanActionHandler(onIntent)
    var sheetOpen by rememberSaveable { mutableStateOf(false) }
    var sheetKeys by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var sheetGroup by rememberSaveable { mutableStateOf<String?>(null) }
    var sheetName by rememberSaveable { mutableStateOf<String?>(null) }
    var renaming by rememberSaveable { mutableStateOf<String?>(null) }
    var cancelling by rememberSaveable { mutableStateOf<String?>(null) }
    val pool = remember(state.subscriptionOverview) { state.subscriptionOverview.lines.map { it.subscription }.distinctBy { it.key } }
    val groups = state.recurringGroups
    val names = state.recurringNames
    val today = LocalDate.ofInstant(state.now, ZoneId.systemDefault())

    fun writeGroups(next: List<RecurringGroup>, text: String, nextNames: Map<String, String> = names) {
        onIntent(Intent.SetRecurringGroups(next))
        if (nextNames != names) onIntent(Intent.SetRecurringNames(nextNames))
        handler.announce(text) {
            onIntent(Intent.SetRecurringGroups(groups))
            if (nextNames != names) onIntent(Intent.SetRecurringNames(names))
        }
    }

    if (sheetOpen) {
        val existing = sheetGroup?.let { id -> groups.firstOrNull { it.id == id } }
        GroupSheet(
            pool = pool, groups = groups, existing = existing, initial = sheetKeys, suggestedName = sheetName,
            onSave = { name, picked, renameEntries ->
                sheetOpen = false
                onSaved()
                val chosen = pool.filter { it.key in picked }
                val matching = if (renameEntries) names + RecurringGroups.matchingNames(name, chosen) else names
                if (existing == null) {
                    writeGroups(RecurringGroups.created(groups, java.util.UUID.randomUUID().toString(), name, chosen.map { it.key }, System.currentTimeMillis()), "Grouped ${chosen.size} entries as ${name.trim()}", matching)
                } else {
                    // Entries unticked in the sheet leave; members that are gone from the list stay stored (they may come back).
                    val dropped = pool.filter { p -> RecurringGroups.keysOf(p).any { it in existing.memberKeys } && p.key !in picked }.flatMap { RecurringGroups.keysOf(it) }
                    val next = RecurringGroups.renamed(RecurringGroups.added(RecurringGroups.removed(groups, dropped), existing.id, chosen.map { it.key }), existing.id, name)
                    writeGroups(next, "Updated ${name.trim()}", matching)
                }
            },
            onDismiss = { sheetOpen = false }
        )
    }
    renaming?.let { id ->
        val group = groups.firstOrNull { it.id == id }
        if (group == null) renaming = null else RenameGroupSheet(
            group.name,
            onSave = { name -> renaming = null; writeGroups(RecurringGroups.renamed(groups, id, name), "Renamed to ${name.trim()}") },
            onDismiss = { renaming = null }
        )
    }
    cancelling?.let { id ->
        val lines = state.subscriptionOverview.lines
        val item = RecurringGroups.arrange(lines, groups, { it.subscription }, { it.state })
            .filterIsInstance<RecurringListItem.Group<SubscriptionLine>>().firstOrNull { it.group.id == id }
        val todo = item?.members?.filter { it.state != SubscriptionState.CANCELLED }.orEmpty()
        if (item == null || todo.isEmpty()) cancelling = null else CancelAllSheet(
            item.name, todo,
            onDismiss = { cancelling = null },
            onConfirm = {
                cancelling = null
                todo.forEach { onIntent(Intent.CancelSubscription(it.key, today)) }
                handler.announce("Marked ${todo.size} in ${item.name} as cancelled") { todo.forEach { onIntent(Intent.RestoreSubscription(it.key)) } }
            }
        )
    }

    return remember(pool, groups, names, onIntent, handler) {
        GroupActions(
            open = { keys, id, name -> sheetKeys = keys; sheetGroup = id; sheetName = name; sheetOpen = true },
            renameGroup = { renaming = it },
            ungroup = { id ->
                val name = groups.firstOrNull { it.id == id }?.name.orEmpty()
                writeGroups(RecurringGroups.ungrouped(groups, id), "Ungrouped $name")
            },
            cancelAll = { cancelling = it },
            remove = { sub ->
                val from = groups.firstOrNull { g -> RecurringGroups.keysOf(sub).any { it in g.memberKeys } }?.name.orEmpty()
                writeGroups(RecurringGroups.removed(groups, RecurringGroups.keysOf(sub)), "Removed ${sub.title} from $from")
            }
        )
    }
}

@Composable
private fun RenameGroupSheet(current: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(current) }
    FormSheet(
        title = "Rename group", onDismiss = onDismiss, primaryLabel = "Save", primaryEnabled = name.isNotBlank(), onPrimary = { onSave(name) }
    ) {
        FormField("Group name", name, { name = it }, capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences)
    }
}

/** Confirms marking every member of a group cancelled; says what it adds up to and that the app cannot cancel for them. */
@Composable
private fun CancelAllSheet(groupName: String, lines: List<SubscriptionLine>, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val monthly = lines.fold(BigDecimal.ZERO) { sum, l -> sum + l.monthly }
    val yearly = lines.fold(BigDecimal.ZERO) { sum, l -> sum + l.yearly }
    val total = "${DisplayFormatters.rupees(monthly)} a month · ${DisplayFormatters.rupees(yearly)} a year"
    AdaptiveSheet(
        title = "Cancel all in $groupName",
        subtitle = "${plural(lines.size, "entry", "entries")} · $total",
        onDismiss = onDismiss,
        contentSpacing = 8.dp,
        footer = {
            AppButton("Mark all ${lines.size} as cancelled", onConfirm, Modifier.fillMaxWidth())
            AppButton("Keep them", onDismiss, Modifier.fillMaxWidth(), style = ButtonStyle.OUTLINE)
            Caption("Zyke Message cannot cancel for you. Mark them once you have, and it will tell you if a charge still comes. One Undo brings them all back.", maxLines = 4)
        }
    ) {
        lines.forEach { l ->
            androidx.compose.runtime.key(l.key) {
                val spoken = privateSpeech("${l.label}, ${DisplayFormatters.rupees(l.subscription.amount)} ${l.cadence.label.lowercase()}")
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics(mergeDescendants = true) { contentDescription = spoken }, verticalAlignment = Alignment.CenterVertically) {
                    Text(l.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 2)
                    Spacer(Modifier.width(8.dp))
                    Text(DisplayFormatters.rupees(l.subscription.amount), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary, maxLines = 1)
                }
            }
        }
    }
}

/**
 * Filing different payments together: a name (the common provider phrase to start with), the entries ticked, what
 * the group comes to, and whether to rename the entries to match. A group changes how rows are shown, never what is counted.
 */
@Composable
internal fun GroupSheet(
    pool: List<Subscription>,
    groups: List<RecurringGroup>,
    existing: RecurringGroup?,
    initial: List<String>,
    suggestedName: String?,
    onSave: (name: String, picked: List<String>, renameEntries: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val container = (LocalContext.current.applicationContext as ZykeMessagesApplication).container
    val nicknames by container.nicknames.nicknames.collectAsState()
    val start = remember(existing, initial, pool) {
        val members = if (existing == null) emptyList() else pool.filter { p -> RecurringGroups.keysOf(p).any { it in existing.memberKeys } }.map { it.key }
        (initial + members).distinct()
    }
    var picked by rememberSaveable { mutableStateOf(start) }
    var typed by rememberSaveable { mutableStateOf<String?>(existing?.name ?: suggestedName) }
    var renameEntries by rememberSaveable { mutableStateOf(false) }
    // Entries that vanished while the sheet was open drop out.
    val chosen = remember(pool, picked) { pool.filter { it.key in picked } }
    val name = typed ?: RecurringGroups.commonName(chosen)
    val suggestions = remember(name, nicknames) { NicknameSuggestions.suggest(name, nicknames) }
    val monthly = chosen.fold(BigDecimal.ZERO) { sum, s -> sum + s.cadence.monthly(s.amount) }
    val yearly = chosen.fold(BigDecimal.ZERO) { sum, s -> sum + s.cadence.yearly(s.amount) }
    val groupOf = remember(groups, pool) {
        pool.associate { p -> p.key to groups.firstOrNull { g -> RecurringGroups.keysOf(p).any { it in g.memberKeys } } }
    }
    FormSheet(
        title = if (existing == null) "Group" else "Add entries to ${existing.name}",
        subtitle = "Different payments to the same provider",
        onDismiss = onDismiss, asPage = true,
        primaryLabel = if (existing == null) "Group ${chosen.size} entries" else "Save",
        primaryEnabled = chosen.size >= 2 && name.isNotBlank(),
        hint = if (chosen.size < 2) "Pick at least two entries" else "Give the group a name",
        onPrimary = { onSave(name.trim(), chosen.map { it.key }, renameEntries) }
    ) {
        FormField("Group name", name, { typed = it }, placeholder = "e.g. Mahindra Manulife Fund", capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences)
        if (suggestions.isNotEmpty()) SuggestionRow(suggestions.map { it.name }) { typed = it }
        FormGroup("Entries") {
            pool.forEach { s ->
                androidx.compose.runtime.key(s.key) {
                    val other = groupOf[s.key]?.takeIf { it.id != existing?.id }
                    GroupPickRow(s, s.key in picked, other?.name) { picked = if (s.key in picked) picked - s.key else picked + s.key }
                }
            }
        }
        if (chosen.isNotEmpty()) {
            FintechCard {
                Text(
                    "Group of ${chosen.size} · ${DisplayFormatters.rupees(monthly)} a month · ${DisplayFormatters.rupees(yearly)} a year (each payment still counts on its own)",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
        SwitchLine("Rename the entries to match", renameEntries) { renameEntries = it }
        Caption("Adds the group name in front of each entry's own name.", maxLines = 2)
    }
}

@Composable
private fun GroupPickRow(sub: Subscription, picked: Boolean, otherGroup: String?, onToggle: () -> Unit) {
    val spoken = privateSpeech(
        "${sub.fullName()}, ${DisplayFormatters.rupees(sub.amount)}, last charged ${DayMonthShort.format(sub.lastPaid)}" + (if (otherGroup != null) ", now in $otherGroup" else "")
    )
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .clickable(role = Role.Checkbox, onClickLabel = if (picked) "Drop from group" else "Add to group", onClick = onToggle)
            .semantics(mergeDescendants = true) { contentDescription = spoken; toggleableState = ToggleableState(picked) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = picked, onCheckedChange = null, modifier = Modifier.size(48.dp))
        Column(Modifier.weight(1f)) {
            Text(sub.fullName(), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface, maxLines = 2)
            Caption(if (otherGroup != null) "Moves from $otherGroup" else "Last charged ${DayMonthShort.format(sub.lastPaid)}")
        }
        Spacer(Modifier.width(8.dp))
        Text(DisplayFormatters.rupees(sub.amount), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary, maxLines = 1)
    }
}

@Composable
private fun SwitchLine(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .clickable(role = Role.Switch, onClickLabel = label, onClick = { onChange(!checked) })
            .semantics(mergeDescendants = true) { stateDescription = if (checked) "On" else "Off" },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        Switch(checked = checked, onCheckedChange = null)
    }
}

/**
 * The header of a group on a list: name, a line under it, the amount, and a chevron. Tapping it opens or closes the
 * members (or ticks it while entries are being picked); the "more" menu is a separate 48 dp button.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun GroupHeaderRow(
    name: String,
    subtitle: String,
    spokenAmounts: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    picking: Boolean,
    picked: Boolean,
    onPick: () -> Unit,
    menu: List<MenuEntry>,
    leading: (@Composable () -> Unit)? = null,
    amount: @Composable () -> Unit
) {
    val spoken = privateSpeech("$name, $subtitle, $spokenAmounts")
    val shape = AppShapes.control
    val source = remember { MutableInteractionSource() }
    Row(Modifier.fillMaxWidth().padding(vertical = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
        if (picking) Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { Checkbox(checked = picked, onCheckedChange = null) }
        Row(
            Modifier.weight(1f).heightIn(min = 56.dp).pressFeedback(source).clip(shape)
                .combinedClickable(
                    interactionSource = source, indication = LocalIndication.current,
                    onClickLabel = if (picking) (if (picked) "Drop from selection" else "Add to selection") else if (expanded) "Collapse $name" else "Expand $name",
                    onClick = { if (picking) onPick() else onToggle() },
                    onLongClickLabel = "Pick several",
                    onLongClick = onPick
                )
                .semantics(mergeDescendants = true) {
                    contentDescription = spoken
                    if (picking) { role = Role.Checkbox; toggleableState = ToggleableState(picked) }
                    else { role = Role.Button; stateDescription = if (expanded) "Expanded" else "Collapsed" }
                },
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (leading != null) { Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.Center) { leading() }; Spacer(Modifier.width(12.dp)) }
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary, maxLines = 2)
                Caption(subtitle, maxLines = 2)
            }
            Spacer(Modifier.width(8.dp))
            amount()
            ChevronTurn(expanded)
        }
        if (!picking) RowMenu(name, menu)
    }
}

/** The members of an opened group, indented under its header; instant when motion is reduced. */
@Composable
internal fun GroupMembers(expanded: Boolean, content: @Composable () -> Unit) {
    val line = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val body: @Composable () -> Unit = {
        Column(
            Modifier.fillMaxWidth().padding(start = 12.dp)
                .drawBehind { drawLine(line, Offset(0f, 0f), Offset(0f, size.height), strokeWidth = 2.dp.toPx()) }
                .padding(start = 8.dp)
        ) { content() }
    }
    AppearVisibility(expanded) { body() }
}

/** "Group these?" for each suggestion, with Group and No. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GroupSuggestionCard(suggestions: List<GroupSuggestion>, onGroup: (GroupSuggestion) -> Unit, onNo: (GroupSuggestion) -> Unit) {
    FintechCard {
        suggestions.forEachIndexed { index, s ->
            androidx.compose.runtime.key(s.id) {
                val monthly = s.members.fold(BigDecimal.ZERO) { sum, m -> sum + m.cadence.monthly(m.amount) }
                val unit = RecurringGroups.unitName(s.members)
                val text = "${s.members.size} ${unit}s look like they go to ${s.name} (${DisplayFormatters.rupees(monthly)} a month). Group them under one line?"
                if (index > 0) Spacer(Modifier.height(12.dp))
                Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                Caption("They still count on their own.", Modifier.padding(top = 2.dp))
                FlowRow(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppButton("Group", { onGroup(s) }, style = ButtonStyle.TONAL, size = ButtonSize.COMPACT)
                    AppButton("No, keep separate", { onNo(s) }, style = ButtonStyle.OUTLINE, size = ButtonSize.COMPACT)
                }
            }
        }
    }
}

/** A line saying how much a group comes to, for the spoken description: "₹10,000 a month, ₹1,20,000 a year". */
internal fun RecurringListItem.Group<*>.spokenTotals(): String =
    "${DisplayFormatters.rupees(monthly)} a month, ${DisplayFormatters.rupees(yearly)} a year" + (stateSummary.takeIf { it.isNotEmpty() }?.let { ", $it" } ?: "")

internal fun RecurringListItem.Group<*>.nextDueLabel(): String = "next ~" + DayMonthShort.format(nextDue)
