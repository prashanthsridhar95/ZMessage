package com.zyke.message.domain.nickname

import com.zyke.message.domain.diag.DiagLog
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.repository.CategoryOverrideRepository
import com.zyke.message.domain.repository.CustomCategoryRepository
import com.zyke.message.domain.repository.NicknameRepository

/**
 * The one place a payment or payee is given a name and / or a category, so every screen does it the same way:
 * a single rename, a single filing, and a bulk edit of many at once are all a [Request].
 *
 * A name that another payment already carries brings that name's category along (see [NicknameProfiles.profile]) unless a
 * category is chosen in the same request: a deliberate choice always wins. The whole request is written in one step
 * under one lock (a change from the main thread and one from a write queue cannot interleave), and [undo] puts every
 * key back as it was.
 */
class NamingEdits(
    private val nicknames: NicknameRepository,
    private val overrides: CategoryOverrideRepository,
    private val customs: CustomCategoryRepository
) {
    private val lock = Any()

    /** A store key (a payee, or `msg:<id>` for one payment) and the payments it covers. */
    data class Target(val key: String, val messageIds: Set<Long> = emptySet())

    /**
     * @param nickname null leaves the names alone; blank takes them away
     * @param category null leaves the categories alone (or lets [inherit] decide); a choice is filed as given
     * @param inherit whether a name already in use brings its category and picture when no [category] is chosen
     */
    data class Request(
        val targets: List<Target>,
        val nickname: String? = null,
        val category: CategoryChoice? = null,
        val inherit: Boolean = true
    )

    /** What one key had: its name, and the category the person filed it under (built-in or their own). */
    data class KeyState(val nickname: String?, val category: CategoryChoice)

    class Receipt(val before: Map<String, KeyState>) {
        val keys: Int get() = before.size
    }

    data class Outcome(
        val receipt: Receipt,
        /** Targets whose name was set or taken away. */
        val renamed: Int,
        /** Targets whose category was set, whether chosen or taken from the name. */
        val refiled: Int,
        /** The category the name brought along, when it did. */
        val inherited: CategoryChoice?,
        /** The name that brought it. */
        val inheritedFrom: String?,
        /** Targets that had a different choice of their own, now replaced by the name's category. */
        val replacedOlder: Int,
        /** Picture source to copy to each target that has none: pairs of source key to target key. */
        val pictureCopies: List<Pair<String, String>>,
        /** Payments the name's category was taken from. */
        val sourcePayments: Int
    ) {
        val changedAnything: Boolean get() = renamed > 0 || refiled > 0
    }

    fun snapshot(keys: Collection<String>): Map<String, KeyState> = synchronized(lock) { snapshotLocked(keys) }

    private fun snapshotLocked(keys: Collection<String>): Map<String, KeyState> {
        val names = nicknames.nicknames.value
        val built = overrides.overrides.value
        val custom = customs.state.value
        val known = custom.categories.mapTo(HashSet()) { it.id }
        return keys.associateWith { key ->
            val customId = custom.assignments[key]?.takeIf { it in known }
            KeyState(names[key], if (customId != null) CategoryChoice.custom(customId) else built[key]?.let { CategoryChoice.of(it) } ?: CategoryChoice.NONE)
        }
    }

    /** The category [key] is filed under by the person, if they filed it. */
    fun choiceOf(key: String): CategoryChoice = snapshot(listOf(key)).getValue(key).category

    fun apply(request: Request, messages: List<ClassifiedMessage>, pictureKeys: Set<String>): Outcome = synchronized(lock) {
        val targets = request.targets.distinctBy { it.key }
        val keys = targets.map { it.key }
        val before = snapshotLocked(keys)
        val name = request.nickname?.trim()

        // What the name already stands for, unless the person chose a category in the same request.
        val chosen = request.category?.takeIf { !it.isEmpty }
        val profile = if (request.inherit && !name.isNullOrEmpty()) {
            NicknameProfiles.profile(
                name, nicknames.nicknames.value, messages, pictureKeys,
                excludeIds = targets.flatMapTo(HashSet()) { it.messageIds }, excludeKeys = keys.toSet()
            )
        } else null
        val inherited = if (chosen == null) profile?.category?.takeIf { !it.isEmpty } else null
        val filing = chosen ?: inherited

        val nameChanges = if (name == null) emptyMap() else keys.filter { before.getValue(it).nickname.orEmpty() != name }.associateWith { name.ifEmpty { null } }
        val refile = if (filing == null) emptyList() else keys.filter { before.getValue(it).category != filing }
        val replaced = if (inherited == null) 0 else refile.count { !before.getValue(it).category.isEmpty }

        if (nameChanges.isNotEmpty()) nicknames.setAll(nameChanges)
        if (refile.isNotEmpty() && filing != null) {
            // One thing is filed one way: a built-in choice clears the person's own category for the key, and the reverse.
            overrides.setAll(refile.associateWith { filing.category })
            customs.assignAll(refile.associateWith { filing.customId })
        }
        val pictureSource = profile?.imageKey
        val copies = if (pictureSource == null || name.isNullOrEmpty()) emptyList()
        else keys.filter { it !in pictureKeys && it != pictureSource }.map { pictureSource to it }

        DiagLog.d("ledger") {
            "naming: targets=${keys.size} renamed=${nameChanges.size} refiled=${refile.size} chosen=${chosen != null} " +
                "inherited=${inherited != null} replacedOlder=$replaced pictures=${copies.size} sourcePayments=${profile?.transactions ?: 0}"
        }
        Outcome(
            Receipt(before), nameChanges.size, refile.size, inherited, if (inherited != null) profile?.nickname else null,
            replaced, copies, profile?.transactions ?: 0
        )
    }

    /** Puts every key of the [receipt] back as it was: its name and its category, built-in or the person's own. */
    fun undo(receipt: Receipt) = synchronized(lock) {
        val now = snapshotLocked(receipt.before.keys)
        val names = receipt.before.filter { (k, s) -> now.getValue(k).nickname != s.nickname }.mapValues { it.value.nickname }
        val filed = receipt.before.filter { (k, s) -> now.getValue(k).category != s.category }
        if (names.isNotEmpty()) nicknames.setAll(names)
        if (filed.isNotEmpty()) {
            overrides.setAll(filed.mapValues { it.value.category.category })
            customs.assignAll(filed.mapValues { it.value.category.customId })
        }
        DiagLog.d("ledger") { "naming undo: keys=${receipt.keys} renamed=${names.size} refiled=${filed.size}" }
    }
}
