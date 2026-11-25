package com.zyke.message.di

import com.zyke.message.domain.demo.DemoDataset

/**
 * Loads the made-up setup into the (in-memory) stores of a demo container. The stores are the app's ordinary ones, built
 * over a [com.zyke.message.data.demo.DemoContext], so these calls go through the same code the app uses for a person's
 * own choices, and end in memory only.
 */
internal object DemoSeeder {
    fun seed(container: AppContainer, data: DemoDataset) {
        container.settings.setFetchLogos(false)
        data.cards.forEach(container.cards::save)
        container.moneyPlan.save(data.plan)
        data.budgets.forEach { (category, amount) -> container.budgets.setBudget(category, amount) }
        data.dailyBudgets.forEach { (category, amount) -> container.budgets.setDailyBudget(category, amount) }
        data.goals.forEach(container.goals::save)
        data.holdings.forEach { container.investments.add(it) }
        data.netWorth.forEach(container.netWorth::save)
        data.loans.forEach(container.loans::save)
        data.reminders.forEach(container.reminders::save)
        data.events.forEach(container.calendarEvents::save)
        container.categoryOverrides.setAll(data.categories)
        container.nicknames.setAll(data.nicknames)
        container.watchlist.save(data.watch)
        // Made-up places for the made-up purchases, around a fictional city: the demo never asks the phone where it is.
        seedPlaces(container, data)
        // No tour on the way in (it is still there under Settings, Help).
        container.tourSeen.update { it.finished().copy(installKind = com.zyke.message.domain.tour.TourInstallKind.RESTORED) }
    }

    private fun seedPlaces(container: AppContainer, data: DemoDataset) {
        val settings = container.spendPlaces.state.value.settings
        for (sms in data.messages) {
            val classified = container.messageClassifier.classify(sms)
            if (com.zyke.message.domain.places.PlaceCapture.decide(classified, settings.copy(enabled = true)) != com.zyke.message.domain.places.CaptureDecision.Capture) continue
            container.spendPlaces.put(com.zyke.message.domain.places.DemoPlaces.place(com.zyke.message.domain.places.PlaceKey.of(sms), classified.merchant.name, sms.date))
        }
    }
}
