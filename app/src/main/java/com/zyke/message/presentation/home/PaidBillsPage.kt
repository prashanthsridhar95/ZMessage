package com.zyke.message.presentation.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.zyke.message.ZykeMessagesApplication
import com.zyke.message.domain.home.PaidBill
import com.zyke.message.domain.home.PaidBillRepository
import com.zyke.message.presentation.common.AppButton
import com.zyke.message.presentation.common.ButtonSize
import com.zyke.message.presentation.common.ButtonStyle
import com.zyke.message.presentation.common.EmptyState
import com.zyke.message.presentation.common.FintechCard
import com.zyke.message.presentation.common.LinkButton
import com.zyke.message.presentation.format.DisplayFormatters
import com.zyke.message.presentation.home.HomeContract.Intent
import com.zyke.message.presentation.messages.components.AmountText
import com.zyke.message.presentation.messages.components.Caption
import com.zyke.message.presentation.messages.components.RowTitle

@Composable
internal fun rememberPaidBills(): PaidBillRepository {
    val container = (LocalContext.current.applicationContext as ZykeMessagesApplication).container
    return remember(container) { container.paidBills }
}

/**
 * A link at the foot of the Bills page to the bills the person marked as paid, each with a Restore that puts the bill
 * back in the list. Shows nothing until a bill has been marked.
 */
@Composable
internal fun PaidBillsLink(onIntent: (Intent) -> Unit) {
    val repository = rememberPaidBills()
    val bills by repository.bills.collectAsState()
    var open by rememberSaveable { mutableStateOf(false) }
    if (bills.isNotEmpty()) LinkButton("Marked as paid (${bills.size})", { open = true })
    if (open) {
        PlanPage("Marked as paid", { open = false }) {
            if (bills.isEmpty()) {
                EmptyState("Nothing marked as paid", "A bill you mark as paid is listed here, so you can bring it back if you marked it by mistake.")
            }
            bills.forEach { bill ->
                PaidBillCard(bill) {
                    onIntent(Intent.RestoreAttention(bill.key))
                    bill.attentionKey?.let { onIntent(Intent.RestoreAttention(it)) }
                    repository.remove(bill.key)
                }
            }
        }
    }
}

@Composable
private fun PaidBillCard(bill: PaidBill, onRestore: () -> Unit) {
    FintechCard {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    RowTitle(bill.title)
                    Caption("Due ${bill.dueDate} · marked paid ${bill.markedOn}")
                }
                AmountText(DisplayFormatters.rupees(bill.amount), androidx.compose.material3.MaterialTheme.colorScheme.secondary)
            }
            AppButton("Restore", onRestore, style = ButtonStyle.OUTLINE, size = ButtonSize.COMPACT)
        }
    }
}
