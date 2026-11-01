package com.zyke.message.notifications

import android.content.Context
import com.zyke.message.ZykeMessagesApplication
import com.zyke.message.domain.privacy.AmountMasker

/**
 * With "Hide amounts" on, no notification or widget carries a money figure: every amount in their text becomes dots.
 * (There is no screen lock check here: a notification cannot ask for one, so the figures simply stay in the app.)
 */
object NotificationPrivacy {
    fun hidden(context: Context): Boolean =
        (context.applicationContext as ZykeMessagesApplication).container.settings.settings.value.hideAmounts

    fun text(context: Context, text: String): String = if (hidden(context)) AmountMasker.mask(text) else text

    /** Like [text] for a screen reader (widget content descriptions). */
    fun spoken(context: Context, text: String): String = if (hidden(context)) AmountMasker.maskSpoken(text) else text
}
