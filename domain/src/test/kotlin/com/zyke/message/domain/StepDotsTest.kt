package com.zyke.message.domain

import com.zyke.message.domain.onboarding.DotState
import com.zyke.message.domain.onboarding.PermissionStep
import com.zyke.message.domain.onboarding.StepDots
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StepDotsTest {
    @Test
    fun theDotsFollowTheStep() {
        assertEquals(listOf(DotState.CURRENT, DotState.UPCOMING, DotState.UPCOMING), StepDots(3, 0).states)
        assertEquals(listOf(DotState.DONE, DotState.CURRENT, DotState.UPCOMING), StepDots(3, 1).states)
        assertEquals(listOf(DotState.DONE, DotState.DONE, DotState.CURRENT), StepDots(3, 2).states)
        assertEquals("Step 2 of 3", StepDots(3, 1).spoken)
    }

    @Test
    fun anOutOfRangeStepIsClamped() {
        assertEquals(DotState.CURRENT, StepDots(3, 9).states.last())
        assertEquals(DotState.CURRENT, StepDots(3, -4).states.first())
        assertTrue(StepDots(0, 0).states.isEmpty())
    }

    @Test
    fun oneStepNeedsNoDots() {
        assertFalse(StepDots(1, 0).visible)
        assertTrue(StepDots(2, 0).visible)
    }

    @Test
    fun onlyReadingMessagesIsRequiredAndEachPageAsksForOneThing() {
        assertEquals(listOf(PermissionStep.SMS_READ), PermissionStep.entries.filter { it.required })
        PermissionStep.entries.forEach { step ->
            assertTrue("${step.name} promise is one short line", step.promise.length <= 100)
            assertTrue(step.allow.isNotBlank())
            assertEquals("an optional ask can be declined", !step.required, step.skip != null)
        }
        assertEquals(
            listOf(PermissionStep.SMS_READ, PermissionStep.SMS_SEND),
            PermissionStep.pending(setOf(PermissionStep.SMS_SEND, PermissionStep.SMS_READ))
        )
    }
}
