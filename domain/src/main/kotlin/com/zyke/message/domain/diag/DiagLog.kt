package com.zyke.message.domain.diag

/**
 * A place for the domain code to say what it is doing, for the developer log. Nothing is recorded unless the app
 * has set a [sink] (it does so only in developer mode), and the message is only built when it is.
 *
 * Rules for what is written: ids, counts, kinds and timings; never message text, amounts, account numbers, names or
 * numbers. A key that identifies a sender or receiver is written as [key], a short fingerprint, so two lines can be
 * seen to refer to the same one without saying which.
 */
object DiagLog {
    @Volatile
    var sink: ((tag: String, message: String) -> Unit)? = null

    fun d(tag: String, message: () -> String) {
        val out = sink ?: return
        out(tag, runCatching(message).getOrElse { "(could not build log line: ${it.javaClass.simpleName})" })
    }

    /** A short stand-in for a key: the same text always gives the same one, and it cannot be read back. */
    fun key(text: String?): String = if (text == null) "null" else "k#" + Integer.toHexString(text.hashCode()).padStart(8, '0') + "(${text.length})"
}
