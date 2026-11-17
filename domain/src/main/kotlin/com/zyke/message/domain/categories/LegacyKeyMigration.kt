package com.zyke.message.domain.categories

import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.repository.CategoryOverrideRepository
import com.zyke.message.domain.repository.CustomCategoryRepository
import com.zyke.message.domain.repository.NicknameRepository
import com.zyke.message.domain.repository.SenderImageRepository

/**
 * Moves what the person saved under a payee's older key ("mr. diy", from [CategoryKey.normalise]) to the key [PayeeKey]
 * gives it now ("mr diy"), so every lookup needs only the one key.
 *
 * Only keys that a message's payee produced are rewritten: the old key of each payee is worked out from the messages
 * themselves, so a key of a sender (a message with no payee), of a single payment ("msg:12") or a word rule is never touched.
 * Where a store already has the new key, that one wins (the same precedence the lookup had) and the old key is dropped.
 * Running it again finds nothing under an old key, so it is idempotent and costs a lookup per distinct payee.
 */
object LegacyKeys {

    /** The distinct payee texts of [messages] (a few hundred, however many messages). */
    fun payeesOf(messages: List<ClassifiedMessage>): Set<String> {
        val out = HashSet<String>()
        for (m in messages) {
            val payee = CategoryKey.payeeOf(m)
            if (payee.isNotBlank()) out.add(payee)
        }
        return out
    }

    /** Old key to new key for each of [payees] whose key changed. A key that is itself some other payee's old key is left out, so nothing moves twice. */
    fun mapping(payees: Collection<String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (payee in payees) {
            if (payee.isBlank()) continue
            val old = CategoryKey.normalise(payee)
            if (old.isEmpty()) continue
            val new = PayeeKey.of(payee) ?: old
            if (old != new) out[old] = new
        }
        if (out.isEmpty()) return out
        return out.filterValues { it !in out }
    }

    /**
     * The writes that move [stored] values from old keys to new ones: the new key gets the value (unless it has one already),
     * the old key is removed (null). Empty when nothing is stored under an old key.
     */
    fun <V> changes(stored: Map<String, V>, mapping: Map<String, String>): Map<String, V?> {
        if (stored.isEmpty() || mapping.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, V?>()
        for (old in mapping.keys.sorted()) {
            val value = stored[old] ?: continue
            val new = mapping.getValue(old)
            if (new !in stored && new !in out) out[new] = value
            out[old] = null
        }
        return out
    }

    /** The pictures to rename (old key to new key): those of [keys] filed under an old key. */
    fun imageMoves(keys: Set<String>, mapping: Map<String, String>): Map<String, String> {
        if (keys.isEmpty() || mapping.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (old in mapping.keys.sorted()) if (old in keys) out[old] = mapping.getValue(old)
        return out
    }
}

/**
 * Runs the key move over the stores as messages arrive. Each distinct payee is looked at once per process; later lists only
 * cost a set lookup per message. A restore of a backup replaces the saved files and the app restarts, so the next start
 * migrates again: nothing is remembered on disk, and nothing needs to be.
 */
class LegacyKeyMigration(
    private val overrides: CategoryOverrideRepository,
    private val nicknames: NicknameRepository,
    private val customCategories: CustomCategoryRepository,
    private val images: SenderImageRepository
) {
    private val seen = HashSet<String>()

    /** Moves what is saved under old keys for the payees in [messages] that were not seen before; returns how many keys moved. */
    @Synchronized
    fun run(messages: List<ClassifiedMessage>): Int {
        if (messages.isEmpty()) return 0
        val fresh = ArrayList<String>()
        for (m in messages) {
            val payee = CategoryKey.payeeOf(m)
            if (payee.isNotBlank() && seen.add(payee)) fresh.add(payee)
        }
        if (fresh.isEmpty()) return 0
        val mapping = LegacyKeys.mapping(fresh)
        if (mapping.isEmpty()) return 0
        var moved = 0
        val categoryChanges = LegacyKeys.changes(overrides.overrides.value, mapping)
        if (categoryChanges.isNotEmpty()) { overrides.setAll(categoryChanges); moved += categoryChanges.count { it.value == null } }
        val nameChanges = LegacyKeys.changes(nicknames.nicknames.value, mapping)
        if (nameChanges.isNotEmpty()) { nicknames.setAll(nameChanges); moved += nameChanges.count { it.value == null } }
        val filed = LegacyKeys.changes(customCategories.state.value.assignments, mapping)
        if (filed.isNotEmpty()) { customCategories.assignAll(filed); moved += filed.count { it.value == null } }
        val pictures = LegacyKeys.imageMoves(images.keysWithImage.value, mapping)
        if (pictures.isNotEmpty()) { images.moveKeys(pictures); moved += pictures.size }
        return moved
    }
}
