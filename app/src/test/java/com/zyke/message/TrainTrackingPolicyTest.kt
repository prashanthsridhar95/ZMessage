package com.zyke.message

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The train twin of [FlightTrackingPolicyTest]: the train notification is a foreground service that fetches, so its shape is pinned
 * here the same way: what it may ask, who it tells, what it may write to the log, that it cannot be dismissed while the journey is under
 * way and can be afterwards, that no exact-alarm permission sneaks in, and that trains and flights together stay within two trips.
 */
class TrainTrackingPolicyTest {
    private val main = File("src/main/java/com/zyke/message")
    private fun text(path: String) = File(main, path).readText()
    private val service get() = text("notifications/TrainTrackingService.kt")
    private val control get() = text("notifications/TrainTrackingControl.kt")
    private val notifier get() = text("notifications/TrainNotifier.kt")

    @Test fun theServiceIsADataSyncForegroundServiceThatEndsWhenTheSystemSays() {
        assertTrue("ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC" in service)
        assertTrue("startForeground(id, notification, ServiceInfo" in service)
        assertTrue("override fun onTimeout(startId: Int, fgsType: Int)" in service)
        assertTrue(service.substringAfter("override fun onTimeout").take(80).contains("StopReason.TIME_LIMIT"))
        assertTrue("START_NOT_STICKY" in service && "START_STICKY" !in service.replace("START_NOT_STICKY", ""))
    }

    @Test fun theOngoingNotificationCannotBeSwipedAwayAndTheFinishedOneCan() {
        val ongoing = notifier.substringAfter("fun ongoing(").substringBefore("fun finished(")
        assertTrue("setOngoing(true)" in ongoing)
        assertTrue("\"Stop tracking\"" in ongoing)
        assertTrue("setProgress(100" in ongoing)
        assertEquals("one action only", 1, Regex("addAction\\(").findAll(ongoing).count())
        val finished = notifier.substringAfter("fun finished(").substringBefore("fun prompt(")
        assertTrue("setOngoing(false)" in finished && "setAutoCancel(true)" in finished)
        assertFalse("addAction(" in finished)
        assertTrue("NotificationPrivacy.text(" in ongoing && "NotificationPrivacy.text(" in finished)
    }

    @Test fun theLockScreenShowsTheTrainUnlessTheOneSharedSwitchIsOff() {
        val lock = notifier.substringAfter("private fun NotificationCompat.Builder.lockScreen(").substringBefore("/** The ongoing notification")
        assertTrue("setVisibility(NotificationCompat.VISIBILITY_PUBLIC)" in lock)
        assertTrue("setVisibility(NotificationCompat.VISIBILITY_PRIVATE)" in lock && "setPublicVersion(" in lock)
        assertTrue("TrainNotificationText.PUBLIC_TITLE" in notifier)
        val ongoing = notifier.substringAfter("fun ongoing(").substringBefore("fun finished(")
        val finished = notifier.substringAfter("fun finished(").substringBefore("fun prompt(")
        for (n in listOf(ongoing, finished)) {
            assertTrue(".lockScreen(context," in n)
            assertFalse("setPublicVersion(" in n || "VISIBILITY_PRIVATE" in n)
        }
        assertTrue("detailsOnLock(context)" in ongoing && "detailsOnLock(context)" in finished)
        // One switch for flights and trains: the train notifier reads the flight service's stored flag.
        assertTrue("container.flights.lockScreenDetails" in notifier)
        assertTrue("VISIBILITY_PRIVATE" in notifier.substringAfter("fun prompt(").substringBefore("fun paused("))
    }

    @Test fun theOngoingNotificationIsGlanceableAndQuiet_onItsOwnChannel() {
        val ongoing = notifier.substringAfter("fun ongoing(").substringBefore("fun finished(")
        assertTrue("setOnlyAlertOnce(true)" in ongoing)
        assertTrue("CATEGORY_PROGRESS" in ongoing)
        assertTrue("setUsesChronometer(true)" in ongoing && "setChronometerCountDown(true)" in ongoing)
        assertFalse(Regex("setColorized|RemoteViews|setCustom\\w*View|setVibrate|setDefaults|setFullScreenIntent|setTimeoutAfter").containsMatchIn(notifier))
        val channel = notifier.substringAfter("TRACKING_CHANNEL, \"Train tracking\"").substringBefore("PROMPT_CHANNEL, \"Train reminders\"")
        assertTrue("IMPORTANCE_DEFAULT" in channel && "IMPORTANCE_HIGH" !in channel)
        assertTrue("setSound(null, null)" in channel && "enableVibration(false)" in channel && "enableLights(false)" in channel)
        assertFalse("lockscreenVisibility =" in channel)
        // Its own channel ids, so the person can mute trains apart from flights (a channel's settings cannot be changed afterwards).
        assertTrue("TRACKING_CHANNEL = \"train_live\"" in notifier && "PROMPT_CHANNEL = \"train_prompts\"" in notifier)
        // Only constants and APIs the flight notifier already uses.
        assertFalse("VISIBILITY_NO_OVERRIDE" in notifier.replace("(VISIBILITY_NO_OVERRIDE)", ""))
    }

    @Test fun liveUpdatesAreAskedForTheOngoingNoticeOnlyAndOnlyWhileTheLockScreenSwitchIsOn() {
        assertTrue("if (details) LiveUpdates.promote(" in notifier)
        assertFalse("LiveUpdates.promote(" in notifier.substringAfter("fun finished(").substringBefore("fun prompt("))
        assertTrue("R.drawable.baseline_train_24" in notifier)
    }

    @Test fun theAlarmIsInexactAndTheAppAsksForNoExactAlarmPermission() {
        assertFalse(Regex("setExact|setAlarmClock|canScheduleExactAlarms").containsMatchIn(control))
        assertTrue("setAndAllowWhileIdle" in control)
        val boot = text("notifications/BootReceiver.kt")
        assertTrue("TrainTrackingControl.rearm(context)" in boot)
        assertFalse("startService" in boot || "TrainTrackingService" in boot)
        assertTrue(control.substringAfter("fun startService(").contains("catch (e: Exception)"))
        assertTrue("TrainNotificationText.tapToStart(" in control)
    }

    @Test fun nothingAboutATrainIsEverWrittenToTheLog() {
        val files = listOf(
            service, control, notifier, text("data/trains/TrainService.kt"), text("presentation/trains/ManualTrainUi.kt"), text("presentation/trains/TrainUi.kt"),
            text("data/repository/ManualTrainRepositoryImpl.kt"), text("data/trains/TrainPlanStore.kt"), text("data/markets/TrainFetcher.kt")
        )
        val logged = files.flatMap { f -> f.lines().filter { "DevLog." in it || "Log." in it || "println(" in it } }
        assertTrue(logged.isNotEmpty())
        // Counts, switches and exception class names only: no key, number, station, route, title, text, PNR or status.
        val forbidden = Regex("\\.key|\\.train|snapshot|notice|pnr|number|title|\\.code|route|station|origin|destination|status\\b|\\.text")
        val offenders = logged.filter { line -> forbidden.containsMatchIn(line.substringAfter("{", "")) && "TrainFetcher: " !in line }
        assertTrue("log lines must not carry train details:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }

    @Test fun theServiceAsksTheNetworkOnlyThroughTrainService_andStopsOnTheListedReasons() {
        assertTrue("trains.refresh(" in service)
        assertFalse(Regex("TrainFetcher|TrainStatusParser").containsMatchIn(service + control))
        val stops = service.substringAfter("private suspend fun watch()").substringBefore("private fun countFailures")
        assertTrue("TrainTracking.stopReason(" in stops)
        assertTrue("TrainTracking.pollDelayMs(" in stops)
        assertTrue("canNotify" in stops && "liveOn" in stops && "trackingOn" in stops)
    }

    @Test fun lowEndModeGetsNoExtraMotion() {
        for (f in listOf("presentation/trains/ManualTrainUi.kt", "presentation/trains/TrainUi.kt", "presentation/trains/TrainSettings.kt", "presentation/trains/TrainParts.kt")) {
            assertFalse("$f must not animate", Regex("animate\\w*As|AnimatedVisibility|AnimatedContent|infiniteRepeatable|rememberInfiniteTransition|Crossfade").containsMatchIn(text(f)))
        }
    }

    @Test fun handMadeTrainsTravelWithTheBackup_andTheServicesBookkeepingDoesNot() {
        val backup = text("data/backup/PrefsBackup.kt")
        val excluded = backup.substringAfter("EXCLUDED = setOf(").substringBefore(")")
        assertTrue("\"train_plan\"" in excluded)
        assertFalse("manual_trains" in excluded || "train_status" in excluded)
        assertTrue("\"manual_trains\"" in text("data/repository/ManualTrainRepositoryImpl.kt"))
        assertTrue("\"train_plan\"" in text("data/trains/TrainPlanStore.kt"))
        assertTrue("\"train_status\"" in text("data/trains/TrainService.kt"))
    }

    @Test fun atMostTwoTripsInAllAndTheThirtySecondPoll() {
        val domain = File("../domain/src/main/kotlin/com/zyke/message/domain/train/TrainTracking.kt").readText()
        assertTrue("const val MAX_TRACKED = FlightTracking.MAX_TRACKED" in domain)
        assertTrue("const val POLL_MS = FlightTracking.POLL_MS" in domain)
        assertTrue("fun slotsLeft(otherActive: Int)" in domain)
        // The flights are counted first, and the train service only takes what is left.
        assertTrue("TrainTracking.slotsLeft(" in control && "FlightTracking.active(" in control)
        val flights = File("../domain/src/main/kotlin/com/zyke/message/domain/flight/FlightTracking.kt").readText()
        assertTrue("const val MAX_TRACKED = 2" in flights && "const val POLL_MS = 30_000L" in flights)
    }

    @Test fun theSettingsExplainWhatIsSentAndKeepTheSwitchesApart() {
        val settings = text("presentation/trains/TrainSettings.kt")
        assertTrue("Only the train number (like 12633) and the day it started are sent" in settings)
        assertTrue("Track trains on the day you travel" in settings && "Notification while I travel" in settings)
        assertTrue("prefs.getBoolean(LIVE, false)" in text("data/trains/TrainService.kt"))
    }
}
