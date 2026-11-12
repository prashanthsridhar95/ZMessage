package com.zyke.message.presentation.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import com.zyke.message.R
import com.zyke.message.domain.onboarding.PermissionStep
import com.zyke.message.domain.onboarding.StepDots
import com.zyke.message.presentation.motion.AppearVisibility
import com.zyke.message.presentation.theme.IconSize
import com.zyke.message.presentation.theme.Spacing
import com.zyke.message.presentation.theme.TypeRole

/**
 * Explains what the app does with one permission before asking, in the one onboarding layout ([OnboardingHero]): an icon,
 * the headline, one line of promise, the ask and a quiet way out. One page asks for one thing. The SMS page (the only
 * one that is required) has no way out but "How it stays private", which opens the three promises under it; an optional
 * page has "Not now" ([onSkip]). [steps] shows the dots when several pages are asked in a row.
 */
@Composable
fun PermissionGate(
    onGrant: () -> Unit,
    modifier: Modifier = Modifier,
    step: PermissionStep = PermissionStep.SMS_READ,
    steps: StepDots? = null,
    onSkip: (() -> Unit)? = null
) {
    var details by rememberSaveable { mutableStateOf(false) }
    val skip = step.skip
    val offerSkip = skip != null && onSkip != null
    val promises: @Composable ColumnScope.() -> Unit = {
        AppearVisibility(details) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Promise(R.drawable.baseline_message_24, "Reads the SMS already on this phone, to find payments, bills and OTPs")
                Promise(R.drawable.baseline_lock_24, "Everything stays on your device. Nothing is uploaded, nothing is shared")
                Promise(R.drawable.baseline_dashboard_24, "Sorts them into spending, upcoming bills and a clean inbox")
            }
        }
    }
    OnboardingHero(
        icon = when (step) {
            PermissionStep.SMS_READ -> R.drawable.baseline_message_24
            PermissionStep.NOTIFICATIONS -> R.drawable.ic_reminder_24
            PermissionStep.NOTIFICATION_ACCESS -> R.drawable.baseline_list_24
            PermissionStep.SMS_SEND -> R.drawable.ic_share_24
        },
        headline = step.headline,
        promise = step.promise,
        primaryLabel = step.allow,
        onPrimary = onGrant,
        modifier = modifier,
        secondaryLabel = if (offerSkip) skip else if (details) "Hide details" else "How it stays private",
        onSecondary = if (offerSkip) onSkip else ({ details = !details }),
        steps = steps,
        footnote = "You can change this any time in Settings",
        extra = if (step == PermissionStep.SMS_READ) promises else null
    )
}

@Composable
private fun Promise(icon: Int, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md), verticalAlignment = Alignment.Top) {
        Image(
            modifier = Modifier.size(IconSize.md),
            painter = painterResource(icon),
            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary),
            contentDescription = null
        )
        Text(text, style = TypeRole.body, color = MaterialTheme.colorScheme.secondary)
    }
}
