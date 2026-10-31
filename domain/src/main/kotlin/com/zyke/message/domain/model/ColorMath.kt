package com.zyke.message.domain.model

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Hue 0..360, saturation 0..1, value (brightness) 0..1. */
data class Hsv(val h: Double, val s: Double, val v: Double)

/** Hue 0..360, saturation 0..1, lightness 0..1. */
data class Hsl(val h: Double, val s: Double, val l: Double)

/** Pure colour conversions on 0xAARRGGBB longs: hex text, HSV (for the picker) and HSL (for deriving palettes). */
object ColorMath {
    private const val OPAQUE = 0xFF000000L

    /** "#RRGGBB", "RRGGBB" or "#RGB" (any case, surrounding spaces ignored) as an opaque colour; anything else is null. */
    fun parseHex(text: String?): Long? {
        var t = text?.trim() ?: return null
        if (t.startsWith("#")) t = t.substring(1)
        if (t.length == 3) t = t.map { "$it$it" }.joinToString("")
        if (t.length != 6 || !t.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        return OPAQUE or t.toLong(16)
    }

    /** "#RRGGBB" in upper case; alpha is dropped. */
    fun formatHex(argb: Long): String = "#" + (argb and 0xFFFFFF).toString(16).padStart(6, '0').uppercase()

    fun rgb(r: Int, g: Int, b: Int): Long =
        OPAQUE or (r.coerceIn(0, 255).toLong() shl 16) or (g.coerceIn(0, 255).toLong() shl 8) or b.coerceIn(0, 255).toLong()

    private fun r(argb: Long) = ((argb shr 16) and 0xFF).toInt()
    private fun g(argb: Long) = ((argb shr 8) and 0xFF).toInt()
    private fun b(argb: Long) = (argb and 0xFF).toInt()

    private fun fromUnit(r: Double, g: Double, b: Double): Long =
        rgb((r * 255).roundToInt(), (g * 255).roundToInt(), (b * 255).roundToInt())

    private fun hueToRgb(h: Double, c: Double, m: Double): Triple<Double, Double, Double> {
        val hp = ((h % 360.0) + 360.0) % 360.0 / 60.0
        val x = c * (1 - abs(hp % 2 - 1))
        val (r1, g1, b1) = when {
            hp < 1 -> Triple(c, x, 0.0)
            hp < 2 -> Triple(x, c, 0.0)
            hp < 3 -> Triple(0.0, c, x)
            hp < 4 -> Triple(0.0, x, c)
            hp < 5 -> Triple(x, 0.0, c)
            else -> Triple(c, 0.0, x)
        }
        return Triple(r1 + m, g1 + m, b1 + m)
    }

    fun toHsv(argb: Long): Hsv {
        val rf = r(argb) / 255.0; val gf = g(argb) / 255.0; val bf = b(argb) / 255.0
        val mx = max(rf, max(gf, bf)); val mn = min(rf, min(gf, bf)); val d = mx - mn
        return Hsv(hueOf(rf, gf, bf, mx, d), if (mx == 0.0) 0.0 else d / mx, mx)
    }

    fun fromHsv(h: Double, s: Double, v: Double): Long {
        val sc = s.coerceIn(0.0, 1.0); val vc = v.coerceIn(0.0, 1.0)
        val c = vc * sc
        val (r, g, b) = hueToRgb(h, c, vc - c)
        return fromUnit(r, g, b)
    }

    fun fromHsv(hsv: Hsv): Long = fromHsv(hsv.h, hsv.s, hsv.v)

    fun toHsl(argb: Long): Hsl {
        val rf = r(argb) / 255.0; val gf = g(argb) / 255.0; val bf = b(argb) / 255.0
        val mx = max(rf, max(gf, bf)); val mn = min(rf, min(gf, bf)); val d = mx - mn
        val l = (mx + mn) / 2
        val s = if (d == 0.0) 0.0 else d / (1 - abs(2 * l - 1))
        return Hsl(hueOf(rf, gf, bf, mx, d), s.coerceIn(0.0, 1.0), l)
    }

    fun fromHsl(h: Double, s: Double, l: Double): Long {
        val lc = l.coerceIn(0.0, 1.0); val sc = s.coerceIn(0.0, 1.0)
        val c = (1 - abs(2 * lc - 1)) * sc
        val (r, g, b) = hueToRgb(h, c, lc - c / 2)
        return fromUnit(r, g, b)
    }

    private fun hueOf(r: Double, g: Double, b: Double, mx: Double, d: Double): Double {
        if (d == 0.0) return 0.0
        val h = when (mx) {
            r -> 60 * (((g - b) / d) % 6)
            g -> 60 * ((b - r) / d + 2)
            else -> 60 * ((r - g) / d + 4)
        }
        return (h + 360.0) % 360.0
    }
}

/** The Material roles derived from one seed, for one mode. Colours are 0xAARRGGBB. */
data class AccentRoles(
    val primary: Long, val onPrimary: Long,
    val primaryContainer: Long, val onPrimaryContainer: Long,
    val secondary: Long, val onSecondary: Long,
    val secondaryContainer: Long, val onSecondaryContainer: Long,
    val tertiary: Long, val onTertiary: Long,
    val tertiaryContainer: Long, val onTertiaryContainer: Long,
    val surfaceTint: Long,
    /** True when the seed's tone had to move noticeably to read well. */
    val adjusted: Boolean
)

/**
 * Derives a full, readable set of Material roles from ONE seed colour. The seed's hue and saturation are kept; only
 * lightness moves, in 1% steps, until every text pair is at least [Contrast.AA_TEXT] (4.5:1) and each accent also reads
 * against the page behind it (Day, Night and True black).
 */
object AccentPalette {
    const val LIGHT_BACKGROUND = 0xFFFFFBFEL
    const val DARK_BACKGROUND = 0xFF1C1B1FL
    const val BLACK_BACKGROUND = 0xFF000000L
    private const val NEAR_BLACK = 0xFF1C1B1FL
    private const val WHITE = 0xFFFFFFFFL
    private const val MIN = Contrast.AA_TEXT

    fun forSeed(seedArgb: Long, dark: Boolean, black: Boolean): AccentRoles {
        val seed = ColorMath.toHsl(seedArgb)
        val background = when { dark && black -> BLACK_BACKGROUND; dark -> DARK_BACKGROUND; else -> LIGHT_BACKGROUND }

        val (primary, onPrimary) = accent(seed.h, seed.s, seed.l, dark, background)
        val ph = ColorMath.toHsl(primary)
        val adjusted = abs(ph.l - seed.l) > 0.04

        val (pc, onPc) = container(seed.h, seed.s, dark, black)
        val secHue = seed.h; val secSat = seed.s * 0.45
        val (secondary, onSecondary) = accent(secHue, secSat, seed.l, dark, background)
        val (sc, onSc) = container(secHue, secSat, dark, black)
        val terHue = (seed.h + 60.0) % 360.0; val terSat = max(seed.s * 0.7, if (seed.s == 0.0) 0.0 else 0.25)
        val (tertiary, onTertiary) = accent(terHue, terSat, seed.l, dark, background)
        val (tc, onTc) = container(terHue, terSat, dark, black)

        return AccentRoles(
            primary, onPrimary, pc, onPc, secondary, onSecondary, sc, onSc, tertiary, onTertiary, tc, onTc,
            surfaceTint = primary, adjusted = adjusted
        )
    }

    /** An accent colour and its text colour: lightness walks from the seed's until both pairs pass. */
    private fun accent(h: Double, s: Double, seedL: Double, dark: Boolean, background: Long): Pair<Long, Long> {
        val step = if (dark) 0.01 else -0.01
        var l = seedL
        // Day needs a darker accent than a pale seed; Night a lighter one than a dark seed.
        var i = 0
        while (i <= 100) {
            val fill = ColorMath.fromHsl(h, s, l.coerceIn(0.0, 1.0))
            val on = bestOn(fill, dark, h, s)
            if (Contrast.ratio(fill, on) >= MIN && Contrast.ratio(fill, background) >= MIN) return fill to on
            // A seed already past the target side may only be taken the other way if it fails there.
            l += step; i++
        }
        val fill = if (dark) WHITE else 0xFF000000L
        return fill to bestOn(fill, dark, h, s)
    }

    /** A tinted container and the text on it. */
    private fun container(h: Double, s: Double, dark: Boolean, black: Boolean): Pair<Long, Long> {
        val fill = ColorMath.fromHsl(h, s.coerceAtMost(0.85), if (dark) (if (black) 0.26 else 0.30) else 0.90)
        return fill to bestOn(fill, dark, h, s)
    }

    /** Text on [fill]: a tint of the hue when it reads, else plain near-black or white, whichever reads better. */
    private fun bestOn(fill: Long, dark: Boolean, h: Double, s: Double): Long {
        val tinted = ColorMath.fromHsl(h, s.coerceAtMost(0.5), if (dark) 0.92 else 0.12)
        val tintedAlt = ColorMath.fromHsl(h, s.coerceAtMost(0.5), if (dark) 0.12 else 0.92)
        val candidates = listOf(tinted, tintedAlt, NEAR_BLACK, WHITE, 0xFF000000L)
        candidates.firstOrNull { Contrast.ratio(fill, it) >= MIN }?.let { return it }
        return candidates.maxBy { Contrast.ratio(fill, it) }
    }
}
