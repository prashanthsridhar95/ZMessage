package com.zyke.message.domain

import com.zyke.message.domain.feed.RowSwipe
import com.zyke.message.domain.feed.SwipeAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RowSwipeTest {
    @Test
    fun anUnreadRowIsMarkedRead() {
        val swipe = RowSwipe.of(isUnread = true, readInPhone = false, isArchived = false)
        assertEquals(SwipeAction.MARK_READ, swipe.toRight)
        assertEquals(SwipeAction.ARCHIVE, swipe.toLeft)
    }

    @Test
    fun aRowReadHereCanBeMarkedUnread() {
        assertEquals(SwipeAction.MARK_UNREAD, RowSwipe.of(isUnread = false, readInPhone = false, isArchived = false).toRight)
    }

    @Test
    fun aRowReadInThePhoneHasNoRightSwipe() {
        assertNull(RowSwipe.of(isUnread = false, readInPhone = true, isArchived = false).toRight)
    }

    @Test
    fun aRowWhoseRepeatIsUnreadCanStillBeMarkedRead() {
        // The anchor is read in the phone but a repeat inside the row is not: the row is unread as a whole.
        assertEquals(SwipeAction.MARK_READ, RowSwipe.of(isUnread = true, readInPhone = true, isArchived = false).toRight)
    }

    @Test
    fun anArchivedRowSwipesLeftToRestore() {
        assertEquals(SwipeAction.RESTORE, RowSwipe.of(isUnread = false, readInPhone = true, isArchived = true).toLeft)
    }

    @Test
    fun theCommitThresholdIsSevenTenths() {
        assertFalse(RowSwipe.committed(0.69f))
        assertTrue(RowSwipe.committed(0.70f))
        assertTrue(RowSwipe.committed(1f))
        assertFalse(RowSwipe.committed(Float.NaN))
        assertFalse(RowSwipe.committed(0f))
    }

    @Test
    fun theFractionIsTheShareOfTheWidthInEitherDirection() {
        assertEquals(0.5f, RowSwipe.fraction(-180f, 360f), 0.0001f)
        assertEquals(0.25f, RowSwipe.fraction(90f, 360f), 0.0001f)
        assertEquals(1f, RowSwipe.fraction(900f, 360f), 0.0001f)
        assertEquals(0f, RowSwipe.fraction(50f, 0f), 0.0001f)
        assertEquals(0f, RowSwipe.fraction(Float.NaN, 360f), 0.0001f)
    }

    @Test
    fun everyActionHasWordsForTheSnackbarAndTheScreenReader() {
        SwipeAction.entries.forEach {
            assertTrue(it.label.isNotBlank())
            assertTrue(it.doneMessage.isNotBlank())
        }
    }
}
