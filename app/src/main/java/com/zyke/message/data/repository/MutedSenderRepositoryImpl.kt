package com.zyke.message.data.repository

import android.content.Context
import com.zyke.message.domain.repository.MutedSenderRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Muted senders, kept in private preferences. */
class MutedSenderRepositoryImpl(context: Context) : MutedSenderRepository {

    private val prefs = context.applicationContext.getSharedPreferences("muted_senders", Context.MODE_PRIVATE)

    private val _muted = MutableStateFlow(prefs.getStringSet(KEY, emptySet()).orEmpty().toSet())
    override val muted: StateFlow<Set<String>> = _muted.asStateFlow()

    override fun setMuted(senderKey: String, muted: Boolean) {
        val updated = if (muted) _muted.value + senderKey else _muted.value - senderKey
        prefs.edit().putStringSet(KEY, updated).apply()
        _muted.value = updated
    }

    private companion object {
        const val KEY = "keys"
    }
}
