package com.zyke.message.presentation.common

import androidx.compose.runtime.compositionLocalOf

/** Opens the subscriptions page (on Home, closing any page over it) from another screen, such as Insights. */
val LocalOpenSubscriptions = compositionLocalOf<() -> Unit> { {} }
