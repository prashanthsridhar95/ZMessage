package com.zyke.message.domain.model

/**
 * A category the person made. Messages filed under it carry [id] in [ClassifiedMessage.customCategoryId]; their
 * built-in [ClassifiedMessage.category] is OTHERS, which is where any screen that does not know custom categories
 * groups them.
 */
data class CustomCategory(
    val id: String,
    val name: String,
    /** One emoji, or null for a plain coloured dot. */
    val emoji: String? = null,
    /** Which of the [com.zyke.message.domain.categories.CustomCategories.COLOUR_COUNT] theme colours. */
    val colourIndex: Int = 0
) : java.io.Serializable
