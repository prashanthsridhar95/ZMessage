package com.zyke.message.domain.cards

import com.zyke.message.domain.backup.MiniJson
import com.zyke.message.domain.model.CardType
import com.zyke.message.domain.model.UserCard

/**
 * The stored form of the person's cards. Fields added over time are optional on reading, so what an older version
 * saved (no network, variant, theme, due day or limit) still loads, with defaults.
 */
object UserCardCodec {

    fun encode(cards: List<UserCard>): String = cards.joinToString(",", "[", "]") { c ->
        buildList {
            add("\"id\":${q(c.id)}")
            add("\"type\":${q(c.type.name)}")
            add("\"last4\":${q(c.last4)}")
            add("\"name\":${q(c.name)}")
            add("\"bankName\":${q(c.bankName)}")
            add("\"statementDay\":${c.statementDay ?: "null"}")
            c.network?.let { add("\"network\":${q(it.name)}") }
            if (c.variant.isNotBlank()) add("\"variant\":${q(c.variant)}")
            if (c.themeId.isNotBlank()) add("\"themeId\":${q(c.themeId)}")
            c.dueDay?.let { add("\"dueDay\":$it") }
            c.creditLimit?.let { add("\"creditLimit\":$it") }
        }.joinToString(",", "{", "}")
    }

    fun decode(text: String?): List<UserCard> {
        if (text.isNullOrBlank()) return emptyList()
        val rows = runCatching { MiniJson.parse(text) as? List<*> }.getOrNull() ?: return emptyList()
        return rows.mapNotNull { row ->
            val o = row as? Map<*, *> ?: return@mapNotNull null
            val type = CardType.entries.firstOrNull { it.name == o["type"] } ?: return@mapNotNull null
            UserCard(
                id = o["id"] as? String ?: return@mapNotNull null,
                type = type,
                last4 = o["last4"] as? String ?: return@mapNotNull null,
                name = o["name"] as? String ?: "",
                bankName = o["bankName"] as? String ?: "",
                statementDay = (o["statementDay"] as? Long)?.toInt(),
                network = CardNetwork.entries.firstOrNull { it.name == o["network"] },
                variant = o["variant"] as? String ?: "",
                themeId = o["themeId"] as? String ?: "",
                dueDay = (o["dueDay"] as? Long)?.toInt(),
                creditLimit = o["creditLimit"] as? Long
            )
        }
    }

    private fun q(s: String): String = buildString {
        append('"')
        s.forEach {
            when {
                it == '"' -> append("\\\"")
                it == '\\' -> append("\\\\")
                it == '\n' -> append("\\n")
                it == '\r' -> append("\\r")
                it == '\t' -> append("\\t")
                it < ' ' -> append("\\u%04x".format(it.code))
                else -> append(it)
            }
        }
        append('"')
    }
}
