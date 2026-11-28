package com.zyke.message.domain.email

/**
 * Where the app password is kept. The app's own implementation seals it with a key in the Android Keystore that cannot be taken out of
 * the phone, in a file that is never backed up. The address is not a secret from the person and lives with the settings; only the
 * password is here. Nothing that reads this ever logs it, puts it in a report, or sends it anywhere but to the provider chosen.
 */
interface EmailCredentialStore {
    fun hasPassword(): Boolean

    /** The saved app password, or null when there is none or the phone's key can no longer open it. */
    fun password(): String?

    /** Saves [password] (already cleaned by [EmailInput.cleanPassword]); false when it could not be kept. */
    fun save(password: String): Boolean

    /** Forgets the password and the key that sealed it. */
    fun clear()
}

/** Nothing is kept: the demo. */
object NoEmailCredentials : EmailCredentialStore {
    override fun hasPassword(): Boolean = false
    override fun password(): String? = null
    override fun save(password: String): Boolean = false
    override fun clear() = Unit
}

/** For tests. */
class InMemoryEmailCredentials : EmailCredentialStore {
    private var value: String? = null
    override fun hasPassword(): Boolean = value != null
    override fun password(): String? = value
    override fun save(password: String): Boolean { value = password; return true }
    override fun clear() { value = null }
}

/** What the person types, checked before anything is sent. */
object EmailInput {
    private val ADDRESS = Regex("[A-Za-z0-9._%+'-]{1,64}@[A-Za-z0-9.-]{1,100}\\.[A-Za-z]{2,24}")
    const val MIN_PASSWORD = 8
    const val MAX_PASSWORD = 64

    fun cleanAddress(raw: String): String = raw.trim()

    /** An app password as the providers show it: groups of letters with spaces between. The spaces are not part of it. */
    fun cleanPassword(raw: String): String = raw.filter { !it.isWhitespace() }

    /** A short plain sentence about what is wrong with the address or password, or null when both can be tried. */
    fun problem(address: String, password: String): String? {
        val a = cleanAddress(address)
        val p = cleanPassword(password)
        return when {
            a.isEmpty() -> "Type your email address."
            !ADDRESS.matches(a) -> "That does not look like an email address."
            p.isEmpty() -> "Type the app password."
            p.length < MIN_PASSWORD -> "An app password is longer than that. Check you copied all of it."
            p.length > MAX_PASSWORD || p.any { it.code !in PRINTABLE } -> "That does not look like an app password. Use the one the provider made for you, not your own password."
            else -> null
        }
    }

    private val PRINTABLE = 0x21..0x7E
}
