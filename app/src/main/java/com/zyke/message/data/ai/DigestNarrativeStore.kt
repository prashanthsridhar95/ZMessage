package com.zyke.message.data.ai

import android.content.Context
import com.zyke.message.domain.ai.AiDigest
import com.zyke.message.domain.ai.AiFeature
import com.zyke.message.domain.insights.DigestSummary

/**
 * The one door the morning notification has to the AI layer, and it is a read-only one: it looks up the opening line that was
 * prepared earlier, while the app was open (`AiHub.prepareDigest`), and checks it once more against the digest it is about to post.
 * It never asks the model, never builds the engine and never waits: a missing, stale or unverifiable line is simply null and the
 * digest goes out exactly as it always did. `AiPolicyTest` pins that this file touches neither the hub, the gateway nor the engine.
 */
object DigestNarrativeStore {
    /** The verified line for [summary], or null when the feature is off or nothing matching was prepared. */
    fun lookup(context: Context, summary: DigestSummary): String? {
        if (!AiSettingsRepositoryImpl(context).settings.value.isOn(AiFeature.DIGEST)) return null
        val facts = AiDigest.facts(summary) ?: return null
        val stored = context.applicationContext.getSharedPreferences(PrefsAiCache.FILE, Context.MODE_PRIVATE).getString(facts.key, null) ?: return null
        // Entries are written as "<sequence>|<text>" (see PrefsAiCache).
        return AiDigest.verify(stored.substringAfter('|'), facts)
    }
}
