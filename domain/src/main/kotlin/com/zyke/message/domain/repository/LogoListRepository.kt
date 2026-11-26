package com.zyke.message.domain.repository

import com.zyke.message.domain.logo.LogoEntry
import com.zyke.message.domain.logo.LogoList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The person's own logo list: for each payee they gave a picture from the web, the brand key and the web address the picture
 * came from (never the picture, and nothing else). It is what "Export logo list" in Settings shares. Keys and addresses are
 * cleaned by [LogoList]; anything that is not clean is refused.
 */
interface LogoListRepository {
    /** The entries, sorted by key. */
    val entries: StateFlow<List<LogoEntry>>

    /** Remembers that the picture for [name] came from [url]. False when either is not clean (a UPI id, a number, a non-https address). */
    fun record(name: String, url: String): Boolean

    /** Forgets the entry for [name]: its picture was removed or replaced by one from the phone. */
    fun forget(name: String)
}

object NoLogoList : LogoListRepository {
    override val entries: StateFlow<List<LogoEntry>> = MutableStateFlow(emptyList())
    override fun record(name: String, url: String): Boolean = false
    override fun forget(name: String) = Unit
}
