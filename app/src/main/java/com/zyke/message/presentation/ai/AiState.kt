package com.zyke.message.presentation.ai

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.zyke.message.ZykeMessagesApplication
import com.zyke.message.data.ai.AiHub
import com.zyke.message.domain.ai.AiFeature
import com.zyke.message.domain.ai.AiGate

/** The app's on-device AI. Nothing is asked of the model by getting this; screens ask only when the person taps. */
@Composable
internal fun rememberAi(): AiHub {
    val context = LocalContext.current
    return remember(context) { (context.applicationContext as ZykeMessagesApplication).container.ai }
}

/** Whether a surface of [feature] should be on screen: it is switched on, this is not Demo mode and the phone can do it at all. */
@Composable
internal fun rememberAiVisible(feature: AiFeature): Boolean {
    val ai = rememberAi()
    val settings by ai.settings.collectAsState()
    val availability by ai.availability.collectAsState()
    return AiGate.visible(feature, settings, availability, ai.aiContext())
}

/** Whether a tap on an AI button can be answered now: the model is ready, or it answered recently (see ModelWatch). */
@Composable
internal fun rememberAiReady(): Boolean {
    val ai = rememberAi()
    val availability by ai.availability.collectAsState()
    return availability.canGenerate || ai.aiContext().modelWorked
}

/** One short word for where the model is, for the Settings row. */
internal fun availabilityLabel(availability: com.zyke.message.domain.ai.AiAvailability): String = when (availability) {
    com.zyke.message.domain.ai.AiAvailability.Unsupported -> "not supported here"
    com.zyke.message.domain.ai.AiAvailability.NeedsDownload -> "model not downloaded"
    is com.zyke.message.domain.ai.AiAvailability.Downloading -> "downloading"
    com.zyke.message.domain.ai.AiAvailability.Ready -> "ready"
    com.zyke.message.domain.ai.AiAvailability.Busy -> "busy"
    is com.zyke.message.domain.ai.AiAvailability.Failed -> "needs a look"
}
