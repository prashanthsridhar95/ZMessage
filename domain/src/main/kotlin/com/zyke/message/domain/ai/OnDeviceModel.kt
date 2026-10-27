package com.zyke.message.domain.ai

/**
 * A language model that runs on the phone (for example Gemini Nano through Android AICore). The domain only
 * knows this small contract, so a device without a model simply uses [NoModel] and everything still works.
 */
interface OnDeviceModel {
    val isAvailable: Boolean

    /** The model's reply, or null if it could not answer. */
    suspend fun generate(prompt: String): String?
}

object NoModel : OnDeviceModel {
    override val isAvailable: Boolean = false
    override suspend fun generate(prompt: String): String? = null
}
