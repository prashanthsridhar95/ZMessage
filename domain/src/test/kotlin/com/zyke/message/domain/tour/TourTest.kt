package com.zyke.message.domain.tour

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TourContentTest {
    private val steps = Tours.full()

    @Test fun idsAreUniqueAndNotBlank() {
        assertTrue(steps.all { it.id.isNotBlank() })
        assertEquals(steps.size, steps.map { it.id }.toSet().size)
    }

    @Test fun everyStepHasATitleAndABodyOfSaneLength() {
        steps.forEach { s ->
            assertTrue("${s.id} title", s.title.isNotBlank() && s.title.length <= Tours.MAX_TITLE)
            assertTrue("${s.id} body (${s.body.length})", s.body.isNotBlank() && s.body.length <= Tours.MAX_BODY)
            s.tip?.let { assertTrue("${s.id} tip", it.isNotBlank() && it.length <= Tours.MAX_TIP) }
            assertTrue("${s.id} covers something", s.covers.isNotEmpty())
        }
    }

    @Test fun chaptersAreContiguousAndInTheDeclaredOrder() {
        val seen = Tours.groups()
        assertEquals("a chapter never reappears", seen.size, seen.toSet().size)
        assertEquals(TourGroup.entries.toList(), seen)
        assertEquals(TourGroup.WELCOME, steps.first().group)
    }

    @Test fun size_isInTheIntendedRange() {
        assertTrue("${steps.size} steps", steps.size in 28..48)
    }

    @Test fun dataStepsCarryAPreviewAndAFallbackSpotlight() {
        steps.filter { it.needsData }.forEach { s ->
            assertNotNull("${s.id} preview", s.preview)
            assertTrue("${s.id} something always present to point at", s.fallbackAnchor != null || s.anchor != null)
        }
        steps.filter { it.preview != null }.forEach { assertTrue("${it.id} preview only on data steps", it.needsData) }
    }

    @Test fun stepsOnOtherPagesDeclareATab() {
        steps.forEach { assertNotNull("${it.id} tab", it.tab) }
    }

    @Test fun dwellScalesWithLengthAndIsBounded() {
        steps.forEach { assertTrue(TourTiming.dwellMillis(it) in TourTiming.MIN_MS..TourTiming.MAX_MS) }
        val short = steps.first().copy(title = "A", body = "B", tip = null)
        val long = short.copy(body = "x".repeat(200))
        assertTrue(TourTiming.dwellMillis(long) > TourTiming.dwellMillis(short))
    }

    @Test fun onlyTheStepsThatAskForAnActionBlockAutoPlay() {
        assertTrue(steps.single { it.id == "accounts.networth" }.needsAction)
        assertFalse(steps.first().needsAction)
    }

    @Test fun settingsPagesMapToSettingsGroups() {
        assertNull(TourPage.SETTINGS_HUB.settingsGroup)
        assertEquals("PRIVACY", TourPage.SETTINGS_PRIVACY.settingsGroup)
        assertNull(TourPage.SEARCH.settingsGroup)
        assertTrue(TourPage.SETTINGS_HELP.isSettings)
    }

    /** Every feature the app has is explained somewhere. Add the key here when the app gains a feature, then a step that covers it. */
    @Test fun featureCoverageTest() {
        val required = listOf(
            "privacy.local_first", "privacy.no_account", "privacy.messages_stay", "sms.reading", "sms.permission", "nav.tabs",
            "home.total_balance", "home.market_strip", "home.todays_budget", "home.streak", "home.adjust_budget", "home.codes_card",
            "home.copy_code", "home.needs_review", "home.attention_strip", "home.shortcut_calendar", "home.shortcut_goals",
            "home.shortcut_reminders", "home.shortcut_afford", "home.plans", "home.snooze_dismiss", "home.mark_paid", "home.pay_bill",
            "add.entry_button", "add.widget", "add.quick_tile", "add.share_to_app",
            "accounts.tabs", "accounts.hero_number", "accounts.card_deck", "accounts.edit_account", "accounts.net_worth", "accounts.leave_out",
            "insights.month_vs_last", "insights.heading_salary", "insights.budgets", "insights.report_card", "insights.categories",
            "insights.subscriptions", "insights.cancel_helper", "insights.year_in_review", "insights.filters", "insights.direction",
            "bills.due_list", "bills.mark_paid", "bills.snooze", "bills.repeats", "bills.split", "bills.friends",
            "messages.feed", "messages.search_filters", "messages.detail", "messages.rename", "messages.category", "messages.custom_categories",
            "messages.picture", "messages.notes_tags", "messages.leave_out", "messages.goal_link", "messages.review", "messages.duplicates",
            "messages.transfers", "messages.teach_parser",
            "search.global", "search.recent", "search.jump_to", "search.filters",
            "goals", "reminders", "calendar", "calendar.warranties",
            "settings.plan_salary", "settings.salary_autodetect", "settings.budgets", "settings.cards_accounts", "settings.categories_rules",
            "settings.notifications", "settings.digest", "settings.weekly_summary", "settings.heads_up", "settings.otp_alerts",
            "settings.privacy_hide", "settings.authentication", "settings.block_screenshots", "settings.app_lock",
            "settings.theme", "settings.true_black", "settings.accent", "settings.app_icon", "settings.market_watch", "settings.market_any",
            "backup.this_phone", "backup.drive", "backup.folder", "backup.restore_file", "backup.schedule",
            "settings.share_logs", "settings.developer", "settings.parsing_report", "widgets", "wear", "tour.settings_entry"
        )
        val missing = required.filter { it !in Tours.coverage() }
        assertTrue("Features with no tour step: $missing", missing.isEmpty())
    }
}

class TourProgressTest {
    private val steps = Tours.full()

    @Test fun nextMovesOnAndRecordsTheStepDone() {
        val p = TourProgress().next(steps)
        assertEquals(1, p.index)
        assertEquals(setOf(steps[0].id), p.completed)
        assertTrue(p.isActive)
    }

    @Test fun lastNextFinishesTheTour() {
        var p = TourProgress()
        repeat(steps.size) { p = p.next(steps) }
        assertTrue(p.finished)
        assertFalse(p.isActive)
        assertEquals(steps.lastIndex, p.index)
        assertEquals(steps.map { it.id }.toSet(), p.completed)
        assertNull(p.current(steps))
        assertEquals(p, p.next(steps))
    }

    @Test fun backStopsAtTheFirstStep() {
        assertEquals(0, TourProgress().back(steps).index)
        assertEquals(1, TourProgress(index = 2).back(steps).index)
    }

    @Test fun skipEndsWithoutCompleting() {
        val p = TourProgress(index = 5, completed = setOf("a")).skip()
        assertTrue(p.skipped)
        assertFalse(p.finished)
        assertFalse(p.isActive)
        assertEquals(setOf("a"), p.completed)
        assertEquals(p, p.next(steps))
        assertEquals(p, p.back(steps))
    }

    @Test fun skipAfterFinishKeepsItFinished() {
        val done = TourProgress(finished = true)
        assertEquals(done, done.skip())
    }

    @Test fun jumpToGroupLandsOnItsFirstStep() {
        TourGroup.entries.forEach { g ->
            val p = TourProgress().jumpToGroup(g, steps)
            assertEquals(g, steps[p.index].group)
            assertEquals(steps.indexOfFirst { it.group == g }, p.index)
        }
    }

    @Test fun jumpingRevivesASkippedTour() {
        val p = TourProgress(skipped = true).jumpToGroup(TourGroup.BILLS, steps)
        assertTrue(p.isActive)
    }

    @Test fun jumpToAGroupWithNoStepsChangesNothing() {
        val p = TourProgress(index = 3)
        assertEquals(p, p.jumpToGroup(TourGroup.BACKUP, steps.filter { it.group != TourGroup.BACKUP }))
    }

    @Test fun positionInGroupCountsWithinTheChapter() {
        val homeStart = steps.indexOfFirst { it.group == TourGroup.HOME }
        val (n, of) = TourProgress(index = homeStart + 1).positionInGroup(steps)
        assertEquals(2, n)
        assertEquals(Tours.stepsIn(TourGroup.HOME).size, of)
    }

    @Test fun remainingCountsStepsAfterThisOne() {
        assertEquals(steps.size - 1, TourProgress().remaining(steps))
        assertEquals(0, TourProgress(index = steps.lastIndex).remaining(steps))
    }

    @Test fun resumeContinuesWhereTheProcessDied() {
        val p = TourProgress.resume(7, steps)
        assertEquals(7, p.index)
        assertEquals(steps.take(7).map { it.id }.toSet(), p.completed)
    }

    @Test fun resumeWithAnIndexThatNoLongerFitsStartsOver() {
        assertEquals(TourProgress(), TourProgress.resume(steps.size + 10, steps))
        assertEquals(TourProgress(), TourProgress.resume(-3, steps))
        assertEquals(TourProgress(), TourProgress.resume(0, steps))
    }

    @Test fun anEmptyTourNeverCrashes() {
        val p = TourProgress()
        assertEquals(p, p.next(emptyList()))
        assertEquals(p, p.back(emptyList()))
        assertNull(p.current(emptyList()))
    }
}

class TourPolicyDecisionTest {
    @Test fun classifyFreshOnlyWithoutSetupAndRightAfterInstall() {
        assertEquals(TourInstallKind.FRESH, TourPolicy.classify(false, 0))
        assertEquals(TourInstallKind.UPDATED, TourPolicy.classify(true, 0))
        assertEquals(TourInstallKind.UPDATED, TourPolicy.classify(false, 30L * 24 * 3600 * 1000))
    }

    @Test fun freshInstallIsInvitedOnce() {
        val s = TourState().withKind(TourInstallKind.FRESH)
        assertEquals(TourOffer.INVITE, TourPolicy.offer(s))
        assertEquals(TourOffer.BANNER, TourPolicy.offer(s.invited()))
    }

    @Test fun updatedAndRestoredInstallsGetOnlyTheBanner() {
        assertEquals(TourOffer.BANNER, TourPolicy.offer(TourState().withKind(TourInstallKind.UPDATED)))
        assertEquals(TourOffer.BANNER, TourPolicy.offer(TourState().withKind(TourInstallKind.FRESH).restored()))
    }

    @Test fun kindIsDecidedOnlyOnce() {
        val s = TourState().withKind(TourInstallKind.FRESH).withKind(TourInstallKind.UPDATED)
        assertEquals(TourInstallKind.FRESH, s.installKind)
    }

    @Test fun dismissingTheBannerHidesItForGood() {
        assertEquals(TourOffer.NONE, TourPolicy.offer(TourState().withKind(TourInstallKind.UPDATED).bannerDismissed()))
    }

    @Test fun finishingEndsEveryOffer() {
        val s = TourState().withKind(TourInstallKind.FRESH).started(0).finished()
        assertTrue(s.completed)
        assertEquals(TourOffer.NONE, TourPolicy.offer(s))
    }

    @Test fun leavingEarlyKeepsTheBanner() {
        val s = TourState().withKind(TourInstallKind.FRESH).started(0).moved(4).left()
        assertFalse(s.running)
        assertEquals(0, s.savedIndex)
        assertEquals(TourOffer.BANNER, TourPolicy.offer(s))
    }

    @Test fun anInterruptedTourResumes() {
        val s = TourState().withKind(TourInstallKind.FRESH).started(0).moved(9)
        assertEquals(TourOffer.RESUME, TourPolicy.offer(s))
        assertEquals(9, s.savedIndex)
    }

    @Test fun restoreClearsARunningTourAndMarksTheInstall() {
        val s = TourState().withKind(TourInstallKind.FRESH).started(0).moved(9).restored()
        assertEquals(TourInstallKind.RESTORED, s.installKind)
        assertFalse(s.running)
        assertEquals(TourOffer.BANNER, TourPolicy.offer(s))
    }

    @Test fun movingWhenNotRunningDoesNothing() {
        val s = TourState().moved(5)
        assertEquals(0, s.savedIndex)
    }

    @Test fun aNewMajorTourIsOfferedAgainAsABanner() {
        val old = TourState(installKind = TourInstallKind.FRESH, offered = true, completedVersion = TOUR_VERSION - 1, bannerDismissedVersion = TOUR_VERSION - 1)
        if (TOUR_VERSION > 1) assertEquals(TourOffer.BANNER, TourPolicy.offer(old))
    }

    @Test fun unknownKindOffersNothing() {
        assertEquals(TourOffer.NONE, TourPolicy.offer(TourState()))
    }

    @Test fun repositoriesBehave() {
        val repo = InMemoryTourSeenRepository()
        repo.update { it.withKind(TourInstallKind.FRESH).withAutoPlay(true) }
        assertTrue(repo.state.value.autoPlay)
        NoTourSeenRepository.update { it.invited() }
        assertFalse(NoTourSeenRepository.state.value.offered)
    }
}
