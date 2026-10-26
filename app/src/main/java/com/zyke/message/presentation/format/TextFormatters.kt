package com.zyke.message.presentation.format

import com.zyke.message.domain.model.Merchant
import com.zyke.message.domain.model.MessageCategory
import java.util.Locale

private val EMAIL_LIKE = Regex("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}")

/** "SOME CAFE" -> "Some Cafe"; words that look like UPI ids are left alone. */
fun String.toTitleCase(): String =
    lowercase(Locale.ROOT).split(" ").joinToString(" ") { word ->
        if (EMAIL_LIKE.matches(word)) word else word.replaceFirstChar { it.titlecase(Locale.ROOT) }
    }

/** UPI ids are shown verbatim, everything else is title-cased. */
val Merchant.displayName: String
    get() = if (category == MessageCategory.UPI_PAYMENT) name else name.toTitleCase()
