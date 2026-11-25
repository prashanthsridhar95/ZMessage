package com.zyke.message

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Remember where I spend" asks the phone where it is, so the doors to that are pinned here: who may ask, from where, that a
 * position is rounded, never logged, never backed up unless the person opted in, never taken in the demo, and that no map picture
 * is requested before the person agreed.
 */
class LocationPolicyTest {
    private val main = File("src/main/java/com/zyke/message")
    private fun kotlinFiles() = main.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    private fun text(path: String) = File(main, path).readText()
    private fun code(file: File) = file.readLines().filter { !it.trim().startsWith("//") && !it.trim().startsWith("*") && !it.trim().startsWith("/*") }

    @Test fun onlyTheCaptureClassAsksThePhoneForAPosition() {
        val api = Regex("""LocationManager|getCurrentLocation|getLastKnownLocation|requestLocationUpdates|requestSingleUpdate|android\.location\.Location\b|LocationListener|LocationCallback|FusedLocation|LocationServices|GnssStatus|addProximityAlert|GeofencingClient""")
        val offenders = kotlinFiles().filter { f -> code(f).any { !it.startsWith("import ") && api.containsMatchIn(it) } }.map { it.name }
        assertEquals(listOf("PlaceCapturer.kt"), offenders)
        // One fix per message: no repeating updates, no long-lived listener, no service of its own.
        val capturer = text("data/places/PlaceCapturer.kt")
        assertFalse(Regex("""requestLocationUpdates|requestSingleUpdate|addProximityAlert|startForeground|\bService\b""").containsMatchIn(code(File(main, "data/places/PlaceCapturer.kt")).joinToString("\n")))
        assertTrue("the fix is a getCurrentLocation call (API 30) with a time limit", capturer.contains("getCurrentLocation(provider, signal") && capturer.contains("withTimeoutOrNull(FIX_TIMEOUT_MS)"))
        assertTrue("and falls back to a last known position under two minutes old", capturer.contains("FRESH_MS = 120_000L") && capturer.contains("getLastKnownLocation"))
        assertTrue("network first, then GPS", capturer.indexOf("NETWORK_PROVIDER") < capturer.indexOf("GPS_PROVIDER"))
        assertTrue("API 30 call is guarded", capturer.contains("Build.VERSION.SDK_INT >= Build.VERSION_CODES.R") && capturer.contains("RequiresApi(Build.VERSION_CODES.R)"))
    }

    @Test fun noPlayServicesLocationAndNoNewLibrary() {
        for (path in listOf("../gradle/libs.versions.toml", "build.gradle.kts")) {
            val f = File("src/main").parentFile.parentFile.let { File(it, path) }.takeIf { it.isFile } ?: File(path)
            if (f.isFile) assertFalse("$path must not depend on a location library", Regex("""play-services-location|play-services-maps|osmdroid|maplibre|mapbox|google.maps|maps-compose""", RegexOption.IGNORE_CASE).containsMatchIn(f.readText()))
        }
        assertFalse(kotlinFiles().any { f -> code(f).any { Regex("""com\.google\.android\.gms\.(location|maps)""").containsMatchIn(it) } })
    }

    @Test fun theCaptureIsCalledOnlyFromTheLiveReceiver_afterClassification_whenThePersonOptedIn() {
        val callers = kotlinFiles().filter { f -> code(f).any { it.contains("PlaceCapturer.onPurchaseText(") } }.map { it.name }
        assertEquals(listOf("SmsReceiver.kt"), callers)
        val receiver = text("notifications/SmsReceiver.kt")
        assertTrue(receiver.indexOf("messageClassifier.classify") in 0 until receiver.indexOf("PlaceCapturer.onPurchaseText("))
        assertTrue("the rule is the tested domain one", receiver.contains("PlaceCapture.decide(classified"))
        assertTrue("only with the switch on", receiver.contains("container.spendPlaces.state.value.settings.enabled"))
        assertTrue("never with reading paused", receiver.contains("settings.readingPaused) return"))
        assertTrue("never in the demo", receiver.contains("DemoMode.active) return"))
        val capturer = text("data/places/PlaceCapturer.kt")
        assertTrue(capturer.substringAfter("suspend fun onPurchaseText").substringBefore("private suspend fun capture").contains("DemoMode.active) return"))
        assertTrue(capturer.contains("!settings.enabled || container.settings.settings.value.readingPaused"))
        // The receiver is still off in the manifest until a switch turns it on, and the switch of places is one of them.
        assertTrue(File("src/main/AndroidManifest.xml").readText().substringAfter("notifications.SmsReceiver").substringBefore("</receiver>").contains("android:enabled=\"false\""))
        assertTrue(text("notifications/LiveAlerts.kt").contains("container.spendPlaces.state.value.settings.enabled"))
    }

    @Test fun aStoredPositionIsRoundedAndNeverLogged() {
        assertTrue(text("data/places/PlaceCapturer.kt").contains("SpendPlace.make(PlaceKey.of(message.message), fix.latitude, fix.longitude"))
        val domain = File("../domain/src/main/kotlin/com/zyke/message/domain/places/SpendPlace.kt").readText()
        assertTrue(domain.contains("Math.round(degrees * 10_000.0) / 10_000.0"))
        // Only SpendPlace.make builds a place from a fix: nobody constructs one from raw coordinates outside the repository's loader and the demo.
        val builders = kotlinFiles().filter { f -> code(f).any { Regex("""(?<![\w.])SpendPlace\(""").containsMatchIn(it) } }.map { it.name }.sorted()
        assertEquals(listOf("SpendPlaceRepositoryImpl.kt"), builders)
        val risky = Regex("""\blat\b|\blon\b|latitude|longitude|coordinates|\.lat\b|\.lon\b|amount|payee|\.body\b|\.address\b""", RegexOption.IGNORE_CASE)
        val logging = Regex("""DevLog\.|Log\.[dievw]\(|println|BlackBox|Timber""")
        val files = listOf("data/places/PlaceCapturer.kt", "data/places/PlaceNamer.kt", "data/places/PlaceRetention.kt", "data/maps/TileFetcher.kt", "data/maps/TileSource.kt", "data/maps/TileCache.kt",
            "data/repository/SpendPlaceRepositoryImpl.kt", "presentation/places/PlaceWhere.kt", "presentation/places/SpendMapPage.kt", "presentation/places/SlippyMap.kt", "presentation/places/PlacesSettings.kt")
        for (name in files) for (line in code(File(main, name))) {
            if (logging.containsMatchIn(line)) assertFalse("$name logs something that may hold a position, amount or name: ${line.trim()}", risky.containsMatchIn(line.substringAfter("DevLog.d(")))
        }
        // Exceptions are logged by class only.
        for (name in files) assertFalse(name, code(File(main, name)).filter { !it.startsWith("import ") }.any { Regex("""\b(e|it|error|t)\.message\b|localizedMessage|stackTraceToString""").containsMatchIn(it) })
    }

    @Test fun placesAreLeftOutOfBackupsUnlessTheyOptedIn() {
        val backup = text("data/backup/PrefsBackup.kt")
        assertTrue(Regex("""EXCLUDED = setOf\([^)]*"spend_places_settings"""").containsMatchIn(backup))
        assertFalse("the places file is not in the always-excluded list: it is opt-in", Regex("""EXCLUDED = setOf\([^)]*"spend_places"[,)]""").containsMatchIn(backup))
        assertTrue(backup.contains("""OPT_IN = setOf("spend_places")"""))
        assertTrue(backup.contains("""SETTINGS_FILE = "spend_places_settings"""") && backup.contains("""BACKUP_FLAG = "backup""""))
        assertTrue("a snapshot is made from opted-in files only", backup.contains("includesForCapture(context, it)") && backup.contains("placesOptedIn(context)"))
        val repo = text("data/repository/SpendPlaceRepositoryImpl.kt")
        assertTrue(repo.contains("""PLACES_FILE = "spend_places"""") && repo.contains("""SETTINGS_FILE = "spend_places_settings"""") && repo.contains(""".putBoolean("backup", c.includeInBackup)"""))
        val defaults = File("../domain/src/main/kotlin/com/zyke/message/domain/places/SpendPlace.kt").readText()
        assertTrue(defaults.contains("val enabled: Boolean = false") && defaults.contains("val includeInBackup: Boolean = false") && defaults.contains("val mapPictures: Boolean = false") && defaults.contains("DEFAULT_KEEP_MONTHS = 24"))
        assertTrue(File("src/main/res/xml/backup_rules.xml").readText().contains("""<exclude domain="sharedpref" path="." />"""))
    }

    @Test fun theDemoNeverTouchesLocationOrTheMapServer() {
        val container = text("di/AppContainer.kt")
        assertTrue(container.contains("if (demo) com.zyke.message.domain.places.SpendPlaceStore() else com.zyke.message.data.repository.SpendPlaceRepositoryImpl(context)"))
        val page = text("presentation/places/SpendMapPage.kt")
        assertTrue(page.contains("val showPictures = settings.mapPictures && !demo"))
        assertTrue(page.contains("Map pictures are off in demo"))
        val thumb = text("presentation/places/PlaceWhere.kt")
        assertTrue(thumb.contains("if (pictures && !demo) TileSource(") && thumb.contains("Map pictures are off in demo"))
        val seeder = text("di/DemoSeeder.kt")
        assertTrue(seeder.contains("DemoPlaces.place(") && !seeder.contains("PlaceCapturer"))
        assertTrue(text("data/places/PlaceRetention.kt").contains("DemoMode.active) return"))
    }

    @Test fun mapPicturesAreAskedForOnlyWhenTheMapIsShown_andOnlyAfterTheyAgreed() {
        val constructors = kotlinFiles().filter { f -> code(f).any { Regex("""(?<![\w.])TileSource\(""").containsMatchIn(it) && !it.contains("class TileSource") } }.map { it.name }.sorted()
        assertEquals(listOf("PlaceWhere.kt", "SpendMapPage.kt"), constructors)
        val fetchers = kotlinFiles().filter { f -> code(f).any { it.contains("TileFetcher") } }.map { it.name }.sorted()
        assertEquals(listOf("TileFetcher.kt", "TileSource.kt"), fetchers)
        val page = text("presentation/places/SpendMapPage.kt")
        assertTrue(page.contains("Map pictures come from OpenStreetMap; only the tile coordinates being viewed are requested."))
        assertTrue(page.contains("DisposableEffect(tiles) { onDispose { tiles?.close() } }"))
        // Tiles are only requested for what is on screen, two at a time, and the attribution is always shown with them.
        val source = text("data/maps/TileSource.kt")
        assertTrue(source.contains("MAX_CONCURRENT = 2") && source.contains("tiles.take(MAX_VISIBLE)") || source.contains("for (t in tiles.take(MAX_VISIBLE))"))
        assertTrue(page.contains("© OpenStreetMap contributors") && text("presentation/places/PlaceWhere.kt").contains("© OpenStreetMap contributors"))
        assertTrue(text("data/maps/TileCache.kt").contains("TTL_MS = 30L * 24 * 3_600_000L") && text("data/maps/TileCache.kt").contains("MAX_BYTES = 40L * 1024 * 1024"))
        assertTrue(text("data/maps/TileCache.kt").contains("File(cacheDir, \"map_tiles\")"))
    }

    @Test fun theLocationPermissionsAreAskedForOnlyOnTheSettingsPage_andDeletingAsksFirst() {
        val askers = kotlinFiles().filter { f -> code(f).any { it.contains("permission.ACCESS_") && !it.startsWith("import ") } }.map { it.name }.sorted()
        assertEquals(listOf("PlaceCapturer.kt", "PlacesSettings.kt"), askers)
        val settings = text("presentation/places/PlacesSettings.kt")
        assertTrue(settings.contains("Manifest.permission.ACCESS_BACKGROUND_LOCATION") && settings.contains("Build.VERSION_CODES.Q"))
        assertTrue("Android 11 and later: the system settings page", settings.contains("else openAppSettings()"))
        assertTrue(settings.substringAfter("if (deleting)").contains("ConfirmDialog(") && settings.contains("repo.clearAll()"))
        assertTrue(settings.contains("Delete all places") && settings.contains("PlaceHealth.problem(facts)"))
        assertTrue("the switch explains itself once before asking", settings.contains("else if (!s.explained) explaining = true"))
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertFalse("no location foreground service", Regex("""foregroundServiceType="[^"]*location""").containsMatchIn(manifest))
    }

    @Test fun theGeocoderIsGuarded_andNamesCanBeSwitchedOff() {
        val namer = text("data/places/PlaceNamer.kt")
        assertTrue(namer.contains("Geocoder.isPresent()") && namer.contains("Build.VERSION_CODES.TIRAMISU") && namer.contains("withTimeoutOrNull(TIMEOUT_MS)") && namer.contains("@Suppress(\"DEPRECATION\")"))
        assertTrue(text("presentation/places/PlaceWhere.kt").contains("geocode -> PlaceNamer.name("))
        assertTrue(text("presentation/places/PlacesSettings.kt").contains("often asks a remote service") || text("presentation/places/PlacesSettings.kt").contains("asks a remote service"))
    }
}
