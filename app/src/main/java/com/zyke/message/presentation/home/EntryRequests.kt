package com.zyke.message.presentation.home

import android.content.Intent
import androidx.compose.runtime.mutableStateOf
import com.zyke.message.domain.ledger.ManualKind
import com.zyke.message.domain.ledger.QuickEntryParser
import java.math.BigDecimal

/** What the entry sheet starts with when it is opened from outside the app; everything is optional and still editable. */
data class EntryRequest(val kind: ManualKind = ManualKind.EXPENSE, val amount: BigDecimal? = null, val title: String = "")

/**
 * The hook by which the home-screen widget, the quick-settings tile and "Share" open the entry sheet. MainActivity puts a
 * request here from the incoming intent; the Home screen takes it and opens the sheet. Shared text is parsed once, here,
 * and never stored or logged.
 */
object EntryRequests {
    const val ACTION_ADD_ENTRY = "com.zyke.message.action.ADD_ENTRY"
    const val EXTRA_KIND = "com.zyke.message.extra.KIND"

    val pending = mutableStateOf<EntryRequest?>(null)

    /** The request an intent carries, or null when it is not one. */
    fun fromIntent(intent: Intent?): EntryRequest? = when {
        intent == null -> null
        intent.action == ACTION_ADD_ENTRY ->
            EntryRequest(kind = ManualKind.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_KIND) } ?: ManualKind.EXPENSE)
        intent.action == Intent.ACTION_SEND && intent.type?.startsWith("text/plain") == true -> {
            val draft = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.let(QuickEntryParser::parse)
            if (draft == null) EntryRequest() else EntryRequest(draft.kind, draft.amount, draft.title)
        }
        else -> null
    }
}
