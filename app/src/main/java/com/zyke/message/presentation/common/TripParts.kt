package com.zyke.message.presentation.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zyke.message.presentation.messages.components.Caption
import com.zyke.message.presentation.messages.components.RowTitle

/*
 * The small pieces the flight sheet and the train sheet, and their add-by-hand forms, have in common: a fact tile, the facts grid with
 * the PNR row, and the panel under the form's fields that says what the look-up found. Each kind passes its own words; nothing else
 * differs. No animation.
 */

/** One fact (a label over a bold value) on a soft tile. A value that may be long (a train's seat or ticket) is given two lines. */
@Composable
internal fun FactTile(label: String, value: String, modifier: Modifier, maxLines: Int = 1) {
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)).padding(horizontal = 14.dp, vertical = 12.dp)) {
        Caption(label)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * [facts] as tiles in two aligned columns (one column when [stack]), then the booking reference with a Copy button when there is one.
 * [tabularPnr] sets the reference in tabular figures.
 */
@Composable
internal fun TripFacts(
    facts: List<Pair<String, String>>, pnr: String?, stack: Boolean, tileMaxLines: Int, tabularPnr: Boolean, onCopyPnr: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        facts.chunked(if (stack) 1 else 2).forEach { rowFacts ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                rowFacts.forEach { (label, value) -> FactTile(label, value, Modifier.weight(1f), tileMaxLines) }
                if (rowFacts.size == 1 && !stack) Spacer(Modifier.weight(1f))
            }
        }
        pnr?.let { reference ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)).padding(start = 14.dp, top = 6.dp, end = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Caption("PNR")
                    val style = MaterialTheme.typography.titleMedium
                    Text(
                        reference, style = if (tabularPnr) style.copy(fontFeatureSettings = "tnum") else style,
                        fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.width(8.dp))
                AppButton("Copy", { onCopyPnr(reference) }, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT)
            }
        }
    }
}

/** What the look-up found: a "Found" card with a title and a one-line summary. */
internal class LookupFound(val title: String, val summary: String)

/** The words under a form's fields while there is no result: each kind's own sentences. */
internal class LookupWords(val unconfirmed: String, val looking: String, val demo: String, val canLookUp: String, val off: String)

/**
 * What the look-up found, or what it could not, under the fields. [found] is the result when there is one to show; the rest is
 * decided in the order: could not confirm, still asking, demo, look-up possible, live status off.
 */
@Composable
internal fun TripLookupPanel(found: LookupFound?, unconfirmed: Boolean, looking: Boolean, demo: Boolean, canLookUp: Boolean, words: LookupWords) {
    when {
        found != null -> {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text("Found", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                RowTitle(found.title)
                Caption(found.summary, maxLines = 3)
            }
        }
        unconfirmed -> Caption(words.unconfirmed, maxLines = 5)
        looking -> Caption(words.looking, maxLines = 2)
        demo -> Caption(words.demo, maxLines = 3)
        canLookUp -> Caption(words.canLookUp, maxLines = 3)
        else -> Caption(words.off, maxLines = 4)
    }
}
