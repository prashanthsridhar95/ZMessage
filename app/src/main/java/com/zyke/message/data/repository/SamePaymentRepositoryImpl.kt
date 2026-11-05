package com.zyke.message.data.repository

import android.content.Context
import com.zyke.message.domain.ledger.MemorySamePayments
import com.zyke.message.domain.ledger.SamePaymentDecisions

/**
 * What the person said about which texts are the same payment as a bank debit, in private preferences. The answers
 * themselves are kept by [MemorySamePayments] (tested); this only loads and saves. The file is a plain preference file, so
 * backups carry it like the others (see PrefsBackup).
 */
class SamePaymentRepositoryImpl private constructor(
    private val prefs: android.content.SharedPreferences
) : MemorySamePayments(load(prefs)) {

    constructor(context: Context) : this(context.applicationContext.getSharedPreferences("same_payments", Context.MODE_PRIVATE))

    override fun answer(id: Long, primaryId: Long?, senderKey: String?, same: Boolean, always: Boolean) {
        super.answer(id, primaryId, senderKey, same, always)
        save(prefs, state.value)
    }

    override fun forget(id: Long, senderKey: String?) {
        super.forget(id, senderKey)
        save(prefs, state.value)
    }

    private companion object {
        const val YES = "yes"
        const val NO = "no"
        const val SENDERS_YES = "senders_yes"
        const val SENDERS_NO = "senders_no"

        fun save(prefs: android.content.SharedPreferences, state: SamePaymentDecisions) {
            prefs.edit()
                .putStringSet(YES, state.yes.mapTo(HashSet()) { (id, primary) -> "$id:$primary" })
                .putStringSet(NO, state.no.mapTo(HashSet()) { it.toString() })
                .putStringSet(SENDERS_YES, HashSet(state.sendersYes))
                .putStringSet(SENDERS_NO, HashSet(state.sendersNo))
                .apply()
        }

        fun load(prefs: android.content.SharedPreferences): SamePaymentDecisions = runCatching {
            SamePaymentDecisions(
                yes = prefs.getStringSet(YES, emptySet()).orEmpty().mapNotNull { pair ->
                    val id = pair.substringBefore(':').toLongOrNull()
                    val primary = pair.substringAfter(':', "").toLongOrNull()
                    if (id != null && primary != null) id to primary else null
                }.toMap(),
                no = prefs.getStringSet(NO, emptySet()).orEmpty().mapNotNull { it.toLongOrNull() }.toSet(),
                sendersYes = prefs.getStringSet(SENDERS_YES, emptySet()).orEmpty().toSet(),
                sendersNo = prefs.getStringSet(SENDERS_NO, emptySet()).orEmpty().toSet()
            )
        }.getOrDefault(SamePaymentDecisions())
    }
}
