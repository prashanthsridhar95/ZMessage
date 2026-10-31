package com.zyke.message.presentation.messages.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.zyke.message.ZykeMessagesApplication
import com.zyke.message.domain.categories.CategoryKey
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.notes.PaymentNotes
import com.zyke.message.domain.notes.Tags
import com.zyke.message.presentation.common.AppButton
import com.zyke.message.presentation.common.ButtonSize
import com.zyke.message.presentation.common.ButtonStyle
import com.zyke.message.presentation.common.ChipOption
import com.zyke.message.presentation.common.Chips
import com.zyke.message.presentation.common.FormField
import com.zyke.message.presentation.common.LocalAppSnackbar
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val SAVE_DELAY_MS = 600L

/** The person's own note and tags on one payment; each change applies at once, and removing a tag can be undone. */
@Composable
internal fun PaymentNotesGroup(item: ClassifiedMessage) {
    val container = (LocalContext.current.applicationContext as ZykeMessagesApplication).container
    val repo = container.paymentNotes
    val key = CategoryKey.messageKey(item.id)
    val all by repo.notes.collectAsState()
    val own = all[key] ?: PaymentNotes()
    val snackbar = LocalAppSnackbar.current
    val scope = rememberCoroutineScope()
    var note by rememberSaveable(item.id) { mutableStateOf(own.note) }
    var typedTag by rememberSaveable(item.id) { mutableStateOf("") }

    // The note is saved a moment after typing stops.
    LaunchedEffect(note) {
        if (note == own.note) return@LaunchedEffect
        delay(SAVE_DELAY_MS)
        repo.set(key, PaymentNotes.of(note, repo.notes.value[key]?.tags.orEmpty()))
    }

    val tags = own.tags.sorted()
    val suggestions = remember(typedTag, all, tags) { Tags.suggest(typedTag, all.values.flatMap { it.tags }, tags) }
    val candidate = PaymentNotes.cleanTag(typedTag)
    val full = tags.size >= PaymentNotes.MAX_TAGS

    SettingsGroup("Note and tags") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FormField(
                "Note", note, { if (it.length <= PaymentNotes.MAX_NOTE) note = it }, placeholder = "Only you see this",
                capitalization = KeyboardCapitalization.Sentences, singleLine = false
            )
            if (tags.isNotEmpty()) {
                Chips(tags.map { ChipOption("$it  ×", true) }, onSelect = { index ->
                    val removed = tags[index]
                    val before = repo.notes.value[key] ?: PaymentNotes()
                    repo.set(key, PaymentNotes.of(before.note, before.tags - removed))
                    scope.launch {
                        snackbar.currentSnackbarData?.dismiss()
                        val result = snackbar.showSnackbar("Tag removed", actionLabel = "Undo", duration = SnackbarDuration.Long)
                        if (result == SnackbarResult.ActionPerformed) {
                            val now = repo.notes.value[key] ?: PaymentNotes()
                            repo.set(key, PaymentNotes.of(now.note, now.tags + removed))
                        }
                    }
                })
            }
            FormField(
                "Add a tag", typedTag, { typedTag = it }, placeholder = if (full) "Up to ${PaymentNotes.MAX_TAGS} tags" else "trip, work, gift",
                suggestions = suggestions, capitalization = KeyboardCapitalization.None
            )
            AppButton(
                "Add tag",
                {
                    val now = repo.notes.value[key] ?: PaymentNotes()
                    repo.set(key, PaymentNotes.of(now.note, now.tags + candidate!!))
                    typedTag = ""
                },
                Modifier, ButtonStyle.TEXT, ButtonSize.COMPACT, enabled = candidate != null && !full && candidate !in tags
            )
        }
    }
}
