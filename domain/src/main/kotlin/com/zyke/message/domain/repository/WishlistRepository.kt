package com.zyke.message.domain.repository

import com.zyke.message.domain.wishlist.WishItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

interface WishlistRepository {
    val items: StateFlow<List<WishItem>>

    /** Adds the item, or replaces the one with the same id. */
    fun save(item: WishItem)

    fun remove(id: String)
}

object NoWishlist : WishlistRepository {
    override val items: StateFlow<List<WishItem>> = MutableStateFlow(emptyList())
    override fun save(item: WishItem) = Unit
    override fun remove(id: String) = Unit
}
