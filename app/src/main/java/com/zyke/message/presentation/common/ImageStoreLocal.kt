package com.zyke.message.presentation.common

import androidx.compose.runtime.staticCompositionLocalOf
import com.zyke.message.data.images.ImageStore

/** The store of sender pictures, provided once at the top of the app; null in previews and tests. */
val LocalImageStore = staticCompositionLocalOf<ImageStore?> { null }
