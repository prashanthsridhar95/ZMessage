package com.zyke.message.presentation.common

import androidx.compose.runtime.compositionLocalOf

/** Lets any screen open the search page, which looks across messages, goals, reminders, the calendar and more. */
val LocalOpenSearch = compositionLocalOf<() -> Unit> { {} }
