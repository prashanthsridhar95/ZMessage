package com.zyke.message.presentation.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zyke.message.ZykeMessagesApplication
import com.zyke.message.domain.logo.LogoList
import com.zyke.message.presentation.common.AppButton
import com.zyke.message.presentation.common.AppPage
import com.zyke.message.presentation.common.ButtonSize
import com.zyke.message.presentation.common.ButtonStyle
import com.zyke.message.presentation.dashboard.components.SectionCard
import com.zyke.message.presentation.messages.components.Caption
import com.zyke.message.presentation.messages.components.RowTitle
import com.zyke.message.presentation.theme.Spacing

/**
 * "Logo list": the shops whose picture the person chose from the web, as shop name and the picture's web address, nothing else, to
 * share as a file (the same way as the parsing report) so the pictures can be shown for everyone. Shown only when there is something
 * to share, and the person can look at every line, and remove any, before sharing.
 */
@Composable
internal fun LogoListCard(onExport: () -> Unit) {
    val container = (LocalContext.current.applicationContext as ZykeMessagesApplication).container
    val entries by container.logoList.entries.collectAsStateWithLifecycle()
    var reviewing by rememberSaveable { mutableStateOf(false) }
    if (entries.isEmpty()) return
    SectionCard("Logo list") {
        RowTitle("${entries.size} ${if (entries.size == 1) "picture" else "pictures"} from the web")
        Caption(
            "Share the shop names and web addresses of the pictures you chose, so they can show for everyone. Only those two things go in the file: no pictures, messages, amounts or numbers.",
            maxLines = 2
        )
        AppButton(
            text = "Export logo list",
            onClick = onExport,
            style = ButtonStyle.OUTLINE,
            modifier = Modifier.fillMaxWidth().padding(top = Spacing.md)
        )
        AppButton(
            text = "Look through the list",
            onClick = { reviewing = true },
            style = ButtonStyle.TEXT,
            size = ButtonSize.COMPACT,
            modifier = Modifier.padding(top = Spacing.xs)
        )
    }
    if (reviewing) {
        AppPage("Logo list", { reviewing = false }) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Caption("Remove a line if it is a person rather than a shop. This only takes it off the list: the picture stays.", maxLines = 3)
                entries.forEach { entry ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            RowTitle(entry.key)
                            Caption(LogoList.hostOf(entry.url).orEmpty(), maxLines = 1)
                        }
                        AppButton(
                            text = "Remove",
                            onClick = { container.logoList.forget(entry.key) },
                            style = ButtonStyle.TEXT,
                            size = ButtonSize.COMPACT
                        )
                    }
                }
            }
        }
    }
}
