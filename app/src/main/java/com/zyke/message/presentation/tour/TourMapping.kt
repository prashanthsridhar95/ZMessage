package com.zyke.message.presentation.tour

import com.zyke.message.domain.tour.TourAnchor
import com.zyke.message.domain.tour.TourPage
import com.zyke.message.domain.tour.TourTab
import com.zyke.message.presentation.AppTab

internal fun AppTab.toTour(): TourTab = when (this) {
    AppTab.HOME -> TourTab.HOME
    AppTab.ACCOUNTS -> TourTab.ACCOUNTS
    AppTab.MONEY -> TourTab.INSIGHTS
    AppTab.BILLS -> TourTab.BILLS
    AppTab.INBOX -> TourTab.MESSAGES
}

internal fun TourTab.toApp(): AppTab = when (this) {
    TourTab.HOME -> AppTab.HOME
    TourTab.ACCOUNTS -> AppTab.ACCOUNTS
    TourTab.INSIGHTS -> AppTab.MONEY
    TourTab.BILLS -> AppTab.BILLS
    TourTab.MESSAGES -> AppTab.INBOX
}

/** Pages that live in Home's own state (opened through the Home view-model) rather than in the app shell. */
internal val TourPage.isHomePage: Boolean get() = this == TourPage.CALENDAR || this == TourPage.GOALS || this == TourPage.REMINDERS

/** Which anchor each bottom-bar tab carries; the one place the five NAV_ values are named in the UI. */
internal fun navAnchor(tab: AppTab): TourAnchor = when (tab) {
    AppTab.HOME -> TourAnchor.NAV_HOME
    AppTab.ACCOUNTS -> TourAnchor.NAV_ACCOUNTS
    AppTab.MONEY -> TourAnchor.NAV_INSIGHTS
    AppTab.BILLS -> TourAnchor.NAV_BILLS
    AppTab.INBOX -> TourAnchor.NAV_MESSAGES
}
