package com.zyke.message.domain.model

data class SmsMessage(
    val id: Long,
    val sender: String,
    val body: String,
    val date: Long,
    val isRead: Boolean,
    /** Captured from a chat (RCS) notification rather than read from the SMS provider. */
    val isRcs: Boolean = false,
    /** Read from the MMS provider (a picture message); its text parts are the body, and its pictures are read only when it is opened. */
    val isMms: Boolean = false,
    /** Made from a bank alert email the person chose to let the app read (see `domain.email`); it is not in the phone's inbox, so it is never cached with it. */
    val isEmail: Boolean = false
) : java.io.Serializable
