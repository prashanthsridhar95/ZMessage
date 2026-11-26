package com.zyke.message.presentation.common

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Lets any screen open the search page, which looks across messages, goals, reminders, the calendar and more. */
val LocalOpenSearch = compositionLocalOf<() -> Unit> { {} }

/** A search asked for from outside Search (the Ask chat's "Search messages for ..."): Search takes it once, fills its field and clears it. */
object SearchRequest {
    var pending by mutableStateOf<String?>(null)
}
