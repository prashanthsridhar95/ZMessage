package com.zyke.message.domain.notes

import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.categories.CategoryKey

/** The person's own note and labels on one payment. Both are their own words and never leave the phone. */
data class PaymentNotes(val note: String = "", val tags: Set<String> = emptySet()) {
    val isEmpty: Boolean get() = note.isEmpty() && tags.isEmpty()

    companion object {
        const val MAX_NOTE = 500
        const val MAX_TAGS = 8
        const val MAX_TAG_LENGTH = 24

        /** A tag as it is stored: lowercase, single spaces, no leading '#', at most [MAX_TAG_LENGTH] characters; null when nothing is left. */
        fun cleanTag(raw: String): String? =
            raw.trim().trimStart('#').trim().lowercase().replace(Regex("\\s+"), " ").take(MAX_TAG_LENGTH).trim().ifEmpty { null }

        fun cleanNote(raw: String): String = raw.trim().take(MAX_NOTE).trim()

        /** A tidy copy: cleaned note, cleaned distinct tags, at most [MAX_TAGS] of them (the first ones win). */
        fun of(note: String, tags: Collection<String>): PaymentNotes =
            PaymentNotes(cleanNote(note), tags.mapNotNull(::cleanTag).distinct().take(MAX_TAGS).toCollection(LinkedHashSet()))

        fun apply(message: ClassifiedMessage, notes: Map<String, PaymentNotes>): ClassifiedMessage {
            val own = notes[CategoryKey.messageKey(message.id)] ?: return message
            if (own.isEmpty) return message
            return message.copy(note = own.note.ifEmpty { null }, tags = own.tags.sorted())
        }
    }
}
