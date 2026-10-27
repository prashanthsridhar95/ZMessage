package com.zyke.message.domain.wishlist

import com.zyke.message.domain.model.MessageCategory
import java.math.BigDecimal

/** Something the person wants to buy and could put off when a month is tight. */
data class WishItem(
    val id: String,
    val name: String,
    val price: BigDecimal,
    /** What kind of spending it would be; used to suggest putting it off when that category runs out. */
    val category: MessageCategory? = null
)
