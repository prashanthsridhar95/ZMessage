package com.zyke.message.presentation.dashboard

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
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
import com.zyke.message.presentation.dashboard.DashboardContract.Intent
import com.zyke.message.presentation.dashboard.DashboardContract.State
import com.zyke.message.presentation.dashboard.components.CategoryRow
import com.zyke.message.presentation.dashboard.components.DashboardControls
import com.zyke.message.presentation.dashboard.components.HeroCard
import com.zyke.message.presentation.dashboard.components.SectionTitle
import com.zyke.message.presentation.dashboard.components.SourceRow
import com.zyke.message.presentation.dashboard.components.TrendChart
import com.zyke.message.presentation.dashboard.components.VendorRow
import com.zyke.message.presentation.messages.components.Caption
import com.zyke.message.presentation.messages.components.MessageListSkeleton
import java.math.BigDecimal

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
            else -> LazyColumn(Modifier.fillMaxSize()) {
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
                        Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                            Notice(stringResource(R.string.dashboard_empty), stringResource(R.string.dashboard_empty_hint))
                        }
                    }
                    return@LazyColumn
                }

                item(key = "hero") { HeroCard(dashboard, state.period) }
                item(key = "trend") { TrendChart(dashboard.trend, dashboard.trendUnit) }

                item(key = "categories-title") { SectionTitle(stringResource(R.string.dashboard_by_category)) }
                val categoryTotal = dashboard.categories.fold(BigDecimal.ZERO) { sum, c -> sum + c.total }
                items(dashboard.categories.size, key = { "cat-${dashboard.categories[it].category}" }) { index ->
                    val item = dashboard.categories[index]
                    CategoryRow(
                        item = item,
                        total = categoryTotal,
                        selected = item.category == state.category,
                        onClick = { onIntent(Intent.SelectCategory(item.category)) }
                    )
                }

                item(key = "vendors-title") { SectionTitle(stringResource(R.string.dashboard_by_vendor)) }
                val vendors = if (state.showAllVendors) dashboard.vendors else dashboard.vendors.take(COLLAPSED_VENDORS)
                items(vendors.size, key = { "vendor-${vendors[it].key}" }) { index ->
                    val vendor = vendors[index]
                    VendorRow(
                        vendor = vendor,
                        total = dashboard.total,
                        expanded = state.expandedVendor == vendor.key,
                        onClick = { onIntent(Intent.ToggleVendor(vendor.key)) }
                    )
                }
                if (dashboard.vendors.size > COLLAPSED_VENDORS) {
                    item(key = "vendors-more") {
                        TextButton(
                            modifier = Modifier.padding(horizontal = 8.dp),
                            onClick = { onIntent(Intent.ToggleShowAllVendors) }
                        ) {
                            Text(
                                if (state.showAllVendors) stringResource(R.string.show_fewer)
                                else stringResource(R.string.show_all_vendors, dashboard.vendors.size)
                            )
                        }
                    }
                }

                item(key = "sources-title") { SectionTitle(stringResource(R.string.dashboard_by_source)) }
                items(dashboard.sources.size, key = { "source-${dashboard.sources[it].label}" }) { index ->
                    SourceRow(dashboard.sources[index], dashboard.total)
                }

                item(key = "footer") { Box(Modifier.padding(24.dp)) }
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
