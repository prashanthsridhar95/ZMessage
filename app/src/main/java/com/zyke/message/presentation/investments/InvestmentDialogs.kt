package com.zyke.message.presentation.investments

import androidx.compose.runtime.Composable
import com.zyke.message.domain.investments.InvestmentMath

/** Everything the tab can open: the holding's page, the add/edit/renew form, and the small value and close sheets. */
@Composable
internal fun InvestmentsDialogs(m: InvestmentsModel) {
    val ui = m.ui
    ui.openId?.let { InvestmentDetailPage(m, it) }

    ui.addKind?.let { kind ->
        InvestmentFormSheet(
            initial = null, startKind = kind, institutions = m.institutions, today = m.today,
            onSave = { holding ->
                val undo = m.repo.add(holding)
                ui.addKind = null
                m.announce("Added ${holding.name}", undo)
            },
            onDismiss = { ui.addKind = null }
        )
    }

    m.find(ui.editId)?.let { h ->
        InvestmentFormSheet(
            initial = h, startKind = h.kind, institutions = m.institutions, today = m.today,
            onSave = { updated ->
                val undo = m.repo.update(updated)
                ui.editId = null
                m.announce("Saved ${updated.name}", undo)
            },
            onDismiss = { ui.editId = null }
        )
    } ?: run { if (ui.editId != null) ui.editId = null }

    m.find(ui.renewId)?.let { old ->
        val draft = androidx.compose.runtime.remember(old.id) { InvestmentMath.renewalDraft(old, newHoldingId(), m.today) }
        InvestmentFormSheet(
            initial = draft, startKind = old.kind, institutions = m.institutions, today = m.today, renewal = true,
            onSave = { successor ->
                val undo = m.repo.renew(old.id, successor)
                ui.renewId = null
                ui.openId = null
                if (undo != null) m.announce("Renewed ${old.name}", undo)
            },
            onDismiss = { ui.renewId = null }
        )
    } ?: run { if (ui.renewId != null) ui.renewId = null }

    m.find(ui.valueId)?.let { UpdateValueSheet(m, it) } ?: run { if (ui.valueId != null) ui.valueId = null }
    m.find(ui.closeId)?.let { CloseSheet(m, it) } ?: run { if (ui.closeId != null) ui.closeId = null }
}
