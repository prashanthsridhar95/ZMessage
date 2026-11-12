package com.zyke.message.presentation.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zyke.message.R
import com.zyke.message.domain.search.TextHighlight
import com.zyke.message.domain.settings.SettingsGroupId
import com.zyke.message.domain.settings.SettingsIndex
import com.zyke.message.domain.settings.SettingsLayout
import com.zyke.message.domain.settings.SettingsMatch
import com.zyke.message.presentation.common.AppButton
import com.zyke.message.presentation.common.CardHeader
import com.zyke.message.presentation.common.EmptyState
import com.zyke.message.presentation.common.FintechCard
import com.zyke.message.presentation.common.SearchField
import com.zyke.message.presentation.common.Text
import com.zyke.message.presentation.common.tappable
import com.zyke.message.presentation.messages.components.Caption
import com.zyke.message.presentation.messages.components.RowTitle
import com.zyke.message.presentation.messages.components.mutedColor
import com.zyke.message.presentation.motion.CrossfadeContent
import com.zyke.message.presentation.motion.EnterGate
import com.zyke.message.presentation.motion.enterOnce
import com.zyke.message.presentation.search.highlighted
import com.zyke.message.presentation.theme.Radius
import com.zyke.message.presentation.theme.Spacing

/**
 * A page of Settings opened from outside Settings (a search result). Set [pending] to a page name, then open Settings;
 * the settings page takes it once and clears it.
 */
object SettingsDeepLink {
    var pending by mutableStateOf<String?>(null)
}

private val TileSize = 40.dp

/**
 * The first screen of Settings: a search field, what is still to set up, and the groups, each a card of rows (an icon
 * tile, what it is, how it is set now, a chevron). Typing in the field replaces the groups with the settings that match,
 * the typed words in bold. [summaryOf] says how a page is set now; [onOpen] opens a page.
 */
@Composable
internal fun SettingsHub(
    developer: Boolean,
    demo: Boolean,
    summaryOf: (SettingsGroup) -> String,
    todo: List<Triple<String, String, SettingsGroup>>,
    gate: EnterGate,
    onOpen: (SettingsGroup) -> Unit
) {
    var query by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    val focus = LocalFocusManager.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        SearchField(
            query, { query = it }, Modifier.fillMaxWidth().enterOnce(gate, 0),
            placeholder = "Search settings", onSearch = { focus.clearFocus() }, autoFocus = false
        )
        CrossfadeContent(query.isNotBlank(), Modifier.fillMaxWidth(), label = "settings-search") { searching ->
            if (searching) {
                SettingsResults(query, developer, demo, onOpen, onClear = { query = "" })
            } else {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    if (todo.isNotEmpty()) {
                        Column(Modifier.enterOnce(gate, 1)) {
                            CardHeader("Finish setting up")
                            FintechCard {
                                todo.forEachIndexed { index, step ->
                                    if (index > 0) RowDivider()
                                    SettingsRow(step.third.icon, step.first, step.second, { onOpen(step.third) })
                                }
                            }
                        }
                    }
                    SettingsLayout.visible(developer).forEachIndexed { index, (group, pages) ->
                        val shown = pages.mapNotNull { id -> SettingsGroup.entries.firstOrNull { it.name == id.name } }
                        if (shown.isNotEmpty()) {
                            Column(Modifier.enterOnce(gate, index + 2)) {
                                CardHeader(group.title)
                                FintechCard {
                                    shown.forEachIndexed { rowIndex, page ->
                                        if (rowIndex > 0) RowDivider()
                                        SettingsRow(page.icon, page.title, summaryOf(page), { onOpen(page) })
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RowDivider() = HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))

/** The settings that match what was typed, under the group they belong to, with the typed words in bold. */
@Composable
private fun SettingsResults(query: String, developer: Boolean, demo: Boolean, onOpen: (SettingsGroup) -> Unit, onClear: () -> Unit) {
    val matches = remember(query, developer, demo) { SettingsIndex.search(query, developer, demo) }
    val words = remember(query) { TextHighlight.words(query) }
    if (matches.isEmpty()) {
        EmptyState(
            "No matches for “${query.trim()}”", "Try fewer words, or another way to say it.",
            icon = R.drawable.ic_search_24, actionLabel = "Clear search", onAction = onClear
        )
        return
    }
    val highlight = SpanStyle(fontWeight = FontWeight.Bold, background = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        Caption(
            com.zyke.message.presentation.format.DisplayFormatters.count(matches.size, "setting", "settings") + " found",
            Modifier.semantics { liveRegion = LiveRegionMode.Polite }
        )
        val byGroup: List<Pair<SettingsGroupId, List<SettingsMatch>>> = SettingsGroupId.entries.mapNotNull { g ->
            matches.filter { it.entry.group == g }.takeIf { it.isNotEmpty() }?.let { g to it }
        }
        byGroup.forEach { (group, found) ->
            Column {
                CardHeader(group.title)
                FintechCard {
                    found.forEachIndexed { index, match ->
                        if (index > 0) RowDivider()
                        val page = SettingsGroup.entries.firstOrNull { it.name == match.entry.page.name }
                        if (page != null) {
                            SettingsRow(
                                page.icon, match.entry.title, match.entry.summary, { onOpen(page) },
                                words = words, highlight = highlight, spokenPage = page.title
                            )
                        }
                    }
                }
            }
        }
    }
}

/** One row: an icon tile, a one-line title, a one-line summary of how it is set, and a chevron. The whole row is the button. */
@Composable
internal fun SettingsRow(
    icon: Int,
    title: String,
    summary: String,
    onClick: () -> Unit,
    words: List<String> = emptyList(),
    highlight: SpanStyle = SpanStyle(),
    spokenPage: String? = null
) {
    val shownTitle = remember(title, words, highlight) { highlighted(title, words, highlight) }
    val shownSummary = remember(summary, words, highlight) { highlighted(summary, words, highlight) }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .tappable(onClick, onClickLabel = "Open " + (spokenPage ?: title))
            .padding(vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(TileSize).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), RoundedCornerShape(Radius.chip)),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(com.zyke.message.presentation.theme.IconSize.md),
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary)
            )
        }
        Column(Modifier.weight(1f).padding(horizontal = Spacing.lg)) {
            Text(shownTitle, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text(shownSummary, style = MaterialTheme.typography.bodySmall, color = mutedColor(), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        Image(
            painter = painterResource(R.drawable.baseline_chevron_right_24), contentDescription = null,
            modifier = Modifier.size(com.zyke.message.presentation.theme.IconSize.md),
            colorFilter = ColorFilter.tint(mutedColor())
        )
    }
}
