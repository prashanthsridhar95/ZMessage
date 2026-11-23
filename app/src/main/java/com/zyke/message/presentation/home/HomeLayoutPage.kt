package com.zyke.message.presentation.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zyke.message.ZykeMessagesApplication
import com.zyke.message.domain.home.HomeLayout
import com.zyke.message.domain.home.HomeSection
import com.zyke.message.presentation.common.AppButton
import com.zyke.message.presentation.common.AppPage
import com.zyke.message.presentation.common.ButtonStyle
import com.zyke.message.presentation.common.Text
import com.zyke.message.presentation.common.fullWidthClickable
import com.zyke.message.presentation.dashboard.components.SectionCard
import com.zyke.message.presentation.messages.components.Caption
import com.zyke.message.presentation.messages.components.RowTitle
import com.zyke.message.presentation.messages.components.mutedColor
import com.zyke.message.presentation.settings.ToggleRow
import com.zyke.message.presentation.theme.Spacing

/** The layout the person chose for Home; calm until they change it. */
@Composable
internal fun rememberHomeLayout(): HomeLayout {
    val container = (LocalContext.current.applicationContext as ZykeMessagesApplication).container
    val layout by container.homeLayout.layout.collectAsStateWithLifecycle()
    return layout
}

/** The one row in Settings, Appearance and feel, that opens "Customize Home". */
@Composable
internal fun HomeLayoutEntry() {
    var open by rememberSaveable { mutableStateOf(false) }
    val layout = rememberHomeLayout()
    SectionCard("Home") {
        Row(
            Modifier.fillMaxWidth().fullWidthClickable(role = Role.Button, onClickLabel = "Open Customize Home") { open = true },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                RowTitle("Customize Home")
                Caption(if (layout.isCalm) "Calm layout" else "Your own layout", maxLines = 2)
            }
            Text("›", color = mutedColor())
        }
    }
    if (open) HomeLayoutPage { open = false }
}

/**
 * "Customize Home": a switch for each optional part of Home, and "Reset to calm layout". Changes apply at once (a switch is its own
 * Save). Nothing here deletes data: a part that is off is still reachable (goals, reminders, calendar and quick adds under Bills,
 * Plan ahead; the market watch under Settings, Market watch).
 */
@Composable
internal fun HomeLayoutPage(onDismiss: () -> Unit) {
    val container = (LocalContext.current.applicationContext as ZykeMessagesApplication).container
    val repository = remember(container) { container.homeLayout }
    val layout by repository.layout.collectAsStateWithLifecycle()
    val watch by container.watchlist.watch.collectAsStateWithLifecycle()
    AppPage(
        title = "Customize Home",
        onBack = onDismiss,
        footer = {
            AppButton("Reset to calm layout", { repository.save(HomeLayout.CALM) }, Modifier.fillMaxWidth(), style = ButtonStyle.OUTLINE, enabled = !layout.isCalm)
        }
    ) {
        Caption(
            "Home always shows your balance and the one thing to look at today. Choose what else sits under them.",
            Modifier.padding(bottom = Spacing.md), maxLines = 3
        )
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            SectionCard("Sections") {
                HomeSection.entries.forEach { section ->
                    // The market strip also needs Market watch switched on in Settings; say so rather than hide the switch.
                    val summary = if (section == HomeSection.MARKET && !watch.enabled) "Turn on Market watch in Settings first" else section.summary
                    ToggleRow(
                        title = section.title,
                        description = summary,
                        checked = layout.shows(section),
                        onCheckedChange = { on -> repository.save(layout.with(section, on)) }
                    )
                }
            }
        }
    }
}
