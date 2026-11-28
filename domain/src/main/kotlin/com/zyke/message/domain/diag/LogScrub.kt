package com.zyke.message.domain.diag

/**
 * A last line of defence for the log files: nothing in the app writes an email address or a mail login to a log, and this takes both out of
 * anything that would, so the file a person shares cannot carry them. Pure text work.
 */
object LogScrub {
    private val ADDRESS = Regex("[A-Za-z0-9._%+'-]{1,64}@[A-Za-z0-9.-]{1,100}\\.[A-Za-z]{2,24}")
    private val LOGIN = Regex("(?i)\\bLOGIN\\s+\"(?:[^\"\\\\]|\\\\.)*\"\\s+\"(?:[^\"\\\\]|\\\\.)*\"")

    fun scrub(text: String): String {
        if ('@' !in text && !text.contains("LOGIN", ignoreCase = true)) return text
        return ADDRESS.replace(LOGIN.replace(text, "LOGIN <hidden>"), "<email>")
    }
}
