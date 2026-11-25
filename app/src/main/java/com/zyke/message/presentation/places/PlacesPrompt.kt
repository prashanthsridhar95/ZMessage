package com.zyke.message.presentation.places

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.zyke.message.ZykeMessagesApplication
import com.zyke.message.data.places.PlaceCapturer
import com.zyke.message.domain.places.PlaceHealth
import com.zyke.message.domain.places.PlaceProblem
import com.zyke.message.domain.places.PlacesPrompt
import com.zyke.message.domain.places.PlacesPromptKind
import com.zyke.message.domain.places.PlacesPromptPhase
import com.zyke.message.notifications.LiveAlerts
import com.zyke.message.presentation.common.ButtonStyle
import com.zyke.message.presentation.common.OnboardingHero
import com.zyke.message.presentation.messages.components.Caption
import com.zyke.message.presentation.theme.Spacing

/**
 * The first-run page (fresh install) and the one-time "New" page (after an update) for spending places, in the one onboarding
 * layout. It switches nothing on by itself: "Turn on" leads to the plain consent page, and only its "Continue" saves the choice
 * and then asks the phone, first for location and then, where Android has the step, for "Allow all the time". "Not now", the
 * back button and a swipe away all mean "leave it off, and do not ask again"; Settings keeps the entry. Never shown in demo mode
 * (the caller decides with [PlacesPrompt.kind]).
 */
@Composable
fun PlacesPromptHost(kind: PlacesPromptKind, onDone: () -> Unit) {
    val context = LocalContext.current
    val repo = (context.applicationContext as ZykeMessagesApplication).container.spendPlaces
    var phase by rememberSaveable { mutableIntStateOf(PlacesPromptPhase.OFFER.ordinal) }
    val current = PlacesPromptPhase.entries[phase]

    fun answered() = repo.update { it.copy(prompted = true) }
    fun notNow() { answered(); onDone() }
    fun afterLocation() {
        val (fine, background) = PlaceCapturer.permissionFacts(context)
        when {
            // The phone said no: the switch goes back off so Settings tells the truth, and it is not asked again.
            !fine -> { repo.update { it.copy(enabled = false) }; LiveAlerts.refreshReceiver(context); onDone() }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !background -> phase = PlacesPromptPhase.ALL_THE_TIME.ordinal
            else -> onDone()
        }
    }
    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { afterLocation() }
    val askBackground = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onDone() }

    fun agree() {
        // The person's own tap on Continue, after reading the consent page, is the only thing that switches capture on.
        repo.update { it.copy(enabled = true, explained = true, prompted = true) }
        LiveAlerts.refreshReceiver(context)
        if (PlaceCapturer.hasLocationPermission(context)) afterLocation()
        else askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    fun allowAllTheTime() {
        // Android 10 asks in a dialog; Android 11 and later only let the person choose "Allow all the time" on the app's own settings page.
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) askBackground.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        else runCatching {
            context.startActivity(
                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", context.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    // Coming back from the phone's settings with "all the time" chosen finishes the page.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, phase) {
        val watcher = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && current == PlacesPromptPhase.ALL_THE_TIME && PlaceCapturer.permissionFacts(context).second) onDone()
        }
        lifecycle.addObserver(watcher)
        onDispose { lifecycle.removeObserver(watcher) }
    }

    Dialog(
        onDismissRequest = { if (current == PlacesPromptPhase.ALL_THE_TIME) onDone() else notNow() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            when (current) {
                PlacesPromptPhase.OFFER -> OnboardingHero(
                    icon = com.zyke.message.R.drawable.ic_place_24,
                    headline = PlacesPrompt.headline(kind),
                    promise = PlacesPrompt.PROMISE,
                    primaryLabel = PlacesPrompt.TURN_ON,
                    onPrimary = { phase = PlacesPromptPhase.CONSENT.ordinal },
                    secondaryLabel = PlacesPrompt.NOT_NOW,
                    onSecondary = { notNow() },
                    secondaryStyle = ButtonStyle.OUTLINE,
                    footnote = PlacesPrompt.FOOTNOTE,
                    extra = { Points(PlacesPrompt.POINTS) }
                )
                PlacesPromptPhase.CONSENT -> OnboardingHero(
                    icon = com.zyke.message.R.drawable.ic_place_24,
                    headline = PlacesPrompt.CONSENT_HEADLINE,
                    promise = PlacesPrompt.CONSENT_PROMISE,
                    primaryLabel = PlacesPrompt.CONTINUE,
                    onPrimary = { agree() },
                    secondaryLabel = PlacesPrompt.NOT_NOW,
                    onSecondary = { notNow() },
                    secondaryStyle = ButtonStyle.OUTLINE,
                    extra = { Points(PlacesPrompt.CONSENT_POINTS) }
                )
                PlacesPromptPhase.ALL_THE_TIME -> OnboardingHero(
                    icon = com.zyke.message.R.drawable.ic_place_24,
                    headline = PlaceHealth.title(PlaceProblem.NEEDS_ALL_THE_TIME),
                    promise = PlaceHealth.detail(PlaceProblem.NEEDS_ALL_THE_TIME),
                    primaryLabel = PlaceHealth.action(PlaceProblem.NEEDS_ALL_THE_TIME) ?: PlacesPrompt.CONTINUE,
                    onPrimary = { allowAllTheTime() },
                    secondaryLabel = PlacesPrompt.LATER,
                    onSecondary = { onDone() },
                    secondaryStyle = ButtonStyle.OUTLINE,
                    footnote = PlacesPrompt.FOOTNOTE
                )
            }
        }
    }
}

/** The short list under the promise: plain bullets that wrap, so a large font or a 320 dp screen only makes the page scroll. */
@Composable
private fun Points(points: List<String>) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        points.forEach { Caption("•  $it", maxLines = 6, color = MaterialTheme.colorScheme.secondary) }
    }
}
