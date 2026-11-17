package com.zyke.message.domain.classification

/**
 * Which name a brand logo is looked up by. The name the person sees leads: a nickname they chose ("Netflix" for a cryptic
 * "NFLX*SUBS") finds the logo of what they called it, and the name in the message is the second try. A logo the person
 * turned off ("This logo is wrong") is off for every name it could have been found by, so changing the nickname does not
 * bring the wrong logo back by another door.
 */
object BrandLogo {

    /** The names to try, in order: [shown] (the nickname when there is one), then [original]; blank and repeated ones dropped. */
    fun names(shown: String?, original: String?): List<String> =
        listOf(shown, original).mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }.distinctBy { key(it) }

    /** What a name is compared by for "turned off". */
    fun key(name: String): String = name.trim().lowercase()

    /** The first of [names] that is a known brand and not turned off, with its website; null when none. */
    fun resolve(
        names: List<String>,
        domainOf: (String) -> String? = KnownEntityDomains::domainFor,
        isHidden: (String) -> Boolean
    ): Pair<String, String>? {
        for (name in names) {
            if (isHidden(name)) continue
            val domain = domainOf(name) ?: continue
            return name to domain
        }
        return null
    }

    /** The names among [names] that a logo could be found by (these are the ones "This logo is wrong" turns off). */
    fun withLogo(names: List<String>, domainOf: (String) -> String? = KnownEntityDomains::domainFor): List<String> =
        names.filter { domainOf(it) != null }
}
