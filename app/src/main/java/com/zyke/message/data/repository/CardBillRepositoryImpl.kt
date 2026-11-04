package com.zyke.message.data.repository

import android.content.Context
import com.zyke.message.domain.ledger.CardBillDecisions
import com.zyke.message.domain.ledger.MemoryCardBills

/**
 * What the person said about which payments paid a credit card's bill, in private preferences. The answers themselves
 * are kept by [MemoryCardBills] (tested); this only loads and saves.
 */
class CardBillRepositoryImpl private constructor(
    private val prefs: android.content.SharedPreferences
) : MemoryCardBills(load(prefs)) {

    constructor(context: Context) : this(context.applicationContext.getSharedPreferences("card_bills", Context.MODE_PRIVATE))

    override fun answer(id: Long, payeeKey: String?, isBillPayment: Boolean, always: Boolean) {
        super.answer(id, payeeKey, isBillPayment, always)
        save(prefs, state.value)
    }

    override fun forget(id: Long, payeeKey: String?) {
        super.forget(id, payeeKey)
        save(prefs, state.value)
    }

    private companion object {
        const val YES = "yes"
        const val NO = "no"
        const val PAYEES_YES = "payees_yes"
        const val PAYEES_NO = "payees_no"

        fun save(prefs: android.content.SharedPreferences, state: CardBillDecisions) {
            prefs.edit()
                .putStringSet(YES, state.yes.mapTo(HashSet()) { it.toString() })
                .putStringSet(NO, state.no.mapTo(HashSet()) { it.toString() })
                .putStringSet(PAYEES_YES, HashSet(state.payeesYes))
                .putStringSet(PAYEES_NO, HashSet(state.payeesNo))
                .apply()
        }

        fun load(prefs: android.content.SharedPreferences): CardBillDecisions = runCatching {
            CardBillDecisions(
                yes = prefs.getStringSet(YES, emptySet()).orEmpty().mapNotNull { it.toLongOrNull() }.toSet(),
                no = prefs.getStringSet(NO, emptySet()).orEmpty().mapNotNull { it.toLongOrNull() }.toSet(),
                payeesYes = prefs.getStringSet(PAYEES_YES, emptySet()).orEmpty().toSet(),
                payeesNo = prefs.getStringSet(PAYEES_NO, emptySet()).orEmpty().toSet()
            )
        }.getOrDefault(CardBillDecisions())
    }
}
