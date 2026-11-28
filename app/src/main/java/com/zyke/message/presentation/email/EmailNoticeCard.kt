package com.zyke.message.presentation.email

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zyke.message.ZykeMessagesApplication
import com.zyke.message.domain.classification.HdfcSmsNotice
import com.zyke.message.domain.model.SmsMessage
import com.zyke.message.presentation.common.AppButton
import com.zyke.message.presentation.common.ButtonStyle
import com.zyke.message.presentation.common.FintechCard
import com.zyke.message.presentation.common.LocalOpenSettings
import com.zyke.message.presentation.common.ResponsiveRow
import com.zyke.message.presentation.messages.components.Caption
import com.zyke.message.presentation.messages.components.RowTitle
import com.zyke.message.presentation.settings.SettingsDeepLink
import com.zyke.message.presentation.theme.Spacing

/** The name of the Settings page for email alerts (the name of `SettingsGroup.EMAIL`, see `SettingsDeepLink`). */
private const val EMAIL_PAGE = "EMAIL"

/**
 * Under HDFC Bank's notice that it is stopping UPI SMS alerts: one card offering to read the alert emails that continue. Shown once. "Not now"
 * and "Set up" both end the offer, so it never comes back; "Set up" opens Settings, Email alerts, where nothing is on until the person
 * switches it on there. Not shown in Demo mode, to someone already connected, or under a message that is itself an email alert.
 */
@Composable
internal fun EmailNoticeCard(message: SmsMessage) {
    if (message.isEmail || com.zyke.message.data.demo.DemoMode.active) return
    if (!HdfcSmsNotice.matches(message.body)) return
    val container = (LocalContext.current.applicationContext as ZykeMessagesApplication).container
    val state by container.emailAlerts.state.collectAsStateWithLifecycle()
    val openSettings = LocalOpenSettings.current
    if (state.settings.connected || state.settings.hdfcNoticeAnswered) return
    FintechCard(Modifier.padding(top = Spacing.sm)) {
        RowTitle(HdfcSmsNotice.TITLE)
        Caption(HdfcSmsNotice.OFFER, Modifier.padding(top = Spacing.xs), maxLines = 4)
        ResponsiveRow(Modifier.fillMaxWidth().padding(top = Spacing.md), spacing = Spacing.sm) {
            AppButton("Not now", { container.emailAlerts.update { it.copy(hdfcNoticeAnswered = true) } }, Modifier.cell(), style = ButtonStyle.OUTLINE)
            AppButton(
                "Set up",
                {
                    container.emailAlerts.update { it.copy(hdfcNoticeAnswered = true) }
                    SettingsDeepLink.pending = EMAIL_PAGE
                    openSettings()
                },
                Modifier.cell()
            )
        }
    }
}
