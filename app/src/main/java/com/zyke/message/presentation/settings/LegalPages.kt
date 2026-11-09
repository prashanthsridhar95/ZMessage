package com.zyke.message.presentation.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zyke.message.domain.legal.LegalInfo
import com.zyke.message.domain.legal.LegalItem
import com.zyke.message.domain.legal.LegalText
import com.zyke.message.domain.legal.NoticeKind
import com.zyke.message.domain.legal.OpenSourceNotices
import com.zyke.message.presentation.common.AdaptiveSheet
import com.zyke.message.presentation.common.AppButton
import com.zyke.message.presentation.common.ButtonSize
import com.zyke.message.presentation.common.ButtonStyle
import com.zyke.message.presentation.common.LocalOpenLink
import com.zyke.message.presentation.common.Text
import com.zyke.message.presentation.common.fullWidthClickable
import com.zyke.message.presentation.common.openExternally
import com.zyke.message.presentation.dashboard.components.LocalSectionsInline
import com.zyke.message.presentation.dashboard.components.SectionCard
import com.zyke.message.presentation.messages.components.Caption

/*
 * The Privacy and terms page and the Open-source licences page. The words come from LegalText and OpenSourceNotices in
 * :domain, where tests keep them in step with the manifest, the network hosts and the dependency list. This file only
 * draws them, in a readable size, with every section a heading.
 */

/** The whole Privacy and terms text as cards; [onOpenLicences] adds a button to the licences page where there is one. */
@Composable
internal fun LegalContent(onOpenLicences: (() -> Unit)? = null) {
    // Plain cards, never folded away: the text is meant to be read, and a heading per section is enough to jump between them.
    CompositionLocalProvider(LocalSectionsInline provides true) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Caption(LegalText.lastUpdatedLine, maxLines = 2)
            Text(
                "This is a plain-language description of what the app does and the rules for using it. It is not legal advice.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface
            )
            LegalText.sections.forEach { section ->
                SectionCard(section.title) {
                    Text(section.intro, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    section.items.forEach { LegalItemView(it) }
                    if (section.id == "contact") {
                        val context = LocalContext.current
                        AppButton(
                            "Email ${LegalInfo.CONTACT_EMAIL}", { openExternally(context, "mailto:" + LegalInfo.CONTACT_EMAIL) },
                            Modifier.padding(top = 12.dp), style = ButtonStyle.OUTLINE
                        )
                    }
                }
            }
            if (onOpenLicences != null) {
                AppButton("Open-source licences", onOpenLicences, Modifier.fillMaxWidth(), style = ButtonStyle.OUTLINE)
            }
        }
    }
}

@Composable
private fun LegalItemView(item: LegalItem) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        item.heading?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
        }
        Text(
            item.body, Modifier.padding(top = if (item.heading != null) 2.dp else 0.dp),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/** The third-party software, font, icons and services, grouped, each with its licence name and a tap to the project page. */
@Composable
internal fun LicencesContent() {
    val open = LocalOpenLink.current
    CompositionLocalProvider(LocalSectionsInline provides true) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "${LegalInfo.APP_NAME} is built on the work of others. Each item below shows its licence and where its project lives; " +
                    "the projects' own licence texts apply. Brand logos belong to their owners.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface
            )
            NoticeKind.entries.forEach { kind ->
                val group = OpenSourceNotices.all.filter { it.kind == kind }
                if (group.isEmpty()) return@forEach
                SectionCard(kind.label) {
                    group.forEach { notice ->
                        Column(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .fullWidthClickable(role = Role.Button, onClickLabel = "Open the ${notice.name} project page") { open(notice.url) }
                                .padding(vertical = 8.dp)
                        ) {
                            Text(notice.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                            Text(notice.what, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                            Text("Licence: ${notice.licence}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                            Caption(notice.url, maxLines = 2)
                        }
                    }
                }
            }
        }
    }
}

/**
 * "See what is sent": a small button that opens the Privacy and terms text in a sheet, for the places where the app is about
 * to do something with consequences (send a text). It needs nothing from the screen around it.
 */
@Composable
internal fun SeeWhatIsSentLink(modifier: Modifier = Modifier, label: String = "See what is sent") {
    var open by rememberSaveable { mutableStateOf(false) }
    AppButton(label, { open = true }, modifier, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT)
    if (open) AdaptiveSheet(LegalText.TITLE, { open = false }) { LegalContent() }
}
