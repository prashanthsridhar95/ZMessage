package com.zyke.message.domain.nickname

import com.zyke.message.domain.categories.CategoryKey
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageCategory

/** A category as the person files things under it: a built-in one, or one of their own by id. Both null means none. */
data class CategoryChoice(val category: MessageCategory? = null, val customId: String? = null) {
    val isEmpty: Boolean get() = category == null && customId == null

    companion object {
        val NONE = CategoryChoice()
        fun of(category: MessageCategory) = CategoryChoice(category, null)
        fun custom(id: String) = CategoryChoice(null, id)
    }
}

/** Something that carries a nickname: a payee / sender (or one payment) by its store [key], and the payments it covers. */
data class NicknameHolder(val key: String, val messageIds: Set<Long>)

/**
 * What a nickname already stands for: the category most of the payments carrying it are filed under (the newest
 * decides a tie), a picture one of them has, and who carries it.
 */
data class NicknameProfile(
    val nickname: String,
    val category: CategoryChoice,
    val imageKey: String?,
    val holders: List<NicknameHolder>
) {
    /** How many payments carry the name. */
    val transactions: Int get() = holders.sumOf { it.messageIds.size }
    val isEmpty: Boolean get() = category.isEmpty && imageKey == null
}

object NicknameProfiles {
    private const val MESSAGE_PREFIX = "msg:"

    /**
     * A name as it is compared: letters and digits only, lower case, single-spaced. Case, spacing, punctuation, emoji
     * and accents (Latin) do not make two names different: "Bajaj  SIP!", "bajaj-sip" and "Bajaj SIP 💸" are one name.
     */
    fun normalise(name: String): String {
        val out = StringBuilder(name.length)
        var gap = false
        for (ch in java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFD)) {
            when {
                ch in '̀'..'ͯ' || ch == '\'' || ch == '’' -> Unit
                ch.isLetterOrDigit() -> {
                    if (gap && out.isNotEmpty()) out.append(' ')
                    gap = false
                    out.append(ch.lowercaseChar())
                }
                else -> gap = true
            }
        }
        return if (out.isEmpty()) name.trim().lowercase() else out.toString()
    }

    /**
     * Who carries [name] (compared by [normalise]), apart from [excludeIds] and [excludeKeys], which are the things being
     * named now. [messages] are the inbox with the person's categories applied, so they hold the category in effect.
     */
    fun holders(
        name: String,
        nicknames: Map<String, String>,
        messages: List<ClassifiedMessage>,
        excludeIds: Set<Long> = emptySet(),
        excludeKeys: Set<String> = emptySet()
    ): List<NicknameHolder> = collect(name, nicknames, messages, excludeIds, excludeKeys).first

    /**
     * What [name] already has, or null when nobody else carries it. The category is the one most of its payments are
     * filed under: choices the person made count first; if there are none, the automatic ones (the vague "others" and
     * "UPI payment" never decide). The newest payment decides a tie.
     */
    fun profile(
        name: String,
        nicknames: Map<String, String>,
        messages: List<ClassifiedMessage>,
        imageKeys: Set<String>,
        excludeIds: Set<Long> = emptySet(),
        excludeKeys: Set<String> = emptySet()
    ): NicknameProfile? {
        val (holders, carriers) = collect(name, nicknames, messages, excludeIds, excludeKeys)
        if (holders.isEmpty()) return null
        val category = majority(carriers)
        val image = holders.map { it.key }.sorted().firstOrNull { it in imageKeys }
        return NicknameProfile(name.trim(), category, image, holders)
    }

    private fun collect(
        name: String,
        nicknames: Map<String, String>,
        messages: List<ClassifiedMessage>,
        excludeIds: Set<Long>,
        excludeKeys: Set<String>
    ): Pair<List<NicknameHolder>, List<ClassifiedMessage>> {
        val wanted = normalise(name)
        if (wanted.isEmpty()) return emptyList<NicknameHolder>() to emptyList()
        val matching = nicknames.entries.filter { it.key !in excludeKeys && it.value.isNotBlank() && normalise(it.value) == wanted }.map { it.key }
        if (matching.isEmpty()) return emptyList<NicknameHolder>() to emptyList()
        val messageKeys = matching.filter { it.startsWith(MESSAGE_PREFIX) }.toSet()
        val payeeKeys = matching.filter { !it.startsWith(MESSAGE_PREFIX) }.toSet()

        val ids = HashMap<String, MutableSet<Long>>()
        val carriers = ArrayList<ClassifiedMessage>()
        for (m in messages) {
            if (m.id in excludeIds) continue
            val payee = CategoryKey.of(m)
            if (payee != null && payee in excludeKeys) continue
            val own = CategoryKey.messageKey(m.id)
            val key = when {
                own in messageKeys -> own
                payee != null && payee in payeeKeys && own !in nicknames -> payee
                else -> continue
            }
            ids.getOrPut(key) { HashSet() } += m.id
            carriers += m
        }
        // A carrier with no payment in the inbox any more still counts for its picture.
        val holders = matching.map { NicknameHolder(it, ids[it] ?: emptySet()) }.sortedBy { it.key }
        return holders to carriers
    }

    private val VAGUE = setOf(MessageCategory.OTHERS, MessageCategory.UPI_PAYMENT)

    private fun choiceOf(m: ClassifiedMessage): CategoryChoice =
        m.customCategoryId?.let { CategoryChoice.custom(it) } ?: CategoryChoice.of(m.category)

    private fun majority(carriers: List<ClassifiedMessage>): CategoryChoice {
        val chosen = carriers.filter { it.customCategoryId != null || it.categoryOverridden }
        val pool = chosen.ifEmpty { carriers.filter { it.category !in VAGUE } }
        if (pool.isEmpty()) return CategoryChoice.NONE
        val groups = pool.groupBy { choiceOf(it) }
        val best = groups.values.maxOf { it.size }
        return groups.filterValues { it.size == best }.maxBy { (_, ms) -> ms.maxOf { it.message.date } }.key
    }
}

/** What to call the choice on screen: its built-in name, or the name of the person's own category. */
fun CategoryChoice.label(customs: List<com.zyke.message.domain.model.CustomCategory>): String =
    customId?.let { id -> customs.firstOrNull { it.id == id }?.name } ?: category?.displayString ?: ""
