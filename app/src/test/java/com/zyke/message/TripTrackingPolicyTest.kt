package com.zyke.message

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The flight and the train notification services are one shared implementation (TripTrackingService, TripTrackingControl, TripNotifier)
 * and two thin parameterisations. This test pins the invariants over BOTH kinds and the shared base together, so moving code between
 * them cannot loosen anything; FlightTrackingPolicyTest and TrainTrackingPolicyTest keep the per-kind details (words, channels, hosts).
 */
class TripTrackingPolicyTest {
    private val notifications = File("src/main/java/com/zyke/message/notifications")
    private fun text(name: String) = File(notifications, name).readText()

    private val baseService get() = text("TripTrackingService.kt")
    private val baseControl get() = text("TripTrackingControl.kt")
    private val baseNotifier get() = text("TripNotifier.kt")

    private val kinds = listOf("Flight", "Train")
    private fun service(k: String) = text("${k}TrackingService.kt")
    private fun control(k: String) = text("${k}TrackingControl.kt")
    private fun notifier(k: String) = text("${k}Notifier.kt")

    private fun everything() = listOf(baseService, baseControl, baseNotifier) + kinds.flatMap { listOf(service(it), control(it), notifier(it)) }

    @Test fun eachManifestComponentIsAThinSubclassOfTheSharedClasses() {
        for (k in kinds) {
            assertTrue("class ${k}TrackingService : TripTrackingService<" in service(k))
            assertTrue("object ${k}TrackingControl : TripTrackingControl<" in control(k))
            assertTrue("object ${k}Notifier : TripNotifier()" in notifier(k))
            assertTrue("class ${k}AlarmReceiver : BroadcastReceiver()" in control(k))
            // The loop, the foreground handling and the builders exist once.
            assertFalse("scope.launch" in service(k) || "startForeground" in service(k) || "onTimeout" in service(k))
            assertFalse("NotificationCompat.Builder" in notifier(k) || "NotificationChannel(" in notifier(k))
            assertFalse("AlarmManager" in control(k) || "PendingIntent" in control(k))
        }
        assertEquals(1, Regex("override fun onTimeout\\(startId: Int, fgsType: Int\\)").findAll(everything().joinToString("\n")).count())
        assertEquals(1, Regex("setAndAllowWhileIdle").findAll(everything().joinToString("\n")).count())
        assertEquals(1, Regex("fun ongoing\\(").findAll(everything().joinToString("\n")).count())
    }

    @Test fun theIdsChannelsAndStopIntentsAreTheOnesThatWereShipped() {
        assertTrue("TRACKING_CHANNEL = \"flight_live\"" in notifier("Flight") && "PROMPT_CHANNEL = \"flight_prompts\"" in notifier("Flight"))
        assertTrue("TRACKING_CHANNEL = \"train_live\"" in notifier("Train") && "PROMPT_CHANNEL = \"train_prompts\"" in notifier("Train"))
        assertTrue("baseId = 4300" in notifier("Flight") && "PAUSED_ID = 4299" in notifier("Flight") && "PLACEHOLDER_ID = 4298" in notifier("Flight"))
        assertTrue("baseId = 5300" in notifier("Train") && "PAUSED_ID = 5299" in notifier("Train") && "PLACEHOLDER_ID = 5298" in notifier("Train"))
        assertTrue("requestCode: Int get() = 811" in control("Flight") && "requestCode: Int get() = 812" in control("Train"))
        assertTrue("ACTION_STOP = \"com.zyke.message.action.STOP_FLIGHT_TRACKING\"" in service("Flight"))
        assertTrue("EXTRA_KEY = \"com.zyke.message.extra.FLIGHT_KEY\"" in service("Flight"))
        assertTrue("ACTION_STOP = \"com.zyke.message.action.STOP_TRAIN_TRACKING\"" in service("Train"))
        assertTrue("EXTRA_KEY = \"com.zyke.message.extra.TRAIN_KEY\"" in service("Train"))
        // The span of ids is one number in one place; flights and trains stay in separate ranges of it.
        assertTrue("const val SPAN = 900" in baseNotifier)
    }

    @Test fun theOngoingNoticeIsOngoingAndQuietAndTheFinishedOnesAreDismissible() {
        val ongoing = baseNotifier.substringAfter("fun ongoing(").substringBefore("fun finished(")
        val finished = baseNotifier.substringAfter("fun finished(").substringBefore("fun prompt(")
        assertTrue("setOngoing(true)" in ongoing && "setOnlyAlertOnce(true)" in ongoing && "CATEGORY_PROGRESS" in ongoing)
        assertTrue("setOngoing(false)" in finished && "setAutoCancel(true)" in finished && "addAction(" !in finished)
        assertEquals(1, Regex("addAction\\(").findAll(ongoing).count())
        // The channel that carries the ongoing notice makes no noise and sets no lock-screen visibility of its own.
        val channel = baseNotifier.substringAfter("NotificationChannel(look.trackingChannel").substringBefore("NotificationChannel(look.promptChannel")
        assertTrue("IMPORTANCE_DEFAULT" in channel && "setSound(null, null)" in channel && "enableVibration(false)" in channel && "enableLights(false)" in channel)
        assertFalse("lockscreenVisibility =" in channel)
        // Live Update promotion is asked for the ongoing notice only, and only while the lock-screen switch is on.
        assertEquals(1, Regex("LiveUpdates\\.promote\\(").findAll(baseNotifier).count())
        assertTrue("if (details) LiveUpdates.promote(" in ongoing)
        assertFalse(Regex("setColorized|RemoteViews|setCustom\\w*View|setVibrate|setDefaults|setFullScreenIntent|setTimeoutAfter").containsMatchIn(baseNotifier))
        // Lock-screen privacy: public only when the switch allows it, otherwise private with the redacted version.
        assertTrue("detailsOnLock(context)" in ongoing && "detailsOnLock(context)" in finished)
        assertFalse("VISIBILITY_PUBLIC" in ongoing || "VISIBILITY_PUBLIC" in finished)
        assertTrue("VISIBILITY_PRIVATE" in baseNotifier.substringAfter("fun prompt(").substringBefore("fun paused("))
    }

    @Test fun theSharedServiceHandlesTheSystemsTimeLimitAndIsNotSticky() {
        assertTrue("ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC" in baseService)
        assertTrue(baseService.substringAfter("override fun onTimeout").take(80).contains("StopReason.TIME_LIMIT"))
        assertTrue("START_NOT_STICKY" in baseService && "START_STICKY" !in baseService.replace("START_NOT_STICKY", ""))
        // The "Tracking paused" notice is left only when the time limit ended it and notifications are still allowed.
        assertTrue("reason == StopReason.TIME_LIMIT && control.canNotify(this)" in baseService)
        // The one cap: flights and trains share two places, and the service never asks for more than its control gives.
        assertTrue("control.activeOf(" in baseService)
    }

    @Test fun nothingReachesTheNetworkFromTheTrackingCode() {
        val all = everything().joinToString("\n")
        assertFalse(Regex("HttpURLConnection|openConnection|java\\.net\\.URL|okhttp|FlightFetcher|TrainFetcher|FlightStatusParser|IndianRailApiParser").containsMatchIn(all))
        // The only way to ask is the kind's own data service.
        assertTrue("container.flights.refresh(" in control("Flight") && "container.trains.refresh(" in control("Train"))
        assertFalse(Regex("setExact|setAlarmClock|canScheduleExactAlarms").containsMatchIn(all))
    }

    @Test fun everyDoorChecksDemoMode() {
        for (door in listOf("fun onForeground(", "fun rearm(", "fun onAlarm(")) {
            assertTrue("$door must stop in the demo", baseControl.substringAfter(door).take(500).contains("DemoMode.active"))
        }
        for (k in kinds) assertTrue("${k}AlarmReceiver must stop in the demo", control(k).substringAfter("class ${k}AlarmReceiver").contains("DemoMode.active"))
        assertTrue("if (DemoMode.active) null else" in baseService)
        assertTrue(baseService.substringAfter("if (first == null || DemoMode.active)").take(120).contains("finishService"))
        assertTrue(baseNotifier.substringAfter("fun post(").take(300).contains("DemoMode.active) return"))
    }

    @Test fun logLinesCarryCountsSwitchesAndClassNamesOnly() {
        val logged = everything().flatMap { f -> f.lines().filter { "DevLog." in it || "Log." in it || "println(" in it } }
        assertTrue(logged.size >= 5)
        val forbidden = Regex("\\.key|\\bkey\\b|\\.flight|\\.train|snapshot|notice|pnr|number|title|\\.code|route|origin|destination|station|status\\b|\\.text")
        val offenders = logged.filter { forbidden.containsMatchIn(it.substringAfter("{", "")) }
        assertTrue("log lines must not carry flight or train details:\n" + offenders.joinToString("\n"), offenders.isEmpty())
        // The tag is the only thing a kind adds to a line.
        assertTrue(logged.filter { "DevLog." in it }.all { "DevLog.d(control.logTag)" in it || "DevLog.d(logTag)" in it })
    }

    @Test fun theSharedTripPiecesOfTheScreensGetNoMotion_andAreDefinedOnce() {
        val presentation = File("src/main/java/com/zyke/message/presentation")
        val parts = File(presentation, "common/TripParts.kt").readText()
        assertFalse(Regex("animate\\w*As|AnimatedVisibility|AnimatedContent|infiniteRepeatable|rememberInfiniteTransition|Crossfade").containsMatchIn(parts))
        for (f in listOf("flights/FlightUi.kt", "trains/TrainUi.kt", "flights/ManualFlightUi.kt", "trains/ManualTrainUi.kt")) {
            val src = File(presentation, f).readText()
            assertFalse("$f must use the shared FactTile", "fun FactTile(" in src)
            assertFalse("$f must use the shared look-up panel", "Caption(\"Couldn’t confirm" in src)
        }
        assertEquals(1, presentation.walkTopDown().filter { it.isFile && it.extension == "kt" }.count { "fun FactTile(" in it.readText() })
    }
}
