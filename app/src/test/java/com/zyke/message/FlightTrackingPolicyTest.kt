package com.zyke.message

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The in-flight notification is a foreground service that fetches, so its shape is pinned here: what it may ask, who it tells,
 * what it may write to the log, that it cannot be dismissed while the flight is in the air and can be afterwards, and that no
 * exact-alarm permission sneaks in.
 */
class FlightTrackingPolicyTest {
    private val main = File("src/main/java/com/zyke/message")
    private fun text(path: String) = File(main, path).readText()
    private val service get() = text("notifications/FlightTrackingService.kt")
    private val control get() = text("notifications/FlightTrackingControl.kt")
    private val notifier get() = text("notifications/FlightNotifier.kt")

    @Test fun theServiceIsADataSyncForegroundServiceThatEndsWhenTheSystemSays() {
        assertTrue("ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC" in service)
        assertTrue("startForeground(id, notification, ServiceInfo" in service)
        // Android 15's six-hour limit: the system calls onTimeout, and the service stops at once.
        assertTrue("override fun onTimeout(startId: Int, fgsType: Int)" in service)
        assertTrue(service.substringAfter("override fun onTimeout").take(80).contains("StopReason.TIME_LIMIT"))
        // Not sticky: a killed process is not brought back from the background.
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
        // The text goes through the same privacy filter as every other notification.
        assertTrue("NotificationPrivacy.text(" in ongoing && "NotificationPrivacy.text(" in finished)
    }

    @Test fun theLockScreenShowsTheFlightUnlessTheSwitchIsOff() {
        val lock = notifier.substringAfter("private fun NotificationCompat.Builder.lockScreen(").substringBefore("/** The ongoing notification")
        // On: public, and no redacted version attached. Off: private, with the short public version.
        assertTrue("setVisibility(NotificationCompat.VISIBILITY_PUBLIC)" in lock)
        assertTrue("setVisibility(NotificationCompat.VISIBILITY_PRIVATE)" in lock && "setPublicVersion(" in lock)
        assertTrue("FlightNotificationText.PUBLIC_TITLE" in notifier)
        val ongoing = notifier.substringAfter("fun ongoing(").substringBefore("fun finished(")
        val finished = notifier.substringAfter("fun finished(").substringBefore("fun prompt(")
        for (n in listOf(ongoing, finished)) {
            assertTrue(".lockScreen(context," in n)
            assertFalse("setPublicVersion(" in n || "VISIBILITY_PRIVATE" in n)
        }
        assertTrue("detailsOnLock(context)" in ongoing && "detailsOnLock(context)" in finished)
        // The switch is a stored flag, on by default, and a Settings row turns it off.
        assertTrue("prefs.getBoolean(LOCK_DETAILS, true)" in text("data/flights/FlightService.kt"))
        assertTrue("Show flight details on the lock screen" in text("presentation/flights/FlightSettings.kt"))
        // A prompt keeps the short lock-screen version (it is a heads-up).
        assertTrue("VISIBILITY_PRIVATE" in notifier.substringAfter("fun prompt(").substringBefore("fun paused("))
    }

    @Test fun theOngoingNotificationIsGlanceableAndQuiet() {
        val ongoing = notifier.substringAfter("fun ongoing(").substringBefore("fun finished(")
        assertTrue("setOnlyAlertOnce(true)" in ongoing)
        assertTrue("CATEGORY_PROGRESS" in ongoing)
        // The system counts down to landing between the 30-second updates.
        assertTrue("setUsesChronometer(true)" in ongoing && "setChronometerCountDown(true)" in ongoing)
        // A colourised notification cannot be a Live Update, and a custom view is never used.
        assertFalse(Regex("setColorized|RemoteViews|setCustom\\w*View|setVibrate|setDefaults|setFullScreenIntent|setTimeoutAfter").containsMatchIn(notifier))
        // The channel: default importance (so it is not in the silent group on the lock screen) with no sound, vibration or lights.
        val channel = notifier.substringAfter("TRACKING_CHANNEL, \"Flight tracking\"").substringBefore("PROMPT_CHANNEL, \"Flight reminders\"")
        assertTrue("IMPORTANCE_DEFAULT" in channel && "IMPORTANCE_HIGH" !in channel)
        assertTrue("setSound(null, null)" in channel && "enableVibration(false)" in channel && "enableLights(false)" in channel)
        assertTrue("VISIBILITY_NO_OVERRIDE" in channel)
        // The old channel (low importance, private) is removed because a channel's settings cannot be changed afterwards.
        assertTrue("deleteNotificationChannel(LEGACY_TRACKING_CHANNEL)" in notifier && "TRACKING_CHANNEL = \"flight_live\"" in notifier)
    }

    @Test fun liveUpdatesAreReachedOnlyByGuardedReflectionAndNeverBreakTheNotification() {
        val live = text("notifications/LiveUpdates.kt")
        val gradle = File("build.gradle.kts").readText()
        // Compiled against 35 (the Android Gradle Plugin and CI image stop there), so 36's classes are looked up by name.
        assertTrue("compileSdk = 35" in gradle)
        assertTrue("Class.forName(STYLE)" in live && "Build.VERSION.SDK_INT >= API_LIVE_UPDATES" in live)
        val promote = live.substringAfter("fun promote(").substringBefore("private fun tryStart")
        assertTrue("catch (t: Throwable)" in promote && promote.trimEnd().contains("built"))
        assertTrue("if (!available()) return built" in promote)
        assertTrue("setRequestPromotedOngoing" in promote && "setShortCriticalText" in promote)
        assertTrue("canPostPromotedNotifications" in live)
        // Only the notifier asks for it, only while the lock-screen switch is on, and only for the ongoing notice.
        assertTrue("LiveUpdates.promote(" in notifier && "if (details) LiveUpdates.promote(" in notifier)
        assertFalse("LiveUpdates.promote(" in notifier.substringAfter("fun finished(").substringBefore("fun prompt("))
        // The lock-screen check is a read of two system settings; no permission, nothing stored or sent.
        assertTrue("lock_screen_allow_private_notifications" in live)
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue("android.permission.POST_PROMOTED_NOTIFICATIONS" in manifest)
    }

    @Test fun theAlarmIsInexactAndTheAppAsksForNoExactAlarmPermission() {
        assertFalse(Regex("setExact|setAlarmClock|canScheduleExactAlarms").containsMatchIn(control))
        assertTrue("setAndAllowWhileIdle" in control)
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertFalse("SCHEDULE_EXACT_ALARM" in manifest || "USE_EXACT_ALARM" in manifest)
        // The service is never started from the boot broadcast (not allowed for a data-sync service on Android 15); the boot receiver only sets the alarm.
        val boot = text("notifications/BootReceiver.kt")
        assertTrue("FlightTrackingControl.rearm(context)" in boot)
        assertFalse("startService" in boot || "FlightTrackingService" in boot)
        // When the system refuses a background start, the person gets a notification to tap instead of a crash.
        assertTrue(control.substringAfter("fun startService(").contains("catch (e: Exception)"))
        assertTrue("FlightNotificationText.tapToStart(" in control)
    }

    @Test fun nothingAboutAFlightIsEverWrittenToTheLog() {
        val files = listOf(service, control, notifier, text("data/flights/FlightService.kt"), text("presentation/flights/ManualFlightUi.kt"), text("data/repository/ManualFlightRepositoryImpl.kt"), text("data/flights/FlightPlanStore.kt"))
        val logged = files.flatMap { f -> f.lines().filter { "DevLog." in it || "Log." in it || "println(" in it } }
        assertTrue(logged.isNotEmpty())
        // Counts, switches and exception class names only: no key, number, route, title, text, PNR or status.
        val forbidden = Regex("\\.key|\\.flight|snapshot|notice|pnr|number|title|\\.code|route|origin|destination|status\\b|\\.text")
        val offenders = logged.filter { line -> forbidden.containsMatchIn(line.substringAfter("{", "")) && "FlightFetcher: " !in line }
        assertTrue("log lines must not carry flight details:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }

    @Test fun theServiceAsksTheNetworkOnlyThroughFlightService_andStopsOnTheListedReasons() {
        assertTrue("flights.refresh(" in service)
        assertFalse(Regex("FlightFetcher|FlightStatusParser").containsMatchIn(service + control))
        val stops = service.substringAfter("private suspend fun watch()").substringBefore("private fun countFailures")
        assertTrue("FlightTracking.stopReason(" in stops)
        assertTrue("FlightTracking.pollDelayMs(" in stops)
        assertTrue("canNotify" in stops && "liveOn" in stops && "trackingOn" in stops)
    }

    @Test fun lowEndModeGetsNoExtraMotion() {
        for (f in listOf("presentation/flights/ManualFlightUi.kt", "presentation/flights/FlightUi.kt", "presentation/flights/FlightSettings.kt")) {
            assertFalse("$f must not animate", Regex("animate\\w*As|AnimatedVisibility|AnimatedContent|infiniteRepeatable|rememberInfiniteTransition|Crossfade").containsMatchIn(text(f)))
        }
    }

    @Test fun handMadeFlightsTravelWithTheBackup_andTheServicesBookkeepingDoesNot() {
        val backup = text("data/backup/PrefsBackup.kt")
        val excluded = backup.substringAfter("EXCLUDED = setOf(").substringBefore(")")
        assertTrue("\"flight_plan\"" in excluded)
        assertFalse("manual_flights" in excluded)
        assertTrue("\"manual_flights\"" in text("data/repository/ManualFlightRepositoryImpl.kt"))
        assertTrue("\"flight_plan\"" in text("data/flights/FlightPlanStore.kt"))
    }

    @Test fun atMostTwoFlightsAndTheThirtySecondPoll() {
        val domain = File("../domain/src/main/kotlin/com/zyke/message/domain/flight/FlightTracking.kt").readText()
        assertTrue("const val MAX_TRACKED = 2" in domain)
        assertTrue("const val POLL_MS = 30_000L" in domain)
    }
}
