package com.zyke.message.presentation.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.zyke.message.presentation.messages.components.Caption
import kotlinx.coroutines.flow.first

/** Content taller than this share of the screen is not a sheet's business: it goes to a page. */
internal const val SHEET_MAX_SCREEN_FRACTION = 0.85f

private const val UNDECIDED = 0
private const val AS_SHEET = 1
private const val AS_PAGE = 2

/**
 * Big content is a page, small content is a sheet. This is the one place that rule is applied.
 *
 * - [asPage] = true: it is a page from the first frame (the same sliding [AppPage] as "Your year"), with no sheet
 *   animation at all. Use it for content that is known to be large.
 * - Otherwise it is a sheet, but it is first laid out invisibly: if the content, header and [footer] together would be
 *   taller than [SHEET_MAX_SCREEN_FRACTION] of the screen (the sheet would scroll or fill the screen) the very same
 *   content is shown as a page instead, and the sheet is never seen. So nothing flickers. [autoPage] = false opts out.
 *
 * The decision is made once, when the content first appears, and kept (also over rotation): content that grows later
 * just scrolls, so the field being typed in is never torn away.
 *
 * State: the content lambda is moved, not copied, so state held by the caller (or `rememberSaveable` outside the
 * lambda) is kept. State remembered inside [content] restarts when the sheet becomes a page, which happens before the
 * person can have touched it.
 *
 * Both shapes share: [title] (a heading; in a sheet beside a close button, on a page the page title with its back arrow),
 * the optional [subtitle], a [footer] that stays pinned (save buttons) and rises with the keyboard, and [onDismiss]
 * (close, back, system back, dragging the sheet down). Small pickers opened from either are sheets and are drawn above it.
 */
@Composable
fun AdaptiveSheet(
    title: String,
    onDismiss: () -> Unit,
    asPage: Boolean = false,
    autoPage: Boolean = true,
    subtitle: String? = null,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    contentSpacing: androidx.compose.ui.unit.Dp = 0.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    val canPage = LocalPageHost.current != null
    var decision by rememberSaveable {
        mutableIntStateOf(
            when {
                !canPage -> AS_SHEET
                asPage -> AS_PAGE
                autoPage -> UNDECIDED
                else -> AS_SHEET
            }
        )
    }
    if (decision == AS_PAGE) {
        AppPage(title, onDismiss, footer = footer) {
            if (subtitle != null) Caption(subtitle, Modifier.padding(bottom = 12.dp), maxLines = 3)
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(contentSpacing)) { content() }
        }
        return
    }
    val scroll = rememberScrollState()
    var columnHeight by remember { mutableIntStateOf(0) }
    AppBottomSheet(onDismiss, contentInset = 0.dp, ready = decision != UNDECIDED) {
        if (decision == UNDECIDED) {
            val limit = LocalSheetWindowHeight.current * SHEET_MAX_SCREEN_FRACTION
            LaunchedEffect(Unit) {
                snapshotFlow { columnHeight }.first { it > 0 }
                withFrameNanos { }
                // What it would need if nothing cut it off: what is shown, plus what is scrolled out of sight.
                val natural = columnHeight + scroll.maxValue
                decision = if (natural > limit) AS_PAGE else AS_SHEET
            }
        }
        Column(Modifier.fillMaxWidth().onSizeChanged { columnHeight = it.height }) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                    Text(
                        title, Modifier.heading(), style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary
                    )
                    if (subtitle != null) Caption(subtitle, Modifier.padding(top = 2.dp), maxLines = 2)
                }
                Box(
                    Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClickLabel = "Close", onClick = onDismiss),
                    contentAlignment = Alignment.Center
                ) {
                    Text("✕", Modifier.clearAndSetSemantics { }, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.secondary)
                }
            }
            Column(
                Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(scroll)
                    .padding(horizontal = 20.dp).padding(top = 12.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(contentSpacing)
            ) {
                content()
            }
            if (footer != null) {
                // The footer does not scroll away: the way to save is always where the thumb expects it.
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 12.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) { footer() }
            }
        }
    }
}
