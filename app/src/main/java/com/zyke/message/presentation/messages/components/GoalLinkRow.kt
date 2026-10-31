package com.zyke.message.presentation.messages.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.zyke.message.ZykeMessagesApplication
import com.zyke.message.domain.goals.GoalContributions
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.presentation.common.FormSheet
import com.zyke.message.presentation.common.LocalAppSnackbar
import com.zyke.message.presentation.common.PickField
import com.zyke.message.presentation.common.fullWidthClickable
import com.zyke.message.presentation.format.DisplayFormatters
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.launch

/** "Count towards a goal": records a transfer as money put towards a goal. Linking the same payment again replaces the link. */
@Composable
internal fun GoalLinkRow(item: ClassifiedMessage) {
    val tx = item.content as? MessageContent.Transaction ?: return
    val amount = tx.amount.toBigDecimalOrNull() ?: return
    val container = (LocalContext.current.applicationContext as ZykeMessagesApplication).container
    val goals by container.goals.goals.collectAsState()
    if (goals.isEmpty()) return
    val contributions by container.goalContributions.contributions.collectAsState()
    val linked = GoalContributions.goalOf(contributions, item.id)?.let { id -> goals.firstOrNull { it.id == id } }
    var open by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<String?>(null) }
    val snackbar = LocalAppSnackbar.current
    val scope = rememberCoroutineScope()
    val label = "Count towards a goal…"

    Row(
        Modifier.fillMaxWidth().fullWidthClickable(role = Role.Button, onClickLabel = label) { picked = linked?.name; open = true }.padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary, maxLines = 1)
        if (linked != null) {
            Spacer(Modifier.width(12.dp))
            Text(linked.name, style = MaterialTheme.typography.bodyMedium, color = mutedColor(), maxLines = 1)
        }
        Text("›", Modifier.padding(start = 8.dp), color = mutedColor())
    }

    if (open) {
        val goal = goals.firstOrNull { it.name == picked }
        FormSheet(
            title = "Count towards a goal",
            onDismiss = { open = false },
            primaryLabel = goal?.let { "Add ${DisplayFormatters.rupees(amount)} to ${it.name}" } ?: "Add",
            primaryEnabled = goal != null,
            secondaryLabel = if (linked != null) "Remove link" else null,
            onSecondary = if (linked != null) ({ container.goalSavings.unlinkTransfer(item.id); open = false }) else null,
            onPrimary = {
                val date = Instant.ofEpochMilli(item.message.date).atZone(ZoneId.systemDefault()).toLocalDate()
                val previous = linked
                container.goalSavings.linkTransfer(goal!!.id, item.id, amount, date)
                open = false
                scope.launch {
                    snackbar.currentSnackbarData?.dismiss()
                    val result = snackbar.showSnackbar("Counted towards ${goal.name}", actionLabel = "Undo", duration = SnackbarDuration.Long)
                    if (result == SnackbarResult.ActionPerformed) {
                        if (previous != null) container.goalSavings.linkTransfer(previous.id, item.id, amount, date) else container.goalSavings.unlinkTransfer(item.id)
                    }
                }
            }
        ) {
            PickField(label = "Goal", options = goals.map { it.name }, selected = picked, onPick = { picked = it })
        }
    }
}
