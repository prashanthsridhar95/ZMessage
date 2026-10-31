package com.zyke.message.presentation.review

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.zyke.message.domain.review.YearInReview
import com.zyke.message.presentation.format.DisplayFormatters
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.time.format.TextStyle
import java.util.Locale

/**
 * Draws the "Your year" summary as a plain 1080 x 1350 picture with Canvas and Paint. Only totals and the
 * top categories are drawn; payee names and single payments never appear on it.
 */
internal object YearCardImage {
    private const val W = 1080
    private const val H = 1350
    private const val PAD = 72f
    private const val PNG_QUALITY = 100

    private val BG = Color.parseColor("#0F1B2D")
    private val FG = Color.parseColor("#FFFFFF")
    private val MUTED = Color.parseColor("#9FB0C5")
    private val ACCENT = Color.parseColor("#4C8BF5")
    private val BAR = Color.parseColor("#3A5A8C")
    private val TRACK = Color.parseColor("#1D2D45")
    private val GOOD = Color.parseColor("#34A853")
    private val BAD = Color.parseColor("#E8505B")

    private fun paint(size: Float, color: Int, bold: Boolean = false, align: Paint.Align = Paint.Align.LEFT) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = color
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            textAlign = align
        }

    fun render(review: YearInReview): Bitmap {
        val bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bitmap)
        c.drawColor(BG)

        c.drawText("YOUR ${review.year}", PAD, 130f, paint(44f, ACCENT, bold = true))
        c.drawText("Spent", PAD, 230f, paint(36f, MUTED))
        c.drawText(DisplayFormatters.rupees(review.spent), PAD, 330f, paint(104f, FG, bold = true))

        val rate = review.savingsRatePercent
        val net = review.net
        val saved = if (net.signum() >= 0) "Saved ${DisplayFormatters.rupees(net)}" else "Over by ${DisplayFormatters.rupees(net.abs())}"
        c.drawText(
            if (rate != null) "$saved  ·  $rate% of income" else saved,
            PAD, 400f, paint(36f, if (net.signum() >= 0) GOOD else BAD, bold = true)
        )

        // Month bars.
        val chartTop = 470f
        val chartBottom = 800f
        val max = review.months.maxOf { it.spent }.takeIf { it.signum() > 0 } ?: BigDecimal.ONE
        val slot = (W - 2 * PAD) / 12f
        val label = paint(24f, MUTED, align = Paint.Align.CENTER)
        review.months.forEachIndexed { i, m ->
            val x = PAD + i * slot
            val height = (chartBottom - chartTop) * m.spent.divide(max, 4, java.math.RoundingMode.HALF_UP).toFloat()
            val bar = RectF(x + 6f, chartBottom - maxOf(height, 4f), x + slot - 6f, chartBottom)
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (m.month == review.busiestMonth) ACCENT else BAR }
            c.drawRoundRect(bar, 8f, 8f, fill)
            c.drawText(m.month.getDisplayName(TextStyle.NARROW, Locale.getDefault()), x + slot / 2f, chartBottom + 36f, label)
        }

        // Top categories.
        c.drawText("TOP CATEGORIES", PAD, 910f, paint(28f, MUTED, bold = true))
        val topMax = review.topCategories.firstOrNull()?.amount?.takeIf { it.signum() > 0 } ?: BigDecimal.ONE
        review.topCategories.take(3).forEachIndexed { i, cat ->
            val y = 970f + i * 90f
            c.drawText(cat.category.displayString, PAD, y, paint(34f, FG))
            c.drawText(DisplayFormatters.rupees(cat.amount), W - PAD, y, paint(34f, FG, bold = true, align = Paint.Align.RIGHT))
            val track = RectF(PAD, y + 14f, W - PAD, y + 26f)
            c.drawRoundRect(track, 6f, 6f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TRACK })
            val share = cat.amount.divide(topMax, 4, java.math.RoundingMode.HALF_UP).toFloat()
            c.drawRoundRect(RectF(PAD, track.top, PAD + (W - 2 * PAD) * share, track.bottom), 6f, 6f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT })
        }

        c.drawText(
            "Based on ${review.monthsOfData} months of messages  ·  Zyke Message",
            W / 2f, H - 60f, paint(26f, MUTED, align = Paint.Align.CENTER)
        )
        return bitmap
    }

    fun png(review: YearInReview): ByteArray {
        val bitmap = render(review)
        try {
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out)
            return out.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }
}
