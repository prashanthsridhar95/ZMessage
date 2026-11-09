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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import com.zyke.message.domain.sharing.ShareLogEntry
import com.zyke.message.domain.sharing.ShareServices
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
                Caption("Send a Netflix, Hotstar, Prime Video, Sun NXT, Sony LIV or ZEE5 login code on to family, so they can sign in while you are busy", maxLines = 4)
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
    var testing by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingTest by rememberSaveable { mutableStateOf<String?>(null) }

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
        if (ok) testing = pendingTest else openAppSettings()
        pendingTest = null
    }

    // ---- what it is, and the care it needs
    FintechCard {
        RowTitle("Pass a login code on, automatically")
        Caption(
            "When a login code for Netflix, Hotstar, Prime Video, Sun NXT, Sony LIV or ZEE5 arrives on this phone, it is texted to the people you choose, so they can sign in while you are asleep or busy.",
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
                detail = "A code from an allowed service will be sent to ${rules.activePeople} ${if (rules.activePeople == 1) "person" else "people"}, within a few minutes of arriving."
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
            if (action != null) AppButton(action, run, Modifier.fillMaxWidth().padding(top = 12.dp))
        }
    }

    // ---- people
    Text("People", Modifier.padding(start = 4.dp, top = 8.dp).heading(), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
    if (rules.contacts.isEmpty()) {
        FintechCard {
            RowTitle("Nobody added yet", color = MaterialTheme.colorScheme.onSurface)
            Caption("Add the people who share your account. You choose which services each one is sent.", Modifier.padding(top = 4.dp), maxLines = 3)
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
                val labels = ShareServices.all.filter { it.id in person.services }.map { it.label }
                val perRow = evenRowSize(labels.size)
                FlowRow(
                    Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                    maxItemsInEachRow = perRow
                ) { labels.forEach { ServiceTag(it) } }
                ResponsiveRow(Modifier.fillMaxWidth().padding(top = 12.dp), spacing = 8.dp) {
                    AppButton("Edit", { editing = person.id }, Modifier.cell(), style = ButtonStyle.OUTLINE, size = ButtonSize.COMPACT)
                    AppButton(
                        "Test message",
                        { if (granted(Manifest.permission.SEND_SMS)) testing = person.id else { pendingTest = person.id; askToTest.launch(Manifest.permission.SEND_SMS) } },
                        Modifier.cell(), style = ButtonStyle.OUTLINE, size = ButtonSize.COMPACT
                    )
                }
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

    // ---- recent log
    Text("Recent", Modifier.padding(start = 4.dp, top = 8.dp).heading(), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
    FintechCard {
        if (log.isEmpty()) {
            Caption("Nothing sent yet. Each code you pass on is listed here with the service, the person and the time, never the code.", maxLines = 4)
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
                "Clear the log", { repository.clearLog() },
                Modifier.fillMaxWidth().padding(top = 12.dp), style = ButtonStyle.TEXT, size = ButtonSize.COMPACT
            )
        }
    }

    // ---- sheets and dialogs
    editing?.let { id ->
        val initial = rules.contacts.firstOrNull { it.id == id }
        if (id == NEW || initial != null) {
            PersonSheet(
                initial = initial,
                everyone = rules.contacts,
                onDismiss = { editing = null },
                onSave = { contact -> repository.saveContact(contact); editing = null },
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
    CodeSharing.text(ShareServices.all.first(), "482913", name)

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

/** A service to tick or untick in the add sheet; ticked ones are filled and carry a tick, so it reads without colour. */
@Composable
private fun ServiceChoice(label: String, selected: Boolean, onToggle: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val fill = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)
    val ink = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
    Row(
        modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).background(fill)
            .toggleable(value = selected, role = Role.Checkbox, onValueChange = onToggle).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (selected) Text("✓ ", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = ink)
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium, color = ink, maxLines = 2)
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
    onDismiss: () -> Unit,
    onSave: (TrustedContact) -> Unit,
    onRemove: (() -> Unit)?
) {
    val context = LocalContext.current
    var name by rememberSaveable { mutableStateOf(initial?.name.orEmpty()) }
    var number by rememberSaveable { mutableStateOf(initial?.number?.let(PhoneNumbers::pretty).orEmpty()) }
    var services by rememberSaveable { mutableStateOf(initial?.services?.sorted().orEmpty()) }
    var id by rememberSaveable { mutableStateOf(initial?.id ?: UUID.randomUUID().toString()) }

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

    val built = TrustedContacts.build(id, name, number, services.toSet(), everyone, enabled = initial?.enabled ?: true)
    val numberError = if (number.isNotBlank() && PhoneNumbers.normalise(number) == null) TrustedContacts.explain(com.zyke.message.domain.sharing.ContactProblem.NUMBER_INVALID) else null
    val duplicate = (built as? ContactResult.Bad)?.problem == com.zyke.message.domain.sharing.ContactProblem.DUPLICATE_NUMBER
    val normalised = PhoneNumbers.normalise(number)

    FormSheet(
        title = if (initial == null) "Add a person" else "Edit ${initial.name}",
        onDismiss = onDismiss,
        primaryLabel = if (initial == null) "Add person" else "Save",
        primaryEnabled = built is ContactResult.Ok,
        onPrimary = { (built as? ContactResult.Ok)?.let { onSave(it.contact) } },
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
        FormGroup("Which services may they get?") {
            val all = ShareServices.all
            FlowRow(
                Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                maxItemsInEachRow = 2
            ) {
                all.forEach { service ->
                    ServiceChoice(
                        service.label, service.id in services,
                        { on -> services = if (on) (services + service.id).distinct().sorted() else services - service.id },
                        Modifier.weight(1f)
                    )
                }
            }
            val every = all.map { it.id }
            ResponsiveRow(Modifier.fillMaxWidth().padding(top = 4.dp), spacing = 8.dp) {
                AppButton("Select all", { services = every.sorted() }, Modifier.cell(), style = ButtonStyle.OUTLINE, size = ButtonSize.COMPACT)
                AppButton("Clear", { services = emptyList() }, Modifier.cell(), style = ButtonStyle.OUTLINE, size = ButtonSize.COMPACT)
            }
            Caption("Hotstar covers Disney+ Hotstar and JioHotstar. Prime Video covers Amazon Prime.", Modifier.padding(top = 4.dp), maxLines = 3)
        }
    }
}
