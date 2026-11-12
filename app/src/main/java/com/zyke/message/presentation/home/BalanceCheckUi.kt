package com.zyke.message.presentation.home

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.telephony.TelephonyManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.size
import com.zyke.message.presentation.common.toggleRow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zyke.message.ZykeMessagesApplication
import com.zyke.message.data.demo.DemoMode
import com.zyke.message.domain.balance.BalanceCheckFlow
import com.zyke.message.domain.balance.BalanceEnquiry
import com.zyke.message.domain.balance.CheckState
import com.zyke.message.domain.balance.TapAction
import com.zyke.message.domain.balance.BalanceRequest
import com.zyke.message.domain.balance.BalanceRequests
import com.zyke.message.domain.balance.EnquiryEdit
import com.zyke.message.domain.balance.EnquiryPlan
import com.zyke.message.domain.balance.RequestPhase
import com.zyke.message.domain.balance.RequestStatus
import com.zyke.message.domain.balance.Uan
import com.zyke.message.domain.model.BankName
import com.zyke.message.notifications.BalanceRequester
import com.zyke.message.presentation.common.AdaptiveSheet
import com.zyke.message.presentation.motion.CrossfadeContent
import com.zyke.message.presentation.theme.Spacing
import com.zyke.message.presentation.common.AppButton
import com.zyke.message.presentation.common.ButtonSize
import com.zyke.message.presentation.common.ButtonStyle
import com.zyke.message.presentation.common.ChipOption
import com.zyke.message.presentation.common.Chips
import com.zyke.message.presentation.common.FintechCard
import com.zyke.message.presentation.common.FormField
import com.zyke.message.presentation.common.LocalAppSnackbar
import com.zyke.message.presentation.common.ResponsiveRow
import com.zyke.message.presentation.common.Text
import com.zyke.message.presentation.messages.components.Caption
import com.zyke.message.presentation.messages.components.RowTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Who a balance request is for: a bank account (by its key) or the provident fund. */
internal data class BalanceTarget(
    val requestKey: String,
    val bank: BankName,
    val bankLabel: String,
    val last4: String,
    val isProvidentFund: Boolean
) {
    val subtitle: String get() = if (isProvidentFund) "EPFO provident fund" else "$bankLabel •••• $last4"
}

private fun balanceCheck(context: android.content.Context) =
    (context.applicationContext as ZykeMessagesApplication).container.balanceCheck

/**
 * The line a tile shows while a request is out: "Request sent · waiting for the reply", then "No reply yet", then nothing. A balance
 * that arrives after the request (from any message that states this account's balance) clears it. Null when there is nothing to say.
 */
@Composable
internal fun rememberRequestStatus(requestKey: String, balanceAt: Long): String? {
    val repository = balanceCheck(LocalContext.current)
    val requests by repository.requests.collectAsStateWithLifecycle()
    val request = requests[requestKey]
    var now by remember(request) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(request, balanceAt) {
        // Only ticks while a request is out; a quiet tile has no timer.
        while (request != null && BalanceRequests.phase(request, balanceAt, now) != RequestPhase.IDLE) {
            delay(10_000)
            now = System.currentTimeMillis()
        }
    }
    return BalanceRequests.statusLine(BalanceRequests.phase(request, balanceAt, now))
}

/** Where a request stands for one tile, re-read every second while a progress or "Updated just now" is showing. */
@Composable
internal fun rememberCheckState(requestKey: String, balanceAt: Long): Pair<CheckState, Float> {
    val repository = balanceCheck(LocalContext.current)
    val requests by repository.requests.collectAsStateWithLifecycle()
    val request = requests[requestKey]
    var now by remember(request) { mutableLongStateOf(System.currentTimeMillis()) }
    val state = BalanceCheckFlow.state(request, balanceAt, now)
    LaunchedEffect(request, balanceAt, state) {
        // Only ticks while a progress or the brief confirmation is showing; a quiet tile has no timer.
        if (BalanceCheckFlow.ticking(state)) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    val progress = if (request == null) 0f else ((now - request.sentAt).toFloat() / BalanceCheckFlow.REPLY_TIMEOUT_MS).coerceIn(0.05f, 1f)
    return state to progress
}

private fun planFor(target: BalanceTarget, setup: com.zyke.message.domain.balance.EnquirySetup, uan: String): EnquiryPlan =
    if (target.isProvidentFund) BalanceEnquiry.planForProvidentFund(setup, uan)
    else BalanceEnquiry.planForAccount(target.bank, target.bankLabel, target.last4, setup)

private fun confirmKey(target: BalanceTarget): String =
    if (target.isProvidentFund) BalanceEnquiry.EPFO_KEY else BalanceEnquiry.bankKey(target.bank, target.bankLabel)

/**
 * What a tap on "Check balance" does. For a bank the person already confirmed (the sheet's "Always send without asking" was on) it
 * sends at once, with no sheet: the slot becomes a progress until the reply arrives. For anything else (first time, a bank with no
 * preset, an MPIN bank, a provident fund, no permission) it calls [openSheet], where the number and exact words are shown first.
 */
@Composable
internal fun rememberCheckTap(target: BalanceTarget, openSheet: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val container = (context.applicationContext as ZykeMessagesApplication).container
    val repository = container.balanceCheck
    val setup by repository.setup.collectAsStateWithLifecycle()
    val skip by repository.skipConfirm.collectAsStateWithLifecycle()
    val snackbar = LocalAppSnackbar.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<EnquiryPlan.Ready?>(null) }

    fun fire(ready: EnquiryPlan.Ready) {
        if (DemoMode.active) {
            scope.launch { snackbar.showSnackbar("Demo: balance updated. Nothing was sent.", duration = SnackbarDuration.Short) }
            return
        }
        scope.launch {
            val result = withContext(Dispatchers.Default) { dispatchRequest(context, container, target.requestKey, ready) }
            when (result) {
                BalanceRequester.Result.NO_PERMISSION -> openSheet()
                BalanceRequester.Result.TOO_SOON -> snackbar.showSnackbar("One request per account every 2 minutes", duration = SnackbarDuration.Short)
                BalanceRequester.Result.QUEUED, BalanceRequester.Result.FAILED -> Unit
            }
        }
    }
    val askFirst = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val ready = pending
        pending = null
        if (ok && ready != null) fire(ready) else openSheet()
    }
    return {
        val plan = planFor(target, setup, "")
        if (target.isProvidentFund || BalanceCheckFlow.onTap(confirmKey(target) in skip, plan) == TapAction.CONFIRM_FIRST) openSheet()
        else if (plan is EnquiryPlan.Ready) {
            if (DemoMode.active || ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) fire(plan)
            else { pending = plan; askFirst.launch(Manifest.permission.SEND_SMS) }
        }
    }
}

/**
 * The secondary action under a tile. The slot at the end keeps one size in every state, so nothing jumps: the "Check balance"
 * button, then a small progress with "Checking\u2026" in its place, then "Updated just now", or "Try again" with a short note.
 */
@Composable
internal fun CheckBalanceRow(label: String, state: CheckState, progress: Float, onClick: () -> Unit, modifier: Modifier = Modifier, showRichHint: Boolean = true) {
    Row(modifier.fillMaxWidth().padding(top = Spacing.xs).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            BalanceCheckFlow.note(state)?.let { note ->
                Caption(note, maxLines = 2, color = if (state == CheckState.FAILED) MaterialTheme.colorScheme.error else com.zyke.message.presentation.messages.components.mutedColor())
            }
            // A rich (RCS) reply reaches Zyke only through notification access: one line and one button, only while it is waiting.
            if (state == CheckState.CHECKING && showRichHint) RichReplyHint(BalanceRequests.RICH_REPLY_HINT)
        }
        // Button, progress and "Updated just now" swap in one slot: a short crossfade inside a slot that is never shorter than a
        // touch target, so nothing above or below it moves.
        CrossfadeContent(state, Modifier.heightIn(min = 48.dp), label = "balance check") { shown ->
            when (shown) {
                CheckState.CHECKING -> Row(
                    Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Checking the balance"; liveRegion = LiveRegionMode.Polite },
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    // Determinate, driven by the one-second tick: no looping animation, so it is calm in Lite mode and with reduced motion.
                    androidx.compose.material3.LinearProgressIndicator(progress = { progress }, Modifier.width(40.dp))
                    Text(BalanceCheckFlow.label(shown).orEmpty(), style = MaterialTheme.typography.labelLarge, color = com.zyke.message.presentation.messages.components.mutedColor(), maxLines = 1)
                }
                CheckState.UPDATED -> Text(
                    BalanceCheckFlow.label(shown).orEmpty(),
                    Modifier.heightIn(min = 48.dp).wrapContentHeight(Alignment.CenterVertically).padding(horizontal = Spacing.md).semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, maxLines = 1
                )
                CheckState.READY -> AppButton(label, onClick, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT)
                CheckState.NO_REPLY, CheckState.FAILED -> AppButton(BalanceCheckFlow.label(shown).orEmpty(), onClick, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT)
            }
        }
    }
}

/** Whether the person has switched on Zyke's notification access in the phone's settings. */
private fun notificationAccessOn(context: android.content.Context): Boolean =
    androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

/**
 * A one-line note with a button to the phone's notification access page, shown only while that access is off (checked again when the
 * person comes back from the page). Nothing at all when it is on, and in the demo.
 */
@Composable
internal fun RichReplyHint(text: String) {
    if (DemoMode.active) return
    val context = LocalContext.current
    var recheck by remember { mutableStateOf(0) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val watcher = androidx.lifecycle.LifecycleEventObserver { _, event -> if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) recheck += 1 }
        lifecycle.addObserver(watcher)
        onDispose { lifecycle.removeObserver(watcher) }
    }
    val on = remember(recheck) { notificationAccessOn(context) }
    if (on) return
    Caption(text, Modifier.padding(top = Spacing.xs), maxLines = 3)
    AppButton(
        "Turn on notification access",
        { runCatching { context.startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } },
        style = ButtonStyle.TEXT, size = ButtonSize.COMPACT
    )
}

/**
 * "Check balance by SMS". The sheet always shows exactly the number and the exact words that Send will text, from the same plan
 * the send uses, beside equal-width Cancel and Send buttons: that is the confirm. Nothing is sent before a tap on Send, there is no
 * schedule, and a request is allowed once per account every two minutes.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CheckBalanceSheet(target: BalanceTarget, balanceAt: Long, onDismiss: () -> Unit, settingsOnly: Boolean = false) {
    val context = LocalContext.current
    val container = (context.applicationContext as ZykeMessagesApplication).container
    val repository = container.balanceCheck
    val setup by repository.setup.collectAsStateWithLifecycle()
    val savedUan by repository.uan.collectAsStateWithLifecycle()
    val requests by repository.requests.collectAsStateWithLifecycle()
    val skipped by repository.skipConfirm.collectAsStateWithLifecycle()
    val now by rememberNowState()
    val snackbar = LocalAppSnackbar.current
    val scope = rememberCoroutineScope()

    // Ticked by default: after this first confirm, a tap on Check balance for this bank sends at once.
    var alwaysSend by rememberSaveable { mutableStateOf(true) }
    var changing by rememberSaveable { mutableStateOf(false) }
    var justSent by rememberSaveable { mutableStateOf(false) }
    var denied by rememberSaveable { mutableStateOf(false) }
    var numberDraft by rememberSaveable { mutableStateOf<String?>(null) }
    var textDraft by rememberSaveable { mutableStateOf<String?>(null) }
    var uanDraft by rememberSaveable { mutableStateOf("") }

    val request = requests[target.requestKey]
    val phase = BalanceRequests.phase(request, balanceAt, now)
    val waitLeft = BalanceRequests.waitLeft(request, now)
    val uan = if (Uan.isValid(savedUan) && uanDraft.isEmpty()) savedUan else Uan.clean(uanDraft)
    val plan = remember(target, setup, uan) {
        if (target.isProvidentFund) BalanceEnquiry.planForProvidentFund(setup, uan)
        else BalanceEnquiry.planForAccount(target.bank, target.bankLabel, target.last4, setup)
    }
    val service = when (plan) {
        is EnquiryPlan.Ready -> plan.service
        is EnquiryPlan.Blocked -> plan.service
        is EnquiryPlan.NeedsLast4 -> plan.service
        is EnquiryPlan.NeedsUan -> plan.service
        is EnquiryPlan.Unsupported -> null
    }
    val key = service?.key ?: if (target.isProvidentFund) BalanceEnquiry.EPFO_KEY else BalanceEnquiry.bankKey(target.bank, target.bankLabel)
    val preset = BalanceEnquiry.presetFor(key)

    fun say(text: String) {
        scope.launch { snackbar.showSnackbar(text, duration = SnackbarDuration.Short) }
    }
    fun openAppSettings() {
        runCatching {
            context.startActivity(
                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
    fun hasPermission() = ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    /** The one place a text is sent from; reached only from the Send button below. */
    fun send(ready: EnquiryPlan.Ready) {
        if (DemoMode.active) {
            // Demo mode sends nothing and changes nothing: a clearly labelled stand-in.
            say("Demo: balance updated. Nothing was sent.")
            if (!target.isProvidentFund) repository.setSkipConfirm(key, alwaysSend)
            onDismiss()
            return
        }
        if (target.isProvidentFund && Uan.isValid(uan)) repository.saveUan(uan)
        scope.launch {
            val result = withContext(Dispatchers.Default) { dispatchRequest(context, container, target.requestKey, ready) }
            when (result) {
                BalanceRequester.Result.QUEUED -> {
                    // The text is on its way: the tile now shows the progress in the button's place, so the sheet closes.
                    if (!target.isProvidentFund) repository.setSkipConfirm(key, alwaysSend)
                    onDismiss()
                }
                BalanceRequester.Result.FAILED -> { denied = false; justSent = true }
                BalanceRequester.Result.NO_PERMISSION -> denied = true
                BalanceRequester.Result.TOO_SOON -> say("One request per account every 2 minutes")
            }
        }
    }
    var pending by remember { mutableStateOf<EnquiryPlan.Ready?>(null) }
    val askToSend = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val ready = pending
        pending = null
        if (ok && ready != null) send(ready) else denied = true
    }
    fun onSend(ready: EnquiryPlan.Ready) {
        if (DemoMode.active || hasPermission()) send(ready) else { pending = ready; askToSend.launch(Manifest.permission.SEND_SMS) }
    }
    fun openMessagesApp(ready: EnquiryPlan.Ready) {
        if (waitLeft > 0L) { say("One request per account every 2 minutes"); return }
        val started = runCatching {
            context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + ready.number)).putExtra("sms_body", ready.text).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
        if (started && !DemoMode.active) repository.record(BalanceRequest(target.requestKey, System.currentTimeMillis(), RequestStatus.SENT))
        if (started) onDismiss()
    }

    val dualSim = remember { runCatching { (context.getSystemService(android.content.Context.TELEPHONY_SERVICE) as TelephonyManager).phoneCount > 1 }.getOrDefault(false) }
    val editorOpen = changing || plan is EnquiryPlan.Unsupported
    val shownNumber = numberDraft ?: service?.number.orEmpty()
    val shownText = textDraft ?: service?.template.orEmpty()
    val canSave = BalanceEnquiry.numberProblem(shownNumber) == null && BalanceEnquiry.templateProblem(shownText) == null

    AdaptiveSheet(
        title = if (settingsOnly) "Balance check settings" else "Check balance by SMS",
        subtitle = target.subtitle,
        onDismiss = onDismiss,
        contentSpacing = Spacing.lg,
        footer = {
            when {
                justSent && plan is EnquiryPlan.Ready -> ResponsiveRow(Modifier.fillMaxWidth(), spacing = Spacing.md) {
                    val again = phase == RequestPhase.FAILED || phase == RequestPhase.NO_REPLY
                    AppButton("Close", onDismiss, Modifier.cell(), style = if (again) ButtonStyle.OUTLINE else ButtonStyle.PRIMARY)
                    if (again) AppButton("Try again", { justSent = false }, Modifier.cell(), enabled = waitLeft == 0L)
                }
                editorOpen -> ResponsiveRow(Modifier.fillMaxWidth(), spacing = Spacing.md) {
                    AppButton("Cancel", { if (plan is EnquiryPlan.Unsupported) onDismiss() else { changing = false; numberDraft = null; textDraft = null } }, Modifier.cell(), style = ButtonStyle.OUTLINE)
                    AppButton("Save", {
                        val number = (numberDraft ?: service?.number.orEmpty()).trim()
                        val text = (textDraft ?: service?.template.orEmpty()).trim()
                        if (preset != null && preset.blockedReason == null && BalanceEnquiry.sameAs(number, preset.number) && BalanceEnquiry.sameAs(text, preset.template)) repository.clearEdit(key)
                        else repository.saveEdit(key, EnquiryEdit(number, text, target.bankLabel))
                        changing = false; numberDraft = null; textDraft = null
                    }, Modifier.cell(), enabled = canSave)
                }
                settingsOnly -> AppButton("Done", onDismiss, Modifier.fillMaxWidth(), style = ButtonStyle.OUTLINE)
                plan is EnquiryPlan.Ready -> ResponsiveRow(Modifier.fillMaxWidth(), spacing = Spacing.md) {
                    AppButton("Cancel", onDismiss, Modifier.cell(), style = ButtonStyle.OUTLINE)
                    AppButton("Send", { onSend(plan) }, Modifier.cell(), enabled = waitLeft == 0L)
                }
                else -> AppButton("Close", onDismiss, Modifier.fillMaxWidth(), style = ButtonStyle.OUTLINE)
            }
        }
    ) {
        if (justSent && plan is EnquiryPlan.Ready) {
            SentState(phase, denied, onSettings = ::openAppSettings)
            return@AdaptiveSheet
        }
        if (editorOpen) {
            if (plan is EnquiryPlan.Unsupported) {
                FintechCard {
                    RowTitle("No number set for ${plan.bankLabel}", color = MaterialTheme.colorScheme.onSurface)
                    Caption(
                        "Zyke has no balance-check number for this bank. If your bank answers a text, add its number and the words to send. " +
                            "It will ask you before it sends.",
                        Modifier.padding(top = Spacing.xs), maxLines = 6
                    )
                }
            }
            FormField(
                "Number", shownNumber, { numberDraft = it }, placeholder = "e.g. 7308080808", keyboardType = KeyboardType.Phone,
                error = numberDraft?.let { BalanceEnquiry.numberProblem(it) }
            )
            FormField(
                "Words to send", shownText, { textDraft = it }, placeholder = "e.g. BAL {last4}",
                supporting = "{last4} becomes the account's last 4 digits. Never type a PIN or password.",
                error = textDraft?.let { BalanceEnquiry.templateProblem(it) }
            )
            val alternatives = (service?.otherNumbers.orEmpty().map { "Number $it" to { numberDraft = it } } +
                service?.otherTexts.orEmpty().map { "Words $it" to { textDraft = it } })
            if (alternatives.isNotEmpty()) {
                Caption("Also listed for this bank", maxLines = 1)
                Chips(alternatives.map { ChipOption(it.first, false) }, { alternatives[it].second() })
            }
            if (service?.isEdited == true) {
                AppButton("Go back to the listed number", { repository.clearEdit(key); changing = false; numberDraft = null; textDraft = null }, Modifier.fillMaxWidth(), style = ButtonStyle.TEXT, size = ButtonSize.COMPACT)
            }
            return@AdaptiveSheet
        }
        when (plan) {
            is EnquiryPlan.Blocked -> {
                FintechCard {
                    RowTitle(plan.reason, color = MaterialTheme.colorScheme.onSurface)
                    Caption("Zyke never stores or sends an MPIN, a PIN or a password. Use your bank's app or call centre for this one.", Modifier.padding(top = Spacing.xs), maxLines = 5)
                }
                AppButton("Set it up another way", { changing = true }, Modifier.fillMaxWidth(), style = ButtonStyle.TEXT, size = ButtonSize.COMPACT)
            }
            is EnquiryPlan.NeedsLast4 ->
                Caption("This request needs the account's last 4 digits, and none was found for it.", maxLines = 4)
            is EnquiryPlan.NeedsUan -> {
                FormField(
                    "UAN (12 digits)", uanDraft, { uanDraft = Uan.clean(it) }, keyboardType = KeyboardType.Number,
                    supporting = "Find it on your payslip or the EPFO member portal. Kept only on this phone, never in backups.",
                    imeAction = androidx.compose.ui.text.input.ImeAction.Done
                )
                Caption("Then Zyke shows the exact text before it sends anything.", maxLines = 2)
            }
            is EnquiryPlan.Ready -> if (settingsOnly) SettingsState(
                plan, askFirst = key !in skipped, onAskFirst = { repository.setSkipConfirm(key, !it) }, onChange = { changing = true }
            ) else ReadyState(
                plan, target, phase, waitLeft, denied, dualSim, uan, alwaysSend, { alwaysSend = it },
                onChange = { changing = true }, onSettings = ::openAppSettings, onMessages = { openMessagesApp(plan) }
            )
            is EnquiryPlan.Unsupported -> Unit
        }
    }
}

@Composable
private fun ReadyState(
    plan: EnquiryPlan.Ready,
    target: BalanceTarget,
    phase: RequestPhase,
    waitLeft: Long,
    denied: Boolean,
    dualSim: Boolean,
    uan: String,
    alwaysSend: Boolean,
    onAlwaysSend: (Boolean) -> Unit,
    onChange: () -> Unit,
    onSettings: () -> Unit,
    onMessages: () -> Unit
) {
    FintechCard {
        Caption("Zyke will text", maxLines = 1)
        Row(Modifier.fillMaxWidth().padding(top = Spacing.sm), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Caption("To", Modifier.padding(top = Spacing.xs), maxLines = 1)
            Text(plan.number, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }
        Row(Modifier.fillMaxWidth().padding(top = Spacing.sm), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Caption("Text", Modifier.padding(top = Spacing.xs), maxLines = 1)
            Text(plan.text, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }
        AppButton("Change", onChange, Modifier.padding(top = Spacing.xs), style = ButtonStyle.TEXT, size = ButtonSize.COMPACT)
    }
    Caption("${BalanceEnquiry.NOTE_RATES} ${BalanceEnquiry.NOTE_UNVERIFIED}", maxLines = 4)
    if (!target.isProvidentFund) {
        ToggleLine("Always send without asking for this bank", alwaysSend, onAlwaysSend, Role.Checkbox)
        Caption("Next time, Check balance texts at once and shows a progress. Turn it back on any time under the account's menu, Balance check settings.", maxLines = 4)
    }
    com.zyke.message.presentation.settings.SeeWhatIsSentLink()
    if (target.isProvidentFund) Caption("UAN ${Uan.mask(Uan.clean(plan.text.filter { it.isDigit() || it == ' ' }.split(' ').firstOrNull { it.length == BalanceEnquiry.UAN_LENGTH }.orEmpty()))} · the reply is shown in Messages and, when it states a balance, under Investments.", maxLines = 3)
    if (dualSim) {
        Caption(BalanceEnquiry.SIM_NOTE, maxLines = 5)
        AppButton("Send from my messages app instead", onMessages, Modifier.fillMaxWidth(), style = ButtonStyle.TEXT, size = ButtonSize.COMPACT)
    }
    if (denied) {
        Caption("Zyke needs permission to send texts. Tap Send to be asked again, or allow it in settings.", maxLines = 4, color = MaterialTheme.colorScheme.error)
        AppButton("Open settings", onSettings, Modifier.fillMaxWidth(), style = ButtonStyle.TEXT, size = ButtonSize.COMPACT)
    }
    if (waitLeft > 0L) {
        val seconds = ((waitLeft + 999) / 1000).toInt()
        Caption("One request per account every 2 minutes. Try again in ${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}.", maxLines = 2)
    } else if (phase == RequestPhase.WAITING || phase == RequestPhase.NO_REPLY) {
        BalanceRequests.statusLine(phase)?.let { Caption(it, maxLines = 1) }
        if (phase == RequestPhase.WAITING) RichReplyHint(BalanceRequests.RICH_REPLY_HINT)
    } else {
        RichReplyHint(BalanceRequests.RICH_NOTE)
    }
}

@Composable
private fun SentState(phase: RequestPhase, denied: Boolean, onSettings: () -> Unit) {
    val (title, detail) = when (phase) {
        RequestPhase.SENDING -> "Sending the request…" to "This takes a moment."
        RequestPhase.WAITING -> "Request sent · waiting for the reply" to "Your bank answers by text. When it arrives, the balance on the tile updates by itself. You can close this."
        RequestPhase.NO_REPLY -> "No reply yet" to "Banks usually answer within a minute. Check the number and words, or try again in a few minutes."
        RequestPhase.FAILED -> "Couldn't send the request" to "Check that this phone can send texts and has signal."
        RequestPhase.IDLE -> "Balance updated" to "The bank's reply arrived."
    }
    FintechCard {
        RowTitle(title, color = if (phase == RequestPhase.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        Caption(detail, Modifier.padding(top = Spacing.xs), maxLines = 5)
    }
    if (phase == RequestPhase.WAITING || phase == RequestPhase.NO_REPLY) RichReplyHint(BalanceRequests.RICH_REPLY_HINT)
    if (denied) AppButton("Open settings", onSettings, Modifier.fillMaxWidth(), style = ButtonStyle.TEXT, size = ButtonSize.COMPACT)
}

/** A 48dp checkbox or switch row that toggles as one target. */
@Composable
private fun ToggleLine(label: String, checked: Boolean, onChange: (Boolean) -> Unit, role: Role) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleRow(checked, role, onChange), verticalAlignment = Alignment.CenterVertically) {
        if (role == Role.Switch) androidx.compose.material3.Switch(checked = checked, onCheckedChange = null)
        else androidx.compose.foundation.layout.Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { androidx.compose.material3.Checkbox(checked = checked, onCheckedChange = null) }
        Text(label, Modifier.weight(1f).padding(start = if (role == Role.Switch) Spacing.md else 0.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

/** "Balance check settings": what is sent, and whether Check balance asks first. Nothing here sends a text. */
@Composable
private fun SettingsState(plan: EnquiryPlan.Ready, askFirst: Boolean, onAskFirst: (Boolean) -> Unit, onChange: () -> Unit) {
    FintechCard {
        Caption("Zyke texts", maxLines = 1)
        Row(Modifier.fillMaxWidth().padding(top = Spacing.sm), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Caption("To", Modifier.padding(top = Spacing.xs), maxLines = 1)
            Text(plan.number, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }
        Row(Modifier.fillMaxWidth().padding(top = Spacing.sm), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Caption("Text", Modifier.padding(top = Spacing.xs), maxLines = 1)
            Text(plan.text, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }
        AppButton("Change number or words", onChange, Modifier.padding(top = Spacing.xs), style = ButtonStyle.TEXT, size = ButtonSize.COMPACT)
    }
    ToggleLine("Ask before sending", askFirst, onAskFirst, Role.Switch)
    Caption(
        if (askFirst) "Check balance shows this screen first. Turn this off to send at once with one tap."
        else "Check balance sends this text at once with one tap and shows a progress on the tile.",
        maxLines = 4
    )
    Caption("${BalanceEnquiry.NOTE_RATES} ${BalanceEnquiry.NOTE_UNVERIFIED}", maxLines = 4)
    com.zyke.message.presentation.settings.SeeWhatIsSentLink()
}

/**
 * The one place a balance request text is handed to the requester. Both doors come here: the Send button of the confirm sheet and a
 * tap on a bank the person already confirmed.
 */
internal fun dispatchRequest(context: android.content.Context, container: com.zyke.message.di.AppContainer, requestKey: String, ready: EnquiryPlan.Ready): BalanceRequester.Result =
    BalanceRequester.send(context, container, requestKey, ready.number, ready.text)
