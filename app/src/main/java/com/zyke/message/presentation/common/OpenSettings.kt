package com.zyke.message.presentation.common

import androidx.compose.runtime.compositionLocalOf

/** Lets any screen send the person to Settings, where the app's settings sheet lives. */
val LocalOpenSettings = compositionLocalOf<() -> Unit> { {} }
