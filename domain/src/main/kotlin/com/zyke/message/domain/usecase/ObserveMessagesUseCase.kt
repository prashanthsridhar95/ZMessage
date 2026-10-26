package com.zyke.message.domain.usecase

import com.zyke.message.domain.classification.MessageClassifier
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.repository.SmsRepository
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/** Newest-first, de-duplicated (same text on the same day), classified inbox. */
class ObserveMessagesUseCase(
    private val repository: SmsRepository,
    private val classifier: MessageClassifier,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    operator fun invoke(): Flow<List<ClassifiedMessage>> =
        repository.observeInbox()
            .map { inbox ->
                val zone = ZoneId.systemDefault()
                inbox.sortedByDescending { it.date }
                    .distinctBy { it.body to Instant.ofEpochMilli(it.date).atZone(zone).toLocalDate() }
                    .map(classifier::classify)
            }
            .flowOn(dispatcher)
}
