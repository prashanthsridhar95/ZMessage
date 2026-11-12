package com.zyke.message.presentation.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zyke.message.ZykeMessagesApplication
import com.zyke.message.domain.categories.CustomCategories
import com.zyke.message.presentation.common.AppButton
import com.zyke.message.presentation.common.ButtonSize
import com.zyke.message.presentation.common.ButtonStyle
import com.zyke.message.presentation.common.CategoryEditSheet
import com.zyke.message.presentation.common.categoryColour
import com.zyke.message.presentation.common.rememberLedgerActions
import com.zyke.message.presentation.dashboard.components.SectionCard
import com.zyke.message.presentation.messages.components.Caption
import com.zyke.message.presentation.messages.components.RowTitle

/**
 * "Your categories": the ones the person made, to add, rename or delete. Deleting sends everything filed under one back
 * to automatic, and offers Undo.
 */
@Composable
internal fun CustomCategoriesSection() {
    val container = (LocalContext.current.applicationContext as ZykeMessagesApplication).container
    val repo = container.customCategories
    val state by repo.state.collectAsStateWithLifecycle()
    val actions = rememberLedgerActions()
    val haptics = com.zyke.message.presentation.motion.rememberHaptics()
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }

    SectionCard("Your categories", subtitle = "Make your own, then pick them when filing a payment", startOpen = true) {
        if (state.categories.isEmpty()) Caption("None yet. Add one here, or from any payment's Category.", maxLines = 3)
        state.categories.forEach { category ->
            val filed = state.assignments.count { it.value == category.id }
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(16.dp).clip(CircleShape).background(categoryColour(MaterialTheme.colorScheme, category.colourIndex)))
                Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    RowTitle(listOfNotNull(category.emoji, category.name).joinToString(" "))
                    Caption(if (filed == 1) "1 sender or payment filed here" else "$filed senders or payments filed here", maxLines = 1)
                }
                AppButton("Edit", { editing = category.id }, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT)
                AppButton("Delete", {
                    haptics.reject()
                    repo.delete(category.id)?.let { deleted ->
                        actions.withUndo("Deleted ${deleted.category.name}") { repo.restore(deleted) }
                    }
                }, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT)
            }
        }
        if (state.categories.size < CustomCategories.MAX_COUNT) {
            AppButton("＋ Add category", { adding = true }, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT)
        } else {
            Caption("That is the most you can have (${CustomCategories.MAX_COUNT}).", maxLines = 2)
        }
    }

    if (adding) CategoryEditSheet(null, { adding = false }, { adding = false })
    val open = state.categories.firstOrNull { it.id == editing }
    if (open != null) CategoryEditSheet(open, { editing = null }, { editing = null })
}
