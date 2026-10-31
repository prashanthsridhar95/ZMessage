package com.zyke.message.data.repository

import android.content.Context
import com.zyke.message.domain.envelopes.CarryOver
import com.zyke.message.domain.envelopes.CyclePlan
import com.zyke.message.domain.envelopes.EnvelopeBook
import com.zyke.message.domain.envelopes.EnvelopeMove
import com.zyke.message.domain.envelopes.EnvelopeRepository
import com.zyke.message.domain.model.MessageCategory
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** The person's envelope budgets, one plan per cycle, kept in private preferences. */
class EnvelopeRepositoryImpl(context: Context) : EnvelopeRepository {

    private val prefs = context.applicationContext.getSharedPreferences("envelopes", Context.MODE_PRIVATE)

    private val _book = MutableStateFlow(load())
    override val book: StateFlow<EnvelopeBook> = _book.asStateFlow()

    override fun save(book: EnvelopeBook) {
        _book.value = book
        val cycles = JSONObject()
        book.cycles.forEach { (start, plan) ->
            val assigned = JSONObject()
            plan.assigned.forEach { (category, amount) -> assigned.put(category.name, amount.toPlainString()) }
            val moves = JSONArray()
            plan.moves.forEach { moves.put(JSONObject().put("from", it.from.name).put("to", it.to.name).put("amount", it.amount.toPlainString())) }
            cycles.put(start.toString(), JSONObject().put("assigned", assigned).put("moves", moves))
        }
        val carry = JSONObject()
        book.carry.forEach { (category, mode) -> carry.put(category.name, mode.name) }
        prefs.edit().putString(KEY, JSONObject().put("cycles", cycles).put("carry", carry).toString()).apply()
    }

    private fun load(): EnvelopeBook = runCatching {
        val root = JSONObject(prefs.getString(KEY, null) ?: return EnvelopeBook())
        val cycles = root.optJSONObject("cycles")?.let { obj ->
            obj.keys().asSequence().mapNotNull { key ->
                val start = runCatching { LocalDate.parse(key) }.getOrNull() ?: return@mapNotNull null
                val o = obj.getJSONObject(key)
                val assignedObj = o.optJSONObject("assigned")
                val assigned = assignedObj?.keys()?.asSequence()?.mapNotNull { name ->
                    val category = category(name) ?: return@mapNotNull null
                    val amount = assignedObj.optString(name).toBigDecimalOrNull() ?: return@mapNotNull null
                    category to amount
                }?.toMap().orEmpty()
                val movesArray = o.optJSONArray("moves") ?: JSONArray()
                val moves = (0 until movesArray.length()).mapNotNull {
                    val m = movesArray.getJSONObject(it)
                    EnvelopeMove(
                        category(m.optString("from")) ?: return@mapNotNull null,
                        category(m.optString("to")) ?: return@mapNotNull null,
                        m.optString("amount").toBigDecimalOrNull() ?: return@mapNotNull null
                    )
                }
                start to CyclePlan(assigned, moves)
            }.toMap()
        }.orEmpty()
        val carryObj = root.optJSONObject("carry")
        val carry = carryObj?.keys()?.asSequence()?.mapNotNull { name ->
            val category = category(name) ?: return@mapNotNull null
            category to (runCatching { CarryOver.valueOf(carryObj.getString(name)) }.getOrNull() ?: return@mapNotNull null)
        }?.toMap().orEmpty()
        EnvelopeBook(cycles, carry)
    }.getOrDefault(EnvelopeBook())

    private fun category(name: String): MessageCategory? = runCatching { MessageCategory.valueOf(name) }.getOrNull()

    private companion object {
        const val KEY = "book"
    }
}
