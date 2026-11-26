package com.zyke.message.presentation.ai

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.zyke.message.data.ai.AiHub
import com.zyke.message.domain.ai.AiFailure
import com.zyke.message.domain.ai.AiFeature
import com.zyke.message.domain.ai.AskAnswer
import com.zyke.message.domain.ai.AskExecutor
import com.zyke.message.domain.ai.AskMetric
import com.zyke.message.domain.ai.AskOutcome
import com.zyke.message.domain.ai.AskQuery
import com.zyke.message.domain.ai.AskResult
import com.zyke.message.domain.ai.AskText
import com.zyke.message.domain.cycle.CycleRule
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.presentation.format.DisplayFormatters
import com.zyke.message.presentation.format.FormatCache
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MAX_PAYEES = 500
private val DayFormat = FormatCache.pattern("d MMM", Locale.getDefault())

/** One payment under an answer: what the row shows, and which message it opens ([messageId] is null in the demo's made-up examples). */
internal data class PaymentLine(val messageId: Long?, val name: String, val day: String, val amount: String)

/** What Zyke said back. The figures are the app's own ([AskAnswer]); the model only read the question. */
internal sealed interface Reply {
    data class Answer(
        val title: String, val figure: String?, val isCount: Boolean, val sentence: String, val understood: List<String>,
        val payments: List<PaymentLine>, val example: Boolean = false
    ) : Reply

    data class NotUnderstood(val reason: String, val question: String) : Reply
    data class Problem(val text: String, val question: String) : Reply
    data object NotReady : Reply
}

/** One bubble of the chat: what the person said, or what Zyke said back. */
internal sealed interface ChatEntry {
    val id: Long
    val at: Long

    data class Said(override val id: Long, override val at: Long, val text: String) : ChatEntry
    data class Replied(override val id: Long, override val at: Long, val reply: Reply) : ChatEntry
}

/**
 * The Ask Zyke conversation, for as long as the app is open. It is held in memory only: nothing of it is written to disk, backed up
 * or logged, and "Clear chat" (or the app closing) ends it. The model reads each question ([AiHub.ask], with the request before it so
 * "and last month?" can change part of it); the figures are worked out by [AskExecutor] from the messages.
 */
@Stable
internal class AskChat {
    val entries = mutableStateListOf<ChatEntry>()
    var thinking by mutableStateOf(false)
        private set

    /** A question another screen (Search's Ask row) wants asked as soon as the page opens. */
    var queued by mutableStateOf<String?>(null)

    private var previous: AskQuery? = null
    private var job: Job? = null
    private var nextId = 1L
    private var showingExamples = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Called as the page opens: in the demo the chat holds made-up examples and nothing else; outside it, never. */
    fun enter(demo: Boolean) {
        if (demo == showingExamples) return
        clear()
        showingExamples = demo
        if (demo) AskExamples.build(::newId).forEach { entries.add(it) }
    }

    fun send(text: String, ai: AiHub, messages: List<ClassifiedMessage>, cycle: CycleRule) {
        val question = text.trim()
        if (question.isEmpty() || thinking || showingExamples) return
        entries.add(ChatEntry.Said(newId(), System.currentTimeMillis(), question))
        thinking = true
        job = scope.launch {
            try {
                val reply = answer(question, ai, messages, cycle)
                entries.add(ChatEntry.Replied(newId(), System.currentTimeMillis(), reply))
            } finally {
                thinking = false
            }
        }
    }

    /** Stops the request being worked on; what was asked stays on screen. */
    fun stop() {
        job?.cancel()
        job = null
        thinking = false
    }

    fun clear() {
        stop()
        entries.clear()
        previous = null
        queued = null
    }

    private fun newId(): Long = nextId++

    private suspend fun answer(question: String, ai: AiHub, messages: List<ClassifiedMessage>, cycle: CycleRule): Reply {
        if (!ai.allowed(AiFeature.ASK)) return Reply.NotReady
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val payees = withContext(Dispatchers.Default) {
            messages.filter { it.content is MessageContent.Transaction }.groupingBy { it.merchant.name }.eachCount()
                .entries.sortedByDescending { it.value }.map { it.key }.filter { it.isNotBlank() }.take(MAX_PAYEES)
        }
        return when (val outcome = ai.ask(question, payees, today, previous)) {
            is AskOutcome.Understood -> {
                val query = outcome.query
                val reply = withContext(Dispatchers.Default) {
                    val result = AskExecutor.run(query, messages, today, zone, cycle, ai.customNames())
                    toReply(result, AskAnswer.of(result, zone), zone)
                }
                previous = query
                reply
            }
            is AskOutcome.Refused -> Reply.NotUnderstood(outcome.reason, question)
            is AskOutcome.Failed ->
                if (outcome.kind == AiFailure.UNAVAILABLE) Reply.NotReady else Reply.Problem(failureText(outcome.kind), question)
        }
    }

    private fun toReply(result: AskResult, text: AskText, zone: ZoneId): Reply.Answer {
        val lines = result.matches.map { m ->
            val content = m.content
            val amount = if (content is MessageContent.Transaction) DisplayFormatters.rupees(content.amount) else ""
            PaymentLine(
                m.id, m.merchant.name.ifBlank { m.message.sender },
                DayFormat.format(Instant.ofEpochMilli(m.message.date).atZone(zone)), amount
            )
        }
        val isCount = result.query.metric == AskMetric.COUNT
        return Reply.Answer(text.title, text.figure, isCount, text.sentence, text.understood, lines)
    }
}

/** The one chat of this run of the app. Held outside the screen so it survives rotation; never stored. */
internal object AskChatSession {
    val chat = AskChat()
}

/** The demo's conversation: made-up, labelled, and never from the model. */
internal object AskExamples {
    fun build(newId: () -> Long): List<ChatEntry> {
        val now = System.currentTimeMillis()
        fun said(text: String) = ChatEntry.Said(newId(), now, text)
        fun replied(reply: Reply) = ChatEntry.Replied(newId(), now, reply)
        return listOf(
            said("This month's spending"),
            replied(
                Reply.Answer(
                    "Spent, this month", "₹18,420", false, "You spent ₹18,420 this month, across 23 payments.", listOf("Spent", "this month"),
                    listOf(
                        PaymentLine(null, "Swiggy", "25 Nov", "₹640"), PaymentLine(null, "Cafe Mocha", "20 Nov", "₹1,250"),
                        PaymentLine(null, "Landlord", "2 Nov", "₹8,500")
                    ),
                    example = true
                )
            ),
            said("and last month?"),
            replied(
                Reply.Answer(
                    "Spent, last month", "₹21,060", false, "You spent ₹21,060 last month, across 31 payments.", listOf("Spent", "last month"),
                    listOf(PaymentLine(null, "Landlord", "2 Oct", "₹8,500")), example = true
                )
            )
        )
    }
}
