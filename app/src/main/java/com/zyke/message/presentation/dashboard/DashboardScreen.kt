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
import androidx.compose.foundation.layout.statusBarsPadding
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
import com.zyke.message.presentation.dashboard.components.DashboardControls
import com.zyke.message.presentation.dashboard.components.HeroCard
import com.zyke.message.presentation.dashboard.components.SectionCard
import com.zyke.message.presentation.dashboard.components.SourceRow
import com.zyke.message.presentation.dashboard.components.TrendChart
import androidx.compose.material3.TextButton
import com.zyke.message.domain.dashboard.Dashboard
import com.zyke.message.presentation.dashboard.components.VendorRow
import com.zyke.message.presentation.messages.components.Caption
import com.zyke.message.presentation.messages.components.MessageListSkeleton
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId

private const val COLLAPSED_VENDORS = 5

@Composable
fun DashboardRoute(viewModel: DashboardViewModel, modifier: Modifier = Modifier) {
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

    DashboardScreen(state, viewModel::onIntent, modifier)
}

@Composable
fun DashboardScreen(state: State, onIntent: (Intent) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize()) {
        Column(Modifier.statusBarsPadding()) {
            Text(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                text = stringResource(R.string.dashboard_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary
            )
            HorizontalDivider(
                Modifier.padding(horizontal = 8.dp).fillMaxWidth(),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
            )
        }

        val dashboard = state.dashboard
        when {
            state.permissionMissing -> Notice(stringResource(R.string.dashboard_permission), stringResource(R.string.dashboard_permission_hint))
            state.hasError -> Notice(stringResource(R.string.messages_error), null)
            state.isLoading || dashboard == null -> MessageListSkeleton()
            else -> DashboardContent(state, dashboard, onIntent)
        }
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
    val categoryTotal = dashboard.categories.fold(BigDecimal.ZERO) { sum, c -> sum + c.total }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item(key = "controls") {
            DashboardControls(
                period = state.period,
                direction = state.direction,
                category = state.category,
                onPeriod = { onIntent(Intent.SelectPeriod(it)) },
                onDirection = { onIntent(Intent.SelectDirection(it)) },
                onClearCategory = { onIntent(Intent.ClearCategory) }
            )
        }

        if (dashboard.isEmpty) {
            item(key = "empty") {
                SectionCard(stringResource(R.string.dashboard_empty)) {
                    Caption(stringResource(R.string.dashboard_empty_hint), maxLines = 3)
                }
            }
        } else {
            item(key = "hero") { HeroCard(dashboard, state.period) }
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
                SectionCard(stringResource(R.string.dashboard_trend)) {
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
                            onClick = { onIntent(Intent.SelectCategory(item.category)) },
                            onEditBudget = { onIntent(Intent.EditBudget(item.category)) }
                        )
                    }
                }
            }

            item(key = "vendors") {
                val vendors = if (state.showAllVendors) dashboard.vendors else dashboard.vendors.take(COLLAPSED_VENDORS)
                SectionCard(stringResource(R.string.dashboard_by_vendor)) {
                    vendors.forEachIndexed { index, vendor ->
                        if (index > 0) HorizontalDivider()
                        VendorRow(
                            vendor = vendor,
                            total = dashboard.total,
                            expanded = state.expandedVendor == vendor.key,
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
                SectionCard(stringResource(R.string.dashboard_by_source)) {
                    dashboard.sources.forEachIndexed { index, source ->
                        if (index > 0) HorizontalDivider()
                        SourceRow(source, dashboard.total)
                    }
                }
            }
        }
    }
}

@Composable
private fun Notice(title: String, hint: String?) {
    Column(
        Modifier.fillMaxWidth().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.secondary, textAlign = TextAlign.Center)
        if (hint != null) Caption(hint, color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.6f), maxLines = 3)
    }
}
