package com.zyke.message.di

import android.content.Context
import com.zyke.message.data.repository.SmsRepositoryImpl
import com.zyke.message.data.repository.UserMessageStateRepositoryImpl
import com.zyke.message.data.sms.SmsDataSource
import com.zyke.message.domain.classification.MessageClassifier
import com.zyke.message.domain.repository.SmsRepository
import com.zyke.message.domain.repository.UserMessageStateRepository
import com.zyke.message.domain.usecase.ObserveMessagesUseCase

/** Manual dependency container; one instance lives in the Application. */
class AppContainer(context: Context) {
    private val smsDataSource = SmsDataSource(context.applicationContext.contentResolver)

    val smsRepository: SmsRepository = SmsRepositoryImpl(smsDataSource)
    val userMessageState: UserMessageStateRepository = UserMessageStateRepositoryImpl(context)
    val messageClassifier = MessageClassifier()
    val observeMessages = ObserveMessagesUseCase(smsRepository, messageClassifier)
}
