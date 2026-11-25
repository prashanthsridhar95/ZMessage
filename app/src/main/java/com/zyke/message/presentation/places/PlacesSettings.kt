package com.zyke.message.presentation.places

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.core.content.ContextCompat
import com.zyke.message.ZykeMessagesApplication
import com.zyke.message.data.places.PlaceCapturer
import com.zyke.message.domain.places.PlaceFacts
import com.zyke.message.domain.places.PlaceHealth
import com.zyke.message.domain.places.PlaceProblem
import com.zyke.message.domain.places.SpendPlaceSettings
import com.zyke.message.notifications.LiveAlerts
import com.zyke.message.notifications.SmsReceiver
import com.zyke.message.presentation.common.AdaptiveSheet
import com.zyke.message.presentation.common.AppButton
import com.zyke.message.presentation.common.ButtonSize
import com.zyke.message.presentation.common.ButtonStyle
import com.zyke.message.presentation.common.ChipOption
import com.zyke.message.presentation.common.Chips
import com.zyke.message.presentation.common.ConfirmDialog
import com.zyke.message.presentation.common.FintechCard
import com.zyke.message.presentation.common.ResponsiveRow
import com.zyke.message.presentation.common.Text
import com.zyke.message.presentation.common.heading
import com.zyke.message.presentation.format.DisplayFormatters
import com.zyke.message.presentation.messages.components.Caption
import com.zyke.message.presentation.messages.components.RowTitle
import com.zyke.message.presentation.settings.ToggleRow
import com.zyke.message.presentation.theme.Spacing

/**
 * Settings, "Spending places": the switch and its calm two-step permission flow, the choices (map pictures, place names, how long
 * to keep places, backup), the map, and deleting everything. Nothing here asks the phone for a position: that happens only in
 * PlaceCapturer, when a purchase text arrives.
 */
@Composable
internal fun PlacesSettingsContent() {
    val context = LocalContext.current
    val container = (context.applicationContext as ZykeMessagesApplication).container
    val repo = container.spendPlaces
    val state by repo.state.collectAsState()
    val s = state.settings
    val demo = com.zyke.message.data.demo.DemoMode.active
    val readingPaused = container.settings.settings.collectAsState().value.readingPaused
    var recheck by remember { mutableIntStateOf(0) }
    var explaining by rememberSaveable { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    var picturesAsk by rememberSaveable { mutableStateOf(false) }

    // Coming back from the phone's settings re-reads what is allowed.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val watcher = androidx.lifecycle.LifecycleEventObserver { _, event -> if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) recheck += 1 }
        lifecycle.addObserver(watcher)
        onDispose { lifecycle.removeObserver(watcher) }
    }

    fun openAppSettings() {
        runCatching {
            context.startActivity(
                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", context.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
    fun openLocationSettings() {
        runCatching { context.startActivity(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
    fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { recheck += 1 }
    val askSms = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { recheck += 1 }
    val askBackground = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { recheck += 1 }

    fun allowLocation() = askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    fun allowAllTheTime() {
        // Android 10 asks in a dialog; Android 11 and later only let the person choose "Allow all the time" on the app's own settings page.
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) askBackground.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) else openAppSettings()
    }
    fun switchOn() {
        repo.update { it.copy(enabled = true, explained = true) }
        LiveAlerts.refreshReceiver(context)
        if (!PlaceCapturer.hasLocationPermission(context)) allowLocation()
        recheck += 1
    }
    fun switchOff() {
        repo.update { it.copy(enabled = false) }
        LiveAlerts.refreshReceiver(context)
        recheck += 1
    }

    FintechCard {
        RowTitle("Remember where I spend")
        Caption(
            "When a text says you paid at a shop, the phone is asked where it is, once, and the place is saved only on this phone. " +
                "Then a payment shows where it was made, and a map shows where your money goes. Never uploaded, never in logs.",
            Modifier.padding(top = Spacing.xs), maxLines = 8
        )
    }

    if (demo) {
        FintechCard {
            RowTitle("Demo mode")
            Caption("The demo never reads your location and never asks the internet for a map. Its places are made up.", Modifier.padding(top = Spacing.xs), maxLines = 4)
            AppButton("Open the Spending map", { SpendMapRequests.show() }, Modifier.fillMaxWidth().padding(top = Spacing.md))
        }
    } else {
        FintechCard {
            ToggleRow(
                title = "Remember where I spend",
                description = "Off until you switch it on",
                checked = s.enabled,
                onCheckedChange = { wanted -> if (!wanted) switchOff() else if (!s.explained) explaining = true else switchOn() }
            )
        }

        if (s.enabled) {
            val facts = remember(s, readingPaused, recheck) {
                val (fine, background) = PlaceCapturer.permissionFacts(context)
                PlaceFacts(
                    switchedOn = s.enabled, readingPaused = readingPaused, canHearTexts = granted(Manifest.permission.RECEIVE_SMS),
                    fineLocation = fine, backgroundLocation = background, needsBackgroundStep = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q,
                    locationServicesOn = PlaceCapturer.locationServicesOn(context),
                    receiverOn = context.packageManager.getComponentEnabledSetting(ComponentName(context, SmsReceiver::class.java)) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                )
            }
            val problem = PlaceHealth.problem(facts)
            FintechCard {
                RowTitle(PlaceHealth.title(problem), color = if (problem == null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
                PlaceHealth.detail(problem).takeIf { it.isNotEmpty() }?.let { Caption(it, Modifier.padding(top = Spacing.xs), maxLines = 6) }
                Caption(
                    "${DisplayFormatters.count(state.places.size, "place", "places")} saved on this phone",
                    Modifier.padding(top = Spacing.xs), maxLines = 2
                )
                ResponsiveRow(Modifier.fillMaxWidth().padding(top = Spacing.md), spacing = Spacing.sm) {
                    val label = PlaceHealth.action(problem)
                    if (label != null) {
                        AppButton(
                            label,
                            {
                                when (problem) {
                                    PlaceProblem.READING_PAUSED -> { container.settings.setReadingPaused(false); container.smsRepository.refreshRecent() }
                                    PlaceProblem.CANNOT_HEAR_TEXTS -> askSms.launch(Manifest.permission.RECEIVE_SMS)
                                    PlaceProblem.NEEDS_LOCATION -> allowLocation()
                                    PlaceProblem.NEEDS_ALL_THE_TIME -> allowAllTheTime()
                                    PlaceProblem.LOCATION_SERVICES_OFF -> openLocationSettings()
                                    PlaceProblem.RECEIVER_OFF -> { LiveAlerts.refreshReceiver(context); recheck += 1 }
                                    else -> Unit
                                }
                            },
                            Modifier.cell()
                        )
                    }
                    AppButton("Turn off", { switchOff() }, Modifier.cell(), style = ButtonStyle.OUTLINE)
                }
            }
        }
    }

    FintechCard {
        RowTitle("Spending map")
        Caption("Heat when zoomed out, price tags when zoomed in, from the places saved on this phone.", Modifier.padding(top = Spacing.xs), maxLines = 3)
        AppButton("Open the Spending map", { SpendMapRequests.show() }, Modifier.fillMaxWidth().padding(top = Spacing.md), style = ButtonStyle.TONAL)
    }

    if (!demo) {
        FintechCard {
            ToggleRow(
                title = "Map pictures",
                description = "From OpenStreetMap, only when the map is open",
                checked = s.mapPictures,
                onCheckedChange = { wanted -> if (wanted) picturesAsk = true else repo.update { it.copy(mapPictures = false) } }
            )
            Caption(
                "Map pictures come from OpenStreetMap; only the tile coordinates being viewed are requested. Off, the pins and heat still work on a blank grid.",
                Modifier.padding(top = Spacing.xs), maxLines = 5
            )
        }
        FintechCard {
            ToggleRow(
                title = "Place names",
                description = "Street or area, from the phone's geocoder",
                checked = s.geocodeNames,
                onCheckedChange = { wanted -> repo.update { it.copy(geocodeNames = wanted) } }
            )
            Caption(
                "The phone's own geocoder turns a position into a name. On some phones it asks a remote service (often Google's), which then sees the position. Turn this off to see coordinates instead.",
                Modifier.padding(top = Spacing.xs), maxLines = 6
            )
        }
        FintechCard {
            Text("Keep places for", Modifier.heading(), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Caption("Older places are removed on their own, and so is the place of a deleted message.", Modifier.padding(top = Spacing.xs, bottom = Spacing.sm), maxLines = 3)
            Chips(
                SpendPlaceSettings.KEEP_CHOICES.map { m -> ChipOption(if (m == 0) "Until I delete" else "$m months", m == s.keepMonths) },
                onSelect = { i -> repo.update { it.copy(keepMonths = SpendPlaceSettings.KEEP_CHOICES[i]) }; repo.prune(System.currentTimeMillis()) }
            )
        }
        FintechCard {
            ToggleRow(
                title = "Include places in my backup",
                description = "Off: places stay on this phone",
                checked = s.includeInBackup,
                onCheckedChange = { wanted -> repo.update { it.copy(includeInBackup = wanted) } }
            )
            Caption("A backup is encrypted with your passphrase. Places travel in it only if you turn this on.", Modifier.padding(top = Spacing.xs), maxLines = 3)
        }
        if (s.captureOverrides.isNotEmpty() || s.skipOverrides.isNotEmpty()) {
            FintechCard {
                RowTitle("Your choices for shops")
                Caption(
                    "${s.captureOverrides.size} shops always get a place, ${s.skipOverrides.size} never do. Online shops and subscriptions are skipped unless you say otherwise on a payment.",
                    Modifier.padding(top = Spacing.xs), maxLines = 4
                )
                AppButton("Forget these choices", { repo.update { it.copy(captureOverrides = emptySet(), skipOverrides = emptySet()) } }, Modifier.fillMaxWidth().padding(top = Spacing.md), style = ButtonStyle.OUTLINE, size = ButtonSize.COMPACT)
            }
        }
        FintechCard {
            RowTitle("Delete all places")
            Caption("Removes every saved place and every name you gave a spot. Payments and messages stay.", Modifier.padding(top = Spacing.xs), maxLines = 3)
            AppButton("Delete all places", { deleting = true }, Modifier.fillMaxWidth().padding(top = Spacing.md), style = ButtonStyle.DANGER, enabled = state.places.isNotEmpty() || state.names.isNotEmpty())
        }
    }

    if (explaining) {
        AdaptiveSheet("Remember where I spend", { explaining = false }) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Text("When a text says you paid at a shop, the app asks the phone where it is, one time, and saves it.", style = MaterialTheme.typography.bodyLarge)
                Caption("• Only on this phone. Never uploaded, never written to the log, left out of backups unless you choose.", maxLines = 4)
                Caption("• One position per purchase text, rounded to about 11 metres. No tracking in between.", maxLines = 4)
                Caption("• Texts arrive when the app is closed, so Android will ask for \"Allow all the time\". It is used for that one position only.", maxLines = 5)
                Caption("• Online shops, bills, card payments and money coming in are skipped. You can delete places any time.", maxLines = 5)
                ResponsiveRow(Modifier.fillMaxWidth(), spacing = Spacing.sm) {
                    AppButton("Not now", { explaining = false }, Modifier.cell(), style = ButtonStyle.OUTLINE)
                    AppButton("Continue", { explaining = false; switchOn() }, Modifier.cell())
                }
            }
        }
    }
    if (picturesAsk) MapPicturesSheet(onDismiss = { picturesAsk = false }, onTurnOn = { repo.update { it.copy(mapPictures = true) }; picturesAsk = false })
    if (deleting) {
        ConfirmDialog(
            title = "Delete all places?",
            body = "Every saved place and name is removed from this phone. This can't be undone.",
            confirmLabel = "Delete",
            onConfirm = { repo.clearAll(); deleting = false },
            onDismiss = { deleting = false }
        )
    }
}
