package com.zyke.message.domain.legal

/**
 * The few facts the Privacy and terms page states about who runs the app and which text the person is reading. They live
 * here, in one place, so a change is made once and the page, the docs and the tests all see it.
 *
 * TODO(owner): confirm [OPERATOR_NAME] (the exact legal name of the person or company that publishes the app) and fill in
 * [OPERATOR_ADDRESS] (a postal address) before any public release. The name below is the repository owner's; the address is
 * deliberately empty and is left out of the page until it is set. A lawyer should review the whole text first (see
 * docs/LEGAL_NOTES.md).
 */
object LegalInfo {
    const val APP_NAME = "Zyke Message"

    /** TODO(owner): confirm the exact name to be shown. */
    const val OPERATOR_NAME = "Prashanth Sridhar"

    /** TODO(owner): a postal address, if one is to be published. Empty means the page says nothing about it. */
    const val OPERATOR_ADDRESS = ""

    const val CONTACT_EMAIL = "hello@prashanthsridhar.com"

    /** Who the app is for. A finance app that can send texts is for adults. */
    const val MINIMUM_AGE = 18

    /** Bump when the meaning of the text changes, and set [LAST_UPDATED] to the same day. */
    const val TEXT_VERSION = 1
    const val LAST_UPDATED = "9 November 2026"
}
