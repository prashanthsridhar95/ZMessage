package com.zyke.message.presentation.dashboard

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
import com.zyke.message.presentation.dashboard.DashboardContract.Effect
import com.zyke.message.presentation.dashboard.DashboardContract.Intent
import com.zyke.message.presentation.dashboard.DashboardContract.State
import com.zyke.message.domain.dashboard.DashboardPeriod
import com.zyke.message.domain.dashboard.FlowDirection
import com.zyke.message.presentation.dashboard.components.BudgetDialog
import com.zyke.message.presentation.dashboard.components.CardBillRow
import com.zyke.message.presentation.dashboard.components.CategoryRow
import com.zyke.message.presentation.dashboard.components.ChartColors
import com.zyke.message.presentation.dashboard.components.ChartLegend
import com.zyke.message.presentation.common.ErrorState
import com.zyke.message.presentation.common.PermissionGate
import com.zyke.message.presentation.dashboard.components.DashboardControls
import com.zyke.message.presentation.dashboard.components.DashboardFilterSheet
import com.zyke.message.presentation.dashboard.components.PeriodTabs
import com.zyke.message.presentation.messages.components.ActiveFilterBar
import com.zyke.message.presentation.messages.components.BarIcon
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import com.zyke.message.presentation.dashboard.components.HeroCard
import com.zyke.message.presentation.dashboard.components.SectionCard
import com.zyke.message.presentation.dashboard.components.SourceRow
import com.zyke.message.presentation.dashboard.components.TrendChart
import androidx.compose.material3.TextButton
import com.zyke.message.domain.dashboard.Dashboard
import com.zyke.message.presentation.dashboard.components.VendorRow
import com.zyke.message.presentation.messages.components.Caption
import com.zyke.message.presentation.messages.components.mutedColor
import com.zyke.message.presentation.messages.components.MessageListSkeleton
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId

private const val COLLAPSED_VENDORS = 5

@Composable
fun DashboardRoute(viewModel: DashboardViewModel, onRequestPermission: () -> Unit, modifier: Modifier = Modifier) {
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
                is Effect.OpenLink -> runCatching {
                    context.startActivity(
                        android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(effect.url))
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }.onFailure {
                    Toast.makeText(context, R.string.link_failed, Toast.LENGTH_SHORT).show()
                }
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
        Column {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp).heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    modifier = Modifier.weight(1f),
                    text = stringResource(R.string.dashboard_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                if (state.dashboard != null) {
                    BarIcon(
                        icon = R.drawable.baseline_filter_alt_24,
                        description = "Filter by category",
                        active = state.activeFilterCount > 0,
                        badge = state.activeFilterCount,
                        onClick = { onIntent(Intent.OpenFilters) }
                    )
                }
            }
            HorizontalDivider(
                Modifier.padding(horizontal = 8.dp).fillMaxWidth(),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
            )
        }

        if (state.dashboard != null && !state.permissionMissing && !state.hasError) {
            PeriodTabs(period = state.period, onSelect = { onIntent(Intent.SelectPeriod(it)) })
            // Always on screen, so switching between money out and money in is one tap from anywhere.
            DashboardControls(
                direction = state.direction,
                onDirection = { onIntent(Intent.SelectDirection(it)) },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }

        val dashboard = state.dashboard
        when {
            state.permissionMissing -> PermissionGate(onGrant = onRequestPermission)
            state.hasError -> ErrorState(
                title = stringResource(R.string.messages_error),
                hint = stringResource(R.string.error_hint),
                onRetry = { onIntent(Intent.Retry) }
            )
            state.isLoading || dashboard == null -> MessageListSkeleton()
            else -> DashboardContent(state, dashboard, onIntent)
        }
    }

    val dashboardForSheet = state.dashboard
    if (state.showFilterSheet && dashboardForSheet != null) {
        DashboardFilterSheet(
            category = state.category,
            dashboard = dashboardForSheet,
            canReset = state.activeFilterCount > 0,
            onCategory = { onIntent(Intent.SetCategory(it)) },
            onReset = { onIntent(Intent.ResetFilters) },
            onDismiss = { onIntent(Intent.CloseFilters) }
        )
    }

    state.editingBudget?.let { category ->
        BudgetDialog(
            category = category,
            current = state.budgets[category],
            onSave = { onIntent(Intent.SaveBudget(category, it)) },
            onDismiss = { onIntent(Intent.EditBudget(null)) }
        )
    }
}

@Composable
private fun DashboardContent(state: State, dashboard: Dashboard, onIntent: (Intent) -> Unit) {
    val today = LocalDate.ofInstant(state.now, ZoneId.systemDefault())
    val ranking = dashboard.categories.map { it.category }
    // Budgets are monthly limits, so they only make sense against this month's spending.
    val budgetsApply = state.period == DashboardPeriod.THIS_MONTH && state.direction == FlowDirection.SPENT
    val received = state.direction == FlowDirection.RECEIVED
    val categoryTotal = dashboard.categories.fold(BigDecimal.ZERO) { sum, c -> sum + c.total }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        state.category?.let { category ->
            item(key = "category-filter") {
                ActiveFilterBar(
                    summary = category.displayString,
                    resultCount = dashboard.count,
                    onChange = { onIntent(Intent.OpenFilters) },
                    onClear = { onIntent(Intent.ClearCategory) },
                    horizontalInset = 0.dp
                )
            }
        }

        if (dashboard.isEmpty) {
            item(key = "empty") {
                SectionCard(
                    stringResource(
                        if (received) R.string.dashboard_empty_received else R.string.dashboard_empty
                    )
                ) {
                    Caption(stringResource(R.string.dashboard_empty_hint), maxLines = 3)
                }
            }
        } else {
            item(key = "hero") { HeroCard(dashboard, state.period, onDirection = { onIntent(Intent.SelectDirection(it)) }) }
        }

        if (state.cardBills.isNotEmpty()) {
            item(key = "card-bills") {
                SectionCard(stringResource(R.string.dashboard_card_bills), subtitle = stringResource(R.string.dashboard_card_bills_hint)) {
                    state.cardBills.forEachIndexed { index, bills ->
                        if (index > 0) HorizontalDivider()
                        CardBillRow(
                            bills = bills,
                            today = today,
                            expanded = state.expandedCard == bills.key,
                            onToggle = { onIntent(Intent.ToggleCard(bills.key)) },
                            onPay = { onIntent(Intent.OpenLink(it)) }
                        )
                    }
                }
            }
        }

        if (!dashboard.isEmpty) {
            item(key = "trend") {
                SectionCard(stringResource(if (received) R.string.dashboard_trend_received else R.string.dashboard_trend)) {
                    TrendChart(dashboard.trend, dashboard.trendUnit, ranking)
                    ChartLegend(ranking, Modifier.padding(top = 12.dp))
                }
            }

            item(key = "categories") {
                SectionCard(stringResource(R.string.dashboard_by_category)) {
                    dashboard.categories.forEachIndexed { index, item ->
                        if (index > 0) HorizontalDivider()
                        CategoryRow(
                            item = item,
                            total = categoryTotal,
                            color = ChartColors.forRank(index),
                            selected = item.category == state.category,
                            canSetBudget = budgetsApply,
                            direction = state.direction,
                            onClick = { onIntent(Intent.SelectCategory(item.category)) },
                            onEditBudget = { onIntent(Intent.EditBudget(item.category)) }
                        )
                    }
                }
            }

            item(key = "vendors") {
                val vendors = if (state.showAllVendors) dashboard.vendors else dashboard.vendors.take(COLLAPSED_VENDORS)
                SectionCard(stringResource(if (received) R.string.dashboard_by_vendor_received else R.string.dashboard_by_vendor)) {
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
                        TextButton(onClick = { onIntent(Intent.ToggleShowAllVendors) }) {
                            Text(
                                if (state.showAllVendors) stringResource(R.string.show_fewer)
                                else stringResource(R.string.show_all_vendors, dashboard.vendors.size)
                            )
                        }
                    }
                }
            }

            item(key = "sources") {
                SectionCard(stringResource(if (received) R.string.dashboard_by_source_received else R.string.dashboard_by_source)) {
                    dashboard.sources.forEachIndexed { index, source ->
                        if (index > 0) HorizontalDivider()
                        SourceRow(source, dashboard.total, state.direction)
                    }
                }
            }
        }
    }
}
