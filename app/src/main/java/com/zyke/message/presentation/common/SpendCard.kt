package com.zyke.message.presentation.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zyke.message.domain.cards.CategoryShare
import com.zyke.message.presentation.format.DisplayFormatters

/** What a [SpendCard] says about its limit: nothing set, fine, close, or passed. Drives the bar colour only; the words carry the meaning. */
enum class LimitTone { NONE, OK, NEAR, OVER }

/**
 * "Spent this month": the one structure the Cards, Accounts and Investments tabs share for a period's money. A big amount, the
 * limit (or "Set a limit") beside it, a thin bar, one line of what is left, then a segmented bar and the category rows.
 * [limit] is already formatted; amounts go through [PrivateAmount] so Hide amounts masks them.
 */
@Composable
fun SpendCard(
    title: String,
    amount: String,
    modifier: Modifier = Modifier,
    limitLabel: String = "Limit",
    limit: String? = null,
    onSetLimit: (() -> Unit)? = null,
    onEditLimit: (() -> Unit)? = null,
    progress: Float? = null,
    tone: LimitTone = LimitTone.NONE,
    note: String? = null,
    shares: List<CategoryShare> = emptyList(),
    categoryName: (CategoryShare) -> String = { it.category?.displayString ?: "Other" },
    emptyText: String = "Nothing spent in this cycle yet."
) {
    val colors = MaterialTheme.colorScheme
    val responsive = LocalResponsive.current
    // Side by side on a normal phone; the limit drops under the amount when text is large or the screen narrow.
    val stacked = responsive.fontScale >= 1.3f || responsive.widthDp < 340
    FintechCard(modifier) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant, maxLines = 1)
        val limitBlock: @Composable (Modifier) -> Unit = { m ->
            Column(m, horizontalAlignment = if (stacked) Alignment.Start else Alignment.End) {
                if (limit != null) {
                    Text(limitLabel, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, maxLines = 1)
                    PrivateAmount(limit, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    if (onEditLimit != null) AppButton("Change", onEditLimit, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT)
                } else if (onSetLimit != null) {
                    AppButton("Set a limit", onSetLimit, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT)
                }
            }
        }
        if (stacked) {
            PrivateAmount(amount, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = colors.onSurface, maxLines = 1, fit = true)
            limitBlock(Modifier.fillMaxWidth())
        } else {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PrivateAmount(amount, Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = colors.onSurface, maxLines = 1, fit = true)
                limitBlock(Modifier)
            }
        }
        if (progress != null) {
            val fill = when (tone) {
                LimitTone.OVER -> colors.error
                else -> colors.primary
            }
            Box(Modifier.padding(top = 12.dp).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(colors.surfaceContainerHighest).clearAndSetSemantics { }) {
                Box(Modifier.fillMaxWidth(progress.coerceIn(0.02f, 1f)).height(6.dp).background(fill))
            }
        }
        if (note != null) Text(note, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = if (tone == LimitTone.OVER) colors.error else colors.onSurfaceVariant, maxLines = 3)
        if (shares.isEmpty()) {
            Text(emptyText, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        } else {
            CategoryBar(shares, Modifier.padding(top = 16.dp))
            val slot = categorySlotColors()
            Column(Modifier.padding(top = 4.dp)) {
                shares.forEachIndexed { i, share ->
                    CategoryRow(categoryName(share), DisplayFormatters.rupees(share.amount), share.percent, slot[i.coerceAtMost(slot.lastIndex)])
                }
            }
        }
    }
}
