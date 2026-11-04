package com.zyke.message.presentation.tour

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zyke.message.domain.tour.TourPreviewKind
import com.zyke.message.presentation.common.Text
import com.zyke.message.presentation.home.StatusPill
import com.zyke.message.presentation.home.Tone

/**
 * A small made-up picture of a feature, shown in the coach card when the real thing is not on screen (a fresh install has
 * no data yet). Nothing here reads or writes the person's data: every name and number is a sample, and the box says so.
 */
@Composable
fun TourPreview(kind: TourPreviewKind, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
            .padding(12.dp)
            // One plain sentence for a screen reader instead of the pieces.
            .semantics(mergeDescendants = true) { contentDescription = "Example of this feature, with made-up numbers" },
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            "EXAMPLE · SAMPLE NUMBERS", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        when (kind) {
            TourPreviewKind.BALANCE -> Line("Total balance", "₹1,24,500")
            TourPreviewKind.TODAY_BUDGET -> {
                Line("Today's budget", "₹1,200 left")
                Bar(0.2f, "of ₹1,500")
                Pills("Streak: 5 days" to Tone.GOOD, "Adjust" to Tone.NEUTRAL)
            }
            TourPreviewKind.CODE -> {
                Line("Sample Bank · OTP", "482 913")
                Pills("Expires in 4:12" to Tone.WARN, "Copy" to Tone.NEUTRAL)
            }
            TourPreviewKind.NEEDS_REVIEW -> {
                Line("3 things to check", "")
                Sub("Possible duplicate · Food order ₹349")
                Sub("Maybe a transfer · To your own account")
            }
            TourPreviewKind.PLAN_ROW -> {
                Line("Credit card bill", "₹4,820")
                Sub("Due in 3 days")
                Pills("Snooze" to Tone.NEUTRAL, "Dismiss" to Tone.NEUTRAL, "Mark as paid" to Tone.GOOD, "Pay bill" to Tone.NEUTRAL)
            }
            TourPreviewKind.ACCOUNT_CARD -> {
                Line("Sample Card ···· 1234", "₹12,400")
                Sub("Spent this cycle · due 18th")
                Pills("Edit" to Tone.NEUTRAL, "Leave out of totals" to Tone.NEUTRAL)
            }
            TourPreviewKind.NET_WORTH -> {
                Line("Net worth", "₹3,42,000")
                Sub("You own ₹4,10,000 · You owe ₹68,000")
            }
            TourPreviewKind.INVESTMENT -> {
                Line("Society FD", "₹1,07,186")
                Sub("Matures 12 Mar 2027 · interest ₹7,186 · 128 days to go")
                Pills("Reminder 7 days before" to Tone.NEUTRAL)
            }
            TourPreviewKind.MONTH_COMPARE -> {
                Line("This month", "₹24,300")
                Bar(0.8f, null)
                Line("Last month", "₹21,800")
                Bar(0.7f, null)
            }
            TourPreviewKind.HEADING -> {
                Line("On track to spend", "~₹31,000")
                Sub("by the end of the month · salary lasts 22 more days")
            }
            TourPreviewKind.REPORT_CARD -> {
                Line("Report card", "B+")
                Sub("2 of 3 budgets on track")
            }
            TourPreviewKind.CATEGORY_BARS -> {
                Line("Food", "38%"); Bar(0.38f, null)
                Line("Travel", "24%"); Bar(0.24f, null)
                Line("Shopping", "18%"); Bar(0.18f, null)
            }
            TourPreviewKind.BILL_ROW -> {
                Line("Electricity", "₹1,430")
                Sub("Due 12th")
                Pills("Mark as paid" to Tone.GOOD, "Snooze" to Tone.NEUTRAL)
            }
            TourPreviewKind.REPEATS -> {
                Line("Streaming plan", "₹649")
                Sub("Every month")
                Pills("Confirm" to Tone.GOOD, "Ignore" to Tone.NEUTRAL)
            }
            TourPreviewKind.FRIEND_SPLIT -> {
                Line("Asha owes you", "₹450")
                Sub("Dinner, split 3 ways")
                Pills("Settle up" to Tone.GOOD)
            }
            TourPreviewKind.MESSAGE_ROW -> {
                Line("Food order", "₹349")
                Sub("Today · Food · Sample Bank")
            }
            TourPreviewKind.MESSAGE_DETAIL -> Pills(
                "Rename" to Tone.NEUTRAL, "Category" to Tone.NEUTRAL, "Picture" to Tone.NEUTRAL, "Notes" to Tone.NEUTRAL,
                "Tags" to Tone.NEUTRAL, "Leave out of totals" to Tone.NEUTRAL, "Transfer" to Tone.NEUTRAL, "Link to a goal" to Tone.NEUTRAL
            )
            TourPreviewKind.SUBSCRIPTION -> {
                Line("Music plan", "₹1,188 a year")
                Pills("How to cancel" to Tone.NEUTRAL)
            }
        }
    }
}

@Composable
private fun Line(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        if (value.isNotEmpty()) Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun Sub(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Bar(share: Float, caption: String?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f))) {
            Box(Modifier.fillMaxWidth(share.coerceIn(0f, 1f)).height(6.dp).background(MaterialTheme.colorScheme.primary))
        }
        if (caption != null) Sub(caption)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Pills(vararg items: Pair<String, Tone>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { (text, tone) -> StatusPill(text, tone) }
    }
}
