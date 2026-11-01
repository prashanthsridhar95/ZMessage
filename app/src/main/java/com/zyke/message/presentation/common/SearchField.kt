package com.zyke.message.presentation.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import com.zyke.message.presentation.common.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.zyke.message.R
import com.zyke.message.presentation.messages.components.mutedColor
import kotlinx.coroutines.delay

/**
 * The one search field, a pill: a magnifier, what is typed, and a clear button once there is something to clear. The
 * Messages tab and the search page both use it, so they look and behave the same. It asks for focus when it appears
 * (after [focusDelayMs], for a page still sliding in) and the keyboard's action key is "search".
 */
@Composable
fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Search messages, merchants, amounts",
    onSearch: (String) -> Unit = {},
    focusDelayMs: Long = 0L
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (focusDelayMs > 0) delay(focusDelayMs)
        // Focus can be refused while the window is still settling; the field is then simply tapped.
        runCatching { focusRequester.requestFocus() }
    }
    val ink = MaterialTheme.colorScheme.primary
    Row(
        modifier
            .heightIn(min = 48.dp)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f), RoundedCornerShape(24.dp))
            .padding(start = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painterResource(R.drawable.ic_search_24), contentDescription = null, modifier = Modifier.size(20.dp),
            colorFilter = ColorFilter.tint(mutedColor())
        )
        Box(Modifier.weight(1f).padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) Text(placeholder, color = mutedColor(), style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = ink),
                cursorBrush = SolidColor(ink),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch(query) })
            )
        }
        if (query.isNotEmpty()) {
            Box(
                Modifier.size(48.dp).clickable(role = Role.Button, onClickLabel = "Clear search") { onQueryChange("") },
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painterResource(R.drawable.baseline_close_24), contentDescription = "Clear search", modifier = Modifier.size(20.dp),
                    colorFilter = ColorFilter.tint(mutedColor())
                )
            }
        } else {
            Box(Modifier.size(width = 16.dp, height = 48.dp))
        }
    }
}
