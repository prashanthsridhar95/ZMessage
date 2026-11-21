package com.zyke.message.domain.triage

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * What happens to a stack of cards on Home.
 *
 * [KEEP] stacks are the ones that count in "to go". [QUIET] stacks wait under their own line and are never counted.
 * [AUTO_CLEAR] stacks are cleared silently into "Auto cleared", from where each card can be restored; it exists only for the
 * two kinds of message that are safe to clear without a look (see [TriageStackSpec.canAutoClear]).
 */
enum class TriageMode(val label: String) {
    KEEP("Keep"),
    QUIET("Quiet"),
    AUTO_CLEAR("Auto clear")
}

/**
 * One of the default stacks: its id (stored), its name, what it starts as, and which modes the person may pick.
 *
 * SAFETY: only [CODES] and [OFFERS] allow [TriageMode.AUTO_CLEAR]. A payment, a bill, a refund, a balance alert or anything
 * else that is about money can never be cleared without being seen, even if a saved setting (an old backup, a hand-edited file)
 * says so: [TriageSettings.modeOf] turns such a value back into the default.
 */
class TriageStackSpec internal constructor(
    val id: String,
    val title: String,
    val defaultMode: TriageMode,
    val canAutoClear: Boolean = false
) {
    val allowed: List<TriageMode> =
        if (canAutoClear) listOf(TriageMode.KEEP, TriageMode.QUIET, TriageMode.AUTO_CLEAR) else listOf(TriageMode.KEEP, TriageMode.QUIET)

    companion object {
        const val PAYMENTS = "payments"
        const val BILLS = "bills"
        const val LIFE = "orders"
        const val REFUNDS = "refunds"
        const val ALERTS = "alerts"
        const val CODES = "codes"
        const val OFFERS = "offers"
        const val OTHER = "other"

        /** The stacks, in the order they start in. */
        val ALL: List<TriageStackSpec> = listOf(
            TriageStackSpec(PAYMENTS, "Payments", TriageMode.KEEP),
            TriageStackSpec(BILLS, "Bills & dues", TriageMode.KEEP),
            TriageStackSpec(LIFE, "Orders & travel", TriageMode.KEEP),
            TriageStackSpec(REFUNDS, "Refunds", TriageMode.KEEP),
            TriageStackSpec(ALERTS, "Account alerts", TriageMode.KEEP),
            TriageStackSpec(CODES, "Codes", TriageMode.AUTO_CLEAR, canAutoClear = true),
            TriageStackSpec(OFFERS, "Offers", TriageMode.QUIET, canAutoClear = true),
            TriageStackSpec(OTHER, "Everything else", TriageMode.KEEP)
        )

        val DEFAULT_ORDER: List<String> = ALL.map { it.id }

        fun byId(id: String): TriageStackSpec? = ALL.firstOrNull { it.id == id }
    }
}

/**
 * The person's order of the stacks and the mode of each. Always read through [orderedIds] and [modeOf], which repair
 * anything unknown, missing or not allowed, so no stored value can break the deck or clear what must be seen.
 */
data class TriageSettings(
    val order: List<String> = TriageStackSpec.DEFAULT_ORDER,
    /** Only what differs from the default is stored. */
    val modes: Map<String, TriageMode> = emptyMap()
) {
    /** Every stack exactly once: the saved order first (unknown and repeated ids dropped), then any stack it left out. */
    fun orderedIds(): List<String> {
        val seen = LinkedHashSet<String>()
        for (id in order) if (TriageStackSpec.byId(id) != null) seen += id
        for (id in TriageStackSpec.DEFAULT_ORDER) seen += id
        return seen.toList()
    }

    fun modeOf(id: String): TriageMode {
        val spec = TriageStackSpec.byId(id) ?: return TriageMode.KEEP
        return modes[id]?.takeIf { it in spec.allowed } ?: spec.defaultMode
    }

    fun withMode(id: String, mode: TriageMode): TriageSettings {
        val spec = TriageStackSpec.byId(id) ?: return this
        if (mode !in spec.allowed) return this
        return copy(order = orderedIds(), modes = if (mode == spec.defaultMode) modes - id else modes + (id to mode))
    }

    /** Moves the stack [id] by [delta] places (-1 up, 1 down), staying inside the list. */
    fun moved(id: String, delta: Int): TriageSettings {
        val ids = orderedIds().toMutableList()
        val from = ids.indexOf(id)
        if (from < 0) return this
        val to = (from + delta).coerceIn(0, ids.lastIndex)
        if (to == from) return this
        ids.add(to, ids.removeAt(from))
        return copy(order = ids)
    }

    val isDefault: Boolean get() = orderedIds() == TriageStackSpec.DEFAULT_ORDER && TriageStackSpec.DEFAULT_ORDER.all { modeOf(it) == TriageStackSpec.byId(it)?.defaultMode }
}

/** Where the deck's settings are kept, and the cards the person brought back from "Auto cleared". On this phone only. */
interface TriageRepository {
    val settings: StateFlow<TriageSettings>

    /** Messages the person restored from "Auto cleared": they are not cleared again, and wait under Quiet. */
    val restored: StateFlow<Set<Long>>

    fun save(settings: TriageSettings)
    fun reset() = save(TriageSettings())
    fun restore(messageId: Long)
    fun unrestore(messageId: Long)
}

object NoTriage : TriageRepository {
    override val settings: StateFlow<TriageSettings> = MutableStateFlow(TriageSettings())
    override val restored: StateFlow<Set<Long>> = MutableStateFlow(emptySet())
    override fun save(settings: TriageSettings) = Unit
    override fun restore(messageId: Long) = Unit
    override fun unrestore(messageId: Long) = Unit
}
