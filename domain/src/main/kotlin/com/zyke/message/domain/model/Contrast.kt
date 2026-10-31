package com.zyke.message.domain.model

import kotlin.math.pow

/** WCAG 2 contrast between two 0xAARRGGBB colours (alpha ignored), from 1.0 (identical) to 21.0 (black on white). */
object Contrast {
    const val AA_TEXT = 4.5

    fun ratio(a: Long, b: Long): Double {
        val x = luminance(a)
        val y = luminance(b)
        return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05)
    }

    fun luminance(argb: Long): Double {
        fun channel(shift: Int): Double {
            val c = ((argb shr shift) and 0xFF) / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }
}
