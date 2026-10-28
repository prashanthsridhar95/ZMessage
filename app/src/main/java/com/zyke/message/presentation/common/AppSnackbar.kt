package com.zyke.message.presentation.common

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.staticCompositionLocalOf

/** The app's one snackbar, drawn at the top of everything, in front of any sheet. */
val LocalAppSnackbar = staticCompositionLocalOf { SnackbarHostState() }
