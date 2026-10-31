package com.zyke.message.domain.repository

import com.zyke.message.domain.dashboard.DashboardPeriod
import com.zyke.message.domain.dashboard.DateRange
import com.zyke.message.domain.dashboard.FlowDirection
import com.zyke.message.domain.dashboard.SourceFilter

/** What the person last chose to look at in Insights, kept so it is still chosen the next time the app opens. */
data class SavedInsightsFilter(
    val period: DashboardPeriod = DashboardPeriod.THIS_MONTH,
    val customRange: DateRange? = null,
    val source: SourceFilter = SourceFilter(),
    /** Whether the lists below the headline show money spent or money received. */
    val direction: FlowDirection = FlowDirection.SPENT
)

interface InsightsFilterRepository {
    fun load(): SavedInsightsFilter

    fun save(filter: SavedInsightsFilter)
}

object NoInsightsFilter : InsightsFilterRepository {
    override fun load() = SavedInsightsFilter()
    override fun save(filter: SavedInsightsFilter) = Unit
}
