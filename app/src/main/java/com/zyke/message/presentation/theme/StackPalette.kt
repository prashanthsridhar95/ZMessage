package com.zyke.message.presentation.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.zyke.message.domain.triage.TriageStackSpec

/**
 * The tonal pairings of Home's deck tiles: one soft container and the ink that reads on it for each stack, in both themes (every pair
 * is at least 8:1). Only tiles use them, as fills and as the text on those fills; a screen never names a literal colour for a stack.
 * Like [SemanticColors] the values are chosen per theme, so a tile never ends up with a colour picked for one theme only.
 */
@androidx.compose.runtime.Immutable
class StackTone(val container: Color, val ink: Color) {
    /** The paper edge peeking out above a tile: the container pulled a little towards the ink, still opaque. */
    val edge: Color get() = lerp(container, ink, EDGE_MIX)

    private companion object {
        const val EDGE_MIX = 0.14f
    }
}

internal object StackPalette {
    private val light = mapOf(
        TriageStackSpec.PAYMENTS to StackTone(Color(0xFFDCE8F8), Color(0xFF123A6B)),
        TriageStackSpec.BILLS to StackTone(Color(0xFFF8E7C8), Color(0xFF5C3B00)),
        TriageStackSpec.LIFE to StackTone(Color(0xFFD5EFE3), Color(0xFF0B4A33)),
        TriageStackSpec.REFUNDS to StackTone(Color(0xFFD3EEEE), Color(0xFF0A4A4A)),
        TriageStackSpec.ALERTS to StackTone(Color(0xFFF7DADA), Color(0xFF6B1A1A)),
        TriageStackSpec.CODES to StackTone(Color(0xFFE6DDF7), Color(0xFF3E2A73)),
        TriageStackSpec.OFFERS to StackTone(Color(0xFFF8E0D2), Color(0xFF6A3015)),
        TriageStackSpec.OTHER to StackTone(Color(0xFFE5E5EA), Color(0xFF2E2E36))
    )
    private val dark = mapOf(
        TriageStackSpec.PAYMENTS to StackTone(Color(0xFF1B2D47), Color(0xFFBFD6F7)),
        TriageStackSpec.BILLS to StackTone(Color(0xFF3B2E12), Color(0xFFF3D79A)),
        TriageStackSpec.LIFE to StackTone(Color(0xFF153A2C), Color(0xFFB5E6D0)),
        TriageStackSpec.REFUNDS to StackTone(Color(0xFF123B3B), Color(0xFFA9E3E3)),
        TriageStackSpec.ALERTS to StackTone(Color(0xFF482121), Color(0xFFF5BDBD)),
        TriageStackSpec.CODES to StackTone(Color(0xFF2D2450), Color(0xFFD2C4F5)),
        TriageStackSpec.OFFERS to StackTone(Color(0xFF482C1B), Color(0xFFF5CDB5)),
        TriageStackSpec.OTHER to StackTone(Color(0xFF2A2A30), Color(0xFFD5D5DD))
    )

    @Composable
    @ReadOnlyComposable
    fun of(stackId: String): StackTone {
        val table = if (isDarkScheme()) dark else light
        return table[stackId] ?: table.getValue(TriageStackSpec.OTHER)
    }
}

/** The tonal pairing of the stack [stackId] (see [StackPalette]). */
@Composable
@ReadOnlyComposable
fun stackTone(stackId: String): StackTone = StackPalette.of(stackId)
