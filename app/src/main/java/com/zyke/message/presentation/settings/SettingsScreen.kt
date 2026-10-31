package com.zyke.message.presentation.settings

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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.zyke.message.domain.model.UserCard
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

    LaunchedEffect(viewModel) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) ==
            PackageManager.PERMISSION_GRANTED
        viewModel.onIntent(Intent.ScreenStarted(granted))
    }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is Effect.ShareReport -> runCatching { ReportSharing.share(context, effect.fileName, effect.text) }
                    .onFailure { Toast.makeText(context, R.string.report_failed, Toast.LENGTH_LONG).show() }
                is Effect.ShowMessage -> Toast.makeText(context, effect.text, Toast.LENGTH_SHORT).show()
                is Effect.ShareData -> runCatching { ReportSharing.share(context, effect.fileName, effect.text, effect.mimeType, effect.subject) }
                    .onFailure { Toast.makeText(context, R.string.report_failed, Toast.LENGTH_LONG).show() }
            }
        }
    }

    SettingsPage(state, versionName, viewModel::onIntent, onDismiss)
}

/** The pages Settings is made of; the hub lists them and each opens as a page of its own. */
private enum class SettingsGroup(val title: String, val icon: Int) {
    MONEY("Money plan", R.drawable.ic_wallet_24),
    LIMITS("Spending limits", R.drawable.ic_goal_24),
    CARDS("Cards and accounts", R.drawable.ic_card_24),
    MESSAGES("Messages and pictures", R.drawable.baseline_message_24),
    ALERTS("Reminders and alerts", R.drawable.ic_reminder_24),
    MARKETS("Market watch", R.drawable.ic_trending_24),
    PRIVACY("Privacy and security", R.drawable.ic_lock_24),
    APPEARANCE("Appearance", R.drawable.ic_sun_24),
    ABOUT("Your data and about", R.drawable.ic_info_24),
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
    val open = SettingsGroup.entries.firstOrNull { it.name == openName }
    val container = (LocalContext.current.applicationContext as com.zyke.message.ZykeMessagesApplication).container
    val watch by container.watchlist.watch.collectAsStateWithLifecycle()
    val developer by com.zyke.message.data.diag.DevLog.developerMode.collectAsStateWithLifecycle()
    val logging by com.zyke.message.data.diag.DevLog.logging.collectAsStateWithLifecycle()

    com.zyke.message.presentation.common.AppPage("Settings", onDismiss) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val todo = setupSteps(state)
            if (todo.isNotEmpty()) {
                com.zyke.message.presentation.common.FintechCard {
                    Caption("FINISH SETTING UP")
                    todo.forEachIndexed { index, step ->
                        Row(
                            Modifier.fillMaxWidth().fullWidthClickable(role = Role.Button, onClickLabel = step.first) { openName = step.third.name }.padding(top = if (index == 0) 8.dp else 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                RowTitle(step.first)
                                Caption(step.second, maxLines = 2)
                            }
                            Text("\u203A", color = com.zyke.message.presentation.messages.components.mutedColor())
                        }
                    }
                }
            }

            SettingsList(
                "Your money",
                listOf(
                    SettingsGroup.MONEY to moneySummary(state),
                    SettingsGroup.LIMITS to limitsSummary(state),
                    SettingsGroup.CARDS to cardsSummary(state)
                )
            ) { openName = it.name }
            SettingsList(
                "Your messages",
                listOf(
                    SettingsGroup.MESSAGES to messagesSummary(state),
                    SettingsGroup.ALERTS to alertsSummary(state),
                    SettingsGroup.MARKETS to if (watch.enabled) "On \u00B7 " + com.zyke.message.presentation.format.DisplayFormatters.count(watch.symbols.size, "share", "shares") else "Off"
                )
            ) { openName = it.name }
            SettingsList(
                "This app",
                buildList {
                    add(SettingsGroup.PRIVACY to if (state.settings.appLock) "App lock on" else "Private on this phone")
                    add(SettingsGroup.APPEARANCE to state.settings.themeMode.label)
                    add(SettingsGroup.ABOUT to "Version $versionName")
                    if (developer) add(SettingsGroup.DEVELOPER to if (logging) "Writing a log file" else "Log is off")
                }
            ) { openName = it.name }
        }
    }

    open?.takeIf { it != SettingsGroup.DEVELOPER || developer }?.let { group ->
        com.zyke.message.presentation.common.AppPage(group.title, { openName = null }) {
            // This page is about one thing, so its sections show their contents instead of folding away.
            CompositionLocalProvider(com.zyke.message.presentation.dashboard.components.LocalSectionsInline provides true) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    GroupContent(group, state, versionName, onIntent, onDeleteAll = { deleting = true })
                }
            }
        }
    }

    if (deleting) DeleteAllSheet(onDismiss = { deleting = false })

    if (state.confirmingReset) {
        AlertDialog(
            onDismissRequest = { onIntent(Intent.CancelReset) },
            title = { Text("Reset marks and budgets?") },
            text = { Text("This clears read, pinned and archived marks, dismissed tips and all budgets. It can't be undone.") },
            confirmButton = { AppButton("Reset", { onIntent(Intent.ConfirmReset) }, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT) },
            dismissButton = { AppButton("Cancel", { onIntent(Intent.CancelReset) }, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT) }
        )
    }
}

/** The last way out: everything the app has saved, gone, after typing a word so it cannot happen by accident. */
@Composable
private fun DeleteAllSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var typed by rememberSaveable { mutableStateOf("") }
    com.zyke.message.presentation.common.FormSheet(
        title = "Delete all my data?",
        subtitle = "This cannot be undone",
        onDismiss = onDismiss,
        primaryLabel = "Delete everything",
        primaryEnabled = typed.trim().equals("DELETE", ignoreCase = false),
        hint = "Type DELETE to confirm",
        onPrimary = {
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
        com.zyke.message.presentation.common.FormField("Type DELETE to confirm", typed, { typed = it }, placeholder = "DELETE")
    }
}

/** What is not set up yet and would help: the thing, why, and the page that sets it. */
private fun setupSteps(state: State): List<Triple<String, String, SettingsGroup>> = buildList {
    if (state.plan.salary == null && state.spottedSalary == null) {
        add(Triple("Add your salary", "So the app can show what is left to spend each month", SettingsGroup.MONEY))
    }
    if (state.limits.isEmpty()) {
        add(Triple("Set spending limits", if (state.limitSuggestions.isNotEmpty()) "Suggestions are ready for you" else "Keep a category within what you choose", SettingsGroup.LIMITS))
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
    val limits = if (count == 0) "None set" else com.zyke.message.presentation.format.DisplayFormatters.count(count, "limit", "limits")
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
    state.settings.morningDigest && state.settings.liveAlerts -> "Morning summary and code buttons on"
    state.settings.morningDigest -> "Morning summary on"
    state.settings.liveAlerts -> "Code buttons on"
    else -> "Off"
}

/** A titled card of rows: an icon, what it is, how it is set, and a chevron. */
@Composable
private fun SettingsList(title: String, rows: List<Pair<SettingsGroup, String>>, onOpen: (SettingsGroup) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        GroupLabel(title)
        com.zyke.message.presentation.common.FintechCard {
            rows.forEachIndexed { index, (group, summary) ->
                if (index > 0) androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .fullWidthClickable(role = Role.Button, onClickLabel = "Open ${group.title}") { onOpen(group) }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    androidx.compose.foundation.layout.Box(
                        Modifier.size(40.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), androidx.compose.foundation.shape.RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        androidx.compose.foundation.Image(
                            painter = androidx.compose.ui.res.painterResource(group.icon), contentDescription = null, modifier = Modifier.size(22.dp),
                            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(MaterialTheme.colorScheme.primary)
                        )
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                        RowTitle(group.title)
                        Caption(summary, maxLines = 2)
                    }
                    Text("\u203A", style = MaterialTheme.typography.titleLarge, color = com.zyke.message.presentation.messages.components.mutedColor())
                }
            }
        }
    }
}

@Composable
private fun GroupContent(group: SettingsGroup, state: State, versionName: String, onIntent: (Intent) -> Unit, onDeleteAll: () -> Unit) {
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
            onSave = { category, daily, monthly -> onIntent(Intent.SetLimits(category, daily, monthly)) },
            onRemove = { onIntent(Intent.RemoveLimits(it)) },
            spendable = state.spendable,
            suggestions = state.limitSuggestions,
            cardSpend = state.cardSpend,
            onCardLimit = { onIntent(Intent.SetCardLimit(it)) }
        )
        SettingsGroup.CARDS -> CardsSection(state.accounts, state.cards, onSave = { onIntent(Intent.SaveCard(it)) }, onDelete = { onIntent(Intent.DeleteCard(it)) })
        SettingsGroup.MESSAGES -> {
            SectionCard("Pictures") {
                Caption("Tap the photo on any message sheet to change it", maxLines = 3)
                ToggleRow(
                    title = "Fetch brand logos from the internet",
                    description = "Looks up logos of well-known brands once each. Only the brand name is sent.",
                    checked = state.settings.fetchLogos,
                    onCheckedChange = { onIntent(Intent.SetFetchLogos(it)) },
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
            RcsSection()
            SectionCard("Parsing health") {
                val diagnostics = state.diagnostics
                if (diagnostics == null) {
                    Caption("Allow SMS access to see how well your messages are understood.", maxLines = 3)
                } else if (diagnostics.moneyMessages == 0) {
                    Caption("No money messages found yet.")
                } else {
                    val fraction = diagnostics.recognised.toFloat() / diagnostics.moneyMessages
                    RowTitle("${diagnostics.recognised} of ${diagnostics.moneyMessages} money messages understood")
                    ShareBar(fraction)
                    Caption(
                        if (diagnostics.unrecognised == 0) "Everything was understood."
                        else "${diagnostics.unrecognised} shown as plain text. Share a report as a file, with numbers, names and links masked, so they can be taught.",
                        Modifier.padding(top = 8.dp),
                        maxLines = 4
                    )
                    AppButton(
                        text = "Share report",
                        onClick = { onIntent(Intent.ShareDiagnostics) },
                        style = ButtonStyle.OUTLINE,
                        enabled = diagnostics.unrecognised > 0,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
            }
        }
        SettingsGroup.ALERTS -> SectionCard("Reminders and alerts") {
            MorningDigestToggle(enabled = state.settings.morningDigest, onChange = { onIntent(Intent.SetMorningDigest(it)) })
            LiveAlertsToggle(enabled = state.settings.liveAlerts, onChange = { onIntent(Intent.SetLiveAlerts(it)) }, modifier = Modifier.padding(top = 12.dp))
            Caption("A \"Your day\" widget is in the widget list", Modifier.padding(top = 12.dp), maxLines = 2)
        }
        SettingsGroup.MARKETS -> com.zyke.message.presentation.markets.MarketSettingsSection()
        SettingsGroup.PRIVACY -> SectionCard("Privacy") {
            Caption("Messages stay on this phone", maxLines = 5)
            ToggleRow(
                title = "Block screenshots",
                description = "Also hides the recent-apps view",
                checked = state.settings.screenProtection,
                onCheckedChange = { onIntent(Intent.SetScreenProtection(it)) },
                modifier = Modifier.padding(top = 12.dp)
            )
            AppLockToggle(enabled = state.settings.appLock, onChange = { onIntent(Intent.SetAppLock(it)) }, modifier = Modifier.padding(top = 12.dp))
            val container = (LocalContext.current.applicationContext as com.zyke.message.ZykeMessagesApplication).container
            ToggleRow(
                title = "Pause reading messages",
                description = "Stops reading your messages and listening for new ones. What was already read stays, and you can carry on any time.",
                checked = state.settings.readingPaused,
                onCheckedChange = { paused ->
                    onIntent(Intent.SetReadingPaused(paused))
                    // Carrying on: look at what arrived while paused.
                    if (!paused) container.smsRepository.refreshRecent()
                },
                modifier = Modifier.padding(top = 12.dp)
            )
        }
        SettingsGroup.APPEARANCE -> SectionCard("Theme") {
            com.zyke.message.presentation.common.SegmentedChoice(
                choices = ThemeMode.entries.map { com.zyke.message.presentation.common.Choice(it, it.label) },
                selected = state.settings.themeMode,
                onSelect = { onIntent(Intent.SetThemeMode(it)) }
            )
            Caption("Automatic follows your phone", Modifier.padding(top = 8.dp))
        }
        SettingsGroup.DEVELOPER -> DeveloperContent()
        SettingsGroup.ABOUT -> {
            SectionCard("Take your data with you") {
                Caption("Nothing leaves the phone unless you share the file yourself.", maxLines = 3)
                AppButton(
                    text = "Export transactions (spreadsheet)",
                    onClick = { onIntent(Intent.ExportTransactions) },
                    style = ButtonStyle.OUTLINE,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                )
                Caption("The transactions read so far. Message text is not included.", Modifier.padding(top = 4.dp), maxLines = 2)
                AppButton(
                    text = "Export my setup",
                    onClick = { onIntent(Intent.ExportSetup) },
                    style = ButtonStyle.OUTLINE,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                )
                Caption("Plan, limits, cards, goals, reminders, events and entries you added.", Modifier.padding(top = 4.dp), maxLines = 2)
            }
            BackupCard()
            SectionCard("Clear data") {
                Caption("Resetting clears marks and budgets, never your messages. Deleting removes everything this app has saved.", maxLines = 4)
                AppButton(
                    text = "Reset marks and budgets",
                    onClick = { onIntent(Intent.AskReset) },
                    style = ButtonStyle.OUTLINE,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                )
                AppButton(
                    text = "Delete all my data",
                    onClick = onDeleteAll,
                    style = ButtonStyle.OUTLINE,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
            }
            SectionCard("About") {
                VersionRow(versionName)
                Caption("Plus Jakarta Sans, SIL Open Font License 1.1", Modifier.padding(top = 4.dp))
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
            "They appear here as soon as a message mentions one, or you can add one by its last four digits.",
            icon = R.drawable.ic_card_24,
            actionLabel = "Add a card or account",
            onAction = { adding = true }
        )
    } else {
        SectionCard("Found and added") {
            Caption("Messages are matched to these by their last four digits", maxLines = 3)
            accounts.forEach { account ->
                val own = account.userCard
                // The details to edit: the added card, or one filled in from what the messages show.
                val edit = {
                    editingIsNew = own == null
                    editing = own ?: UserCard(
                        id = java.util.UUID.randomUUID().toString(),
                        type = when (account.kind) {
                            com.zyke.message.domain.accounts.AccountKind.CREDIT_CARD -> com.zyke.message.domain.model.CardType.CREDIT
                            com.zyke.message.domain.accounts.AccountKind.DEBIT_CARD -> com.zyke.message.domain.model.CardType.DEBIT
                            com.zyke.message.domain.accounts.AccountKind.BANK_ACCOUNT -> com.zyke.message.domain.model.CardType.ACCOUNT
                        },
                        last4 = account.last4, name = "", bankName = account.bankName.displayString
                    )
                }
                Row(
                    Modifier.fillMaxWidth().fullWidthClickable(role = Role.Button, onClickLabel = "Edit ${account.title}", onClick = edit).padding(top = 14.dp),
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
                modifier = Modifier.padding(top = 14.dp)
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
private fun MorningDigestToggle(enabled: Boolean, onChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> onChange(granted) }
    ToggleRow(
        title = "Morning digest",
        description = "A short summary at about 8 AM",
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
            maxLines = 3
        )
        Caption(
            "Adds new chats from Google and Samsung Messages, and UPI Lite top-ups from Google Pay so they count as spending",
            maxLines = 5
        )
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
            modifier = Modifier.padding(top = 12.dp)
        )
    }
}

@Composable
private fun ToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier
            .fillMaxWidth()
            .toggleable(value = checked, onValueChange = onCheckedChange, role = Role.Switch),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            RowTitle(title)
            Caption(description, maxLines = 6)
        }
        Switch(checked = checked, onCheckedChange = null, modifier = Modifier.padding(start = 12.dp))
    }
}

/** A small heading over a group of settings tiles. */
@Composable
private fun GroupLabel(text: String) {
    Text(
        text,
        Modifier.padding(start = 4.dp, top = 12.dp).heading(),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.secondary
    )
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
        }.padding(vertical = 4.dp)
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
            description = "Records what the app does while you use it: when things happen, counts and ids. Never message text, amounts, account numbers or names.",
            checked = logging,
            onCheckedChange = { com.zyke.message.data.diag.DevLog.setLogging(context, it); refresh += 1 }
        )
        Caption("Log size: $sizeKb KB", Modifier.padding(top = 8.dp))
        AppButton(
            "Share the log file",
            {
                val stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))
                runCatching { ReportSharing.share(context, "zyke-log-$stamp.txt", com.zyke.message.data.diag.DevLog.readAll(), "text/plain", "Zyke Message developer log") }
                    .onFailure { Toast.makeText(context, "Could not share the log", Toast.LENGTH_LONG).show() }
            },
            Modifier.fillMaxWidth().padding(top = 8.dp),
            enabled = sizeKb > 0 || tail.isNotEmpty()
        )
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppButton("Refresh", { refresh += 1 }, Modifier.weight(1f), style = ButtonStyle.OUTLINE, size = ButtonSize.COMPACT)
            AppButton("Clear", { com.zyke.message.data.diag.DevLog.clear(); refresh += 1 }, Modifier.weight(1f), style = ButtonStyle.OUTLINE, size = ButtonSize.COMPACT)
        }
    }

    SectionCard("Checks") {
        Caption("Run these, then share the log. The second sets a test name and category on one message, checks they show up, and puts everything back.", maxLines = 4)
        AppButton(
            if (running) "Running\u2026" else "Log a snapshot",
            {
                running = true
                scope.launch { results = com.zyke.message.data.diag.DevTools.snapshot(container); running = false; refresh += 1 }
            },
            Modifier.fillMaxWidth().padding(top = 8.dp), style = ButtonStyle.OUTLINE, enabled = !running && logging
        )
        AppButton(
            if (running) "Running\u2026" else "Test rename and category",
            {
                running = true
                scope.launch { results = com.zyke.message.data.diag.DevTools.selfTest(container); running = false; refresh += 1 }
            },
            Modifier.fillMaxWidth().padding(top = 8.dp), style = ButtonStyle.OUTLINE, enabled = !running && logging
        )
        if (!logging) Caption("Turn the log file on to run the checks.", Modifier.padding(top = 8.dp))
        results.forEach { line ->
            Text(line, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
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

    SectionCard("Developer mode") {
        AppButton(
            "Turn developer options off",
            { com.zyke.message.data.diag.DevLog.setDeveloperMode(context, false) },
            Modifier.fillMaxWidth(), style = ButtonStyle.OUTLINE
        )
        Caption("This stops the log and hides this page. Tap the version seven times to bring it back.", Modifier.padding(top = 8.dp), maxLines = 3)
    }
}

private const val MAX_SHOWN_CHARS = 6_000
