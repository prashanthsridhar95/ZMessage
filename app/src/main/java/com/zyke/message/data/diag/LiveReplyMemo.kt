package com.zyke.message.data.diag

import com.zyke.message.domain.diag.LiveCheck
import com.zyke.message.domain.diag.LiveCheckReport

/**
 * The last raw live-status reply a developer test fetched, held in memory so "Share raw reply" can offer it. It is the public reply for
 * one sample or typed train or flight number: nothing of the person's goes out in the request, so nothing of theirs is in it. Only the
 * latest one is kept; it is never written to disk, never logged, and gone when the app process ends.
 */
object LiveReplyMemo {
    private class Held(val report: LiveCheckReport, val body: String)

    @Volatile
    private var held: Held? = null

    fun put(report: LiveCheckReport, body: String) {
        held = Held(report, body)
    }

    /** The text to share (header, then the body cut to 64 KB), or null when no test has run or the reply had no body. */
    fun shareText(): String? = held?.takeIf { it.body.isNotEmpty() }?.let { LiveCheck.shareText(it.report, it.body) }

    fun clear() {
        held = null
    }
}
