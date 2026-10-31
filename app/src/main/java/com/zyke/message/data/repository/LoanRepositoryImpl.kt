package com.zyke.message.data.repository

import android.content.Context
import com.zyke.message.domain.loans.Loan
import com.zyke.message.domain.loans.LoanKind
import com.zyke.message.domain.repository.LoanRepository
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** The loans the person entered, kept in private preferences. */
class LoanRepositoryImpl(context: Context) : LoanRepository {

    private val prefs = context.applicationContext.getSharedPreferences("loans", Context.MODE_PRIVATE)

    private val _loans = MutableStateFlow(load())
    override val loans: StateFlow<List<Loan>> = _loans.asStateFlow()

    override fun save(loan: Loan) {
        val exists = _loans.value.any { it.id == loan.id }
        write(if (exists) _loans.value.map { if (it.id == loan.id) loan else it } else _loans.value + loan)
    }

    override fun remove(id: String) = write(_loans.value.filter { it.id != id })

    private fun write(loans: List<Loan>) {
        _loans.value = loans
        val array = JSONArray()
        loans.forEach {
            array.put(
                JSONObject().put("id", it.id).put("name", it.name).put("kind", it.kind.name)
                    .put("principal", it.principal.toPlainString()).put("rate", it.annualRatePercent.toPlainString())
                    .put("months", it.tenureMonths).put("start", it.startDate.toString())
                    .put("emi", it.emiOverride?.toPlainString() ?: JSONObject.NULL).put("day", it.emiDay)
            )
        }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    private fun load(): List<Loan> = runCatching {
        val array = JSONArray(prefs.getString(KEY, null) ?: return emptyList())
        (0 until array.length()).mapNotNull {
            val o = array.getJSONObject(it)
            Loan(
                id = o.getString("id"),
                name = o.getString("name"),
                kind = runCatching { LoanKind.valueOf(o.getString("kind")) }.getOrDefault(LoanKind.OTHER),
                principal = o.optString("principal").toBigDecimalOrNull() ?: return@mapNotNull null,
                annualRatePercent = o.optString("rate").toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO,
                tenureMonths = o.optInt("months", 0),
                startDate = runCatching { LocalDate.parse(o.getString("start")) }.getOrNull() ?: return@mapNotNull null,
                emiOverride = if (o.isNull("emi")) null else o.optString("emi").toBigDecimalOrNull(),
                emiDay = o.optInt("day", 1)
            )
        }
    }.getOrDefault(emptyList())

    private companion object {
        const val KEY = "loans"
    }
}
