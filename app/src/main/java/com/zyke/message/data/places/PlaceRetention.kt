package com.zyke.message.data.places

import com.zyke.message.di.AppContainer
import com.zyke.message.domain.places.PlaceKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Keeps the stored places tidy once per start: places older than the "keep for" setting go, and so does the place of a message
 * that was deleted from the phone. It waits for the inbox to be read completely first, so a half-read inbox never looks like
 * a deleted one; the lists of keys are built in memory and dropped.
 */
internal object PlaceRetention {
    fun start(scope: CoroutineScope, container: AppContainer) {
        if (com.zyke.message.data.demo.DemoMode.active) return
        scope.launch(Dispatchers.Default) { runCatching { tidy(container) } }
    }

    private suspend fun tidy(container: AppContainer) {
        val repo = container.spendPlaces
        if (repo.state.value.places.isNotEmpty()) {
            repo.prune(System.currentTimeMillis())
            if (!container.settings.settings.value.readingPaused) {
                container.observeMessages.progress.first { it.isDone }
                val messages = container.observeMessages().first()
                val live = withContext(Dispatchers.Default) {
                    val keys = HashSet<String>(messages.size * 3)
                    for (m in messages) {
                        keys.addAll(PlaceKey.nearby(m.message))
                        for (merged in m.mergedMessages) keys.addAll(PlaceKey.nearby(merged))
                    }
                    keys
                }
                repo.dropOrphans(live)
            }
        }
    }
}
