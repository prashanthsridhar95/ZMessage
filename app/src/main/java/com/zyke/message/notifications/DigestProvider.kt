package com.zyke.message.notifications

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.zyke.message.ZykeMessagesApplication
import com.zyke.message.domain.insights.DailyDigest
import com.zyke.message.domain.insights.DigestSummary
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.first

/** Works out today's digest from the inbox, for the morning notification and the home-screen widget. */
internal object DigestProvider {

    /** Null when SMS access is missing or there is nothing worth saying. */
    suspend fun load(context: Context): DigestSummary? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) return null
        val container = (context.applicationContext as ZykeMessagesApplication).container
        val messages = container.observeMessages().first()
        return DailyDigest.build(messages, container.userMessageState.state.value, Instant.now(), ZoneId.systemDefault(), container.reminders.reminders.value)
    }
}
