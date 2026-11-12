package com.zyke.message.presentation.settings

import com.zyke.message.domain.tour.TourAnchor
import com.zyke.message.domain.tour.TourTab
import com.zyke.message.presentation.tour.TourScroll
import com.zyke.message.presentation.tour.tourAnchor
import androidx.compose.ui.text.font.FontWeight
import com.zyke.message.presentation.dashboard.components.LocalSectionsAsSheets
import androidx.compose.runtime.CompositionLocalProvider
import com.zyke.message.presentation.common.fullWidthClickable
import com.zyke.message.presentation.common.heading
import com.zyke.message.presentation.common.AppBottomSheet
import com.zyke.message.presentation.common.AppButton
import com.zyke.message.presentation.common.ButtonSize
import com.zyke.message.presentation.common.ButtonStyle
import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import com.zyke.message.presentation.theme.Spacing
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import com.zyke.message.presentation.common.Text
import com.zyke.message.presentation.common.runWhenRevealed
import androidx.compose.material3.TextButton
import com.zyke.message.presentation.common.LearnMore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import com.zyke.message.R
import com.zyke.message.notifications.LiveAlerts
import com.zyke.message.domain.model.UserCard
import com.zyke.message.presentation.common.faceCard
import com.zyke.message.domain.model.ThemeMode
import com.zyke.message.presentation.dashboard.components.SectionCard
import com.zyke.message.presentation.dashboard.components.ShareBar
import com.zyke.message.presentation.messages.components.Caption
import com.zyke.message.presentation.messages.components.RowTitle
import com.zyke.message.presentation.settings.SettingsContract.Effect
import com.zyke.message.presentation.settings.SettingsContract.Intent
import com.zyke.message.presentation.settings.SettingsContract.State

@Composable
fun SettingsRoute(viewModel: SettingsViewModel, versionName: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val amountsHidden by androidx.compose.runtime.rememberUpdatedState(com.zyke.message.presentation.common.LocalPrivacyMode.current.hidden)

    LaunchedEffect(viewModel) {
        // Demo mode reads no messages, so it needs no permission and never asks for it.
        val granted = com.zyke.message.data.demo.DemoMode.active || ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) ==
            PackageManager.PERMISSION_GRANTED
        viewModel.onIntent(Intent.ScreenStarted(granted))
    }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is Effect.ShareReport -> runCatching { ReportSharing.share(context, effect.fileName, effect.text) }
                    .onFailure { Toast.makeText(context, R.string.report_failed, Toast.LENGTH_LONG).show() }
                is Effect.ShowMessage -> Toast.makeText(
                    context, if (amountsHidden) com.zyke.message.domain.privacy.AmountMasker.mask(effect.text) else effect.text, Toast.LENGTH_SHORT
                ).show()
                is Effect.ShareData -> runCatching { ReportSharing.share(context, effect.fileName, effect.text, effect.mimeType, effect.subject) }
                    .onFailure { Toast.makeText(context, R.string.report_failed, Toast.LENGTH_LONG).show() }
            }
        }
    }

    SettingsPage(state, versionName, viewModel::onIntent, onDismiss)
}

/** The pages Settings is made of; the hub lists them and each opens as a page of its own. */
internal enum class SettingsGroup(val title: String, val icon: Int) {
    MONEY("Money plan", R.drawable.ic_wallet_24),
    LIMITS("Budgets", R.drawable.ic_goal_24),
    CARDS("Cards and accounts", R.drawable.ic_card_24),
    MESSAGES("Messages and pictures", R.drawable.baseline_message_24),
    RULES("Your rules", R.drawable.baseline_list_24),
    ALERTS("Reminders and alerts", R.drawable.ic_reminder_24),
    MARKETS("Market watch", R.drawable.ic_trending_24),
    FLIGHTS("Flights and trains", R.drawable.baseline_flight_24),
    PRIVACY("Privacy and security", R.drawable.ic_lock_24),
    APPEARANCE("Appearance", R.drawable.ic_sun_24),
    HELP("Help and tour", R.drawable.ic_info_24),
    DEMO("Demo mode", R.drawable.ic_demo_24),
    ABOUT("Your data and about", R.drawable.ic_info_24),
    LEGAL("Privacy and terms", R.drawable.ic_lock_24),
    LICENCES("Open-source licences", R.drawable.baseline_list_24),
    DEVELOPER("Developer options", R.drawable.ic_info_24)
}

/**
 * Settings as a page of its own. The first screen is a short list of what can be set, each with its current state beside
 * it, so the answer to "how is this set?" needs no opening. Anything still to set up is called out above the list. A
 * row opens one page about that one thing.
 */
@Composable
fun SettingsPage(
    state: State,
    versionName: String,
    onIntent: (Intent) -> Unit,
    onDismiss: () -> Unit
) {
    var openName by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    // While the tour runs it asks for one of these pages (or the hub); the person's own taps are blocked meanwhile.
    androidx.compose.runtime.LaunchedEffect(com.zyke.message.presentation.tour.TourSettingsRequest.active, com.zyke.message.presentation.tour.TourSettingsRequest.group) {
        if (com.zyke.message.presentation.tour.TourSettingsRequest.active) openName = com.zyke.message.presentation.tour.TourSettingsRequest.group
    }
    val open = SettingsGroup.entries.firstOrNull { it.name == openName }
    val container = (LocalContext.current.applicationContext as com.zyke.message.ZykeMessagesApplication).container
    val watch by container.watchlist.watch.collectAsStateWithLifecycle()
    val flightsOn by container.flights.liveEnabled.collectAsStateWithLifecycle()
    val trainsOn by container.trains.liveEnabled.collectAsStateWithLifecycle()
    val developer by com.zyke.message.data.diag.DevLog.developerMode.collectAsStateWithLifecycle()
    val logging by com.zyke.message.data.diag.DevLog.logging.collectAsStateWithLifecycle()

    val demoOn = com.zyke.message.data.demo.DemoMode.active
    val gate = com.zyke.message.presentation.motion.rememberEnterGate()
    // A search result for a setting asks for its page by name.
    LaunchedEffect(SettingsDeepLink.pending) {
        SettingsDeepLink.pending?.let { openName = it; SettingsDeepLink.pending = null }
    }
    val summaryOf: (SettingsGroup) -> String = { group ->
        when (group) {
            SettingsGroup.MONEY -> moneySummary(state)
            SettingsGroup.LIMITS -> limitsSummary(state)
            SettingsGroup.CARDS -> cardsSummary(state)
            SettingsGroup.MESSAGES -> messagesSummary(state)
            SettingsGroup.RULES -> "What you taught the app"
            SettingsGroup.ALERTS -> alertsSummary(state)
            SettingsGroup.MARKETS -> if (watch.enabled) "On \u00B7 " + com.zyke.message.presentation.format.DisplayFormatters.count(watch.symbols.size, "share", "shares") else "Off"
            SettingsGroup.FLIGHTS -> when { flightsOn && trainsOn -> "Flights and trains on"; flightsOn -> "Flights on"; trainsOn -> "Trains on"; else -> "Off" }
            SettingsGroup.PRIVACY -> if (state.settings.appLock) "App lock on" else "Private on this phone"
            SettingsGroup.APPEARANCE -> state.settings.themeMode.label + if (state.settings.accent != com.zyke.message.domain.model.AccentColor.DEFAULT) ", ${state.settings.accent.label}" else ""
            SettingsGroup.HELP -> "Take the app tour"
            SettingsGroup.DEMO -> if (demoOn) "On \u00B7 sample data" else "Show the app with sample data"
            SettingsGroup.ABOUT -> "Backup, export and delete \u00B7 version $versionName"
            SettingsGroup.LEGAL -> "What is sent, permissions, terms"
            SettingsGroup.LICENCES -> "Libraries, font and icons we use"
            SettingsGroup.DEVELOPER -> if (logging) "Writing a log file" else "Log is off"
        }
    }

    com.zyke.message.presentation.common.AppPage("Settings", onDismiss) {
        SettingsHub(
            developer = developer, demo = demoOn, summaryOf = summaryOf, todo = setupSteps(state), gate = gate,
            onOpen = { openName = it.name }
        )
    }

    open?.takeIf { it != SettingsGroup.DEVELOPER || developer }?.let { group ->
        com.zyke.message.presentation.common.AppPage(group.title, { openName = null }) {
            // This page is about one thing, so its sections show their contents instead of folding away.
            CompositionLocalProvider(com.zyke.message.presentation.dashboard.components.LocalSectionsInline provides true) {
                Column(Modifier.fillMaxWidth().tourAnchor(TourAnchor.SETTINGS_GROUP_BODY), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    GroupContent(group, state, versionName, onIntent, onDeleteAll = { deleting = true }, onOpenGroup = { openName = it.name })
                }
            }
        }
    }

    if (deleting) DeleteAllSheet(onDismiss = { deleting = false })

    if (state.confirmingReset) {
        com.zyke.message.presentation.common.ConfirmDialog(
            title = "Reset marks and budgets?",
            body = "This clears your read, pinned and archived marks, dismissed tips and every budget. Your messages are not touched. It can't be undone.",
            confirmLabel = "Reset",
            onConfirm = { onIntent(Intent.ConfirmReset) },
            onDismiss = { onIntent(Intent.CancelReset) }
        )
    }
}

/** The last way out: everything the app has saved, gone, after typing a word so it cannot happen by accident. */
@Composable
private fun DeleteAllSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val haptics = com.zyke.message.presentation.motion.rememberHaptics()
    var typed by rememberSaveable { mutableStateOf("") }
    com.zyke.message.presentation.common.FormSheet(
        title = "Delete all my data?",
        subtitle = "This cannot be undone",
        onDismiss = onDismiss,
        primaryLabel = "Delete everything",
        primaryEnabled = typed.trim().equals("DELETE", ignoreCase = false),
        hint = "Type DELETE to confirm",
        onPrimary = {
            haptics.reject()
            com.zyke.message.data.DataWipe.wipe(context)
            // Closed at once, so nothing still in memory writes itself back.
            (context as? android.app.Activity)?.finishAndRemoveTask()
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    ) {
        Caption(
            "Removes the transactions read from your messages, your plan, limits, cards, goals, reminders, events, entries, names, categories, pictures and settings from this app. Your messages on the phone are not touched.",
            maxLines = 8
        )
        com.zyke.message.presentation.common.FormField("Type DELETE to confirm", typed, { typed = it }, placeholder = "DELETE", imeAction = androidx.compose.ui.text.input.ImeAction.Done, autoFocus = true)
    }
}

/** What is not set up yet and would help: the thing, why, and the page that sets it. */
private fun setupSteps(state: State): List<Triple<String, String, SettingsGroup>> = buildList {
    if (state.plan.salary == null && state.spottedSalary == null) {
        add(Triple("Add your salary", "So the app can show what is left to spend each month", SettingsGroup.MONEY))
    }
    if (state.limits.isEmpty()) {
        add(Triple("Set budgets", if (state.limitSuggestions.isNotEmpty()) "Suggestions are ready for you" else "Keep a category within what you choose", SettingsGroup.LIMITS))
    }
    if (state.cardSpend == null && state.accounts.any { it.kind == com.zyke.message.domain.accounts.AccountKind.CREDIT_CARD }) {
        add(Triple("Set a credit card limit", "One limit across all your cards", SettingsGroup.LIMITS))
    }
}.take(MAX_STEPS)

private const val MAX_STEPS = 3

private fun moneySummary(state: State): String {
    val salary = state.plan.salary?.let { com.zyke.message.presentation.format.DisplayFormatters.rupees(it) }
    return when {
        salary != null -> salary
        state.spottedSalary != null -> "Salary found in messages"
        else -> "Not set up"
    }
}

private fun limitsSummary(state: State): String {
    val count = state.limits.size
    val limits = if (count == 0) "None set" else com.zyke.message.presentation.format.DisplayFormatters.count(count, "budget", "budgets")
    return if (state.cardSpend != null) "$limits \u00B7 card limit" else limits
}

private fun cardsSummary(state: State): String {
    val cards = state.accounts.count { it.isCard }
    val accounts = state.accounts.size - cards
    if (cards == 0 && accounts == 0) return "None found"
    return listOfNotNull(
        cards.takeIf { it > 0 }?.let { com.zyke.message.presentation.format.DisplayFormatters.count(it, "card", "cards") },
        accounts.takeIf { it > 0 }?.let { com.zyke.message.presentation.format.DisplayFormatters.count(it, "account", "accounts") }
    ).joinToString(" \u00B7 ")
}

private fun messagesSummary(state: State): String {
    val d = state.diagnostics ?: return "Allow SMS access"
    if (d.moneyMessages == 0) return "No money messages yet"
    return "${d.recognised * 100 / d.moneyMessages}% understood"
}

private fun alertsSummary(state: State): String = when {
    state.settings.weeklySummary || state.settings.headsUp -> "Summaries and heads-ups on"
    state.settings.morningDigest && state.settings.liveAlerts -> "Morning summary and code buttons on"
    state.settings.morningDigest -> "Morning summary on"
    state.settings.liveAlerts -> "Code buttons on"
    else -> "Off"
}

@Composable
private fun GroupContent(
    group: SettingsGroup,
    state: State,
    versionName: String,
    onIntent: (Intent) -> Unit,
    onDeleteAll: () -> Unit,
    onOpenGroup: (SettingsGroup) -> Unit
) {
    when (group) {
        SettingsGroup.MONEY -> {
            PlanSection(
                plan = state.plan,
                spotted = state.spottedSalary,
                onSalary = { amount, day -> onIntent(Intent.SetSalary(amount, day)) },
                onSavingsGoal = { onIntent(Intent.SetSavingsGoal(it)) },
                onSaveItem = { onIntent(Intent.SavePlanItem(it)) },
                onDeleteItem = { onIntent(Intent.DeletePlanItem(it)) }
            )
            CycleSection(
                plan = state.plan,
                spotted = state.spottedSalary,
                current = state.cycleRange,
                onChange = { mode, day -> onIntent(Intent.SetCycle(mode, day)) }
            )
        }
        SettingsGroup.LIMITS -> LimitsSection(
            limits = state.limits,
            onSave = { category, daily, monthly, carry -> onIntent(Intent.SetLimits(category, daily, monthly, carry)) },
            onRemove = { onIntent(Intent.RemoveLimits(it)) },
            onMove = { from, to, amount -> onIntent(Intent.MoveBudget(from, to, amount)) },
            onUndoMove = { onIntent(Intent.UndoBudgetMove) },
            status = state.budgetStatus,
            spendable = state.spendable,
            suggestions = state.limitSuggestions,
            cardSpend = state.cardSpend,
            onCardLimit = { onIntent(Intent.SetCardLimit(it)) }
        )
        SettingsGroup.CARDS -> CardsSection(state.accounts, state.cards, onSave = { onIntent(Intent.SaveCard(it)) }, onDelete = { onIntent(Intent.DeleteCard(it)) })
        SettingsGroup.RULES -> {
            CustomCategoriesSection()
            RulesSection()
        }
        SettingsGroup.MESSAGES -> {
            SectionCard("Pictures") {
                Caption("Tap the photo on any message sheet to change it", maxLines = 2)
                ToggleRow(
                    title = "Fetch brand logos",
                    description = "Only the brand name is sent",
                    learnMore = "Looks up logos of well-known brands from the internet, once each. Only the brand name is sent: never message text, amounts or numbers.",
                    checked = state.settings.fetchLogos,
                    onCheckedChange = { onIntent(Intent.SetFetchLogos(it)) },
                    modifier = Modifier.padding(top = Spacing.md)
                )
            }
            TagsEntry()
            RcsSection()
            SectionCard("Parsing health") {
                val diagnostics = state.diagnostics
                if (diagnostics == null) {
                    Caption("Allow SMS access to see how well your messages are understood.", maxLines = 2)
                } else if (diagnostics.moneyMessages == 0) {
                    Caption("No money messages found yet.")
                } else {
                    val fraction = diagnostics.recognised.toFloat() / diagnostics.moneyMessages
                    RowTitle("${diagnostics.recognised} of ${diagnostics.moneyMessages} money messages understood")
                    ShareBar(fraction)
                    Caption(
                        if (diagnostics.unrecognised == 0) "Everything was understood."
                        else "${diagnostics.unrecognised} shown as plain text",
                        Modifier.padding(top = Spacing.sm)
                    )
                    if (diagnostics.unrecognised > 0) LearnMore("Share a report as a file, with numbers, names and links masked, so these messages can be taught.")
                    AppButton(
                        text = "Share report",
                        onClick = { onIntent(Intent.ShareDiagnostics) },
                        style = ButtonStyle.OUTLINE,
                        enabled = diagnostics.unrecognised > 0,
                        modifier = Modifier.padding(top = Spacing.md)
                    )
                }
            }
        }
        SettingsGroup.ALERTS -> {
        SectionCard("Reminders and alerts") {
            CodeAlertStatus(
                switchedOn = state.settings.liveAlerts, readingPaused = state.settings.readingPaused,
                onTurnOn = { onIntent(Intent.SetLiveAlerts(true)) }, onResume = { onIntent(Intent.SetReadingPaused(false)) }
            )
            MorningDigestToggle(enabled = state.settings.morningDigest, onChange = { onIntent(Intent.SetMorningDigest(it)) })
            LiveAlertsToggle(enabled = state.settings.liveAlerts, onChange = { onIntent(Intent.SetLiveAlerts(it)) }, modifier = Modifier.padding(top = Spacing.md))
            Box(Modifier.padding(top = Spacing.md)) {
                MorningDigestToggle(
                    enabled = state.settings.weeklySummary, onChange = { onIntent(Intent.SetWeeklySummary(it)) },
                    title = "Weekly summary", description = "Sunday at about 9 AM: the week's spending and what is due next"
                )
            }
            Box(Modifier.padding(top = Spacing.md)) {
                MorningDigestToggle(
                    enabled = state.settings.headsUp && state.settings.liveAlerts, onChange = { onIntent(Intent.SetHeadsUp(it)) },
                    title = "Heads-up alerts", description = "Unusual payments and low balances",
                    learnMore = "A quiet note when a payment is far above usual or leaves an account low. Needs the Copy-code and Pay buttons above to be on."
                )
            }
            var floor by remember { mutableStateOf(state.settings.lowBalanceFloor?.toPlainString().orEmpty()) }
            com.zyke.message.presentation.common.FormField(
                "Low balance floor", floor, { floor = it; onIntent(Intent.SetLowBalanceFloor(it)) },
                modifier = Modifier.padding(top = Spacing.md), placeholder = "None", prefix = "\u20b9",
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number, imeAction = androidx.compose.ui.text.input.ImeAction.Done
            )
            Caption("A \"Your day\" widget is in the widget list", Modifier.padding(top = Spacing.md), maxLines = 2)
        }
        CodeSharingEntry()
        }
        SettingsGroup.MARKETS -> com.zyke.message.presentation.markets.MarketSettingsSection()
        SettingsGroup.FLIGHTS -> {
            com.zyke.message.presentation.flights.FlightSettingsSection()
            com.zyke.message.presentation.trains.TrainSettingsSection()
            val developerOn by com.zyke.message.data.diag.DevLog.developerMode.collectAsStateWithLifecycle()
            if (developerOn) LiveStatusChecks()
        }
        SettingsGroup.PRIVACY -> SectionCard("Privacy") {
            Caption("Messages stay on this phone", maxLines = 2)
            val reveal = com.zyke.message.presentation.common.LocalAmountReveal.current
            val screenLock = LocalContext.current.getSystemService(android.app.KeyguardManager::class.java)?.isDeviceSecure == true
            ToggleRow(
                title = "Hide amounts until tapped",
                description = "Money shows as dots, everywhere",
                learnMore = "Every money amount in the app shows as dots, including in messages and notifications. Tap one to show them for a short while.",
                checked = state.settings.hideAmounts,
                // Showing money for good needs the screen lock, like showing it for a minute does.
                onCheckedChange = { on ->
                    if (on) onIntent(Intent.SetHideAmounts(true))
                    else reveal.runWhenRevealed { onIntent(Intent.SetHideAmounts(false)) }
                },
                modifier = Modifier.padding(top = Spacing.md)
            )
            ToggleRow(
                title = "Ask to unlock amounts",
                description = if (screenLock) "Fingerprint, face or screen lock first" else "Needs a screen lock on the phone",
                learnMore = if (screenLock) "Your fingerprint, face, PIN, pattern or password is asked before hidden amounts are shown."
                else "Your phone has no screen lock, so a tap shows amounts for 5 seconds. Set a screen lock in the phone's settings to turn this on.",
                checked = state.settings.authForAmounts && screenLock,
                onCheckedChange = { on ->
                    if (!screenLock) return@ToggleRow
                    if (on) onIntent(Intent.SetAuthForAmounts(true))
                    else reveal.runWhenRevealed { onIntent(Intent.SetAuthForAmounts(false)) }
                },
                modifier = Modifier.padding(top = Spacing.md)
            )
            if (state.settings.hideAmounts) {
                Caption("HIDE AMOUNTS AGAIN", Modifier.padding(top = Spacing.lg))
                com.zyke.message.presentation.common.ChoiceCards(
                    choices = com.zyke.message.domain.privacy.RevealDuration.entries.map {
                        com.zyke.message.presentation.common.Choice(it, it.label, it.description)
                    },
                    selected = state.settings.revealDuration,
                    onSelect = { onIntent(Intent.SetRevealDuration(it)) },
                    modifier = Modifier.padding(top = Spacing.sm)
                )
                LearnMore("How long amounts stay shown after you unlock them. Without a screen lock a tap always shows them for 5 seconds.")
            }
            ToggleRow(
                title = "Block screenshots",
                description = "Also hides the recent-apps view",
                checked = state.settings.screenProtection,
                onCheckedChange = { onIntent(Intent.SetScreenProtection(it)) },
                modifier = Modifier.padding(top = Spacing.md)
            )
            AppLockToggle(enabled = state.settings.appLock, onChange = { onIntent(Intent.SetAppLock(it)) }, modifier = Modifier.padding(top = Spacing.md))
            val container = (LocalContext.current.applicationContext as com.zyke.message.ZykeMessagesApplication).container
            ToggleRow(
                title = "Pause reading messages",
                description = "Stops reading until you carry on",
                learnMore = "Stops reading your messages and listening for new ones. What was already read stays, and you can carry on any time.",
                checked = state.settings.readingPaused,
                onCheckedChange = { paused ->
                    onIntent(Intent.SetReadingPaused(paused))
                    // Carrying on: look at what arrived while paused.
                    if (!paused) container.smsRepository.refreshRecent()
                },
                modifier = Modifier.padding(top = Spacing.md)
            )
        }
        SettingsGroup.APPEARANCE -> { SectionCard("Theme") {
            com.zyke.message.presentation.common.ChoiceCards(
                choices = ThemeMode.entries.map { com.zyke.message.presentation.common.Choice(it, it.label) },
                selected = state.settings.themeMode,
                onSelect = { onIntent(Intent.SetThemeMode(it)) }
            )
            LearnMore("Automatic follows your phone. True black is pure black, easy on OLED screens.")
            Caption("COLOUR", Modifier.padding(top = Spacing.lg))
            var picking by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
            val customSeed = state.settings.customAccent
            com.zyke.message.presentation.theme.AccentSwatches(
                selected = state.settings.accent,
                onSelect = { onIntent(Intent.SetAccent(it)) },
                modifier = Modifier.padding(top = Spacing.sm),
                customAccent = customSeed,
                onCustom = { picking = true }
            )
            LearnMore("Default uses your phone's wallpaper colours where it has them. The ring is your own colour.")
            val demoOn = com.zyke.message.data.demo.DemoMode.active
            if (!demoOn) Caption("APP ICON", Modifier.padding(top = Spacing.lg))
            val context = LocalContext.current
            var pendingIcon by rememberSaveable { mutableStateOf<String?>(null) }
            // The launcher icon is the phone's, not the demo's: Demo mode leaves it alone.
            if (!demoOn) {
                com.zyke.message.presentation.theme.AppIconChoices(
                    selected = state.settings.appIcon,
                    onSelect = { if (it != state.settings.appIcon) pendingIcon = it.id },
                    modifier = Modifier.padding(top = Spacing.sm)
                )
                LearnMore("The colour of the icon on your home screen. Only these colours are possible: Android cannot recolour an icon freely.")
            }
            pendingIcon?.let { id ->
                val icon = com.zyke.message.domain.model.AppIconChoice.fromStored(id)
                com.zyke.message.presentation.common.ConfirmDialog(
                    title = "Change app icon to ${icon.label}?",
                    body = "Your launcher may take a few seconds to update, and some launchers close the app or move the icon.",
                    confirmLabel = "Change icon",
                    destructive = false,
                    onConfirm = {
                        onIntent(Intent.SetAppIcon(icon))
                        AppIconSwitcher.applyAsync(context, icon)
                        pendingIcon = null
                    },
                    onDismiss = { pendingIcon = null }
                )
            }
            if (picking) {
                CustomAccentSheet(
                    initial = customSeed,
                    onSave = { onIntent(Intent.SetCustomAccent(it)); picking = false },
                    onReset = { onIntent(Intent.SetAccent(com.zyke.message.domain.model.AccentColor.DEFAULT)); picking = false },
                    onDismiss = { picking = false }
                )
            }
        }
        SectionCard("Feel") {
            ToggleRow(
                title = "Haptic feedback",
                description = "A light tick on switches, a firmer one on deletes",
                learnMore = "A light tick when you flip a switch or choose an option, and a firmer one when you confirm something that cannot be undone.",
                checked = state.settings.hapticFeedback,
                onCheckedChange = { onIntent(Intent.SetHapticFeedback(it)) }
            )
        }
        PerformanceCard(state.settings.performanceMode) { onIntent(Intent.SetPerformanceMode(it)) }
        }
        SettingsGroup.HELP -> com.zyke.message.presentation.tour.TourHelpSection()
        SettingsGroup.DEMO -> com.zyke.message.presentation.demo.DemoModeSettings()
        SettingsGroup.DEVELOPER -> DeveloperContent()
        SettingsGroup.LEGAL -> LegalContent(onOpenLicences = { onOpenGroup(SettingsGroup.LICENCES) })
        SettingsGroup.LICENCES -> LicencesContent()
        SettingsGroup.ABOUT -> {
            SectionCard("Take your data with you") {
                Caption("Nothing leaves the phone unless you share the file yourself.", maxLines = 2)
                AppButton(
                    text = "Export transactions (spreadsheet)",
                    onClick = { onIntent(Intent.ExportTransactions) },
                    style = ButtonStyle.OUTLINE,
                    modifier = Modifier.fillMaxWidth().padding(top = Spacing.md)
                )
                Caption("The transactions read so far. Message text is not included.", Modifier.padding(top = Spacing.xs), maxLines = 2)
                AppButton(
                    text = "Export my setup",
                    onClick = { onIntent(Intent.ExportSetup) },
                    style = ButtonStyle.OUTLINE,
                    modifier = Modifier.fillMaxWidth().padding(top = Spacing.md)
                )
                Caption("Plan, limits, cards, goals, reminders, events and entries you added.", Modifier.padding(top = Spacing.xs), maxLines = 2)
            }
            val demo = com.zyke.message.data.demo.DemoMode.active
            androidx.compose.foundation.layout.Box(Modifier.tourAnchor(TourAnchor.SETTINGS_BACKUP)) {
                if (demo) com.zyke.message.presentation.demo.DemoBackupNote() else com.zyke.message.presentation.backup.AutoBackupCard()
            }
            if (!demo) BackupCard()
            SectionCard("Clear data") {
                Caption("Reset keeps your messages. Delete removes everything saved here.", maxLines = 2)
                AppButton(
                    text = "Reset marks and budgets",
                    onClick = { onIntent(Intent.AskReset) },
                    style = ButtonStyle.OUTLINE,
                    modifier = Modifier.fillMaxWidth().padding(top = Spacing.md)
                )
                if (!demo) {
                    AppButton(
                        text = "Delete all my data",
                        onClick = onDeleteAll,
                        style = ButtonStyle.OUTLINE,
                        modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm)
                    )
                }
            }
            SectionCard("About") {
                VersionRow(versionName)
                if (!demo) ShareLogsLink()
                Caption("Plus Jakarta Sans, SIL Open Font License 1.1", Modifier.padding(top = Spacing.xs))
                TextButton(onClick = { onOpenGroup(SettingsGroup.LEGAL) }) { Text("Privacy and terms") }
                TextButton(onClick = { onOpenGroup(SettingsGroup.LICENCES) }) { Text("Open-source licences") }
            }
        }
    }
}

/**
 * Every card and account the app knows, those found in the messages as well as those added by hand. A found one can be
 * given its details (a name, a statement day); an added one can be edited or removed.
 */
@Composable
private fun CardsSection(
    accounts: List<com.zyke.message.domain.accounts.AccountSummary>,
    cards: List<UserCard>,
    onSave: (UserCard) -> Unit,
    onDelete: (String) -> Unit
) {
    var editing by remember { mutableStateOf<UserCard?>(null) }
    var editingIsNew by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }

    if (accounts.isEmpty()) {
        com.zyke.message.presentation.common.EmptyState(
            "No cards or accounts yet",
            "They appear when a message mentions one, or add one by its last four digits.",
            icon = R.drawable.ic_card_24,
            actionLabel = "Add a card or account",
            onAction = { adding = true }
        )
    } else {
        SectionCard("Found and added") {
            Caption("Messages are matched to these by their last four digits", maxLines = 2)
            val editAccount = { account: com.zyke.message.domain.accounts.AccountSummary ->
                val own = account.userCard
                editingIsNew = own == null
                editing = own ?: account.faceCard().let { face ->
                    face.copy(id = java.util.UUID.randomUUID().toString())
                }
            }
            val deckCards = accounts.filter { it.isCard }
            if (deckCards.isNotEmpty()) {
                val deckState = androidx.compose.foundation.pager.rememberPagerState(pageCount = { deckCards.size })
                com.zyke.message.presentation.common.CardDeck(
                    deckCards.map { com.zyke.message.presentation.common.DeckCard(it.key, it.faceCard()) },
                    deckState,
                    onOpen = { entry -> deckCards.firstOrNull { it.key == entry.key }?.let(editAccount) },
                    modifier = Modifier.padding(top = Spacing.md)
                )
            }
            accounts.forEach { account ->
                val own = account.userCard
                val edit = { editAccount(account) }
                Row(
                    Modifier.fillMaxWidth().fullWidthClickable(role = Role.Button, onClickLabel = "Edit ${account.title}", onClick = edit).padding(top = Spacing.md),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        RowTitle(account.title)
                        Caption(
                            listOfNotNull(
                                "${account.kind.label} \u2022\u2022 ${account.last4}",
                                own?.statementDay?.let { "statement on day $it" }
                            ).joinToString(" \u00B7 ")
                        )
                    }
                    com.zyke.message.presentation.home.StatusPill(
                        if (own != null) "Added" else "Found",
                        if (own != null) com.zyke.message.presentation.home.Tone.GOOD else com.zyke.message.presentation.home.Tone.NEUTRAL
                    )
                    AppButton(if (own != null) "Edit" else "Add details", edit, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT)
                }
            }
            AppButton(
                text = "Add a card or account",
                onClick = { adding = true },
                style = ButtonStyle.OUTLINE,
                modifier = Modifier.padding(top = Spacing.md)
            )
        }
    }

    if (adding) {
        CardDialog(initial = null, onSave = { onSave(it); adding = false }, onDelete = null, onDismiss = { adding = false })
    }
    editing?.let { card ->
        CardDialog(
            initial = card,
            onSave = { onSave(it); editing = null },
            onDelete = if (editingIsNew) null else ({ onDelete(card.id); editing = null }),
            onDismiss = { editing = null }
        )
    }
}

/** Ask for the phone's fingerprint, face or screen lock when coming back to the app; needs a screen lock to exist. */
@Composable
private fun AppLockToggle(enabled: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val hasScreenLock = remember {
        (context.getSystemService(android.content.Context.KEYGUARD_SERVICE) as android.app.KeyguardManager).isDeviceSecure
    }
    ToggleRow(
        title = "Lock the app",
        description = if (hasScreenLock) {
            "Asks for your screen lock on return"
        } else {
            "Needs a screen lock on the phone"
        },
        checked = enabled && hasScreenLock,
        onCheckedChange = { if (hasScreenLock) onChange(it) },
        modifier = modifier
    )
}

/** The morning digest needs permission to show notifications on newer Android, asked for only when it is switched on. */
@Composable
private fun MorningDigestToggle(
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
    title: String = "Morning digest",
    description: String = "A short summary at about 8 AM",
    learnMore: String? = null
) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> onChange(granted) }
    ToggleRow(
        title = title,
        description = description,
        learnMore = learnMore,
        checked = enabled,
        onCheckedChange = { wanted ->
            val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            when {
                !wanted -> onChange(false)
                allowed -> onChange(true)
                else -> launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    )
}

/**
 * Says whether a one-time code that arrives now would be announced, and if not, why and the one tap that fixes it
 * (or opens the system page that does). A test button posts a made-up code so the person can see for themselves.
 */
@Composable
private fun CodeAlertStatus(switchedOn: Boolean, readingPaused: Boolean, onTurnOn: () -> Unit, onResume: () -> Unit) {
    val context = LocalContext.current
    var recheck by remember { mutableStateOf(0) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    // Coming back from the system's settings page looks again.
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val watcher = androidx.lifecycle.LifecycleEventObserver { _, event -> if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) recheck += 1 }
        lifecycle.addObserver(watcher)
        onDispose { lifecycle.removeObserver(watcher) }
    }
    val facts = remember(switchedOn, readingPaused, recheck) { LiveAlerts.facts(context, switchedOn, readingPaused) }
    val problem = com.zyke.message.domain.device.CodeAlertHealth.problem(facts)

    fun openSystem(intent: android.content.Intent) {
        val launch = intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(launch) }.onFailure {
            runCatching {
                context.startActivity(
                    android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", context.packageName, null))
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }
    fun appNotifications() = android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
    val allPermissions = buildList {
        add(Manifest.permission.RECEIVE_SMS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()
    val asker = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        recheck += 1
        val missing = allPermissions.filter { results[it] != true }
        when {
            missing.isEmpty() -> onTurnOn()
            // Android stops showing its prompt after a refusal or two; the settings page is the way left.
            else -> openSystem(
                if (Manifest.permission.POST_NOTIFICATIONS in missing) appNotifications()
                else android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", context.packageName, null))
            )
        }
    }
    val container = (context.applicationContext as com.zyke.message.ZykeMessagesApplication).container
    val title: String
    val detail: String
    val action: String?
    val run: () -> Unit
    when (problem) {
        null -> { title = "Code alerts are on"; detail = "A one-time code is announced with a Copy code button the moment it arrives."; action = null; run = {} }
        com.zyke.message.domain.device.CodeAlertProblem.SWITCHED_OFF -> {
            title = "Code alerts are off"; detail = "Turn on the buttons below to hear about codes."
            action = "Turn on"; run = {
                val allowed = allPermissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
                if (allowed) onTurnOn() else asker.launch(allPermissions)
            }
        }
        com.zyke.message.domain.device.CodeAlertProblem.READING_PAUSED -> {
            title = "Code alerts are paused"; detail = "Reading is paused, so new codes are not announced."; action = "Resume reading"; run = onResume
        }
        com.zyke.message.domain.device.CodeAlertProblem.CANNOT_HEAR_TEXTS -> {
            title = "Code alerts cannot hear new texts"; detail = "Allow SMS access so a code can be announced when it arrives."; action = "Allow SMS access"
            run = { asker.launch(allPermissions) }
        }
        com.zyke.message.domain.device.CodeAlertProblem.NOTIFICATIONS_BLOCKED -> {
            title = "Code alerts are off: notifications are blocked"; detail = "Android is not letting this app show notifications, so a code will not appear."
            action = "Open notification settings"; run = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) asker.launch(allPermissions) else openSystem(appNotifications())
            }
        }
        com.zyke.message.domain.device.CodeAlertProblem.CHANNEL_OFF -> {
            title = "Code alerts are off: the code channel is muted"; detail = "The One-time codes notification category is turned off in Android's settings."
            action = "Open notification settings"; run = {
                LiveAlerts.ensureChannels(context)
                openSystem(
                    android.content.Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                        .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, LiveAlerts.CODE_CHANNEL_ID)
                )
            }
        }
    }
    Column(Modifier.fillMaxWidth().padding(bottom = Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        RowTitle(title, color = if (problem == null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
        Caption(detail, maxLines = 2)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            if (action != null) AppButton(action, run, Modifier.weight(1f))
            // A test only means something when it can arrive.
            if (problem == null || problem == com.zyke.message.domain.device.CodeAlertProblem.SWITCHED_OFF) {
                AppButton(
                    "Send a test code", {
                        val posted = LiveAlerts.postTest(context, container.messageClassifier)
                        Toast.makeText(context, if (posted) "Test code sent: look in your notifications" else "Could not send: notifications are blocked", Toast.LENGTH_SHORT).show()
                    },
                    Modifier.weight(1f), style = if (action != null) ButtonStyle.OUTLINE else ButtonStyle.PRIMARY
                )
            }
        }
    }
}

/**
 * "Copy code" and "Pay" buttons the moment a code or card bill arrives. It needs permission to hear about new
 * texts and to show notifications, and both are asked for only when this is switched on.
 */
@Composable
private fun LiveAlertsToggle(enabled: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val permissions = buildList {
        add(Manifest.permission.RECEIVE_SMS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        onChange(permissions.all { results[it] == true })
    }
    ToggleRow(
        title = "Copy-code and Pay buttons",
        description = "A Copy code or Pay button the moment one arrives",
        checked = enabled,
        onCheckedChange = { wanted ->
            val allowed = permissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
            when {
                !wanted -> onChange(false)
                allowed -> onChange(true)
                else -> launcher.launch(permissions)
            }
        },
        modifier = modifier
    )
}

/** RCS chats are not in the SMS provider; the only way to see them is the messaging app's notifications. */
@Composable
private fun RcsSection() {
    val context = LocalContext.current
    SectionCard("RCS messages") {
        // Whether the reader is on, and what it has caught, so a missing top-up can be traced.
        val container = (context.applicationContext as com.zyke.message.ZykeMessagesApplication).container
        val enabled = androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
        val topUps = androidx.compose.runtime.remember { container.rcsStore.all().count { com.zyke.message.domain.classification.UpiLiteNotification.isFromNotification(it.sender) } }
        Caption(
            (if (enabled) "Notification access is on" else "Notification access is off") +
                " · $topUps UPI Lite top-up${if (topUps == 1) "" else "s"} caught from Google Pay",
            maxLines = 2
        )
        LearnMore("Adds new chats from Google and Samsung Messages, and UPI Lite top-ups from Google Pay so they count as spending.")
        AppButton(
            text = "Notification access",
            onClick = {
                runCatching {
                    context.startActivity(
                        android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            },
            style = ButtonStyle.OUTLINE,
            modifier = Modifier.padding(top = Spacing.md)
        )
    }
}

/**
 * A switch as a row: a title, one line saying what it does and the switch; the whole row toggles and gives the light tick.
 * Anything longer than a line goes in [learnMore], a "Learn more" line under the row that opens it.
 */
@Composable
internal fun ToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    learnMore: String? = null
) {
    val tick = com.zyke.message.presentation.common.rememberTick()
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .toggleable(value = checked, onValueChange = { tick(); onCheckedChange(it) }, role = Role.Switch),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                RowTitle(title)
                Caption(description, maxLines = 2)
            }
            Switch(checked = checked, onCheckedChange = null, modifier = Modifier.padding(start = Spacing.md))
        }
        if (learnMore != null) com.zyke.message.presentation.common.LearnMore(learnMore)
    }
}

/** The same sharing the launcher icon's "Share logs" shortcut does, for anyone: say what it holds, then open the share sheet. */
private fun shareLogs(context: android.content.Context) {
    when (com.zyke.message.data.diag.LogShare.share(context)) {
        com.zyke.message.data.diag.LogShare.Result.SHARED -> Unit
        com.zyke.message.data.diag.LogShare.Result.NOTHING_YET -> Toast.makeText(context, "No logs yet", Toast.LENGTH_SHORT).show()
        com.zyke.message.data.diag.LogShare.Result.FAILED -> Toast.makeText(context, "Could not share the logs", Toast.LENGTH_LONG).show()
    }
}

/** Always visible (not behind the seven taps) so that anyone can send the start-up record when something is wrong. */
@Composable
private fun ShareLogsLink() {
    val context = LocalContext.current
    TextButton(onClick = { shareLogs(context) }) { Text("Share logs") }
    Caption("A small file of start-up steps. Never message text.", maxLines = 2)
    LearnMore("A small text file of start-up steps and crash details (screen and function names, line numbers, how earlier runs ended). Never message text, amounts or names. It goes nowhere unless you pick an app to send it with. If the app crashes on launch, long-press its icon and choose Share logs.")
}

/** The version, which also unlocks developer options when tapped seven times, as on Android itself. */
@Composable
private fun VersionRow(versionName: String) {
    val context = LocalContext.current
    var taps by remember { mutableStateOf(0) }
    val developer by com.zyke.message.data.diag.DevLog.developerMode.collectAsStateWithLifecycle()
    Column(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "Version") {
            if (developer) {
                Toast.makeText(context, "You are already a developer", Toast.LENGTH_SHORT).show()
            } else {
                taps += 1
                when {
                    taps >= TAPS_TO_UNLOCK -> {
                        com.zyke.message.data.diag.DevLog.setDeveloperMode(context, true)
                        Toast.makeText(context, "Developer options are on", Toast.LENGTH_LONG).show()
                        taps = 0
                    }
                    taps >= TAPS_TO_UNLOCK - 3 -> Toast.makeText(context, "${TAPS_TO_UNLOCK - taps} more taps to become a developer", Toast.LENGTH_SHORT).show()
                }
            }
        }.padding(vertical = Spacing.xs)
    ) {
        Caption("Version $versionName")
    }
}

private const val TAPS_TO_UNLOCK = 7

/**
 * Developer options: the log file the app writes while this is on, to share when something does not work, and checks
 * that run the rename and category paths and say where they stop.
 */
@Composable
private fun DeveloperContent() {
    val context = LocalContext.current
    val container = (context.applicationContext as com.zyke.message.ZykeMessagesApplication).container
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val logging by com.zyke.message.data.diag.DevLog.logging.collectAsStateWithLifecycle()
    var refresh by remember { mutableStateOf(0) }
    var results by remember { mutableStateOf<List<String>>(emptyList()) }
    var running by remember { mutableStateOf(false) }
    val sizeKb = remember(refresh, logging) { com.zyke.message.data.diag.DevLog.sizeBytes() / 1024 }
    val tail = remember(refresh, logging) { com.zyke.message.data.diag.DevLog.tail() }

    SectionCard("Log file") {
        ToggleRow(
            title = "Write a log file",
            description = "Counts and ids only, never message text",
            learnMore = "Records what the app does while you use it: when things happen, counts and ids. Never message text, amounts, account numbers or names.",
            checked = logging,
            onCheckedChange = { com.zyke.message.data.diag.DevLog.setLogging(context, it); refresh += 1 }
        )
        Caption("Log size: $sizeKb KB", Modifier.padding(top = Spacing.sm))
        AppButton(
            "Share logs",
            { shareLogs(context) },
            Modifier.fillMaxWidth().padding(top = Spacing.sm)
        )
        Row(Modifier.fillMaxWidth().padding(top = Spacing.xs), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            AppButton("Refresh", { refresh += 1 }, Modifier.weight(1f), style = ButtonStyle.OUTLINE, size = ButtonSize.COMPACT)
            AppButton("Clear", { com.zyke.message.data.diag.DevLog.clear(); refresh += 1 }, Modifier.weight(1f), style = ButtonStyle.OUTLINE, size = ButtonSize.COMPACT)
        }
    }

    SectionCard("Checks") {
        Caption("Run these, then share the log.", maxLines = 2)
        LearnMore("The second check sets a test name and category on one message, looks that they show up, and puts everything back.")
        AppButton(
            if (running) "Running\u2026" else "Log a snapshot",
            {
                running = true
                scope.launch { results = com.zyke.message.data.diag.DevTools.snapshot(container); running = false; refresh += 1 }
            },
            Modifier.fillMaxWidth().padding(top = Spacing.sm), style = ButtonStyle.OUTLINE, enabled = !running && logging
        )
        AppButton(
            if (running) "Running\u2026" else "Test rename and category",
            {
                running = true
                scope.launch { results = com.zyke.message.data.diag.DevTools.selfTest(container); running = false; refresh += 1 }
            },
            Modifier.fillMaxWidth().padding(top = Spacing.sm), style = ButtonStyle.OUTLINE, enabled = !running && logging
        )
        if (!logging) Caption("Turn the log file on to run the checks.", Modifier.padding(top = Spacing.sm))
        results.forEach { line ->
            Text(line, Modifier.padding(top = Spacing.xs), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
    }

    SectionCard("Latest lines") {
        if (tail.isEmpty()) {
            Caption("Nothing recorded yet.")
        } else {
            Text(
                tail.takeLast(MAX_SHOWN_CHARS),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = androidx.compose.ui.unit.TextUnit(10f, androidx.compose.ui.unit.TextUnitType.Sp)),
                color = MaterialTheme.colorScheme.secondary
            )
        }
    }

    InboxAuditCard()

    SimulatorCard()

    SectionCard("Developer mode") {
        AppButton(
            "Turn developer options off",
            { com.zyke.message.data.diag.DevLog.setDeveloperMode(context, false) },
            Modifier.fillMaxWidth(), style = ButtonStyle.OUTLINE
        )
        Caption("Stops the log and hides this page. Tap the version seven times to bring it back.", Modifier.padding(top = Spacing.sm), maxLines = 2)
    }
}

private const val MAX_SHOWN_CHARS = 6_000
