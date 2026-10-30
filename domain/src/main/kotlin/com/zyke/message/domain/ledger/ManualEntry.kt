package com.zyke.message.domain.ledger

import com.zyke.message.domain.model.BankName
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.domain.model.Merchant
import com.zyke.message.domain.model.MerchantLogo
import com.zyke.message.domain.model.SmsMessage
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class ManualKind(val label: String) { EXPENSE("Expense"), INCOME("Income"), TRANSFER("Transfer") }

/** Money the person records by hand: cash spent, a payment that sent no message, a transfer between their own accounts. */
data class ManualEntry(
    /** A positive number that is theirs alone; the message made from it carries the negative of it. */
    val id: Long,
    val kind: ManualKind,
    val amount: BigDecimal,
    /** Who it was paid to or came from, or what it was for. */
    val title: String,
    val category: MessageCategory,
    val date: LocalDate,
    val note: String = "",
    /** Where the money came from or went: "Cash" unless the person says otherwise. */
    val account: String = "Cash"
)

interface ManualEntryRepository {
    val entries: StateFlow<List<ManualEntry>>
    fun save(entry: ManualEntry)
    fun remove(id: Long)
    /** A number not used by any entry. */
    fun nextId(): Long
}

object NoManualEntries : ManualEntryRepository {
    override val entries: StateFlow<List<ManualEntry>> = MutableStateFlow(emptyList())
    override fun save(entry: ManualEntry) = Unit
    override fun remove(id: Long) = Unit
    override fun nextId(): Long = 1L
}

object ManualEntries {
    /** The sender a hand-made entry appears under, so it is told apart from messages. */
    const val SENDER = "You"

    fun isManual(message: ClassifiedMessage) = message.message.sender == SENDER && message.message.id < 0

    /** The entry's own id back from the id of the message made from it. */
    fun entryId(messageId: Long): Long = -messageId

    /**
     * An entry as a message, so every list, total, limit and calendar treats it as it treats a payment read from the
     * phone. A transfer is set aside from income and spending from the start.
     */
    fun toMessage(entry: ManualEntry, zone: ZoneId): ClassifiedMessage {
        val at = entry.date.atTime(LocalTime.NOON).atZone(zone).toInstant().toEpochMilli()
        val category = when {
            entry.kind == ManualKind.TRANSFER -> MessageCategory.OTHERS
            else -> entry.category
        }
        return ClassifiedMessage(
            message = SmsMessage(-entry.id, SENDER, entry.note.ifBlank { entry.title }, at, isRead = true),
            category = category,
            merchant = Merchant(entry.title, MerchantLogo.PERSON, category, renamed = true),
            content = MessageContent.Transaction(
                bankName = BankName.OTHERS,
                payee = entry.title,
                amount = entry.amount.stripTrailingZeros().toPlainString(),
                accountNumber = "",
                isReceived = entry.kind == ManualKind.INCOME,
                isCard = false,
                isCardPayment = entry.kind == ManualKind.TRANSFER
            ),
            categoryOverridden = true,
            setAside = if (entry.kind == ManualKind.TRANSFER) SetAside.TRANSFER else null
        )
    }
}
