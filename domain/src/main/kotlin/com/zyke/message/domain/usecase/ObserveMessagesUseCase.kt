package com.zyke.message.domain.usecase

import com.zyke.message.domain.repository.NoMerges
import com.zyke.message.domain.repository.MergeRepository
import com.zyke.message.domain.merge.Merges
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.CoroutineScope
import com.zyke.message.domain.categories.CategoryKey
import com.zyke.message.domain.diag.DiagLog
import com.zyke.message.domain.classification.MessageClassifier
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.ai.ModelTransactionParser
import com.zyke.message.domain.ai.NoModel
import com.zyke.message.domain.cards.CardMatcher
import com.zyke.message.domain.classification.GenericTransactionParser
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.domain.insights.CategorySuggester
import com.zyke.message.domain.repository.CardRepository
import com.zyke.message.domain.repository.CategoryOverrideRepository
import com.zyke.message.domain.repository.NoCards
import com.zyke.message.domain.repository.NoCategoryOverrides
import com.zyke.message.domain.conversation.SenderKey
import com.zyke.message.domain.model.SmsMessage
import com.zyke.message.domain.repository.ClassificationCache
import com.zyke.message.domain.repository.MutedSenderRepository
import com.zyke.message.domain.repository.NoClassificationCache
import com.zyke.message.domain.repository.UserMessageStateRepository
import com.zyke.message.domain.repository.NoMutedSenders
import com.zyke.message.domain.repository.NicknameRepository
import com.zyke.message.domain.repository.NoNicknames
import com.zyke.message.domain.repository.NoSenderImages
import com.zyke.message.domain.repository.SenderImageRepository
import com.zyke.message.domain.repository.SmsRepository
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/** Newest-first, de-duplicated (same text on the same day), classified inbox with the user's categories applied. */
class ObserveMessagesUseCase(
    private val repository: SmsRepository,
    private val classifier: MessageClassifier,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val categoryOverrides: CategoryOverrideRepository = NoCategoryOverrides,
    private val tokenRules: com.zyke.message.domain.repository.TokenRuleRepository = com.zyke.message.domain.repository.NoTokenRules,
    private val cardRepository: CardRepository = NoCards,
    private val modelParser: ModelTransactionParser = ModelTransactionParser(NoModel),
    private val senderImages: SenderImageRepository = NoSenderImages,
    private val mutedSenders: MutedSenderRepository = NoMutedSenders,
    private val nicknameRepository: NicknameRepository = NoNicknames,
    private val mergeRepository: MergeRepository = NoMerges,
    private val classificationCache: ClassificationCache = NoClassificationCache,
    /** When given, marks for messages that have left the inbox are dropped each time the inbox is read. */
    private val userState: UserMessageStateRepository? = null,
    /** When given, the result is shared by every screen that asks for it instead of being worked out again for each. */
    private val scope: CoroutineScope? = null,
    /** True while the person has paused reading messages: the inbox is left alone and what was remembered is shown. */
    private val isReadingPaused: () -> Boolean = { false },
    /** Payments the person records by hand; they are read as if they had come in a message. */
    private val manualEntries: com.zyke.message.domain.ledger.ManualEntryRepository = com.zyke.message.domain.ledger.NoManualEntries,
    /** Payments left out of the totals and transfers between the person's own accounts. */
    private val setAsides: com.zyke.message.domain.ledger.SetAsideRepository = com.zyke.message.domain.ledger.NoSetAsides,
    /** Message formats the person taught; applied to messages the built-in rules left as plain text. */
    private val learnedTemplates: com.zyke.message.domain.repository.LearnedTemplateRepository = com.zyke.message.domain.repository.NoLearnedTemplates
) {
    private val progressive: ProgressiveInbox? by lazy {
        if (repository.supportsWindows) {
            ProgressiveInbox(repository, ::classifyBatch, classificationCache, userState, paused = isReadingPaused)
        } else {
            null
        }
    }

    /** How far a very large inbox has been read; always done for a small one. */
    val progress: StateFlow<LoadProgress> get() = progressive?.progress ?: DONE

    private val shared: Flow<List<ClassifiedMessage>>? by lazy {
        scope?.let { pipeline().shareIn(it, SharingStarted.WhileSubscribed(SHARE_TIMEOUT_MS), replay = 1) }
    }

    /** The classified inbox: the newest month first, older months added behind it as they are read. */
    operator fun invoke(): Flow<List<ClassifiedMessage>> = shared ?: pipeline()

    /**
     * The same inbox for a screen that rebuilds a lot from it: the first list at once; while older months are still
     * being read, at most one list per [minGapMs] (the newest is kept, never dropped); afterwards every change at once.
     */
    fun calm(minGapMs: Long = CALM_GAP_MS): Flow<List<ClassifiedMessage>> = kotlinx.coroutines.flow.flow {
        var last = 0L
        invoke().conflate().collect { list ->
            if (last != 0L && !progress.value.isDone) {
                val wait = minGapMs - (System.currentTimeMillis() - last)
                if (wait > 0) kotlinx.coroutines.delay(wait)
            }
            emit(list)
            last = System.currentTimeMillis()
        }
    }

    private fun pipeline(): Flow<List<ClassifiedMessage>> =
        (progressive?.messages() ?: whole())
            .combine(manualEntries.entries) { classified, entries ->
                if (entries.isEmpty()) {
                    classified
                } else {
                    val zone = ZoneId.systemDefault()
                    (classified + entries.map { com.zyke.message.domain.ledger.ManualEntries.toMessage(it, zone) }).sortedByDescending { it.message.date }
                }
            }
            .combine(learnedTemplates.templates) { classified, templates -> applyLearned(classified, templates) }
            .combine(categoryOverrides.overrides.combine(tokenRules.rules) { o, t -> o to t }) { classified, (overrides, words) ->
                val applied = classified.map { CategoryKey.apply(it, overrides, words) }
                DiagLog.d("pipeline") {
                    "category overrides: ${overrides.size} set, ${applied.count { it.categoryOverridden }} of ${applied.size} messages carry one, ${applied.count { it.categoryOwn }} their own"
                }
                applied
            }
            .combine(cardRepository.cards) { classified, cards ->
                if (cards.isEmpty()) classified else classified.map { CardMatcher.apply(it, cards) }
            }
            .combine(senderImages.keysWithImage) { classified, keys ->
                if (keys.isEmpty()) classified else classified.map { message ->
                    // A picture chosen for this one payment beats one chosen for everything from its sender or receiver.
                    val own = CategoryKey.messageKey(message.id)
                    val key = CategoryKey.of(message)
                    when {
                        own in keys -> message.copy(merchant = message.merchant.copy(imageKey = own))
                        key != null && key in keys -> message.copy(merchant = message.merchant.copy(imageKey = key))
                        else -> message
                    }
                }
            }
            .combine(mutedSenders.muted) { classified, muted ->
                if (muted.isEmpty()) classified else classified.map { message ->
                    if (SenderKey.of(message.message.sender) in muted) message.copy(isPromotional = true, isMuted = true) else message
                }
            }
            .combine(nicknameRepository.nicknames) { classified, nicknames ->
                DiagLog.d("pipeline") {
                    val named = classified.count { it.merchant.renamed }
                    "nicknames: ${nicknames.size} set, ${classified.count { m -> nicknames.containsKey(CategoryKey.messageKey(m.id)) || CategoryKey.of(m)?.let(nicknames::containsKey) == true }} of ${classified.size} messages match one (before applying), $named already renamed"
                }
                if (nicknames.isEmpty()) classified else classified.map { message ->
                    // A name given to this one transaction wins over one given to everything from the same sender or receiver.
                    val own = nicknames[CategoryKey.messageKey(message.id)]
                    val nickname = own ?: CategoryKey.of(message)?.let(nicknames::get)
                    if (nickname != null) message.copy(merchant = message.merchant.copy(name = nickname, renamed = true), nicknameOwn = own != null) else message
                }
            }
            .combine(setAsides.state) { classified, asides ->
                DiagLog.d("pipeline") { "set aside: ${asides.excluded.size} left out, ${asides.transfers.size} transfers, ${asides.dismissed.size} dismissed suggestions, over ${classified.size} messages" }
                if (asides.excluded.isEmpty() && asides.transfers.isEmpty()) classified else classified.map { message ->
                    val kind = message.setAside ?: asides.kindOf(message.id) ?: return@map message
                    val content = message.content
                    val internal = if (content is MessageContent.Transaction) content.copy(isCardPayment = true) else content
                    message.copy(content = internal, setAside = kind)
                }
            }
            .combine(mergeRepository.merges) { classified, merges -> Merges.apply(classified, merges) }
            .map(CategorySuggester::annotate)
            // Nothing changed for any message (every one is the same instance): do not wake every screen again.
            .distinctUntilChanged(::sameInstances)
            .flowOn(dispatcher)

    /** Only messages left as plain text that a taught format reads fully are copied; every other one stays the same instance. */
    private fun applyLearned(classified: List<ClassifiedMessage>, templates: List<com.zyke.message.domain.teach.LearnedTemplate>): List<ClassifiedMessage> {
        if (templates.isEmpty()) return classified
        return classified.map { message ->
            if (message.content != MessageContent.Generic) return@map message
            val (template, tx) = com.zyke.message.domain.teach.LearnedTemplates.firstMatch(templates, message.message) ?: return@map message
            val read = classifier.fromLearned(message.message, tx)
            val category = template.category
            val filed = if (category == null) read else read.copy(category = category, merchant = read.merchant.copy(category = category))
            filed.copy(learnedTemplateId = template.id)
        }
    }

    private fun sameInstances(a: List<ClassifiedMessage>, b: List<ClassifiedMessage>): Boolean {
        if (a === b) return true
        if (a.size != b.size) return false
        for (i in a.indices) if (a[i] !== b[i]) return false
        return true
    }

    /** A small inbox, or a repository that cannot read by month: read all of it at once. */
    private fun whole(): Flow<List<ClassifiedMessage>> =
        repository.observeInbox().map { inbox ->
            if (isReadingPaused()) return@map classificationCache.load().values.sortedByDescending { it.message.date }
            val classified = classifyBatch(inbox)
            if (inbox.isNotEmpty()) {
                // A message deleted in the messaging app is gone from here too, along with every trace of it.
                val present = inbox.mapTo(HashSet()) { it.id }
                classificationCache.retainOnly(present)
                userState?.retainOnly(present)
            }
            classified
        }

    /** Reads [inbox] (all of it, or one month): from memory where a message is remembered, by the rules where it is new. */
    private suspend fun classifyBatch(inbox: List<SmsMessage>): List<ClassifiedMessage> {
        val zone = ZoneId.systemDefault()
        val ordered = inbox.sortedByDescending { it.date }
            .distinctBy { it.body to Instant.ofEpochMilli(it.date).atZone(zone).toLocalDate() }
        val remembered = classificationCache.load(ordered.map { it.id })
        val fresh = ArrayList<ClassifiedMessage>()

        val classified = ordered.map { sms -> remembered[sms.id]?.takeIf { sameText(it.message, sms) }?.withMessage(sms) ?: read(sms, fresh) }

        // Only what is new or changed is saved.
        if (fresh.isNotEmpty()) classificationCache.save(fresh)

        return if (modelParser.isAvailable) classified.map { upgrade(it) } else classified
    }

    private fun read(sms: SmsMessage, fresh: MutableList<ClassifiedMessage>): ClassifiedMessage =
        classifier.classify(sms).also { fresh += it }

    /** The same text from the same sender at the same time; the read flag may have changed since, which does not matter. */
    private fun sameText(old: SmsMessage, new: SmsMessage) =
        old.sender == new.sender && old.body == new.body && old.date == new.date

    private fun ClassifiedMessage.withMessage(sms: SmsMessage) = if (message == sms) this else copy(message = sms)

    /** Money messages the rules left as plain text get a second read by the on-device model, if the phone has one. */
    private suspend fun upgrade(message: ClassifiedMessage): ClassifiedMessage {
        if (message.content != MessageContent.Generic || message.isPromotional) return message
        val body = message.message.body
        if (!GenericTransactionParser.mentionsMoney(body)) return message
        val transaction = modelParser.parse(body) ?: return message
        return classifier.fromTransaction(message.message, transaction)
    }

    private companion object {
        const val SHARE_TIMEOUT_MS = 5_000L
        const val CALM_GAP_MS = 700L
        val DONE: StateFlow<LoadProgress> = MutableStateFlow(LoadProgress())
    }
}
