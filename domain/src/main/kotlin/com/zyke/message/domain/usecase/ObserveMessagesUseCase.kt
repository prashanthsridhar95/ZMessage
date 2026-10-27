package com.zyke.message.domain.usecase

import com.zyke.message.domain.categories.CategoryKey
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
import com.zyke.message.domain.repository.MutedSenderRepository
import com.zyke.message.domain.repository.NoMutedSenders
import com.zyke.message.domain.repository.NoSenderImages
import com.zyke.message.domain.repository.SenderImageRepository
import com.zyke.message.domain.repository.SmsRepository
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/** Newest-first, de-duplicated (same text on the same day), classified inbox with the user's categories applied. */
class ObserveMessagesUseCase(
    private val repository: SmsRepository,
    private val classifier: MessageClassifier,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val categoryOverrides: CategoryOverrideRepository = NoCategoryOverrides,
    private val cardRepository: CardRepository = NoCards,
    private val modelParser: ModelTransactionParser = ModelTransactionParser(NoModel),
    private val senderImages: SenderImageRepository = NoSenderImages,
    private val mutedSenders: MutedSenderRepository = NoMutedSenders
) {
    operator fun invoke(): Flow<List<ClassifiedMessage>> =
        repository.observeInbox()
            .map { inbox ->
                val zone = ZoneId.systemDefault()
                inbox.sortedByDescending { it.date }
                    .distinctBy { it.body to Instant.ofEpochMilli(it.date).atZone(zone).toLocalDate() }
                    .map(classifier::classify)
                    .let { classified -> if (modelParser.isAvailable) classified.map { upgrade(it) } else classified }
            }
            .combine(categoryOverrides.overrides) { classified, overrides ->
                classified.map { CategoryKey.apply(it, overrides) }
            }
            .combine(cardRepository.cards) { classified, cards ->
                if (cards.isEmpty()) classified else classified.map { CardMatcher.apply(it, cards) }
            }
            .combine(senderImages.keysWithImage) { classified, keys ->
                if (keys.isEmpty()) classified else classified.map { message ->
                    val key = CategoryKey.of(message)
                    if (key != null && key in keys) message.copy(merchant = message.merchant.copy(imageKey = key)) else message
                }
            }
            .combine(mutedSenders.muted) { classified, muted ->
                if (muted.isEmpty()) classified else classified.map { message ->
                    if (SenderKey.of(message.message.sender) in muted) message.copy(isPromotional = true, isMuted = true) else message
                }
            }
            .map(CategorySuggester::annotate)
            .flowOn(dispatcher)

    /** Money messages the rules left as plain text get a second read by the on-device model, if the phone has one. */
    private suspend fun upgrade(message: ClassifiedMessage): ClassifiedMessage {
        if (message.content != MessageContent.Generic || message.isPromotional) return message
        val body = message.message.body
        if (!GenericTransactionParser.mentionsMoney(body)) return message
        val transaction = modelParser.parse(body) ?: return message
        return classifier.fromTransaction(message.message, transaction)
    }
}
