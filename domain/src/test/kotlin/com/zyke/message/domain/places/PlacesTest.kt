package com.zyke.message.domain.places

import com.zyke.message.domain.classification.MessageClassifier
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.model.SmsMessage
import java.math.BigDecimal
import java.time.ZoneOffset
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceKeyTest {
    private val t = 1_700_000_000_000L
    @Test fun sameTextSameMinute_sameKey_regardlessOfSpacingAndSeconds() {
        val a = PlaceKey.of("VM-HDFCBK", "Rs.640 spent at  ZARA", t)
        assertEquals(a, PlaceKey.of("vm-hdfcbk ", "Rs.640 spent at ZARA", t + 20_000 - (t % 60_000)))
        assertEquals(16, a.length)
    }
    @Test fun differentMinuteOrBodyOrSender_differentKey() {
        val a = PlaceKey.of("S", "b", t)
        assertNotEquals(a, PlaceKey.of("S", "b", t + 120_000))
        assertNotEquals(a, PlaceKey.of("S", "c", t))
        assertNotEquals(a, PlaceKey.of("T", "b", t))
    }
    @Test fun nearbyCoversTheMinuteEitherSide() {
        val live = SmsMessage(0, "S", "b", t, false)
        val later = SmsMessage(77, "S", "b", t + 61_000, false)
        assertTrue(PlaceKey.of(live) in PlaceKey.nearby(later))
        assertEquals(PlaceKey.of(live), PlaceKey.nearby(live).first())
        assertFalse(PlaceKey.of(live) in PlaceKey.nearby(SmsMessage(1, "S", "b", t + 300_000, false)))
    }
    @Test fun theKeyDoesNotContainTheText() {
        assertFalse(PlaceKey.of("HDFC", "Rs.640 spent", t).contains("640"))
    }
}

class SpendPlaceTest {
    @Test fun coordinatesAreRoundedToFourDecimals() {
        val p = SpendPlace.make("k", 12.97163999, 77.59460001, 30f, 1L)!!
        assertEquals(12.9716, p.lat, 0.0); assertEquals(77.5946, p.lon, 0.0)
    }
    @Test fun accuracyClass() {
        assertEquals(PlaceAccuracy.PRECISE, SpendPlace.make("k", 1.0, 1.0, 150f, 1)!!.accuracy)
        assertEquals(PlaceAccuracy.ROUGH, SpendPlace.make("k", 1.0, 1.0, 151f, 1)!!.accuracy)
        assertEquals(PlaceAccuracy.ROUGH, SpendPlace.make("k", 1.0, 1.0, null, 1)!!.accuracy)
    }
    @Test fun nonsenseCoordinatesAreRefused() {
        assertNull(SpendPlace.make("k", 91.0, 0.0, 1f, 1)); assertNull(SpendPlace.make("k", 0.0, Double.NaN, 1f, 1))
    }
    @Test fun spotKeyIsSharedByNeighbours() {
        assertEquals(SpendPlace.spotKey(12.97161, 77.59461), SpendPlace.spotKey(12.97164, 77.59459))
    }
}

class OnlineMerchantsTest {
    @Test fun onlineShopsAndAppsAreOnline() {
        for (p in listOf("AMAZON PAY INDIA", "Flipkart Internet Pvt Ltd", "SWIGGY", "Zomato Ltd", "NETFLIX.COM", "Google Play", "netflix@icici", "swiggy.rzp@axisbank", "amazonpay@apl", "IRCTC"))
            assertTrue(p, OnlineMerchants.isOnline(p))
    }
    @Test fun shopsAndPeopleAreNot() {
        for (p in listOf("Zara", "Mr Diy", "Sunita Rao", "40-5@okhdfcbank", "Paytm QR shop@paytm", "Apollo Pharmacy", "Bata India"))
            assertFalse(p, OnlineMerchants.isOnline(p))
    }
    @Test fun aMandateOrSubscriptionTextIsOnlineWhateverThePayee() {
        assertTrue(OnlineMerchants.isOnline("Some Gym", "UPI Mandate debit for subscription"))
    }
    @Test fun wholeWordsOnly() {
        assertFalse(OnlineMerchants.isOnline("Jiomartin Bakery".replace("mart", "mx")))
        assertFalse(OnlineMerchants.isOnline("Zomatoes Fresh Fruits"))
    }
}

class PlaceCaptureTest {
    private val classifier = MessageClassifier()
    private fun msg(body: String) = classifier.classify(SmsMessage(0, "VM-HDFCBK", body, 1_700_000_000_000L, false))
    private val on = SpendPlaceSettings(enabled = true)

    @Test fun aCardPurchaseAtAShopCaptures() {
        val m = msg("Rs.640.00 spent on HDFC Bank Card x5580 at ZARA INDIA on 24-11-26:16:12:10. Avl bal Rs.1200")
        assertEquals(CaptureDecision.Capture, PlaceCapture.decide(m, on))
    }
    @Test fun moneyInNeverCaptures() {
        val m = msg("Rs.5000.00 credited to HDFC Bank A/c XX1234 on 24-11-26 by UPI Ref 123 from RAHUL")
        assertTrue(PlaceCapture.decide(m, on) is CaptureDecision.Skip)
    }
    @Test fun anOtpNeverCaptures() {
        assertTrue(PlaceCapture.decide(msg("123456 is your OTP for txn of Rs.640 at ZARA. Do not share."), on) is CaptureDecision.Skip)
    }
    @Test fun anOnlineShopSkipsUnlessTheyOverride() {
        val m = msg("Rs.640.00 spent on HDFC Bank Card x5580 at AMAZON PAY INDIA on 24-11-26:16:12:10. Avl bal Rs.1200")
        val skip = PlaceCapture.decide(m, on)
        assertTrue(skip is CaptureDecision.Skip)
        val key = com.zyke.message.domain.categories.PayeeKey.of((m.content as com.zyke.message.domain.model.MessageContent.Transaction).payee)!!
        assertEquals(CaptureDecision.Capture, PlaceCapture.decide(m, on.copy(captureOverrides = setOf(key))))
    }
    @Test fun aPayeeTheyNeverWantIsSkipped() {
        val m = msg("Rs.640.00 spent on HDFC Bank Card x5580 at ZARA INDIA on 24-11-26:16:12:10. Avl bal Rs.1200")
        val key = com.zyke.message.domain.categories.PayeeKey.of((m.content as com.zyke.message.domain.model.MessageContent.Transaction).payee)!!
        assertTrue(PlaceCapture.decide(m, on.copy(skipOverrides = setOf(key))) is CaptureDecision.Skip)
    }
    @Test fun aCardBillPaymentAndFastagAreSkipped() {
        val bill = msg("Payment of Rs.12000 received towards your HDFC Bank Credit Card ending 5580")
        val fastag = msg("Rs.500 debited from A/c XX1234 FASTag recharge via NETC on 24-11-26")
        assertTrue("$bill", PlaceCapture.decide(bill, on) is CaptureDecision.Skip)
        assertTrue("$fastag", PlaceCapture.decide(fastag, on) is CaptureDecision.Skip)
    }
}

class TileMathTest {
    private val eps = 1e-6
    @Test fun worldCornersAndCentre() {
        assertEquals(0.0, TileMath.lonToX(-180.0, 0.0), eps)
        assertEquals(256.0, TileMath.lonToX(180.0, 0.0), eps)
        assertEquals(128.0, TileMath.latToY(0.0, 0.0), eps)
        assertEquals(0.0, TileMath.latToY(TileMath.MAX_LAT, 0.0), 1e-3)
    }
    @Test fun roundTripPixelsAndCoordinates() {
        for (z in listOf(3.0, 10.5, 19.0)) {
            val lat = 12.9716; val lon = 77.5946
            assertEquals(lat, TileMath.yToLat(TileMath.latToY(lat, z), z), 1e-7)
            assertEquals(lon, TileMath.xToLon(TileMath.lonToX(lon, z), z), 1e-7)
        }
    }
    @Test fun knownTile() {
        // The Greenwich observatory at zoom 10 is the well-known tile 511/340.
        assertEquals(TileId(10, 511, 340), TileMath.tileOf(51.4779, -0.0015, 10))
        assertEquals(TileId(1, 1, 0), TileMath.tileOf(60.0, 90.0, 1))
    }
    @Test fun screenRoundTripAndCentre() {
        val v = MapView(12.97, 77.59, 14.3)
        val (x, y) = TileMath.toScreen(v.centerLat, v.centerLon, v, 360.0, 640.0)
        assertEquals(180.0, x, 1e-6); assertEquals(320.0, y, 1e-6)
        val (lat, lon) = TileMath.fromScreen(50.0, 100.0, v, 360.0, 640.0)
        val back = TileMath.toScreen(lat, lon, v, 360.0, 640.0)
        assertEquals(50.0, back.first, 1e-6); assertEquals(100.0, back.second, 1e-6)
    }
    @Test fun panFollowsTheFinger() {
        val v = MapView(12.97, 77.59, 15.0)
        val p = TileMath.pan(v, 100.0, 0.0)
        assertTrue(p.centerLon < v.centerLon)
        val (x, _) = TileMath.toScreen(v.centerLat, v.centerLon, p, 360.0, 640.0)
        assertEquals(280.0, x, 1e-6)
    }
    @Test fun zoomAboutKeepsThePointUnderTheFinger() {
        val v = MapView(12.97, 77.59, 12.0)
        val before = TileMath.fromScreen(300.0, 200.0, v, 360.0, 640.0)
        val z = TileMath.zoomAbout(v, 2.0, 300.0, 200.0, 360.0, 640.0)
        assertEquals(13.0, z.zoom, 1e-9)
        val after = TileMath.fromScreen(300.0, 200.0, z, 360.0, 640.0)
        assertEquals(before.first, after.first, 1e-7); assertEquals(before.second, after.second, 1e-7)
    }
    @Test fun zoomIsClampedToTheMapRange() {
        assertEquals(19.0, TileMath.zoomAbout(MapView(0.0, 0.0, 19.0), 2.0, 0.0, 0.0, 100.0, 100.0).zoom, 0.0)
        assertEquals(3.0, TileMath.zoomAbout(MapView(0.0, 0.0, 3.0), 0.5, 0.0, 0.0, 100.0, 100.0).zoom, 0.0)
    }
    @Test fun fitShowsEveryPointWithPadding() {
        val pts = listOf(12.90 to 77.50, 13.05 to 77.70)
        val v = TileMath.fit(pts, 360.0, 640.0, 48.0)!!
        for ((lat, lon) in pts) {
            val (x, y) = TileMath.toScreen(lat, lon, v, 360.0, 640.0)
            assertTrue("$x", x >= 47.9 && x <= 360 - 47.9); assertTrue("$y", y >= 47.9 && y <= 640 - 47.9)
        }
        // One step further in would not fit.
        val tighter = v.copy(zoom = v.zoom + 0.25)
        val a = TileMath.toScreen(pts[0].first, pts[0].second, tighter, 360.0, 640.0)
        val b = TileMath.toScreen(pts[1].first, pts[1].second, tighter, 360.0, 640.0)
        assertTrue(abs(a.first - b.first) > 264 || abs(a.second - b.second) > 544)
    }
    @Test fun fitOnOnePointStopsAtTheMaxZoom() {
        assertEquals(17.0, TileMath.fit(listOf(12.9 to 77.5), 360.0, 640.0)!!.zoom, 0.0)
        assertNull(TileMath.fit(emptyList(), 360.0, 640.0))
    }
    @Test fun visibleTilesCoverTheScreenNearestFirst() {
        val v = MapView(12.9716, 77.5946, 12.0)
        val tiles = TileMath.visibleTiles(v, 360.0, 640.0, 12)
        assertEquals(TileMath.tileOf(12.9716, 77.5946, 12), tiles.first())
        assertTrue(tiles.size in 4..12)
        assertTrue(tiles.all { it.z == 12 && it.x in 0 until 4096 && it.y in 0 until 4096 })
        assertEquals(tiles.size, tiles.toSet().size)
    }
    @Test fun tilesWrapAroundTheDateLine() {
        val tiles = TileMath.visibleTiles(MapView(0.0, 179.99, 4.0), 800.0, 400.0, 4)
        assertTrue(tiles.any { it.x == 0 } && tiles.any { it.x == 15 })
    }
    @Test fun tileZoomIsTheFloor() {
        assertEquals(14, TileMath.tileZoom(14.9)); assertEquals(3, TileMath.tileZoom(1.0)); assertEquals(19, TileMath.tileZoom(25.0))
    }
    @Test fun scaleAtTheEquator() {
        assertEquals(156543.03392, TileMath.metersPerPixel(0.0, 0.0), 1e-3)
    }
}

private fun pt(key: String, lat: Double, lon: Double, amount: Int, date: Long = 0L) =
    SpendPoint(key, key.hashCode().toLong(), lat, lon, BigDecimal(amount), date, "M$key", MessageCategory.SHOPPING, false)

class PlaceClustersTest {
    @Test fun nearbyPlacesMergeAndFarOnesDoNot() {
        val pts = listOf(pt("a", 12.9716, 77.5946, 100), pt("b", 12.9717, 77.5947, 200), pt("c", 13.2, 77.9, 50))
        val z = 12.0
        val low = PlaceClusters.build(pts, z)
        assertEquals(2, low.size)
        val near = low.first { it.count == 2 }
        assertEquals(BigDecimal(300), near.total)
        // Weighted towards the bigger spend.
        assertTrue(near.lat > 12.97165)
        assertEquals(3, PlaceClusters.build(pts, 19.0).size)
    }
    @Test fun zoomingInSplitsThem() {
        val pts = listOf(pt("a", 12.9716, 77.5946, 100), pt("b", 12.9726, 77.5956, 200))
        assertEquals(1, PlaceClusters.build(pts, 12.0).size)
        assertEquals(2, PlaceClusters.build(pts, 18.0).size)
        val cl = PlaceClusters.build(pts, 12.0).single()
        val z = PlaceClusters.zoomToSplit(cl, 12.0)
        assertTrue(z > 12.0 && PlaceClusters.build(pts, z).size == 2)
    }
    @Test fun everyPaymentIsInExactlyOneCluster() {
        val pts = (0 until 500).map { pt("p$it", 12.9 + (it % 25) * 0.003, 77.5 + (it / 25) * 0.003, it + 1) }
        for (z in listOf(5.0, 12.0, 16.0)) {
            val cl = PlaceClusters.build(pts, z)
            assertEquals(500, cl.sumOf { it.count })
            assertEquals(500, cl.flatMap { it.members }.map { it.key }.toSet().size)
        }
    }
    @Test fun deterministicAndEmptySafe() {
        val pts = listOf(pt("a", 1.0, 1.0, 5), pt("b", 1.0, 1.0, 5))
        assertEquals(PlaceClusters.build(pts, 10.0), PlaceClusters.build(pts.reversed(), 10.0))
        assertTrue(PlaceClusters.build(emptyList(), 10.0).isEmpty())
    }
    @Test fun theSamePlaceMergesAtMaxZoom() {
        assertEquals(1, PlaceClusters.build(listOf(pt("a", 12.9716, 77.5946, 1), pt("b", 12.9716, 77.5946, 1)), 19.0).size)
    }
}

class SpendHeatTest {
    private val view = MapView(12.9716, 77.5946, 14.0)
    @Test fun noPointsNoCells() = assertTrue(SpendHeat.cells(emptyList(), view, 360.0, 640.0).isEmpty())
    @Test fun theBiggestCellIsOneAndOthersAreScaledByRoot() {
        val pts = listOf(pt("a", 12.9716, 77.5946, 10_000), pt("b", 12.9800, 77.6000, 2_500))
        val cells = SpendHeat.cells(pts, view, 360.0, 640.0)
        assertEquals(2, cells.size)
        assertEquals(1f, cells.maxOf { it.intensity }, 1e-6f)
        assertEquals(0.5f, cells.minOf { it.intensity }, 1e-4f)
    }
    @Test fun nearbyPaymentsShareACell_andTheBlobSitsAtTheirWeightedMiddle() {
        val pts = listOf(pt("a", 12.97160, 77.59460, 300), pt("b", 12.97161, 77.59461, 100))
        val cells = SpendHeat.cells(pts, view, 360.0, 640.0)
        assertEquals(1, cells.size); assertEquals(400.0, cells[0].amount, 1e-9)
    }
    @Test fun pointsOffScreenAreLeftOut() {
        assertTrue(SpendHeat.cells(listOf(pt("a", 20.0, 80.0, 100)), view, 360.0, 640.0).isEmpty())
    }
    @Test fun liteMakesCoarserGridAndFewerOrEqualBlobs() {
        val pts = (0 until 60).map { pt("p$it", 12.9716 + it * 0.0004, 77.5946 + it * 0.0004, 100) }
        assertTrue(SpendHeat.cells(pts, view, 360.0, 640.0, lite = true).size <= SpendHeat.cells(pts, view, 360.0, 640.0).size)
    }
    @Test fun aCellDoesNotChangeWhenTheViewPans() {
        val pts = listOf(pt("a", 12.9716, 77.5946, 100))
        val a = SpendHeat.cells(pts, view, 360.0, 640.0).single()
        val moved = TileMath.pan(view, 40.0, 0.0)
        val b = SpendHeat.cells(pts, moved, 360.0, 640.0).single()
        assertEquals(a.intensity, b.intensity, 0f); assertEquals(a.x + 40.0, b.x, 1e-6)
    }
}

class PriceTagTest {
    @Test fun fullAndCompactLabels() {
        assertEquals("₹1,240", PriceTag.label(BigDecimal("1240"), false))
        assertEquals("₹1.2k", PriceTag.label(BigDecimal("1240"), true))
        assertEquals("₹420", PriceTag.label(BigDecimal("419.6"), true))
        assertEquals("₹12.3k", PriceTag.label(BigDecimal("12399"), true))
        assertEquals("₹1.5L", PriceTag.label(BigDecimal("150000"), true))
        assertEquals("₹2Cr", PriceTag.label(BigDecimal("20000000"), true))
        assertEquals("₹12,34,567", PriceTag.label(BigDecimal("1234567"), false))
        assertEquals("₹ ••••", PriceTag.label(BigDecimal("1240"), true, hidden = true))
    }
    @Test fun compactBelowStreetZoom() { assertTrue(PriceTag.compactAt(12.0)); assertFalse(PriceTag.compactAt(16.0)) }
}

class SpendPointsTest {
    private val classifier = MessageClassifier()
    private val zone = ZoneOffset.UTC
    private fun at(day: Int) = java.time.LocalDate.of(2026, 11, day).atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
    private fun classified(id: Long, body: String, date: Long) = classifier.classify(SmsMessage(id, "VM-HDFCBK", body, date, true))
    private val spend = "Rs.640.00 spent on HDFC Bank Card x5580 at ZARA INDIA on 24-11-26:16:12:10. Avl bal Rs.1200"

    @Test fun aStoredPlaceIsFoundForTheReReadMessage_evenAMinuteApart() {
        val live = SmsMessage(0, "VM-HDFCBK", spend, at(24), false)
        val place = SpendPlace.make(PlaceKey.of(live), 12.9716, 77.5946, 20f, at(24))!!
        val reRead = classified(991, spend, at(24) + 61_000)
        val points = SpendPoints.build(listOf(reRead), mapOf(place.key to place))
        assertEquals(1, points.size); assertEquals(0, BigDecimal("640").compareTo(points[0].amount)); assertEquals(991L, points[0].messageId)
    }
    @Test fun creditsAndLeftOutPaymentsHaveNoPoint() {
        val credit = classified(1, "Rs.5000.00 credited to HDFC Bank A/c XX1234 on 24-11-26 by UPI Ref 123 from RAHUL", at(24))
        val places = listOf(credit).associate { PlaceKey.of(it.message) to SpendPlace(PlaceKey.of(it.message), 1.0, 1.0, PlaceAccuracy.PRECISE, 0) }
        assertTrue(SpendPoints.build(listOf(credit), places).isEmpty())
        val tx = classified(2, spend, at(24)).copy(setAside = com.zyke.message.domain.ledger.SetAside.EXCLUDED)
        assertTrue(SpendPoints.build(listOf(tx), mapOf(PlaceKey.of(tx.message) to SpendPlace(PlaceKey.of(tx.message), 1.0, 1.0, PlaceAccuracy.PRECISE, 0))).isEmpty())
    }
    @Test fun periodsAndCategoriesFilter() {
        val old = pt("o", 1.0, 1.0, 10, at(1) - 200L * 86_400_000L)
        val recent = pt("r", 1.0, 1.0, 20, at(20))
        val now = at(25)
        assertEquals(listOf("r"), SpendPoints.filter(listOf(old, recent), PlacePeriod.THIS_MONTH, null, now, zone).map { it.key })
        assertEquals(2, SpendPoints.filter(listOf(old, recent), PlacePeriod.ALL, null, now, zone).size)
        assertEquals(2, SpendPoints.filter(listOf(old, recent), PlacePeriod.YEAR, MessageCategory.SHOPPING, now, zone).size)
        assertEquals(1, SpendPoints.filter(listOf(old, recent), PlacePeriod.THREE_MONTHS, null, now, zone).size)
        assertTrue(SpendPoints.filter(listOf(recent), PlacePeriod.ALL, MessageCategory.FOOD, now, zone).isEmpty())
    }
    @Test fun inViewSumsOnlyWhatIsOnScreen() {
        val v = MapView(12.9716, 77.5946, 14.0)
        val iv = SpendPoints.inView(listOf(pt("a", 12.9716, 77.5946, 100), pt("b", 20.0, 80.0, 900)), v, 360.0, 640.0)
        assertEquals(BigDecimal(100), iv.total); assertEquals(1, iv.count)
    }
}

class SpendPlaceStoreTest {
    private fun place(k: String, at: Long) = SpendPlace(k, 1.0, 1.0, PlaceAccuracy.PRECISE, at)
    @Test fun putRemoveRestoreAndPersist() {
        var saved: SpendPlaceState? = null
        val s = SpendPlaceStore { saved = it }
        s.put(place("a", 1)); assertEquals(setOf("a"), saved!!.places.keys)
        val gone = s.remove("a")!!; assertTrue(s.state.value.places.isEmpty())
        s.restore(gone); assertEquals(1, s.state.value.places.size)
        assertNull(s.remove("zzz"))
    }
    @Test fun theCapDropsTheOldest() {
        val s = SpendPlaceStore()
        for (i in 1..SpendPlaceStore.MAX_PLACES + 10) s.put(place("k$i", i.toLong()))
        assertEquals(SpendPlaceStore.MAX_PLACES, s.state.value.places.size)
        assertFalse("k1" in s.state.value.places); assertTrue("k${SpendPlaceStore.MAX_PLACES + 10}" in s.state.value.places)
    }
    @Test fun pruneKeepsTheSettingOfMonths() {
        val now = java.time.LocalDate.of(2026, 11, 25).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val old = java.time.LocalDate.of(2024, 10, 1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val s = SpendPlaceStore()
        s.put(place("old", old)); s.put(place("new", now - 1000))
        assertEquals(1, s.prune(now, ZoneOffset.UTC)); assertEquals(setOf("new"), s.state.value.places.keys)
        s.update { it.copy(keepMonths = 0) }; s.put(place("old2", old))
        assertEquals(0, s.prune(now, ZoneOffset.UTC)); assertTrue("old2" in s.state.value.places)
    }
    @Test fun orphansGoOnlyWhenOldEnough() {
        val s = SpendPlaceStore()
        s.put(place("fresh", System.currentTimeMillis())); s.put(place("gone", 1L)); s.put(place("kept", 1L))
        assertEquals(1, s.dropOrphans(setOf("kept")))
        assertEquals(setOf("fresh", "kept"), s.state.value.places.keys)
        assertEquals(0, s.dropOrphans(emptySet()))
    }
    @Test fun nicknamesTrimAndClear() {
        val s = SpendPlaceStore()
        s.setNickname("1.000,1.000", "  Corner shop  "); assertEquals("Corner shop", s.state.value.names["1.000,1.000"])
        s.setNickname("1.000,1.000", " "); assertTrue(s.state.value.names.isEmpty())
    }
    @Test fun clearAllRemovesPlacesAndNamesButKeepsChoices() {
        val s = SpendPlaceStore(); s.update { it.copy(enabled = true) }; s.put(place("a", 1)); s.setNickname("x", "y")
        s.clearAll(); assertTrue(s.state.value.places.isEmpty() && s.state.value.names.isEmpty()); assertTrue(s.state.value.settings.enabled)
    }
    @Test fun defaultsAreOffAndPrivate() {
        val d = SpendPlaceSettings()
        assertFalse(d.enabled); assertFalse(d.includeInBackup); assertEquals(24, d.keepMonths)
    }
}

class PlaceHealthTest {
    private val ok = PlaceFacts(true, false, true, true, true, true, true, true)
    @Test fun walksTheTwoStepFlowInOrder() {
        assertNull(PlaceHealth.problem(ok))
        assertEquals(PlaceProblem.SWITCHED_OFF, PlaceHealth.problem(ok.copy(switchedOn = false)))
        assertEquals(PlaceProblem.NEEDS_LOCATION, PlaceHealth.problem(ok.copy(fineLocation = false, backgroundLocation = false)))
        assertEquals(PlaceProblem.NEEDS_ALL_THE_TIME, PlaceHealth.problem(ok.copy(backgroundLocation = false)))
        assertNull(PlaceHealth.problem(ok.copy(backgroundLocation = false, needsBackgroundStep = false)))
        assertEquals("Allow all the time", PlaceHealth.title(PlaceProblem.NEEDS_ALL_THE_TIME))
        assertEquals("Allow location", PlaceHealth.title(PlaceProblem.NEEDS_LOCATION))
        assertEquals("On", PlaceHealth.title(null)); assertEquals("Off", PlaceHealth.title(PlaceProblem.SWITCHED_OFF))
        assertTrue(PlaceHealth.detail(PlaceProblem.NEEDS_ALL_THE_TIME).contains("texts arrive when the app is closed"))
    }
}

class DemoPlacesTest {
    @Test fun aPayeeStaysInItsNeighbourhood_andNothingIsNearARealCity() {
        val a = DemoPlaces.place("k1", "Zara", 1); val b = DemoPlaces.place("k2", "ZARA", 2)
        assertTrue(abs(a.lat - b.lat) < 0.01 && abs(a.lon - b.lon) < 0.01)
        assertEquals(a, DemoPlaces.place("k1", "Zara", 1))
        assertTrue(abs(a.lat - DemoPlaces.CENTER_LAT) < 0.1 && abs(a.lon - DemoPlaces.CENTER_LON) < 0.1)
    }
}
