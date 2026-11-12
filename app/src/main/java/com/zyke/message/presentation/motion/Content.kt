package com.zyke.message.presentation.motion

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/**
 * Swaps one state of a screen for another (loading, content, error) with a short crossfade, and animates the change in
 * size when the two differ. Instant when motion is reduced. Give the states as a small enum or an Int.
 */
@Composable
fun <S> CrossfadeContent(
    targetState: S,
    modifier: Modifier = Modifier,
    label: String = "crossfade",
    content: @Composable (S) -> Unit
) {
    val motion = rememberMotion()
    AnimatedContent(
        targetState = targetState,
        modifier = modifier,
        transitionSpec = {
            if (motion.reduced) {
                EnterTransition.None togetherWith ExitTransition.None
            } else {
                (fadeIn(motion.tween(Motion.STANDARD_MS, delayMs = Motion.INSTANT_MS)) togetherWith
                    fadeOut(motion.tween(Motion.INSTANT_MS))) using
                    SizeTransform(clip = false) { _, _ -> motion.tween(Motion.STANDARD_MS) }
            }
        },
        label = label
    ) { state -> content(state) }
}

/** Shows [content] opening from its top edge with a fade when [visible] turns true, and closing the same way when false. */
@Composable
fun AppearVisibility(visible: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val motion = rememberMotion()
    AnimatedVisibility(visible = visible, modifier = modifier, enter = motion.appear, exit = motion.disappear) { content() }
}

/**
 * The motion for an item of a lazy list that comes and goes (needs a stable `key`): it fades in, and fades out while the
 * items below it close the gap. Items shown with the first composition of the list are not animated.
 */
@Composable
fun LazyItemScope.itemMotion(): Modifier {
    val motion = rememberMotion()
    if (motion.reduced) return Modifier
    return Modifier.animateItem(
        fadeInSpec = motion.tween(Motion.STANDARD_MS),
        placementSpec = motion.gentle(),
        fadeOutSpec = motion.exit(Motion.QUICK_MS)
    )
}

private class Retained<T>(val key: Any, val item: T, val present: Boolean)

private class RetainedItems<T> {
    var entries: List<Retained<T>> = emptyList()
    var started = false
    val initialKeys = HashSet<Any>()

    fun merge(items: List<T>, keyOf: (T) -> Any): List<Retained<T>> {
        val current = LinkedHashMap<Any, T>()
        items.forEach { current[keyOf(it)] = it }
        if (!started) {
            started = true
            initialKeys.addAll(current.keys)
            entries = items.map { Retained(keyOf(it), it, true) }
            return entries
        }
        val result = ArrayList<Retained<T>>(items.map { Retained(keyOf(it), it, true) })
        // Items that have left stay (marked absent) where they were until their exit has played.
        entries.forEachIndexed { index, old ->
            if (old.key in current) return@forEachIndexed
            var at = 0
            for (before in index - 1 downTo 0) {
                val position = result.indexOfFirst { it.key == entries[before].key }
                if (position >= 0) {
                    at = position + 1
                    break
                }
            }
            result.add(at.coerceAtMost(result.size), Retained(old.key, old.item, false))
        }
        entries = result
        return result
    }

    fun drop(key: Any) {
        entries = entries.filter { it.key != key }
    }
}

/**
 * A column of rows where a row that is added opens (fade and grow) and a row that is removed fades and closes, instead of
 * popping. A removed row stays on screen only for the length of its exit. Rows present when it is first shown do not
 * animate. [itemKey] must be stable and unique; [content] draws one row and gets its position (for a divider above all but the first).
 */
@Composable
fun <T : Any> AnimatedColumnItems(
    items: List<T>,
    itemKey: (T) -> Any,
    modifier: Modifier = Modifier,
    content: @Composable (item: T, index: Int) -> Unit
) {
    val motion = rememberMotion()
    val holder = remember { RetainedItems<T>() }
    var settled by remember { mutableIntStateOf(0) }
    @Suppress("UNUSED_VARIABLE") val observed = settled
    val merged = holder.merge(items, itemKey)
    Column(modifier) {
        merged.forEachIndexed { index, entry ->
            androidx.compose.runtime.key(entry.key) {
                val state = remember { MutableTransitionState(entry.key in holder.initialKeys).apply { targetState = true } }
                SideEffect { if (state.targetState != entry.present) state.targetState = entry.present }
                LaunchedEffect(entry.present, state.isIdle) {
                    if (!entry.present && state.isIdle && !state.currentState) {
                        holder.drop(entry.key)
                        settled++
                    }
                }
                AnimatedVisibility(visibleState = state, enter = motion.appear, exit = motion.disappear) {
                    content(entry.item, index)
                }
            }
        }
    }
}
