package com.zyke.message.presentation.dashboard

import com.zyke.message.presentation.dashboard.components.DirectionSwitch
import com.zyke.message.presentation.common.heading
import com.zyke.message.presentation.common.FintechCard
import com.zyke.message.presentation.dashboard.components.CardCycleSheet
import com.zyke.message.presentation.dashboard.components.rangeLabel
import com.zyke.message.presentation.dashboard.components.countLabel
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import com.zyke.message.presentation.common.AppBottomSheet
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import com.zyke.message.presentation.format.DisplayFormatters
import com.zyke.message.presentation.common.LinkButton
import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import com.zyke.message.presentation.common.Motion
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import com.zyke.message.presentation.dashboard.components.CollapsedHeroBar
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zyke.message.R
import com.zyke.message.presentation.common.ButtonStyle
import com.zyke.message.presentation.common.ButtonSize
import com.zyke.message.presentation.common.AppButton
import com.zyke.message.presentation.common.LocalOpenSettings
import com.zyke.message.presentation.dashboard.DashboardContract.Effect
import com.zyke.message.presentation.dashboard.DashboardContract.Intent
import com.zyke.message.presentation.dashboard.DashboardContract.State
import com.zyke.message.domain.dashboard.DashboardPeriod
import com.zyke.message.domain.dashboard.FlowDirection
import com.zyke.message.presentation.dashboard.components.LimitsCard
import com.zyke.message.presentation.dashboard.components.PlanCard
import com.zyke.message.presentation.dashboard.components.CardBillRow
import com.zyke.message.presentation.dashboard.components.CardCyclesSection
import com.zyke.message.presentation.dashboard.components.CardBillSheet
import com.zyke.message.presentation.dashboard.components.CategoryRow
import com.zyke.message.presentation.dashboard.components.ChartColors
import com.zyke.message.presentation.dashboard.components.ChartLegend
import com.zyke.message.presentation.common.ErrorState
import com.zyke.message.presentation.common.PermissionGate
import com.zyke.message.presentation.dashboard.components.DashboardFilterSheet
import com.zyke.message.presentation.messages.components.ActiveFilterBar
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import com.zyke.message.presentation.dashboard.components.IncomeExpenseCard
import com.zyke.message.presentation.dashboard.components.LocalSectionsAsSheets
import com.zyke.message.presentation.dashboard.components.ReportCardSheet
import com.zyke.message.presentation.dashboard.components.ReportCardSummary
import com.zyke.message.presentation.dashboard.components.SectionCard
import com.zyke.message.presentation.dashboard.components.SourceRow
import com.zyke.message.presentation.dashboard.components.TrendChart
import com.zyke.message.domain.dashboard.Dashboard
import com.zyke.message.presentation.dashboard.components.VendorRow
import com.zyke.message.presentation.messages.components.Caption
import com.zyke.message.presentation.messages.components.mutedColor
import com.zyke.message.presentation.messages.components.MessageListSkeleton
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId

private enum class Stage { PERMISSION, ERROR, LOADING, CONTENT }

private const val COLLAPSED_VENDORS = 5
private val COLLAPSE_AT = 24.dp

@Composable
fun DashboardRoute(viewModel: DashboardViewModel, onRequestPermission: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()

    val openLink = com.zyke.message.presentation.common.LocalOpenLink.current
    LaunchedEffect(viewModel) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) ==
            PackageManager.PERMISSION_GRANTED
        viewModel.onIntent(Intent.ScreenStarted(granted))
    }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is Effect.OpenLink -> openLink(effect.url)
            }
        }
    }

    DashboardScreen(state, viewModel::onIntent, onRequestPermission, modifier)
}

@Composable
fun DashboardScreen(
    state: State,
    onIntent: (Intent) -> Unit,
    onRequestPermission: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxSize()) {
        val dashboard = state.dashboard
        val stage = when {
            state.permissionMissing -> Stage.PERMISSION
            state.hasError -> Stage.ERROR
            state.isLoading || dashboard == null -> Stage.LOADING
            else -> Stage.CONTENT
        }
        // The skeleton fades into the real content instead of being swapped for it.
        Crossfade(stage, Modifier.weight(1f), animationSpec = tween(Motion.STANDARD_MS), label = "dashboard-stage") { shown ->
            when {
                shown == Stage.PERMISSION -> PermissionGate(onGrant = onRequestPermission)
                shown == Stage.ERROR -> ErrorState(
                    title = stringResource(R.string.messages_error),
                    hint = stringResource(R.string.error_hint),
                    onRetry = { onIntent(Intent.Retry) }
                )
                shown == Stage.CONTENT && dashboard != null -> DashboardContent(state, dashboard, onIntent)
                else -> MessageListSkeleton()
            }
        }
    }

    val dashboardForSheet = state.dashboard
    if (state.showFilterSheet && dashboardForSheet != null) {
        DashboardFilterSheet(
            period = state.period,
            customRange = state.customRange,
            category = state.category,
            dashboard = dashboardForSheet,
            canReset = state.activeFilterCount > 0,
            onPeriod = { onIntent(Intent.SelectPeriod(it)) },
            onCustomRange = { start, end -> onIntent(Intent.SelectCustomRange(start, end)) },
            onCategory = { onIntent(Intent.SetCategory(it)) },
            onReset = { onIntent(Intent.ResetFilters) },
            onDismiss = { onIntent(Intent.CloseFilters) }
        )
    }

    val categoryForSheet = state.openCategory
    val detailForSheet = state.categoryDetail
    if (categoryForSheet != null && detailForSheet != null) {
        CategorySheet(
            category = categoryForSheet,
            detail = detailForSheet,
            direction = state.direction,
            expandedVendor = state.expandedVendor,
            onVendor = { onIntent(Intent.ToggleVendor(it)) },
            onDismiss = { onIntent(Intent.CloseCategory) }
        )
    }

    val reportCardForSheet = state.reportCard
    if (state.showReportCard && reportCardForSheet != null) {
        ReportCardSheet(reportCardForSheet, onDismiss = { onIntent(Intent.CloseReportCard) })
    }

    state.cardCycles.firstOrNull { it.card.id == state.expandedCardCycle }?.let { entry ->
        CardCycleSheet(entry = entry, onDismiss = { onIntent(Intent.ToggleCardCycle(entry.card.id)) })
    }

    state.cardBills.firstOrNull { it.key == state.expandedCard }?.let { bills ->
        CardBillSheet(
            bills = bills,
            today = LocalDate.ofInstant(state.now, ZoneId.systemDefault()),
            onPay = { onIntent(Intent.OpenLink(it)) },
            onDismiss = { onIntent(Intent.ToggleCard(bills.key)) }
        )
    }
}

@Composable
private fun DashboardContent(state: State, dashboard: Dashboard, onIntent: (Intent) -> Unit, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    Column(modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            androidx.compose.runtime.CompositionLocalProvider(
                LocalSectionsAsSheets provides true,
                com.zyke.message.presentation.common.LocalContentInset provides 16.dp
            ) {
                DashboardList(state, dashboard, onIntent, listState)
            }
        }
    }
}

/** Fades the compact bar in over the list. A function of its own so no layout scope's overload gets picked. */
@Composable
private fun HeroBarOverlay(visible: Boolean, dashboard: Dashboard, onDirection: (FlowDirection) -> Unit, modifier: Modifier) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn() + slideInVertically { -it / 2 },
        exit = fadeOut() + slideOutVertically { -it / 2 }
    ) {
        CollapsedHeroBar(dashboard, onDirection)
    }
}

@Composable
private fun DashboardList(state: State, dashboard: Dashboard, onIntent: (Intent) -> Unit, listState: LazyListState) {
    val today = LocalDate.ofInstant(state.now, ZoneId.systemDefault())
    val ranking = dashboard.categories.map { it.category }
    val received = state.direction == FlowDirection.RECEIVED
    val categoryTotal = dashboard.categories.fold(BigDecimal.ZERO) { sum, c -> sum + c.total }

    LazyColumn(
        Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (state.activeFilterCount > 0) {
            item(key = "active-filters") {
                ActiveFilterBar(
                    summary = listOfNotNull(
                        (if (state.period == DashboardPeriod.CUSTOM) state.customRange?.let(::rangeLabel) else state.period.label).takeIf { state.period != DashboardPeriod.THIS_MONTH },
                        state.category?.displayString
                    ).joinToString(" · "),
                    resultCount = dashboard.count,
                    onChange = { onIntent(Intent.OpenFilters) },
                    onClear = { onIntent(Intent.ResetFilters) },
                    horizontalInset = 0.dp
                )
            }
        }

        if (dashboard.isEmpty) {
            item(key = "empty") {
                SectionCard(
                    stringResource(
                        if (received) R.string.dashboard_empty_received else R.string.dashboard_empty
                    ),
                    startOpen = true
                ) {
                    Caption(stringResource(R.string.dashboard_empty_hint), maxLines = 3)
                    DirectionSwitch(state.direction, { onIntent(Intent.SelectDirection(it)) }, Modifier.padding(top = 12.dp))
                }
            }
        } else {
            item(key = "hero") {
                FintechCard {
                    IncomeExpenseCard(
                        dashboard, state.expenseTrend, state.incomeTrend, state.period,
                        onShift = { onIntent(Intent.ShiftCycle(it)) },
                        canGoForward = state.cycleOffset < 0 || state.period != DashboardPeriod.THIS_MONTH
                    )
                }
            }
        }

        val reportCard = state.reportCard
        if (!dashboard.isEmpty && reportCard != null && !reportCard.isEmpty) {
            item(key = "report-card") { ReportCardSummary(reportCard, onOpen = { onIntent(Intent.OpenReportCard) }) }
        }

        if (!dashboard.isEmpty) {
            item(key = "label-spending") {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    GroupLabel(if (received) "Income" else "Spending", Modifier.weight(1f))
                    DirectionSwitch(state.direction, { onIntent(Intent.SelectDirection(it)) })
                }
            }

            item(key = "categories") {
                SectionCard(
                    stringResource(R.string.dashboard_by_category),
                    summary = dashboard.categories.firstOrNull()?.let { "${it.category.displayString} ${DisplayFormatters.rupees(it.total)}" }
                ) {
                    dashboard.categories.forEachIndexed { index, item ->
                        if (index > 0) HorizontalDivider()
                        CategoryRow(
                            item = item,
                            total = categoryTotal,
                            color = ChartColors.forRank(index),
                            selected = false,
                            // Limits are set in Settings; here they are only shown.
                            canSetBudget = false,
                            direction = state.direction,
                            onClick = { onIntent(Intent.OpenCategory(item.category)) },
                            onEditBudget = { onIntent(Intent.EditBudget(item.category)) }
                        )
                    }
                }
            }

            item(key = "vendors") {
                val vendors = if (state.showAllVendors) dashboard.vendors else dashboard.vendors.take(COLLAPSED_VENDORS)
                SectionCard(
                    stringResource(if (received) R.string.dashboard_by_vendor_received else R.string.dashboard_by_vendor),
                    summary = dashboard.vendors.firstOrNull()?.let { it.merchant.name }
                ) {
                    vendors.forEachIndexed { index, vendor ->
                        if (index > 0) HorizontalDivider()
                        VendorRow(
                            vendor = vendor,
                            total = dashboard.total,
                            expanded = state.expandedVendor == vendor.key,
                            direction = state.direction,
                            onClick = { onIntent(Intent.ToggleVendor(vendor.key)) }
                        )
                    }
                    if (dashboard.vendors.size > COLLAPSED_VENDORS) {
                        LinkButton(
                            text = if (state.showAllVendors) stringResource(R.string.show_fewer)
                            else stringResource(R.string.show_all_vendors, dashboard.vendors.size),
                            onClick = { onIntent(Intent.ToggleShowAllVendors) }
                        )
                    }
                }
            }

            item(key = "trend") {
                SectionCard(stringResource(if (received) R.string.dashboard_trend_received else R.string.dashboard_trend)) {
                    TrendChart(dashboard.trend, dashboard.trendUnit, ranking)
                    ChartLegend(ranking, Modifier.padding(top = 12.dp))
                }
            }
        }

        item(key = "label-planning") { GroupLabel("Planning", Modifier.padding(top = 8.dp)) }

        item(key = "plan") {
            PlanCard(state.plan, state.planInsights, onEdit = LocalOpenSettings.current)
        }

        item(key = "limits") {
            LimitsCard(state.limits, onManage = LocalOpenSettings.current)
        }
    }
}


/** What went into one category: its total, then who the money went to (or came from), each opening to its payments. */
@Composable
private fun CategorySheet(
    category: com.zyke.message.domain.model.MessageCategory,
    detail: Dashboard,
    direction: FlowDirection,
    expandedVendor: String?,
    onVendor: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AppBottomSheet(onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp)
        ) {
            Text(category.displayString, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            Text(
                DisplayFormatters.rupees(detail.total),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Caption(direction.countLabel(detail.count))
            Spacer(Modifier.height(12.dp))
            detail.vendors.forEachIndexed { index, vendor ->
                if (index > 0) HorizontalDivider()
                VendorRow(
                    vendor = vendor,
                    total = detail.total,
                    expanded = expandedVendor == vendor.key,
                    direction = direction,
                    onClick = { onVendor(vendor.key) }
                )
            }
        }
    }
}

/** A small heading over a group of tiles. */
@Composable
private fun GroupLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier.padding(start = 4.dp).heading(),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.secondary
    )
}
