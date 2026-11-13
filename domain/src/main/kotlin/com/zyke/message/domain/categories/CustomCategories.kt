package com.zyke.message.domain.categories

import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.CustomCategory
import com.zyke.message.domain.model.MessageCategory

/** What the person has made and which senders, receivers and single payments are filed under it (key to category id). */
data class CustomCategoryState(
    val categories: List<CustomCategory> = emptyList(),
    val assignments: Map<String, String> = emptyMap()
)

/** The result of checking a name before it is used. */
sealed interface NameCheck {
    data class Valid(val name: String) : NameCheck
    data class Invalid(val reason: String) : NameCheck
}

/** What a category looks like on screen: always from [CustomCategories.look], so every screen agrees. */
data class CategoryLook(val name: String, val emoji: String?, val colourIndex: Int?, val custom: Boolean)

/** Rules for the person's own categories. */
object CustomCategories {
    const val MAX_COUNT = 30
    const val MAX_NAME = 24
    const val COLOUR_COUNT = 8

    /** A small curated set to pick an icon from. */
    val EMOJIS: List<String> = listOf(
        "\uD83C\uDF7D\uFE0F", "\u2615", "\uD83C\uDF7A", "\uD83D\uDED2", "\uD83D\uDECD\uFE0F", "\uD83D\uDE95", "\u2708\uFE0F", "\uD83C\uDFE0",
        "\uD83D\uDC76", "\uD83D\uDC36", "\uD83D\uDC8A", "\uD83C\uDFCB\uFE0F", "\uD83C\uDFAC", "\uD83C\uDFAE", "\uD83C\uDFB5", "\uD83D\uDCDA",
        "\uD83D\uDCBC", "\uD83D\uDD27", "\uD83C\uDF81", "\uD83D\uDC87", "\uD83D\uDCF1", "\uD83C\uDF31", "\u2764\uFE0F", "\u2B50"
    )

    private val SPACES = Regex("\\s+")

    private fun fold(text: String) = text.trim().replace(SPACES, " ").lowercase()

    /** Trimmed and single-spaced. */
    fun tidy(raw: String): String = raw.trim().replace(SPACES, " ")

    /**
     * 1 to [MAX_NAME] characters once trimmed, no control characters, and not the same (ignoring case) as a built-in
     * category or another of [existing]. [ignoreId] is the category being renamed, which may keep its own name.
     */
    fun validate(raw: String, existing: List<CustomCategory>, ignoreId: String? = null): NameCheck {
        val name = tidy(raw)
        if (name.isEmpty()) return NameCheck.Invalid("Give it a name")
        if (name.length > MAX_NAME) return NameCheck.Invalid("Keep it to $MAX_NAME characters")
        if (name.any { it.isISOControl() }) return NameCheck.Invalid("Use letters, numbers and symbols only")
        val folded = fold(name)
        if (MessageCategory.entries.any { fold(it.displayString) == folded || fold(it.name.replace('_', ' ')) == folded }) {
            return NameCheck.Invalid("That is already a category")
        }
        if (existing.any { it.id != ignoreId && fold(it.name) == folded }) return NameCheck.Invalid("You already have a category with that name")
        return NameCheck.Valid(name)
    }

    fun clampColour(index: Int): Int = ((index % COLOUR_COUNT) + COLOUR_COUNT) % COLOUR_COUNT

    /** A fresh id that no category in [existing] has. [random] is only for tests. */
    fun newId(existing: List<CustomCategory>, random: () -> Long = { java.util.concurrent.ThreadLocalRandom.current().nextLong(0, Long.MAX_VALUE) }): String {
        val used = existing.mapTo(HashSet()) { it.id }
        while (true) {
            val id = "c" + random().toString(36).take(8)
            if (id !in used) return id
        }
    }

    /**
     * Files messages under the person's categories: a choice for the one payment first, then one for its sender or
     * receiver. A payment the person gave a built-in category of its own keeps it. Messages are returned as they were
     * (same instances) when nothing applies.
     */
    fun apply(messages: List<ClassifiedMessage>, state: CustomCategoryState): List<ClassifiedMessage> {
        if (state.assignments.isEmpty() || state.categories.isEmpty()) return messages
        val known = state.categories.mapTo(HashSet()) { it.id }
        return messages.map { message ->
            val own = state.assignments[CategoryKey.messageKey(message.id)]?.takeIf { it in known }
            val id = own
                ?: message.takeIf { !it.categoryOwn }?.let { CategoryKey.find(state.assignments, it) }?.takeIf { it in known }
                ?: return@map message
            message.copy(
                category = MessageCategory.OTHERS,
                merchant = message.merchant.copy(category = MessageCategory.OTHERS),
                categoryOverridden = true,
                categoryOwn = own != null,
                customCategoryId = id
            )
        }
    }

    /** The single place a category's name, emoji and colour are worked out, for a message or a spending line. */
    fun look(category: MessageCategory, customId: String?, customs: List<CustomCategory>): CategoryLook {
        val custom = customId?.let { id -> customs.firstOrNull { it.id == id } }
        return if (custom != null) CategoryLook(custom.name, custom.emoji, clampColour(custom.colourIndex), true)
        else CategoryLook(category.displayString, null, null, false)
    }

    fun look(message: ClassifiedMessage, customs: List<CustomCategory>): CategoryLook = look(message.category, message.customCategoryId, customs)

    /** Just the name. */
    fun label(message: ClassifiedMessage, customs: List<CustomCategory>): String = look(message, customs).name

    /** The pieces stored for [state], as text, and read back. Unreadable or missing text reads as nothing made. */
    fun encodeCategories(categories: List<CustomCategory>): String =
        categories.joinToString("\n") { listOf(it.id, it.name, it.emoji.orEmpty(), it.colourIndex.toString()).joinToString("\t") { f -> escape(f) } }

    fun decodeCategories(text: String?): List<CustomCategory> = text.orEmpty().lineSequence().mapNotNull { line ->
        val f = line.split('\t').map(::unescape)
        if (f.size < 4 || f[0].isBlank() || f[1].isBlank()) null
        else CustomCategory(f[0], f[1].take(MAX_NAME), f[2].ifEmpty { null }, clampColour(f[3].toIntOrNull() ?: 0))
    }.distinctBy { it.id }.take(MAX_COUNT).toList()

    fun encodeAssignments(assignments: Map<String, String>): String =
        assignments.entries.joinToString("\n") { escape(it.key) + "\t" + escape(it.value) }

    fun decodeAssignments(text: String?): Map<String, String> = text.orEmpty().lineSequence().mapNotNull { line ->
        val f = line.split('\t')
        if (f.size == 2 && f[0].isNotEmpty() && f[1].isNotEmpty()) unescape(f[0]) to unescape(f[1]) else null
    }.toMap()

    private fun escape(text: String) = text.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")

    private fun unescape(text: String): String {
        if ('\\' !in text) return text
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\\' && i + 1 < text.length) {
                out.append(when (text[i + 1]) { 't' -> '\t'; 'n' -> '\n'; else -> text[i + 1] }); i += 2
            } else { out.append(c); i++ }
        }
        return out.toString()
    }
}
