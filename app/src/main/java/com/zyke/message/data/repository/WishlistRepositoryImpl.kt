package com.zyke.message.data.repository

import android.content.Context
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.repository.WishlistRepository
import com.zyke.message.domain.wishlist.WishItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

class WishlistRepositoryImpl(context: Context) : WishlistRepository {

    private val prefs = context.applicationContext.getSharedPreferences("wishlist", Context.MODE_PRIVATE)

    private val _items = MutableStateFlow(load())
    override val items: StateFlow<List<WishItem>> = _items.asStateFlow()

    override fun save(item: WishItem) {
        val others = _items.value.filter { it.id != item.id }
        update(if (others.size == _items.value.size) others + item else _items.value.map { if (it.id == item.id) item else it })
    }

    override fun remove(id: String) = update(_items.value.filter { it.id != id })

    private fun update(items: List<WishItem>) {
        _items.value = items
        val array = JSONArray()
        items.forEach { item ->
            array.put(
                JSONObject()
                    .put("id", item.id).put("name", item.name).put("price", item.price.toPlainString())
                    .put("category", item.category?.name ?: JSONObject.NULL)
            )
        }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    private fun load(): List<WishItem> = runCatching {
        val array = JSONArray(prefs.getString(KEY, null) ?: return emptyList())
        (0 until array.length()).mapNotNull { index ->
            val json = array.getJSONObject(index)
            val price = json.optString("price").toBigDecimalOrNull() ?: return@mapNotNull null
            WishItem(
                json.getString("id"), json.getString("name"), price,
                json.optString("category").takeIf { it.isNotEmpty() && it != "null" }
                    ?.let { name -> MessageCategory.entries.firstOrNull { it.name == name } }
            )
        }
    }.getOrDefault(emptyList())

    private companion object {
        const val KEY = "items"
    }
}
