package com.zyke.message.data.repository

import android.content.Context
import com.zyke.message.domain.dashboard.DashboardPeriod
import com.zyke.message.domain.dashboard.DateRange
import com.zyke.message.domain.dashboard.SourceFilter
import com.zyke.message.domain.dashboard.SourceKind
import com.zyke.message.domain.repository.InsightsFilterRepository
import com.zyke.message.domain.repository.SavedInsightsFilter
import java.time.LocalDate

/** The Insights period, date range and source, in private preferences. */
class InsightsFilterRepositoryImpl(context: Context) : InsightsFilterRepository {
    private val prefs = context.applicationContext.getSharedPreferences("insights_filter", Context.MODE_PRIVATE)

    override fun load(): SavedInsightsFilter = runCatching {
        val period = prefs.getString(PERIOD, null)?.let { name -> DashboardPeriod.entries.firstOrNull { it.name == name } } ?: DashboardPeriod.THIS_MONTH
        val range = runCatching { DateRange(LocalDate.parse(prefs.getString(START, null)), LocalDate.parse(prefs.getString(END, null))) }.getOrNull()
        val kind = prefs.getString(SOURCE, null)?.let { name -> SourceKind.entries.firstOrNull { it.name == name } } ?: SourceKind.ALL
        // A custom period with no range left to show falls back to the month.
        SavedInsightsFilter(
            period = if (period == DashboardPeriod.CUSTOM && range == null) DashboardPeriod.THIS_MONTH else period,
            customRange = range,
            source = SourceFilter(kind, prefs.getString(LAST4, null))
        )
    }.getOrDefault(SavedInsightsFilter())

    override fun save(filter: SavedInsightsFilter) {
        prefs.edit()
            .putString(PERIOD, filter.period.name)
            .putString(START, filter.customRange?.start?.toString())
            .putString(END, filter.customRange?.endInclusive?.toString())
            .putString(SOURCE, filter.source.kind.name)
            .putString(LAST4, filter.source.last4)
            .apply()
    }

    private companion object {
        const val PERIOD = "period"
        const val START = "start"
        const val END = "end"
        const val SOURCE = "source"
        const val LAST4 = "last4"
    }
}
