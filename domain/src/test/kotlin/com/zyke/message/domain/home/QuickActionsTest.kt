package com.zyke.message.domain.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickActionsTest {
    @Test fun scrollingDownHidesAndUpShows() {
        assertFalse(QuickActions.shown(true, -40f, atTop = false))
        assertTrue(QuickActions.shown(false, 40f, atTop = false))
    }

    @Test fun aTinyMoveChangesNothing() {
        assertTrue(QuickActions.shown(true, -3f, atTop = false))
        assertFalse(QuickActions.shown(false, 3f, atTop = false))
    }

    @Test fun atTheTopItIsAlwaysThere() {
        assertTrue(QuickActions.shown(false, -100f, atTop = true))
        assertTrue(QuickActions.extended(true))
        assertFalse(QuickActions.extended(false))
    }

    @Test fun theListKeepsRoomForTheWholeStack() {
        assertEquals(88, QuickActions.reservedBottomDp(askShown = false))
        assertEquals(148, QuickActions.reservedBottomDp(askShown = true))
    }
}
