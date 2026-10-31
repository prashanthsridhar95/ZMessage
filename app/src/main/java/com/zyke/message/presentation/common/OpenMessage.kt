package com.zyke.message.presentation.common

import androidx.compose.runtime.compositionLocalOf

/** Opens one message's detail sheet on Home (closing any page over it), for pages that list payments. */
val LocalOpenMessage = compositionLocalOf<(Long) -> Unit> { {} }
