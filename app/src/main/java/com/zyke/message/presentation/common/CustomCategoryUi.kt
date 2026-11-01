package com.zyke.message.presentation.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import com.zyke.message.presentation.common.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.zyke.message.ZykeMessagesApplication
import com.zyke.message.domain.categories.CategoryLook
import com.zyke.message.domain.categories.CustomCategories
import com.zyke.message.domain.categories.NameCheck
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.CustomCategory
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.repository.CustomCategoryRepository

/** The categories the person made, for every screen that names a category. Provided once by the app shell. */
val LocalCustomCategories = compositionLocalOf<List<CustomCategory>> { emptyList() }

/** The one way a category is named on screen: a built-in one by its name, a made one by its emoji and name. */
@Composable
fun categoryText(message: ClassifiedMessage): String = lookText(CustomCategories.look(message, LocalCustomCategories.current))

@Composable
fun categoryText(category: MessageCategory, customId: String?): String =
    lookText(CustomCategories.look(category, customId, LocalCustomCategories.current))

fun lookText(look: CategoryLook): String = if (look.emoji != null) look.emoji + " " + look.name else look.name

/** A theme colour for a made category: every one is a role of the current scheme, so it follows dark and true-black themes. */
fun categoryColour(scheme: ColorScheme, index: Int): Color = when (CustomCategories.clampColour(index)) {
    0 -> scheme.primary
    1 -> scheme.secondary
    2 -> scheme.tertiary
    3 -> scheme.primaryContainer
    4 -> scheme.secondaryContainer
    5 -> scheme.tertiaryContainer
    6 -> scheme.inversePrimary
    else -> scheme.outline
}

/** The row of colours to choose from; each is a 48dp target that says its number and whether it is chosen. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColourSwatches(selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (index in 0 until CustomCategories.COLOUR_COUNT) {
            val chosen = index == selected
            Box(
                Modifier
                    .size(48.dp)
                    .selectable(selected = chosen, role = Role.RadioButton, onClick = { onSelect(index) })
                    .semantics { contentDescription = "Colour ${index + 1}" + if (chosen) ", chosen" else "" },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(categoryColour(MaterialTheme.colorScheme, index))
                        .border(
                            if (chosen) BorderStroke(3.dp, MaterialTheme.colorScheme.onSurface) else BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) { if (chosen) Text("✓", color = MaterialTheme.colorScheme.onSurface) }
            }
        }
    }
}

/**
 * Making or changing a category: a name, an emoji from a small grid, and a colour. [existing] is the one being
 * changed, or null for a new one. [onSaved] is given the category once it is stored.
 */
@Composable
fun CategoryEditSheet(existing: CustomCategory?, onDismiss: () -> Unit, onSaved: (CustomCategory) -> Unit) {
    val container = (LocalContext.current.applicationContext as ZykeMessagesApplication).container
    val repo = container.customCategories
    var name by rememberSaveable { mutableStateOf(existing?.name.orEmpty()) }
    var emoji by rememberSaveable { mutableStateOf(existing?.emoji) }
    var colour by rememberSaveable { mutableStateOf(existing?.colourIndex ?: 0) }
    var refused by rememberSaveable { mutableStateOf<String?>(null) }
    val check = CustomCategories.validate(name, repo.state.value.categories, ignoreId = existing?.id)
    val problem = (check as? NameCheck.Invalid)?.reason?.takeIf { name.isNotBlank() }

    FormSheet(
        title = if (existing == null) "Add a category" else "Change category",
        onDismiss = onDismiss,
        primaryLabel = if (existing == null) "Add category" else "Save",
        primaryEnabled = check is NameCheck.Valid,
        onPrimary = {
            if (existing == null) {
                when (val result = repo.add(name, emoji, colour)) {
                    is CustomCategoryRepository.Result.Created -> onSaved(result.category)
                    is CustomCategoryRepository.Result.Rejected -> refused = result.reason
                }
            } else {
                when (val result = repo.edit(existing.id, name, emoji, colour)) {
                    is NameCheck.Valid -> onSaved(existing.copy(name = result.name, emoji = emoji, colourIndex = colour))
                    is NameCheck.Invalid -> refused = result.reason
                }
            }
        },
        hint = problem ?: "Give it a name"
    ) {
        FormField(
            label = "Name",
            value = name,
            onChange = { name = it.take(CustomCategories.MAX_NAME + 8); refused = null },
            placeholder = "For example Gym or Pets",
            error = refused ?: problem,
            capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences
        )
        FormGroup("Icon") {
            val options = listOf("None") + CustomCategories.EMOJIS
            Chips(
                options.mapIndexed { i, label -> ChipOption(label, if (i == 0) emoji == null else emoji == label) },
                onSelect = { i -> emoji = if (i == 0) null else options[i] }
            )
        }
        FormGroup("Colour") { ColourSwatches(colour, { colour = it }) }
    }
}
