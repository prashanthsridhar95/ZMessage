package com.zyke.message.data.repository

import android.content.Context
import com.zyke.message.domain.home.PaidBill
import com.zyke.message.domain.home.PaidBillRepository
import com.zyke.message.domain.home.PaidBills
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** The bills the person marked as paid, in private preferences. */
class PaidBillRepositoryImpl(context: Context) : PaidBillRepository {
    private val prefs = context.applicationContext.getSharedPreferences("paid_bills", Context.MODE_PRIVATE)
    private val _bills = MutableStateFlow(load())
    override val bills: StateFlow<List<PaidBill>> = _bills.asStateFlow()

    override fun record(bill: PaidBill) = write(PaidBills.add(_bills.value, bill))

    override fun remove(key: String) = write(_bills.value.filter { it.key != key })

    private fun write(bills: List<PaidBill>) {
        _bills.value = bills
        val array = JSONArray()
        bills.forEach {
            array.put(
                JSONObject().put("key", it.key).put("attention", it.attentionKey ?: JSONObject.NULL).put("title", it.title)
                    .put("amount", it.amount.toPlainString()).put("due", it.dueDate.toString()).put("marked", it.markedOn.toString())
            )
        }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    private fun load(): List<PaidBill> = runCatching {
        val array = JSONArray(prefs.getString(KEY, null) ?: return emptyList())
        (0 until array.length()).mapNotNull {
            val o = array.getJSONObject(it)
            PaidBill(
                key = o.getString("key"),
                attentionKey = if (o.isNull("attention")) null else o.optString("attention"),
                title = o.optString("title"),
                amount = o.optString("amount").toBigDecimalOrNull() ?: return@mapNotNull null,
                dueDate = runCatching { LocalDate.parse(o.getString("due")) }.getOrNull() ?: return@mapNotNull null,
                markedOn = runCatching { LocalDate.parse(o.getString("marked")) }.getOrNull() ?: return@mapNotNull null
            )
        }
    }.getOrDefault(emptyList())

    private companion object {
        const val KEY = "bills"
    }
}
