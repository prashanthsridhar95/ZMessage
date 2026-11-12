package com.zyke.message.presentation.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.ContactsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zyke.message.ZykeMessagesApplication
import com.zyke.message.data.demo.DemoMode
import com.zyke.message.domain.device.CodeSharingProblem
import com.zyke.message.domain.sharing.CodeSharing
import com.zyke.message.domain.sharing.ContactResult
import com.zyke.message.domain.sharing.PhoneNumbers
import com.zyke.message.domain.sharing.SeenCodes
import com.zyke.message.domain.sharing.SeenOutcome
import com.zyke.message.domain.sharing.ReceivedCodeSenders
import com.zyke.message.domain.sharing.ShareSource
import com.zyke.message.domain.sharing.ShareSources
import com.zyke.message.domain.sharing.ShareLogEntry
import com.zyke.message.domain.sharing.ShareStatus
import com.zyke.message.domain.sharing.ShareWindow
import com.zyke.message.domain.sharing.TrustedContact
import com.zyke.message.domain.sharing.TrustedContacts
import com.zyke.message.notifications.CodeSharer
import com.zyke.message.notifications.LiveAlerts
import com.zyke.message.presentation.common.AppButton
import com.zyke.message.presentation.common.AppPage
import com.zyke.message.presentation.common.ButtonSize
import com.zyke.message.presentation.common.ButtonStyle
import com.zyke.message.presentation.common.ChipOption
import com.zyke.message.presentation.common.Chips
import com.zyke.message.presentation.common.FintechCard
import com.zyke.message.presentation.common.FormField
import com.zyke.message.presentation.common.FormGroup
import com.zyke.message.presentation.common.FormSheet
import com.zyke.message.presentation.common.LocalAppSnackbar
import com.zyke.message.presentation.common.ResponsiveRow
import com.zyke.message.presentation.common.Text
import com.zyke.message.presentation.common.TimeField
import com.zyke.message.presentation.common.fullWidthClickable
import com.zyke.message.presentation.common.heading
import com.zyke.message.presentation.dashboard.components.SectionCard
import com.zyke.message.presentation.format.DisplayFormatters
import com.zyke.message.presentation.messages.components.Caption
import com.zyke.message.presentation.messages.components.RowTitle
import com.zyke.message.presentation.messages.components.mutedColor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.util.UUID

/** The one row in Reminders and alerts that opens "Share codes with trusted people". */
@Composable
internal fun CodeSharingEntry() {
    var open by rememberSaveable { mutableStateOf(false) }
    val container = (LocalContext.current.applicationContext as ZykeMessagesApplication).container
    val rules by container.codeSharing.rules.collectAsStateWithLifecycle()
    SectionCard("Share codes with trusted people") {
        Row(
            Modifier.fillMaxWidth().fullWidthClickable(role = Role.Button, onClickLabel = "Open share codes with trusted people") { open = true },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                RowTitle(if (DemoMode.active) "Not in Demo mode" else CodeSharing.summary(rules, System.currentTimeMillis()))
                Caption("Send a login code from any service you choose, such as Netflix, JioHotstar or Swiggy, on to family, so they can sign in while you are busy", maxLines = 4)
            }
            Text("›", color = mutedColor())
        }
    }
    if (open) CodeSharingPage { open = false }
}

/** The page: switch, people, pause, hours, health and the recent log. Everything is off until the person turns it on. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CodeSharingPage(onDismiss: () -> Unit) {
    AppPage("Share codes with trusted people", onDismiss) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (DemoMode.active) {
                FintechCard {
                    RowTitle("Not available in Demo mode")
                    Caption("Demo mode never sends a text and never reads your contacts. Leave Demo mode to set this up.", Modifier.padding(top = 4.dp), maxLines = 4)
                }
            } else {
                CodeSharingContent()
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CodeSharingContent() {
    val context = LocalContext.current
    val container = (context.applicationContext as ZykeMessagesApplication).container
    val repository = container.codeSharing
    val rules by repository.rules.collectAsStateWithLifecycle()
    val log by repository.log.collectAsStateWithLifecycle()
    val seen by repository.seen.collectAsStateWithLifecycle()
    val lastReceiverAt by repository.lastReceiverAt.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val snackbar = LocalAppSnackbar.current
    val scope = rememberCoroutineScope()

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }
    // Coming back from the system's permission page looks again.
    var recheck by remember { mutableLongStateOf(0L) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val watcher = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                recheck += 1
                now = System.currentTimeMillis()
            }
        }
        lifecycle.addObserver(watcher)
        onDispose { lifecycle.removeObserver(watcher) }
    }

    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    // 1: clearing the list of codes seen, 2: clearing the log of codes passed on.
    var clearing by rememberSaveable { mutableStateOf(0) }
    val haptics = com.zyke.message.presentation.motion.rememberHaptics()
    var testing by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingTest by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingCode by rememberSaveable { mutableStateOf(false) }
    var codeTesting by rememberSaveable { mutableStateOf<String?>(null) }
    var codeTestService by rememberSaveable { mutableStateOf<String?>(null) }
    // The seen row whose sender is being shared, and the people picked for it.
    var sharing by rememberSaveable { mutableStateOf<String?>(null) }
    var shareTargets by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    // The page-level picker ("Choose from codes you've received") and the person it is for.
    var picking by rememberSaveable { mutableStateOf(false) }
    var pickFor by rememberSaveable { mutableStateOf<String?>(null) }
    var renaming by rememberSaveable { mutableStateOf<String?>(null) }
    var renameText by rememberSaveable { mutableStateOf("") }
    var removing by rememberSaveable { mutableStateOf<String?>(null) }

    fun openAppSettings() {
        runCatching {
            context.startActivity(
                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", context.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
    fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    fun turnOn() {
        repository.setEnabled(true)
        LiveAlerts.refreshReceiver(context)
        CodeSharer.ensureChannel(context)
    }
    fun say(text: String) {
        scope.launch { snackbar.showSnackbar(text, duration = SnackbarDuration.Short) }
    }

    val needed = buildList {
        add(Manifest.permission.RECEIVE_SMS)
        add(Manifest.permission.SEND_SMS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()
    val askToTurnOn = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        recheck += 1
        if (granted(Manifest.permission.RECEIVE_SMS) && granted(Manifest.permission.SEND_SMS)) turnOn()
        else {
            say("Sharing needs permission to receive and send texts")
            // After a refusal or two Android stops asking; its settings page is the way left.
            openAppSettings()
        }
    }
    val askToFix = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        recheck += 1
        if (!(granted(Manifest.permission.RECEIVE_SMS) && granted(Manifest.permission.SEND_SMS))) openAppSettings()
    }
    val askToTest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        recheck += 1
        if (ok) { if (pendingCode) codeTesting = pendingTest else testing = pendingTest } else openAppSettings()
        pendingTest = null
        pendingCode = false
    }

    // ---- what it is, and the care it needs
    FintechCard {
        RowTitle("Pass a login code on, automatically")
        Caption(
            "When a login code from a sender you chose arrives on this phone, it is texted to the people you choose, so they can sign in while you are asleep or busy.",
            Modifier.padding(top = 4.dp), maxLines = 6
        )
    }
    FintechCard {
        RowTitle("Only add people you trust", color = MaterialTheme.colorScheme.error)
        Caption(
            "Anyone who gets a code can sign in to that account while it works. Only the code and the service name are sent, never the message. " +
                "Bank, UPI, card, payment, government, insurance and phone-company codes are never sent. " +
                "The texts go from your SIM and your mobile plan may charge for them.",
            Modifier.padding(top = 4.dp), maxLines = 10
        )
        SeeWhatIsSentLink()
    }

    FintechCard {
        RowTitle("How it works")
        Caption(
            "Choose any sender from the codes you've received. Bank and payment codes are never shared. " +
                "Some services send codes by email or in-app instead of SMS, which Zyke can't see.",
            Modifier.padding(top = 4.dp), maxLines = 6
        )
    }

    // ---- the switch
    FintechCard {
        ToggleRow(
            title = "Share codes",
            description = "Off until you switch it on. Asks to receive and send texts.",
            checked = rules.enabled,
            onCheckedChange = { wanted ->
                when {
                    !wanted -> {
                        repository.setEnabled(false)
                        LiveAlerts.refreshReceiver(context)
                    }
                    granted(Manifest.permission.RECEIVE_SMS) && granted(Manifest.permission.SEND_SMS) -> turnOn()
                    else -> askToTurnOn.launch(needed)
                }
            }
        )
    }

    // ---- is it going to work?
    if (rules.enabled) {
        val facts = remember(rules, settings.readingPaused, recheck, now) { CodeSharer.facts(context, container, now) }
        val problem = com.zyke.message.domain.device.CodeSharingHealth.problem(facts)
        val title: String
        val detail: String
        val action: String?
        val run: () -> Unit
        when (problem) {
            null -> {
                title = "Ready"
                detail = "A code from a sender you chose will be sent to ${rules.activePeople} ${if (rules.activePeople == 1) "person" else "people"}, within a few minutes of arriving."
                action = null; run = {}
            }
            CodeSharingProblem.SWITCHED_OFF -> { title = "Off"; detail = ""; action = null; run = {} }
            CodeSharingProblem.NOBODY_ADDED -> { title = "Add someone"; detail = "Nothing is sent until you add at least one person."; action = null; run = {} }
            CodeSharingProblem.READING_PAUSED -> {
                title = "Paused: reading is paused"; detail = "Reading messages is paused in Privacy and security, so new codes are not seen."
                action = "Resume reading"; run = { container.settings.setReadingPaused(false); container.smsRepository.refreshRecent() }
            }
            CodeSharingProblem.PAUSED -> {
                title = "Off for now"; detail = "Paused until ${DisplayFormatters.time(rules.pausedUntil)}."
                action = "Resume sharing"; run = { repository.setPausedUntil(0L) }
            }
            CodeSharingProblem.CANNOT_HEAR_TEXTS -> {
                title = "Cannot hear new texts"; detail = "Allow SMS access so a code can be seen the moment it arrives."
                action = "Allow SMS access"; run = { askToFix.launch(needed) }
            }
            CodeSharingProblem.CANNOT_SEND_TEXTS -> {
                title = "Cannot send texts"; detail = "Allow sending texts so a code can be passed on."
                action = "Allow sending texts"; run = { askToFix.launch(needed) }
            }
            CodeSharingProblem.RECEIVER_OFF -> {
                title = "Not listening yet"; detail = "The part that hears new texts is switched off."
                action = "Fix"; run = { LiveAlerts.refreshReceiver(context); recheck += 1 }
            }
        }
        FintechCard {
            RowTitle(title, color = if (problem == null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
            if (detail.isNotEmpty()) Caption(detail, Modifier.padding(top = 4.dp), maxLines = 4)
            Caption(
                if (lastReceiverAt > 0L) "Last text seen by the receiver: " + DisplayFormatters.listStamp(lastReceiverAt)
                else "No text has reached the receiver yet since you switched this on",
                Modifier.padding(top = 4.dp), maxLines = 3
            )
            if (action != null) AppButton(action, run, Modifier.fillMaxWidth().padding(top = 12.dp))
        }
    }

    // ---- people
    Text("People", Modifier.padding(start = 4.dp, top = 8.dp).heading(), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
    if (rules.contacts.isEmpty()) {
        FintechCard {
            RowTitle("Nobody added yet", color = MaterialTheme.colorScheme.onSurface)
            Caption("Add the people who share your account. You choose which senders each one gets.", Modifier.padding(top = 4.dp), maxLines = 3)
        }
    }
    rules.contacts.forEach { person ->
        androidx.compose.runtime.key(person.id) {
            FintechCard {
                ToggleRow(
                    title = person.name,
                    description = PhoneNumbers.mask(person.number) + if (person.enabled) "" else " · off",
                    checked = person.enabled,
                    onCheckedChange = { repository.setContactEnabled(person.id, it) }
                )
                val labels = ShareSources.all(rules.sources).filter { it.id in person.services }.map { it.label }
                val perRow = evenRowSize(labels.size)
                SeenCodes.noServicesWarning(person, rules.sources)?.let { warning ->
                    Caption(warning, Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.error, maxLines = 3)
                }
                FlowRow(
                    Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                    maxItemsInEachRow = perRow
                ) { labels.forEach { ServiceTag(it) } }
                ResponsiveRow(Modifier.fillMaxWidth().padding(top = 12.dp), spacing = 8.dp) {
                    AppButton("Edit", { editing = person.id }, Modifier.cell(), style = ButtonStyle.OUTLINE, size = ButtonSize.COMPACT)
                    AppButton(
                        "Test message",
                        { if (granted(Manifest.permission.SEND_SMS)) testing = person.id else { pendingTest = person.id; pendingCode = false; askToTest.launch(Manifest.permission.SEND_SMS) } },
                        Modifier.cell(), style = ButtonStyle.OUTLINE, size = ButtonSize.COMPACT
                    )
                }
                AppButton(
                    "Send a test code",
                    {
                        codeTestService = ShareSources.all(rules.sources).firstOrNull { it.id in person.services && !it.isPhone }?.id
                        if (granted(Manifest.permission.SEND_SMS)) codeTesting = person.id else { pendingTest = person.id; pendingCode = true; askToTest.launch(Manifest.permission.SEND_SMS) }
                    },
                    Modifier.fillMaxWidth().padding(top = 8.dp), style = ButtonStyle.OUTLINE, size = ButtonSize.COMPACT,
                    enabled = SeenCodes.noServicesWarning(person, rules.sources) == null && ShareSources.all(rules.sources).any { it.id in person.services && !it.isPhone }
                )
            }
        }
    }
    AppButton(
        if (rules.contacts.isEmpty()) "Add a person" else "Add another person",
        { editing = NEW },
        Modifier.fillMaxWidth(),
        style = if (rules.contacts.isEmpty()) ButtonStyle.PRIMARY else ButtonStyle.OUTLINE,
        enabled = rules.contacts.size < TrustedContacts.MAX_PEOPLE
    )

    // ---- senders chosen from the codes received
    Text("Senders", Modifier.padding(start = 4.dp, top = 8.dp).heading(), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
    FintechCard {
        RowTitle("Choose any sender")
        Caption(
            "Pick from the senders that texted you a login code in the last 90 days. Bank and payment senders are listed but can't be chosen.",
            Modifier.padding(top = 4.dp), maxLines = 4
        )
        if (rules.contacts.isEmpty()) {
            Caption("Add a person first, then choose what they get.", Modifier.padding(top = 8.dp), maxLines = 2)
        }
        AppButton(
            "Choose from codes you've received",
            { pickFor = rules.contacts.firstOrNull { it.enabled }?.id ?: rules.contacts.firstOrNull()?.id; picking = true },
            Modifier.fillMaxWidth().padding(top = 12.dp), style = ButtonStyle.OUTLINE, enabled = rules.contacts.isNotEmpty()
        )
        if (rules.sources.isNotEmpty()) {
            Caption("Your senders", Modifier.padding(top = 16.dp), maxLines = 1)
            rules.sources.sortedBy { it.label.lowercase() }.forEach { source ->
                androidx.compose.runtime.key(source.id) {
                    val people = rules.contacts.count { source.id in it.services }
                    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                        RowTitle(source.label)
                        Caption(
                            (if (source.isPhone) "A phone number" else source.header.ifEmpty { source.key }) + " \u00b7 " +
                                if (people == 0) "nobody yet" else "$people ${if (people == 1) "person" else "people"}",
                            maxLines = 2
                        )
                        ResponsiveRow(Modifier.fillMaxWidth().padding(top = 4.dp), spacing = 8.dp) {
                            AppButton("Rename", { renameText = source.label; renaming = source.id }, Modifier.cell(), style = ButtonStyle.TEXT, size = ButtonSize.COMPACT)
                            AppButton("Remove", { removing = source.id }, Modifier.cell(), style = ButtonStyle.TEXT, size = ButtonSize.COMPACT)
                        }
                    }
                }
            }
        }
    }

    // ---- pause, hours, name
    if (rules.enabled) {
        FintechCard {
            RowTitle("Quick pause", color = MaterialTheme.colorScheme.onSurface)
            val paused = CodeSharing.isPaused(rules, now)
            Caption(
                if (paused) "Off until ${DisplayFormatters.time(rules.pausedUntil)}. Codes that arrive meanwhile are not sent later."
                else "Switch sharing off for a while, then it carries on by itself.",
                Modifier.padding(top = 4.dp), maxLines = 3
            )
            Chips(
                CodeSharing.pauseHours.map { ChipOption("Off for ${if (it == 1) "1 hour" else "$it hours"}", false) } +
                    (if (paused) listOf(ChipOption("Resume now", false)) else emptyList()),
                onSelect = { index ->
                    val hours = CodeSharing.pauseHours.getOrNull(index)
                    if (hours != null) repository.setPausedUntil(CodeSharing.pausedUntil(System.currentTimeMillis(), hours)) else repository.setPausedUntil(0L)
                    now = System.currentTimeMillis()
                },
                modifier = Modifier.padding(top = 12.dp)
            )
        }
        FintechCard {
            val window = rules.window
            ToggleRow(
                title = "Only at certain hours",
                description = if (window == null) "Any time of day" else "Only from ${window.label()}",
                checked = window != null,
                onCheckedChange = { on -> repository.setWindow(if (on) ShareWindow(22 * 60, 8 * 60) else null) }
            )
            if (window != null) {
                ResponsiveRow(Modifier.fillMaxWidth().padding(top = 12.dp), spacing = 12.dp) {
                    TimeField("From", LocalTime.of(window.startMinute / 60, window.startMinute % 60), { picked ->
                        if (picked != null) repository.setWindow(ShareWindow(picked.hour * 60 + picked.minute, window.endMinute))
                    }, Modifier.cell())
                    TimeField("Until", LocalTime.of(window.endMinute / 60, window.endMinute % 60), { picked ->
                        if (picked != null) repository.setWindow(ShareWindow(window.startMinute, picked.hour * 60 + picked.minute))
                    }, Modifier.cell())
                }
                Caption("A window can run past midnight, like 10:00 PM to 8:00 AM.", Modifier.padding(top = 8.dp), maxLines = 2)
            }
        }
        FintechCard {
            var name by rememberSaveable { mutableStateOf(rules.senderName) }
            FormField(
                "Your name in the message", name, { name = it; repository.setSenderName(it) },
                placeholder = "Zyke",
                supporting = "They see: “" + sampleText(name) + "”",
                capitalization = KeyboardCapitalization.Words
            )
        }
    }

    // ---- recent codes seen: why was it (not) sent?
    Text("Recent codes seen", Modifier.padding(start = 4.dp, top = 8.dp).heading(), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
    FintechCard {
        RowTitle("Why wasn't it sent?")
        Caption(
            "Each code-like text Zyke heard, with its sender name, the service it was taken for and what happened. Never the code or the message.",
            Modifier.padding(top = 4.dp), maxLines = 4
        )
        if (seen.isEmpty()) {
            Caption("Nothing yet. After the next login code arrives, it is listed here.", Modifier.padding(top = 12.dp), maxLines = 3)
        } else {
            seen.forEachIndexed { index, entry ->
                androidx.compose.runtime.key(entry.id) {
                    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                        Text(
                            entry.header.ifEmpty { "(no sender)" } + " · " + SeenCodes.serviceLabel(entry, rules.sources),
                            style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 2
                        )
                        Caption(
                            DisplayFormatters.listStamp(entry.at) + " · " + SeenCodes.reason(entry, rules.sources),
                            color = if (entry.outcome == SeenOutcome.SEND_FAILED) MaterialTheme.colorScheme.error else mutedColor(),
                            maxLines = 4
                        )
                        ResponsiveRow(Modifier.fillMaxWidth().padding(top = 4.dp), spacing = 8.dp) {
                            if (entry.header.isNotEmpty() && !entry.header.startsWith("•")) {
                                AppButton("Copy sender", {
                                    runCatching {
                                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Sender", entry.header))
                                    }
                                    say("Copied ${entry.header}")
                                }, Modifier.cell(), style = ButtonStyle.TEXT, size = ButtonSize.COMPACT)
                            }
                            if (SeenCodes.shareable(entry, rules.sources) != null) {
                                AppButton("Share this sender's codes", { shareTargets = emptyList(); sharing = entry.id }, Modifier.cell(), style = ButtonStyle.OUTLINE, size = ButtonSize.COMPACT)
                            }
                        }
                    }
                }
            }
            AppButton(
                "Clear this list", { clearing = 1 },
                Modifier.fillMaxWidth().padding(top = 12.dp), style = ButtonStyle.TEXT, size = ButtonSize.COMPACT
            )
        }
    }
    // ---- recent log
    Text("Recent", Modifier.padding(start = 4.dp, top = 8.dp).heading(), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
    FintechCard {
        if (log.isEmpty()) {
            Caption("Nothing sent yet. Each code you pass on is listed here, never the code itself.", maxLines = 2)
        } else {
            log.take(MAX_LOG_SHOWN).forEachIndexed { index, entry ->
                androidx.compose.runtime.key(entry.id) {
                    Column(Modifier.fillMaxWidth().padding(top = if (index == 0) 0.dp else 12.dp)) {
                        Text(logLine(entry), style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                        val status = statusLabel(entry, now)
                        Caption(
                            DisplayFormatters.listStamp(entry.at) + " · " + status,
                            color = if (entry.status == ShareStatus.FAILED) MaterialTheme.colorScheme.error else mutedColor(),
                            maxLines = 2
                        )
                    }
                }
            }
            AppButton(
                "Clear the log", { clearing = 2 },
                Modifier.fillMaxWidth().padding(top = 12.dp), style = ButtonStyle.TEXT, size = ButtonSize.COMPACT
            )
        }
    }

    // ---- sheets and dialogs
    if (clearing != 0) {
        val seenList = clearing == 1
        com.zyke.message.presentation.common.ConfirmDialog(
            title = if (seenList) "Clear the list of texts seen?" else "Clear the log of codes passed on?",
            body = if (seenList) "The senders listed here are forgotten, and with them the one-tap way to share one. Senders you already chose stay."
            else "Every line of what was passed on, to whom and when, is removed. Nothing is sent again.",
            confirmLabel = "Clear",
            onConfirm = { if (seenList) repository.clearSeen() else repository.clearLog(); clearing = 0 },
            onDismiss = { clearing = 0 }
        )
    }
    editing?.let { id ->
        val initial = rules.contacts.firstOrNull { it.id == id }
        if (id == NEW || initial != null) {
            PersonSheet(
                initial = initial,
                everyone = rules.contacts,
                sources = rules.sources,
                now = now,
                onDismiss = { editing = null },
                onSave = { contact, created ->
                    created.forEach { repository.addSource(it) }
                    repository.saveContact(contact)
                    editing = null
                },
                onRemove = if (initial == null) null else {
                    {
                        repository.removeContact(initial.id)
                        editing = null
                        undoRemove(scope, snackbar, initial) { repository.saveContact(initial) }
                    }
                }
            )
        } else editing = null
    }
    sharing?.let { id ->
        val row = seen.firstOrNull { it.id == id }
        val candidate = row?.let { SeenCodes.shareable(it, rules.sources) }
        if (row == null || candidate == null) sharing = null else {
            val chosen = rules.contacts.filter { it.id in shareTargets }
            val problem = ShareSources.problem(candidate.key, candidate.label, rules.sources)
            AlertDialog(
                onDismissRequest = { sharing = null },
                title = { Text("Share ${candidate.label} codes?") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            if (rules.contacts.isEmpty()) "Add a person first, then choose what they get."
                            else if (problem != null) ShareSources.explain(problem)
                            else "A login code from \u201c${row.header}\u201d will be texted to the people you tick, as \u201c${candidate.label} code\u201d: " +
                                "only the code and the name, never the message. Bank and payment texts are still never sent."
                        )
                        rules.contacts.forEach { person ->
                            androidx.compose.runtime.key(person.id) {
                                ServiceChoice(
                                    person.name, person.id in shareTargets,
                                    { on -> shareTargets = if (on) (shareTargets + person.id).distinct() else shareTargets - person.id },
                                    Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    AppButton("Share with ${chosen.size}", {
                        if (problem == null && chosen.isNotEmpty()) {
                            repository.addSource(candidate)
                            chosen.forEach { repository.saveContact(it.copy(services = it.services + candidate.id)) }
                            say("${candidate.label} codes will go to ${chosen.joinToString { it.name }}")
                        }
                        sharing = null
                    }, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT, enabled = problem == null && chosen.isNotEmpty())
                },
                dismissButton = { AppButton("Cancel", { sharing = null }, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT) }
            )
        }
    }
    renaming?.let { id ->
        val source = rules.sources.firstOrNull { it.id == id }
        if (source == null) renaming = null else {
            AlertDialog(
                onDismissRequest = { renaming = null },
                title = { Text("Rename ${source.label}") },
                text = {
                    FormField(
                        "Name in the text", renameText, { renameText = it }, placeholder = "JioHotstar",
                        supporting = "They see: \u201c" + CodeSharing.text(source.copy(label = ShareSources.cleanLabel(renameText).ifEmpty { source.label }), "482913", rules.senderName) + "\u201d",
                        capitalization = KeyboardCapitalization.Words
                    )
                },
                confirmButton = {
                    AppButton("Save", { repository.renameSource(id, renameText); renaming = null }, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT, enabled = ShareSources.cleanLabel(renameText).isNotEmpty())
                },
                dismissButton = { AppButton("Cancel", { renaming = null }, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT) }
            )
        }
    }
    removing?.let { id ->
        val source = rules.sources.firstOrNull { it.id == id }
        if (source == null) removing = null else {
            val people = rules.contacts.count { id in it.services }
            com.zyke.message.presentation.common.ConfirmDialog(
                title = "Remove ${source.label}?",
                body = if (people == 0) "It leaves your list of senders. Nothing is sent for it."
                else "It stops being sent to $people ${if (people == 1) "person" else "people"}. Their other senders stay.",
                confirmLabel = "Remove",
                onConfirm = { haptics.reject(); repository.removeSource(id); removing = null },
                onDismiss = { removing = null }
            )
        }
    }
    if (picking) {
        val person = rules.contacts.firstOrNull { it.id == pickFor } ?: rules.contacts.firstOrNull()
        if (person == null) picking = false else {
            val received by rememberReceivedSenders(true, rules.sources)
            ReceivedSendersSheet(
                title = "Choose from codes you've received",
                subtitle = "For ${person.name}",
                senders = received,
                selected = person.services,
                now = now,
                onToggle = { sender, on ->
                    val source = ReceivedCodeSenders.sourceFor(sender, rules.sources)
                    if (source != null) {
                        if (on) repository.addSource(source)
                        repository.saveContact(person.copy(services = if (on) person.services + source.id else person.services - source.id))
                    }
                },
                onDismiss = { picking = false },
                header = if (rules.contacts.size < 2) null else {
                    {
                        Chips(rules.contacts.map { ChipOption(it.name, it.id == person.id) }, onSelect = { index -> pickFor = rules.contacts[index].id })
                    }
                }
            )
        }
    }
    codeTesting?.let { id ->
        val person = rules.contacts.firstOrNull { it.id == id }
        if (person == null) codeTesting = null else {
            val choices = ShareSources.all(rules.sources).filter { it.id in person.services && !it.isPhone }
            val chosen = codeTestService ?: choices.firstOrNull()?.id
            AlertDialog(
                onDismissRequest = { codeTesting = null },
                title = { Text("Send a test code to ${person.name}?") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Chips(choices.map { ChipOption(it.label, it.id == chosen) }, onSelect = { index -> codeTestService = choices[index].id })
                        Text(
                            "A made-up code goes through the same steps as a real one and is texted from your SIM to ${PhoneNumbers.mask(person.number)}: " +
                                "\u201cZyke TEST - ${ShareSources.find(chosen.orEmpty(), rules.sources)?.label ?: "Netflix"} code ${CodeSharing.TEST_CODE}\u201d. " +
                                "It follows your pause, hours and switches. Your mobile plan may charge for it."
                        )
                    }
                },
                confirmButton = {
                    AppButton("Send test code", {
                        val service = chosen
                        codeTesting = null
                        if (service != null) {
                            scope.launch(Dispatchers.Default) { say(CodeSharer.sendTestCode(context, container, person, service)) }
                        }
                    }, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT, enabled = chosen != null)
                },
                dismissButton = { AppButton("Cancel", { codeTesting = null }, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT) }
            )
        }
    }
    testing?.let { id ->
        val person = rules.contacts.firstOrNull { it.id == id }
        if (person == null) testing = null else {
            AlertDialog(
                onDismissRequest = { testing = null },
                title = { Text("Send a test message to ${person.name}?") },
                text = {
                    Text(
                        "This sends a real text from your SIM to ${PhoneNumbers.mask(person.number)}: “${CodeSharing.TEST_TEXT}”. " +
                            "It holds no code. Your mobile plan may charge for it."
                    )
                },
                confirmButton = {
                    AppButton("Send test", {
                        testing = null
                        scope.launch(Dispatchers.Default) {
                            val result = CodeSharer.sendTest(context, container, person)
                            say(
                                when (result) {
                                    CodeSharer.TestResult.QUEUED -> "Test message sent to ${person.name}"
                                    CodeSharer.TestResult.TOO_SOON -> "A test was just sent: wait a minute"
                                    CodeSharer.TestResult.NO_PERMISSION -> "Allow sending texts first"
                                    CodeSharer.TestResult.FAILED -> "Couldn't send the test message"
                                }
                            )
                        }
                    }, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT)
                },
                dismissButton = { AppButton("Cancel", { testing = null }, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT) }
            )
        }
    }
}

private const val NEW = "new"
private const val MAX_LOG_SHOWN = 12

/** "Removed Sister" with an Undo; the person comes back as they were. */
private fun undoRemove(scope: CoroutineScope, snackbar: androidx.compose.material3.SnackbarHostState, person: TrustedContact, restore: () -> Unit) {
    scope.launch {
        val result = snackbar.showSnackbar("Removed ${person.name}", actionLabel = "Undo", duration = SnackbarDuration.Long)
        if (result == SnackbarResult.ActionPerformed) restore()
    }
}

/** How many tags go in a row so wrapping is even (4 become 2 and 2, never 3 and 1). */
internal fun evenRowSize(count: Int): Int = when {
    count <= 3 -> count.coerceAtLeast(1)
    else -> { val rows = (count + 2) / 3; (count + rows - 1) / rows }
}

private fun sampleText(name: String): String =
    CodeSharing.text(ShareSources.builtIns.first(), "482913", name)

private fun logLine(entry: ShareLogEntry): String = com.zyke.message.domain.sharing.ShareLog.headline(entry)

/** A text still "sending" long after is one the phone never reported on. */
private fun statusLabel(entry: ShareLogEntry, now: Long): String =
    if (entry.status == ShareStatus.SENDING && now - entry.at > 10 * 60_000L) "No report from the phone" else entry.status.label

/** A service name, small and read-only. */
@Composable
private fun ServiceTag(label: String) {
    Text(
        label,
        Modifier.clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)).padding(horizontal = 10.dp, vertical = 6.dp),
        style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary, maxLines = 1
    )
}

/** A sender to tick or untick in the add sheet; ticked ones are filled and carry a tick, so it reads without colour. Always 48dp tall. */
@Composable
private fun ServiceChoice(label: String, selected: Boolean, onToggle: (Boolean) -> Unit, modifier: Modifier = Modifier, onRemove: (() -> Unit)? = null) {
    val haptics = com.zyke.message.presentation.motion.rememberHaptics()
    val fill = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)
    val ink = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
    Row(
        modifier.height(48.dp).clip(RoundedCornerShape(12.dp)).background(fill)
            .toggleable(value = selected, role = Role.Checkbox, onValueChange = { haptics.tick(); onToggle(it) }).padding(start = 12.dp, end = if (onRemove != null && selected) 0.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (selected) Text("\u2713 ", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = ink)
        Text(
            label, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium, color = ink, maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
        )
        if (onRemove != null && selected) {
            Box(
                Modifier.size(48.dp).clickable(role = Role.Button, onClickLabel = "Remove $label from this person") { haptics.tick(); onRemove() },
                contentAlignment = Alignment.Center
            ) { Text("\u2715", style = MaterialTheme.typography.labelLarge, color = ink) }
        }
    }
}

/**
 * Add or edit one person. The contact picker is the system's own (ACTION_PICK on phone numbers), which hands over only the
 * row picked, so no contacts permission is asked for; a number can also be typed. Only the name and the number are kept.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PersonSheet(
    initial: TrustedContact?,
    everyone: List<TrustedContact>,
    sources: List<ShareSource>,
    now: Long,
    onDismiss: () -> Unit,
    onSave: (TrustedContact, List<ShareSource>) -> Unit,
    onRemove: (() -> Unit)?
) {
    val context = LocalContext.current
    var name by rememberSaveable { mutableStateOf(initial?.name.orEmpty()) }
    var number by rememberSaveable { mutableStateOf(initial?.number?.let(PhoneNumbers::pretty).orEmpty()) }
    var services by rememberSaveable { mutableStateOf(initial?.services?.sorted().orEmpty()) }
    var id by rememberSaveable { mutableStateOf(initial?.id ?: UUID.randomUUID().toString()) }
    // Senders picked from the codes received but not saved yet: they join the list when the person is saved, so cancelling leaves nothing behind.
    var pendingRaw by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var picking by rememberSaveable { mutableStateOf(false) }
    val pending = pendingRaw.mapNotNull(::decodeSource)
    val library = (sources + pending.filter { p -> sources.none { it.id == p.id } })

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data ?: return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.query(
                uri, arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val pickedName = cursor.getString(0).orEmpty()
                    val pickedNumber = cursor.getString(1).orEmpty()
                    if (name.isBlank()) name = TrustedContacts.cleanName(pickedName)
                    number = PhoneNumbers.normalise(pickedNumber)?.let(PhoneNumbers::pretty) ?: pickedNumber
                }
            }
        }
    }

    val built = TrustedContacts.build(id, name, number, services.toSet(), everyone, enabled = initial?.enabled ?: true, sources = library)
    val numberError = if (number.isNotBlank() && PhoneNumbers.normalise(number) == null) TrustedContacts.explain(com.zyke.message.domain.sharing.ContactProblem.NUMBER_INVALID) else null
    val duplicate = (built as? ContactResult.Bad)?.problem == com.zyke.message.domain.sharing.ContactProblem.DUPLICATE_NUMBER
    val normalised = PhoneNumbers.normalise(number)

    FormSheet(
        title = if (initial == null) "Add a person" else "Edit ${initial.name}",
        onDismiss = onDismiss,
        primaryLabel = if (initial == null) "Add person" else "Save",
        primaryEnabled = built is ContactResult.Ok,
        onPrimary = { (built as? ContactResult.Ok)?.let { ok -> onSave(ok.contact, pending.filter { p -> p.id in ok.contact.services && sources.none { it.id == p.id } }) } },
        secondaryLabel = if (onRemove != null) "Remove this person" else null,
        onSecondary = onRemove,
        hint = (built as? ContactResult.Bad)?.let { TrustedContacts.explain(it.problem) }
    ) {
        AppButton(
            "Choose from contacts",
            {
                runCatching { picker.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)) }
                    .onFailure { Toast.makeText(context, "No contacts app found. Type the number instead.", Toast.LENGTH_SHORT).show() }
            },
            Modifier.fillMaxWidth(), style = ButtonStyle.OUTLINE
        )
        FormField("Name", name, { name = it }, placeholder = "Sister", capitalization = KeyboardCapitalization.Words)
        FormField(
            "Mobile number", number, { number = it }, placeholder = "98765 43210", keyboardType = KeyboardType.Phone,
            imeAction = androidx.compose.ui.text.input.ImeAction.Done,
            error = numberError ?: if (duplicate) TrustedContacts.explain(com.zyke.message.domain.sharing.ContactProblem.DUPLICATE_NUMBER) else null,
            supporting = if (normalised != null) "Texts go to ${PhoneNumbers.pretty(normalised)}" else "10 digits, or with + and the country code"
        )
        FormGroup("Which senders may they get?") {
            val builtIns = ShareSources.builtIns
            val customs = ShareSources.all(library).filter { !it.builtIn }
            val every = (builtIns + customs).map { it.id }
            FlowRow(
                Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                maxItemsInEachRow = 2
            ) {
                (builtIns + customs).forEach { source ->
                    androidx.compose.runtime.key(source.id) {
                        ServiceChoice(
                            source.label, source.id in services,
                            { on -> services = if (on) (services + source.id).distinct().sorted() else services - source.id },
                            Modifier.weight(1f),
                            onRemove = if (source.builtIn) null else ({ services = services - source.id })
                        )
                    }
                }
            }
            AppButton(
                "Choose from codes you've received", { picking = true },
                Modifier.fillMaxWidth().padding(top = 4.dp), style = ButtonStyle.OUTLINE, size = ButtonSize.COMPACT
            )
            ResponsiveRow(Modifier.fillMaxWidth().padding(top = 4.dp), spacing = 8.dp) {
                AppButton("Select all", { services = every.sorted() }, Modifier.cell(), style = ButtonStyle.OUTLINE, size = ButtonSize.COMPACT)
                AppButton("Clear", { services = emptyList() }, Modifier.cell(), style = ButtonStyle.OUTLINE, size = ButtonSize.COMPACT)
            }
            Caption("Hotstar covers Disney+ Hotstar and JioHotstar. Prime Video covers Amazon Prime.", Modifier.padding(top = 4.dp), maxLines = 3)
        }
    }
    if (picking) {
        val received by rememberReceivedSenders(true, library)
        ReceivedSendersSheet(
            title = "Choose from codes you've received",
            subtitle = null,
            senders = received,
            selected = services.toSet(),
            now = now,
            onToggle = { sender, on ->
                val source = ReceivedCodeSenders.sourceFor(sender, library)
                if (source != null) {
                    if (on && library.none { it.id == source.id }) pendingRaw = pendingRaw + encodeSource(source)
                    services = if (on) (services + source.id).distinct().sorted() else services - source.id
                }
            },
            onDismiss = { picking = false }
        )
    }
}
