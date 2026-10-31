package com.zyke.message.presentation.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zyke.message.ZykeMessagesApplication
import com.zyke.message.domain.notes.Tags
import com.zyke.message.presentation.common.AppPage
import com.zyke.message.presentation.common.EmptyState
import com.zyke.message.presentation.common.FintechCard
import com.zyke.message.presentation.common.LocalOpenMessage
import com.zyke.message.presentation.common.fullWidthClickable
import com.zyke.message.presentation.format.DisplayFormatters
import com.zyke.message.presentation.home.PageSummary
import com.zyke.message.presentation.home.RecentRow
import com.zyke.message.presentation.home.SectionHeader
import com.zyke.message.presentation.messages.components.Caption
import com.zyke.message.presentation.messages.components.RowTitle
import com.zyke.message.presentation.messages.components.mutedColor
import com.zyke.message.presentation.dashboard.components.SectionCard

/** The one row in Settings that opens the Tags page. */
@Composable
internal fun TagsEntry() {
    var open by rememberSaveable { mutableStateOf(false) }
    SectionCard("Tags") {
        Row(
            Modifier.fillMaxWidth().fullWidthClickable(role = Role.Button, onClickLabel = "Open tags") { open = true },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                RowTitle("Your tags")
                Caption("Labels you put on payments, with what each adds up to", maxLines = 2)
            }
            Text("›", color = mutedColor())
        }
    }
    if (open) TagsPage { open = false }
}

/** Every tag with its payments and what was spent; a tap lists the payments and opens one. */
@Composable
internal fun TagsPage(onDismiss: () -> Unit) {
    val container = (LocalContext.current.applicationContext as ZykeMessagesApplication).container
    val messages by remember { container.observeMessages() }.collectAsStateWithLifecycle(emptyList())
    val summaries = remember(messages) { Tags.summaries(messages) }
    var chosen by rememberSaveable { mutableStateOf<String?>(null) }
    val openMessage = LocalOpenMessage.current

    val current = summaries.firstOrNull { it.tag == chosen }
    if (current == null) {
        AppPage("Tags", onDismiss) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (summaries.isEmpty()) {
                    EmptyState("No tags yet", "Open a payment and add a tag in \"Note and tags\", for example trip or work.")
                } else {
                    SectionHeader("Tags", summaries.size)
                    FintechCard {
                        summaries.forEach { summary ->
                            Row(
                                Modifier.fillMaxWidth().fullWidthClickable(role = Role.Button, onClickLabel = "Show payments tagged ${summary.tag}") { chosen = summary.tag }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    RowTitle(summary.tag)
                                    Caption(DisplayFormatters.count(summary.count, "payment", "payments"))
                                }
                                Text(DisplayFormatters.rupees(summary.spent))
                            }
                        }
                    }
                }
            }
        }
    } else {
        val byId = remember(messages) { messages.associateBy { it.id } }
        AppPage("#${current.tag}", { chosen = null }) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PageSummary("Spent", DisplayFormatters.rupees(current.spent), DisplayFormatters.count(current.count, "payment", "payments"))
                FintechCard {
                    current.messageIds.mapNotNull { byId[it] }.forEach { message -> RecentRow(message, onOpen = { openMessage(message.id) }) }
                }
            }
        }
    }
}
