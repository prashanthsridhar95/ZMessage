package com.zyke.message.presentation

import com.zyke.message.presentation.common.ReadingNote
import kotlinx.coroutines.flow.first
import com.zyke.message.notifications.BackfillService
import android.Manifest
import android.app.KeyguardManager
import android.os.Bundle
import android.os.SystemClock
import androidx.compose.runtime.mutableStateOf
import com.zyke.message.notifications.DaysAheadWidget
import com.zyke.message.notifications.DigestScheduler
import com.zyke.message.notifications.LiveAlerts
import com.zyke.message.notifications.WeeklyScheduler
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.runtime.CompositionLocalProvider
import com.zyke.message.presentation.common.LocalImageStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.zyke.message.ZykeMessagesApplication
import com.zyke.message.domain.model.ThemeMode
import com.zyke.message.presentation.dashboard.DashboardContract
import com.zyke.message.presentation.dashboard.DashboardRoute
import com.zyke.message.presentation.dashboard.DashboardViewModel
import com.zyke.message.presentation.home.HomeContract
import com.zyke.message.presentation.home.HomePage
import com.zyke.message.presentation.home.HomeRoute
import com.zyke.message.presentation.home.HomeViewModel
import com.zyke.message.presentation.messages.MessagesContract
import com.zyke.message.presentation.messages.MessagesRoute
import com.zyke.message.presentation.messages.MessagesViewModel
import com.zyke.message.presentation.settings.SettingsRoute
import com.zyke.message.presentation.settings.SettingsViewModel
import com.zyke.message.presentation.theme.ZykeMessageTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val container get() = (application as ZykeMessagesApplication).container

    private val messagesViewModel: MessagesViewModel by viewModels {
        MessagesViewModel.Factory(
            container.observeMessages, container.userMessageState, container.categoryOverrides, container.mutedSenders, container.nicknames, container.merges
        )
    }

    private val dashboardViewModel: DashboardViewModel by viewModels {
        DashboardViewModel.Factory(
            container.observeMessages, container.budgets, container.cards, container.userMessageState, container.moneyPlan,
            container.hiddenAccounts, container.providentFundAccounts, container.insightsFilter
        )
    }

    private val homeViewModel: HomeViewModel by viewModels {
        HomeViewModel.Factory(
            container.observeMessages, container.userMessageState, container.budgets, container.categoryOverrides, container.mutedSenders,
            container.wishlist, container.splits, container.nicknames, container.moneyPlan, container.cards,
            container.goals, container.reminders, container.hiddenAccounts, container.providentFundAccounts,
            container.calendarEvents, container.setAsides, container.manualEntries, container.merges, container.recurringPayments, container.loans, container.planActions, container.dailyBudgetOverride,
            container.cancelledSubscriptions
        )
    }

    private val settingsViewModel: SettingsViewModel by viewModels {
        SettingsViewModel.Factory(
            container.observeMessages, container.settings, container.userMessageState, container.budgets, container.cards,
            onLogosTurnedOff = container.imageStore::clearLogos,
            onDigestChanged = { DigestScheduler(this).apply(it) },
            onLiveAlertsChanged = { LiveAlerts.setEnabled(this, it) },
            onWeeklySummaryChanged = { WeeklyScheduler(this).apply(it) },
            planRepository = container.moneyPlan,
            hiddenAccounts = container.hiddenAccounts,
            setupExtras = { com.zyke.message.data.export.SetupExport.extras(container) }
        )
    }

    /** One permission prompt for the whole app; every tab is told the answer. */
    private val smsPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        com.zyke.message.data.diag.DevLog.d("permission") { "SMS permission result: granted=$granted" }
        messagesViewModel.onIntent(MessagesContract.Intent.PermissionResult(granted))
        if (granted) {
            // Anything that tried to read before the permission was granted found nothing: ask for a full look now.
            runCatching { container.smsRepository.refresh() }
            homeViewModel.onIntent(HomeContract.Intent.ScreenStarted(true))
            dashboardViewModel.onIntent(DashboardContract.Intent.ScreenStarted(true))
        }
        askNextPermission()
    }

    // ---- Google sign-in for the optional Drive backup: Google's own screen, then a short-lived access token.

    private var pendingSignIn: ((String?) -> Unit)? = null

    private val driveConsent = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val callback = pendingSignIn
        pendingSignIn = null
        val token = if (result.resultCode == RESULT_OK) container.backup.driveAuth?.tokenFromResult(result.data) else null
        callback?.invoke(token)
    }

    private fun requestDriveToken(onToken: (String?) -> Unit) {
        val auth = container.backup.driveAuth
        if (auth == null) { onToken(null); return }
        lifecycleScope.launch {
            when (val answer = auth.authorize()) {
                is com.zyke.message.data.backup.DriveAuthResult.Token -> onToken(answer.value)
                is com.zyke.message.data.backup.DriveAuthResult.NeedsUser -> {
                    pendingSignIn = onToken
                    driveConsent.launch(androidx.activity.result.IntentSenderRequest.Builder(answer.request.intentSender).build())
                }
                is com.zyke.message.data.backup.DriveAuthResult.Failed -> onToken(null)
            }
        }
    }

    private val backupHost by lazy { com.zyke.message.presentation.backup.BackupHost(::requestDriveToken) }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { askNextPermission() }

    // ---- asking for what the app needs, once each time it is opened

    private var permissionStage = -1
    private val showAccessPrompt = mutableStateOf(false)
    private val showSmsExplainer = mutableStateOf(false)

    /**
     * On opening, whatever has not been allowed is asked for, one at a time: SMS access, then permission to show
     * notifications, then notification access (which Android only lets the person grant on its own settings page, so
     * it is explained first). Something already allowed is skipped, and it is asked only once per opening.
     */
    private fun startPermissionFlow() {
        if (permissionStage != -1) return
        permissionStage = 0
        askNextPermission()
    }

    private fun askNextPermission() {
        // Only while the opening flow is running, so a prompt from a button elsewhere does not start it.
        if (permissionStage < 0 || permissionStage > LAST_STAGE) return
        when (permissionStage++) {
            // Why, in plain words, before the phone's own prompt appears.
            0 -> if (!has(Manifest.permission.READ_SMS)) showSmsExplainer.value = true else askNextPermission()
            1 -> if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU && !has(Manifest.permission.POST_NOTIFICATIONS)) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                askNextPermission()
            }
            2 -> if (!hasNotificationAccess()) showAccessPrompt.value = true
        }
    }

    private fun has(permission: String) =
        androidx.core.content.ContextCompat.checkSelfPermission(this, permission) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun hasNotificationAccess() =
        androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        com.zyke.message.presentation.home.EntryRequests.fromIntent(intent)?.let { com.zyke.message.presentation.home.EntryRequests.pending.value = it }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Opened by the quick-add widget, the quick-settings tile or "Share": the entry sheet follows on Home.
        if (savedInstanceState == null) com.zyke.message.presentation.home.EntryRequests.fromIntent(intent)?.let { com.zyke.message.presentation.home.EntryRequests.pending.value = it }
        applyScreenProtection(container.settings.settings.value.screenProtection)
        // After a restore the saved icon colour may differ from the enabled launcher alias: put them in step, off the main thread.
        val savedIcon = container.settings.settings.value.appIcon
        val appContext = applicationContext
        Thread({ com.zyke.message.presentation.settings.AppIconSwitcher.reconcile(appContext, savedIcon) }, "app-icon-reconcile").start()
        observeScreenProtection()
        // The alarm for the next timed reminder follows whatever reminders there are.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                container.reminders.reminders.collect {
                    // Alarm and widget bookkeeping, off the main thread so it never lands in a frame.
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                        com.zyke.message.notifications.ReminderAlarms.schedule(this@MainActivity)
                        DaysAheadWidget.refresh(this@MainActivity)
                    }
                }
            }
        }
        // The paired watch (if any) gets a small snapshot when the app comes to the front and whenever what it shows changes.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                kotlinx.coroutines.flow.combine(homeViewModel.state, container.settings.settings) { home, settings ->
                    com.zyke.message.wear.WatchSnapshots.from(home, settings.hideAmounts)
                }.flowOn(kotlinx.coroutines.Dispatchers.Default).collect { snapshot ->
                    if (snapshot != null) container.wearSync.offer(snapshot)
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                container.calendarEvents.events.collect {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { DaysAheadWidget.refresh(this@MainActivity) }
                }
            }
        }
        // The "Your day" widget shows dots for amounts while they are hidden, so it is redrawn when that changes.
        lifecycleScope.launch {
            container.settings.settings.map { it.hideAmounts }.distinctUntilChanged().drop(1).collect {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { DaysAheadWidget.refresh(this@MainActivity) }
            }
        }
        // Locked from the very first frame, so the inbox never flashes before the lock screen.
        lockedState.value = container.settings.settings.value.appLock &&
            getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

        // The install time rides along with the version, so it is plain which build is on the phone.
        val versionName by lazy { runCatching {
            val info = packageManager.getPackageInfo(packageName, 0)
            val installed = java.time.format.DateTimeFormatter.ofPattern("d MMM, HH:mm")
                .format(java.time.Instant.ofEpochMilli(info.lastUpdateTime).atZone(java.time.ZoneId.systemDefault()))
            "${info.versionName} (installed $installed)"
        }.getOrNull().orEmpty() }

        setContent {
            val appSettings by container.settings.settings.collectAsStateWithLifecycle()
            val darkTheme = when (appSettings.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK, ThemeMode.BLACK -> true
            }
            // The bar icons follow the app's theme, not the phone's: a Day theme on a dark phone needs dark icons.
            val view = LocalView.current
            SideEffect {
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
            val amountsRevealed by amountReveal.revealed.collectAsStateWithLifecycle()
            CompositionLocalProvider(
                LocalImageStore provides container.imageStore,
                com.zyke.message.presentation.backup.LocalBackupHost provides backupHost,
                com.zyke.message.presentation.common.LocalAmountReveal provides amountReveal,
                // Hidden = the setting is on and no reveal window is open. The eye turns hiding on at once, and shows
                // amounts (after the screen lock) only for a window; leaving hiding off for good is a setting.
                com.zyke.message.presentation.common.LocalPrivacyMode provides remember(appSettings.hideAmounts, amountsRevealed) {
                    com.zyke.message.presentation.common.PrivacyState(
                        hidden = appSettings.hideAmounts && !amountsRevealed,
                        enabled = appSettings.hideAmounts,
                        toggle = {
                            when {
                                !container.settings.settings.value.hideAmounts -> container.settings.setHideAmounts(true)
                                amountReveal.revealed.value -> amountReveal.hideNow()
                                else -> amountReveal.requestReveal()
                            }
                        }
                    )
                }
            ) {
            ZykeMessageTheme(darkTheme = darkTheme, trueBlack = appSettings.themeMode == ThemeMode.BLACK, accent = appSettings.accent, customAccent = appSettings.customAccent) {
                val locked by lockedState
                // Behind the lock screen nothing is asked; once it is open, the permissions are.
                var welcomePending by remember { mutableStateOf(container.welcomeStillPending()) }
                androidx.compose.runtime.LaunchedEffect(locked, welcomePending) { if (!locked && !welcomePending) startPermissionFlow() }
                if (showSmsExplainer.value && !locked) {
                    SmsExplainer(
                        onContinue = {
                            showSmsExplainer.value = false
                            smsPermission.launch(Manifest.permission.READ_SMS)
                        },
                        onLater = {
                            showSmsExplainer.value = false
                            askNextPermission()
                        }
                    )
                }
                if (showAccessPrompt.value && !locked) {
                    NotificationAccessPrompt(
                        onOpenSettings = {
                            showAccessPrompt.value = false
                            runCatching {
                                startActivity(android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                            }
                        },
                        onLater = { showAccessPrompt.value = false }
                    )
                }
                if (welcomePending) {
                    com.zyke.message.presentation.backup.WelcomeBack(onFinished = { welcomePending = false })
                } else if (locked) {
                    LockScreen(onUnlock = ::requestUnlock)
                } else {
                // Only the few fields the shell shows are watched, and the flows are built once: watching a whole screen
                // state here would redraw the toolbar and the bottom bar on every change to the lists beneath them.
                val unread by remember { homeViewModel.state.map { it.unreadCount }.distinctUntilChanged() }
                    .collectAsStateWithLifecycle(0)
                val inbox by remember {
                    messagesViewModel.state.map { it.toToolbarState() }.distinctUntilChanged()
                }.collectAsStateWithLifecycle(InboxToolbarState())
                val money by remember {
                    dashboardViewModel.state
                        .map { MoneyToolbarState(it.dashboard != null && !it.permissionMissing && !it.hasError, it.activeFilterCount) }
                        .distinctUntilChanged()
                }.collectAsStateWithLifecycle(MoneyToolbarState())

                val toolbarCallbacks = remember {
                    ToolbarCallbacks(
                        onToggleSearch = { messagesViewModel.onIntent(MessagesContract.Intent.ToggleSearch) },
                        onQueryChange = { messagesViewModel.onIntent(MessagesContract.Intent.SetQuery(it)) },
                        onInboxFilters = { messagesViewModel.onIntent(MessagesContract.Intent.OpenFilters) },
                        onMoneyFilters = { dashboardViewModel.onIntent(DashboardContract.Intent.OpenFilters) }
                    )
                }

                AppRoot(
                    inboxBadge = unread,
                    // Collected inside the note, so the progress ticking while the inbox is read redraws only the note.
                    topNote = {
                        val reading by container.observeMessages.progress.collectAsStateWithLifecycle()
                        ReadingNote(reading)
                    },
                    toolbar = { tab, openSettings ->
                        AppToolbar(
                            tab = tab,
                            inbox = inbox,
                            money = money,
                            callbacks = toolbarCallbacks,
                            onOpenSettings = openSettings
                        )
                    },
                    settings = { onDismiss ->
                        SettingsRoute(settingsViewModel, versionName, onDismiss)
                    },
                    onOpenMessage = { homeViewModel.onIntent(HomeContract.Intent.OpenMessage(it)) },
                    onOpenSubscriptions = { homeViewModel.onIntent(HomeContract.Intent.OpenSubscriptions) },
                    search = { onDismiss, navigate ->
                        val openSettings = com.zyke.message.presentation.common.LocalOpenSettings.current
                        com.zyke.message.presentation.search.SearchRoute(
                            home = homeViewModel.state,
                            watchlist = container.watchlist,
                            recents = container.recentSearches,
                            onOpen = { hit ->
                                val kind = hit.kind
                                // Tabs and Home's own pages are reached by dismissing the search and then asking for them.
                                fun home(intent: HomeContract.Intent?) {
                                    onDismiss()
                                    navigate(AppTab.HOME)
                                    if (intent != null) homeViewModel.onIntent(intent)
                                }
                                when (kind) {
                                    com.zyke.message.domain.search.SearchKind.MESSAGE,
                                    com.zyke.message.domain.search.SearchKind.HAND_MADE,
                                    com.zyke.message.domain.search.SearchKind.TAG ->
                                        hit.messageId?.let { home(HomeContract.Intent.OpenMessage(it)) }
                                    com.zyke.message.domain.search.SearchKind.ACCOUNT -> home(HomeContract.Intent.OpenAccount(hit.id))
                                    com.zyke.message.domain.search.SearchKind.GOAL -> home(HomeContract.Intent.OpenGoals)
                                    com.zyke.message.domain.search.SearchKind.REMINDER -> home(HomeContract.Intent.OpenReminders)
                                    com.zyke.message.domain.search.SearchKind.EVENT -> home(HomeContract.Intent.OpenAgenda)
                                    com.zyke.message.domain.search.SearchKind.STOCK -> Unit
                                    com.zyke.message.domain.search.SearchKind.PAGE -> when (hit.id) {
                                        com.zyke.message.domain.search.SearchPages.ACCOUNTS -> { onDismiss(); navigate(AppTab.ACCOUNTS) }
                                        com.zyke.message.domain.search.SearchPages.BILLS -> { onDismiss(); navigate(AppTab.BILLS) }
                                        com.zyke.message.domain.search.SearchPages.INSIGHTS -> { onDismiss(); navigate(AppTab.MONEY) }
                                        com.zyke.message.domain.search.SearchPages.MESSAGES -> { onDismiss(); navigate(AppTab.INBOX) }
                                        com.zyke.message.domain.search.SearchPages.SETTINGS -> { onDismiss(); openSettings() }
                                        com.zyke.message.domain.search.SearchPages.CALENDAR -> home(HomeContract.Intent.OpenCalendar)
                                        com.zyke.message.domain.search.SearchPages.GOALS -> home(HomeContract.Intent.OpenGoals)
                                        com.zyke.message.domain.search.SearchPages.REMINDERS -> home(HomeContract.Intent.OpenReminders)
                                        com.zyke.message.domain.search.SearchPages.SUBSCRIPTIONS -> home(HomeContract.Intent.OpenSubscriptions)
                                    }
                                }
                            },
                            onDismiss = onDismiss
                        )
                    }
                ) { tab, modifier, navigate ->
                    when (tab) {
                        AppTab.HOME -> HomeRoute(
                            viewModel = homeViewModel,
                            onNavigate = navigate,
                            onRequestPermission = { smsPermission.launch(Manifest.permission.READ_SMS) },
                            modifier = modifier
                        )
                        AppTab.MONEY -> DashboardRoute(
                            viewModel = dashboardViewModel,
                            onRequestPermission = { smsPermission.launch(Manifest.permission.READ_SMS) },
                            modifier = modifier
                        )
                        AppTab.BILLS -> HomeRoute(
                            viewModel = homeViewModel,
                            onNavigate = navigate,
                            onRequestPermission = { smsPermission.launch(Manifest.permission.READ_SMS) },
                            modifier = modifier,
                            page = HomePage.BILLS
                        )
                        AppTab.ACCOUNTS -> HomeRoute(
                            viewModel = homeViewModel,
                            onNavigate = navigate,
                            onRequestPermission = { smsPermission.launch(Manifest.permission.READ_SMS) },
                            modifier = modifier,
                            page = HomePage.ACCOUNTS
                        )
                        AppTab.INBOX -> MessagesRoute(messagesViewModel, modifier)
                    }
                }
                }
            }
            }
        }
    }

    // ---- app lock

    private val lockedState = mutableStateOf(false)
    private var leftAt = 0L
    private var authenticating = false

    /** Hidden amounts: shown for a short window once the phone's own lock screen has been passed. Nothing is stored. */
    private val amountReveal: com.zyke.message.presentation.common.TimedAmountReveal by lazy {
        com.zyke.message.presentation.common.TimedAmountReveal(
            scope = lifecycleScope,
            clock = SystemClock::elapsedRealtime,
            authRequired = { container.settings.settings.value.authForAmounts },
            windowMillis = { container.settings.settings.value.revealDuration.millis },
            screenLockExists = { getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true },
            authenticate = ::authenticateForAmounts
        )
    }
    private var amountsResult: ((Boolean) -> Unit)? = null

    private val amountsCredential = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        authenticating = false
        amountsResult?.invoke(result.resultCode == RESULT_OK)
        amountsResult = null
    }

    private fun authenticateForAmounts(onResult: (Boolean) -> Unit) {
        val intent = getSystemService(KeyguardManager::class.java)?.createConfirmDeviceCredentialIntent("Show amounts", "Confirm it is you to see your money figures")
        if (intent == null) { onResult(false); return }
        amountsResult = onResult
        authenticating = true
        amountsCredential.launch(intent)
    }

    private val credential = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        authenticating = false
        if (result.resultCode == RESULT_OK) lockedState.value = false
    }

    /** The phone's own fingerprint / face / PIN screen. It needs no library and no permission. */
    private fun requestUnlock() {
        val keyguard = getSystemService(KeyguardManager::class.java)
        val intent = keyguard?.createConfirmDeviceCredentialIntent("Unlock Zyke", null)
        if (intent == null) {
            lockedState.value = false
            return
        }
        authenticating = true
        credential.launch(intent)
    }

    private fun lockIfAway() {
        val lockOn = container.settings.settings.value.appLock
        if (!lockOn || authenticating) return
        val away = SystemClock.elapsedRealtime() - leftAt
        if (leftAt == 0L || away > LOCK_AFTER_MS) {
            lockedState.value = true
            requestUnlock()
        }
    }

    override fun onStart() {
        super.onStart()
        com.zyke.message.data.diag.DevLog.d("lifecycle") { "MainActivity started (SMS permission=${has(Manifest.permission.READ_SMS)}, notification access=${hasNotificationAccess()}, paused=${container.settings.settings.value.readingPaused})" }
        lockIfAway()
        // Whatever was deleted or read in the messaging app while this app was away shows up now. A fresh start already
        // reads everything, and a very large inbox is not read again for a quick switch between apps.
        if (leftAt != 0L && SystemClock.elapsedRealtime() - leftAt > REFRESH_AFTER_MS) container.smsRepository.refreshRecent()
        keepReadingInBackground()
        // After an update the phone can leave the notification reader unconnected until it is asked to reconnect.
        if (hasNotificationAccess()) {
            runCatching {
                android.service.notification.NotificationListenerService.requestRebind(
                    android.content.ComponentName(this, com.zyke.message.data.sms.RcsNotificationListener::class.java)
                )
            }
        }
        // Alarms and the widget do not need to be ready for the first frame.
        val current = container.settings.settings.value
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            DigestScheduler(this@MainActivity).apply(current.morningDigest)
            WeeklyScheduler(this@MainActivity).apply(current.weeklySummary)
            DaysAheadWidget.refresh(this@MainActivity)
            // Backups: queue what is missing, and refresh the Auto Backup file if its time has passed (no network needed).
            runCatching {
                container.backup.scheduler.ensureScheduled()
                container.backup.runner.runOfflineIfDue()
            }
        }
    }

    /** A very large inbox takes a while to read; a notification keeps the work alive if the app is left meanwhile. */
    private fun keepReadingInBackground() {
        lifecycleScope.launch {
            val progress = container.observeMessages.progress
            // Wait for the first look at the inbox, then see whether there is a lot still to read.
            val seen = progress.first { it.monthsTotal > 0 || !it.isDone }
            // The first full read of an inbox has to finish even if the app is left, so it gets a service whatever its size;
            // so does a large inbox, and a visit with a lot of new messages waiting.
            if (seen.isWorking && (seen.isFirstSync || seen.messagesTotal >= BackfillService.BIG_INBOX || seen.pending >= BackfillService.MANY_NEW)) {
                runCatching { BackfillService.start(this@MainActivity) }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        com.zyke.message.data.diag.DevLog.d("lifecycle") { "MainActivity stopped" }
        // Leaving the app hides the amounts again at once, unless the person chose to keep them shown until they hide them.
        if (!container.settings.settings.value.revealDuration.staysAfterLeaving) amountReveal.hideNow()
        leftAt = SystemClock.elapsedRealtime()
    }

    private fun observeScreenProtection() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                container.settings.settings.collect { applyScreenProtection(it.screenProtection) }
            }
        }
    }

    /** FLAG_SECURE blocks screenshots, screen recording and the recent-apps thumbnail. */
    private fun applyScreenProtection(enabled: Boolean) {
        if (enabled) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}

/** Away this long, coming back reads the messaging app's inbox again (deletions, read marks). */
private const val REFRESH_AFTER_MS = 15_000L

/** How long away from the app before it asks to be unlocked again. */
private const val LOCK_AFTER_MS = 30_000L

/** Buttons are only offered once the screen underneath has something to filter or search. */
private fun MessagesContract.State.toToolbarState() = InboxToolbarState(
    buttonsVisible = permission == MessagesContract.PermissionStatus.GRANTED && !isLoading && !hasError,
    searching = isSearching,
    query = query,
    filterCount = filter.extraCount
)

private const val LAST_STAGE = 2

/** What the app reads, why, and what it does with it, before Android asks for permission to read messages. */
@androidx.compose.runtime.Composable
private fun SmsExplainer(onContinue: () -> Unit, onLater: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onLater,
        title = { com.zyke.message.presentation.common.Text("Why Zyke Message reads your messages") },
        text = {
            androidx.compose.foundation.layout.Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                com.zyke.message.presentation.common.Text("To turn bank and payment texts into a picture of your money: what you spent, what is due, and where you stand.")
                com.zyke.message.presentation.common.Text("\u2022 Everything is read and kept on this phone. Nothing is uploaded.")
                com.zyke.message.presentation.common.Text("\u2022 It never sends, changes or deletes a message.")
                com.zyke.message.presentation.common.Text("\u2022 You can pause reading, export your data, or delete it all from Settings at any time.")
                com.zyke.message.presentation.common.Text("Without this permission the app has nothing to show, but you can still add entries by hand.")
            }
        },
        confirmButton = {
            com.zyke.message.presentation.common.AppButton(
                "Continue", onContinue,
                style = com.zyke.message.presentation.common.ButtonStyle.TEXT,
                size = com.zyke.message.presentation.common.ButtonSize.COMPACT
            )
        },
        dismissButton = {
            com.zyke.message.presentation.common.AppButton(
                "Not now", onLater,
                style = com.zyke.message.presentation.common.ButtonStyle.TEXT,
                size = com.zyke.message.presentation.common.ButtonSize.COMPACT
            )
        }
    )
}

/** Why notification access is asked for, in plain words, before the phone's own settings page is opened. */
@androidx.compose.runtime.Composable
private fun NotificationAccessPrompt(onOpenSettings: () -> Unit, onLater: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onLater,
        title = { com.zyke.message.presentation.common.Text("Allow notification access") },
        text = {
            com.zyke.message.presentation.common.Text(
                "Zyke Message can read notifications from your messaging app and Google Pay, so chats and UPI Lite top-ups " +
                    "are counted too. It only reads those, and nothing leaves your phone. Android asks you to allow this on " +
                    "its own settings page: switch Zyke Message on there."
            )
        },
        confirmButton = {
            com.zyke.message.presentation.common.AppButton(
                "Open settings", onOpenSettings,
                style = com.zyke.message.presentation.common.ButtonStyle.TEXT,
                size = com.zyke.message.presentation.common.ButtonSize.COMPACT
            )
        },
        dismissButton = {
            com.zyke.message.presentation.common.AppButton(
                "Not now", onLater,
                style = com.zyke.message.presentation.common.ButtonStyle.TEXT,
                size = com.zyke.message.presentation.common.ButtonSize.COMPACT
            )
        }
    )
}
