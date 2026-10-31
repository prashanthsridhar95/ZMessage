package com.zyke.message.presentation.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zyke.message.ZykeMessagesApplication
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.repository.LearnedTemplateRepository
import com.zyke.message.domain.teach.LearnedTemplate
import com.zyke.message.domain.teach.TeachPicks
import com.zyke.message.domain.teach.TemplateLearner
import com.zyke.message.presentation.common.AppButton
import com.zyke.message.presentation.common.ButtonSize
import com.zyke.message.presentation.common.ButtonStyle
import com.zyke.message.presentation.common.ChipOption
import com.zyke.message.presentation.common.Chips
import com.zyke.message.presentation.common.Choice
import com.zyke.message.presentation.common.FintechCard
import com.zyke.message.presentation.common.FormGroup
import com.zyke.message.presentation.common.FormSheet
import com.zyke.message.presentation.common.PickField
import com.zyke.message.presentation.common.SegmentedChoice
import com.zyke.message.presentation.format.DisplayFormatters
import com.zyke.message.presentation.messages.components.Caption
import com.zyke.message.presentation.messages.components.RowTitle

/** A taught format and how many messages it has read. */
data class TaughtFormat(val template: LearnedTemplate, val recognised: Int)

@Composable
fun rememberLearnedTemplates(): LearnedTemplateRepository {
    val container = (LocalContext.current.applicationContext as ZykeMessagesApplication).container
    return remember(container) { container.learnedTemplates }
}

private enum class Role(val label: String) { AMOUNT("Amount"), PAYEE("Payee"), ACCOUNT("Account") }

/** Tap the amount (and, if there, the payee and account) in a message the app did not understand. */
@Composable
internal fun TeachSheet(message: ClassifiedMessage, onSave: (LearnedTemplate) -> Unit, onDismiss: () -> Unit) {
    val sms = message.message
    val tokens = remember(sms.body) { TemplateLearner.tokenize(sms.body) }
    var role by rememberSaveable { mutableStateOf(Role.AMOUNT) }
    var amount by rememberSaveable { mutableStateOf<Int?>(null) }
    var payeeFrom by rememberSaveable { mutableStateOf<Int?>(null) }
    var payeeTo by rememberSaveable { mutableStateOf<Int?>(null) }
    var account by rememberSaveable { mutableStateOf<Int?>(null) }
    var received by rememberSaveable { mutableStateOf(false) }
    var categoryName by rememberSaveable { mutableStateOf<String?>(null) }
    val categories = remember { MessageCategory.assignable }
    val chosen = categories.firstOrNull { it.name == categoryName }

    val template = remember(sms.id, amount, payeeFrom, payeeTo, account, received, categoryName) {
        val a = amount ?: return@remember null
        val range = payeeFrom?.let { from -> from..(payeeTo ?: from) }
        TemplateLearner.learn(sms, TeachPicks(a, range, account, received, chosen))
    }
    val read = template?.apply(sms)

    fun tap(i: Int) {
        when (role) {
            Role.AMOUNT -> amount = if (amount == i) null else i
            Role.ACCOUNT -> account = if (account == i) null else i
            Role.PAYEE -> {
                val from = payeeFrom
                val to = payeeTo
                when {
                    from == null || to == null -> { payeeFrom = i; payeeTo = i }
                    i == from && i == to -> { payeeFrom = null; payeeTo = null }
                    i in from..to -> { payeeFrom = i; payeeTo = i }
                    i == to + 1 -> payeeTo = i
                    i == from - 1 -> payeeFrom = i
                    else -> { payeeFrom = i; payeeTo = i }
                }
            }
        }
    }

    fun roleOf(i: Int): Role? = when {
        i == amount -> Role.AMOUNT
        i == account -> Role.ACCOUNT
        payeeFrom != null && i in payeeFrom!!..(payeeTo ?: payeeFrom!!) -> Role.PAYEE
        else -> null
    }

    FormSheet(
        title = "Teach this message",
        onDismiss = onDismiss,
        primaryLabel = "Teach",
        primaryEnabled = template != null,
        onPrimary = { template?.let(onSave) },
        subtitle = "Tap the amount, then the payee and account if they are there. This stays on your phone.",
        hint = if (amount != null && template == null) "That does not make a format the app can trust yet. Try a different word, or pick the payee too." else null
    ) {
        SegmentedChoice(Role.entries.map { Choice(it, it.label) }, role, { role = it })
        Chips(
            tokens.mapIndexed { i, t -> ChipOption(roleOf(i)?.let { r -> "${t.text} · ${r.label.lowercase()}" } ?: t.text, roleOf(i) != null) },
            onSelect = ::tap
        )
        FormGroup("Money was") {
            SegmentedChoice(listOf(Choice(false, "Spent"), Choice(true, "Received")), received, { received = it })
        }
        PickField(
            "Category (optional)", categories.map { it.displayString }, chosen?.displayString,
            onPick = { picked -> categoryName = categories.firstOrNull { it.displayString == picked }?.name },
            placeholder = "Choose a category"
        )
        FormGroup("The app would record") {
            Text(
                if (read == null) "Nothing yet" else {
                    val money = DisplayFormatters.rupees(read.amount.replace(",", "").toBigDecimal())
                    buildString {
                        append(if (received) "Received " else "Spent ").append(money)
                        if (read.payee.isNotBlank()) append(if (received) " from " else " at ").append(read.payee)
                        if (read.accountNumber.isNotBlank()) append(", account ••").append(read.accountNumber)
                    }
                },
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

/** The formats the person has taught, each with how many messages it read and a way to take it back. */
@Composable
internal fun TaughtFormats(formats: List<TaughtFormat>, onDelete: (TaughtFormat) -> Unit) {
    if (formats.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader("Taught formats", formats.size)
        formats.forEach { format ->
            FintechCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        RowTitle((if (format.template.isReceived) "Money in" else "Money out") + " · " + format.template.senderKey)
                        Caption(
                            if (format.recognised == 1) "Read 1 message" else "Read ${format.recognised} messages",
                            Modifier.padding(top = 2.dp)
                        )
                    }
                    AppButton("Delete", { onDelete(format) }, style = ButtonStyle.OUTLINE, size = ButtonSize.COMPACT)
                }
            }
        }
    }
}
