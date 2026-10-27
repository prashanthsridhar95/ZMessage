package com.zyke.message.presentation

import android.Manifest
import android.app.KeyguardManager
import android.os.Bundle
import android.os.SystemClock
import androidx.compose.runtime.mutableStateOf
import com.zyke.message.notifications.DaysAheadWidget
import com.zyke.message.notifications.DigestScheduler
import com.zyke.message.notifications.LiveAlerts
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
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.zyke.message.ZykeMessagesApplication
import com.zyke.message.domain.model.ThemeMode
import com.zyke.message.presentation.dashboard.DashboardContract
import com.zyke.message.presentation.dashboard.DashboardRoute
import com.zyke.message.presentation.dashboard.DashboardViewModel
import com.zyke.message.presentation.home.HomeContract
import com.zyke.message.presentation.home.HomeRoute
import com.zyke.message.presentation.home.HomeViewModel
import com.zyke.message.presentation.messages.MessagesContract
import com.zyke.message.presentation.messages.MessagesRoute
import com.zyke.message.presentation.messages.MessagesViewModel
import com.zyke.message.presentation.settings.SettingsRoute
import com.zyke.message.presentation.settings.SettingsViewModel
import com.zyke.message.presentation.theme.ZykeMessageTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val container get() = (application as ZykeMessagesApplication).container

    private val messagesViewModel: MessagesViewModel by viewModels {
        MessagesViewModel.Factory(
            container.observeMessages, container.userMessageState, container.categoryOverrides, container.mutedSenders
        )
    }

    private val dashboardViewModel: DashboardViewModel by viewModels {
        DashboardViewModel.Factory(
            container.observeMessages, container.budgets, container.cards, container.userMessageState, container.moneyPlan
        )
    }

    private val homeViewModel: HomeViewModel by viewModels {
        HomeViewModel.Factory(
            container.observeMessages, container.userMessageState, container.budgets, container.categoryOverrides, container.mutedSenders,
            container.wishlist, container.splits
        )
    }

    private val settingsViewModel: SettingsViewModel by viewModels {
        SettingsViewModel.Factory(
            container.observeMessages, container.settings, container.userMessageState, container.budgets, container.cards,
            onLogosTurnedOff = container.imageStore::clearLogos,
            onDigestChanged = { DigestScheduler(this).apply(it) },
            onLiveAlertsChanged = { LiveAlerts.setEnabled(this, it) },
            planRepository = container.moneyPlan,
            wishlistRepository = container.wishlist
        )
    }

    /** One permission prompt for the whole app; every tab is told the answer. */
    private val smsPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        messagesViewModel.onIntent(MessagesContract.Intent.PermissionResult(granted))
        if (granted) {
            homeViewModel.onIntent(HomeContract.Intent.ScreenStarted(true))
            dashboardViewModel.onIntent(DashboardContract.Intent.ScreenStarted(true))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        applyScreenProtection(container.settings.settings.value.screenProtection)
        observeScreenProtection()
        // Locked from the very first frame, so the inbox never flashes before the lock screen.
        lockedState.value = container.settings.settings.value.appLock &&
            getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

        val versionName = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }
            .getOrNull().orEmpty()

        setContent {
            val appSettings by container.settings.settings.collectAsStateWithLifecycle()
            val darkTheme = when (appSettings.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            // The bar icons follow the app's theme, not the phone's: a Day theme on a dark phone needs dark icons.
            val view = LocalView.current
            SideEffect {
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
            CompositionLocalProvider(LocalImageStore provides container.imageStore) {
            ZykeMessageTheme(darkTheme = darkTheme) {
                val locked by lockedState
                if (locked) {
                    LockScreen(onUnlock = ::requestUnlock)
                } else {
                val home by homeViewModel.state.collectAsStateWithLifecycle()
                val inbox by messagesViewModel.state
                    .map { it.toToolbarState() }
                    .distinctUntilChanged()
                    .collectAsStateWithLifecycle(InboxToolbarState())
                val money by dashboardViewModel.state
                    .map { MoneyToolbarState(it.dashboard != null && !it.permissionMissing && !it.hasError, it.activeFilterCount) }
                    .distinctUntilChanged()
                    .collectAsStateWithLifecycle(MoneyToolbarState())

                val toolbarCallbacks = remember {
                    ToolbarCallbacks(
                        onToggleSearch = { messagesViewModel.onIntent(MessagesContract.Intent.ToggleSearch) },
                        onQueryChange = { messagesViewModel.onIntent(MessagesContract.Intent.SetQuery(it)) },
                        onInboxFilters = { messagesViewModel.onIntent(MessagesContract.Intent.OpenFilters) },
                        onMoneyFilters = { dashboardViewModel.onIntent(DashboardContract.Intent.OpenFilters) }
                    )
                }

                AppRoot(
                    inboxBadge = home.unreadCount,
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
        lockIfAway()
        // Whatever was deleted or read in the messaging app while this app was away shows up now.
        container.smsRepository.refresh()
        container.settings.settings.value.morningDigest.let { DigestScheduler(this).apply(it) }
        DaysAheadWidget.refresh(this)
    }

    override fun onStop() {
        super.onStop()
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

/** How long away from the app before it asks to be unlocked again. */
private const val LOCK_AFTER_MS = 30_000L

/** Buttons are only offered once the screen underneath has something to filter or search. */
private fun MessagesContract.State.toToolbarState() = InboxToolbarState(
    buttonsVisible = permission == MessagesContract.PermissionStatus.GRANTED && !isLoading && !hasError,
    searching = isSearching,
    query = query,
    filterCount = filter.extraCount
)
