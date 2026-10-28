package com.zyke.message.notifications

import android.Manifest
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import com.zyke.message.R
import com.zyke.message.domain.insights.DigestItem
import com.zyke.message.domain.insights.DigestKind
import com.zyke.message.domain.insights.DigestSummary
import com.zyke.message.presentation.MainActivity
import com.zyke.message.presentation.format.DisplayFormatters
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * A home-screen widget with the same short digest as the morning notification, laid out as rows: a coloured marker
 * for how pressing it is, what it is and when, and the amount on the right. It shows more rows the taller it is made.
 */
class DaysAheadWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) = redraw(context, manager, appWidgetIds)

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) =
        redraw(context, manager, intArrayOf(appWidgetId))

    private fun redraw(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val canRead = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
                val digest = if (canRead) DigestProvider.load(context) else null
                ids.forEach { id ->
                    val options = manager.getAppWidgetOptions(id)
                    val height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)
                    val width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
                    manager.updateAppWidget(id, views(context, digest, canRead, rowsFor(height), showHero = width <= 0 || width >= NARROW_DP))
                }
            } finally {
                pending.finish()
            }
        }
    }

    /** How many rows fit: two in a short widget, up to four in a tall one. */
    private fun rowsFor(heightDp: Int): Int = when {
        heightDp <= 0 -> MAX_ROWS
        heightDp < SHORT_DP -> 2
        heightDp < MEDIUM_DP -> 3
        else -> MAX_ROWS
    }

    private fun views(context: Context, digest: DigestSummary?, canRead: Boolean, rows: Int, showHero: Boolean): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_days_ahead)
        val today = LocalDate.now()
        views.setTextViewText(R.id.widget_date, DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault()).format(today))

        val items = digest?.items.orEmpty()

        // The headline on the left: what is about to leave, or today's date on a day with nothing due.
        views.setViewVisibility(R.id.widget_hero, if (showHero) View.VISIBLE else View.GONE)
        views.setViewVisibility(R.id.widget_divider, if (showHero) View.VISIBLE else View.GONE)
        if (digest != null && digest.dueTotal.signum() > 0) {
            views.setTextViewText(R.id.widget_hero_label, "Due soon")
            views.setTextViewText(R.id.widget_hero_value, DisplayFormatters.rupees(digest.dueTotal))
            views.setTextViewText(R.id.widget_hero_caption, DisplayFormatters.count(digest.dueCount, "payment", "payments"))
        } else {
            views.setTextViewText(R.id.widget_hero_label, "Today")
            views.setTextViewText(R.id.widget_hero_value, today.dayOfMonth.toString())
            views.setTextViewText(R.id.widget_hero_caption, DateTimeFormatter.ofPattern("EEEE", Locale.getDefault()).format(today))
        }
        val shown = items.take(rows)
        ROWS.forEachIndexed { index, row ->
            val item = shown.getOrNull(index)
            views.setViewVisibility(row.container, if (item == null) View.GONE else View.VISIBLE)
            if (item != null) bind(context, views, row, item)
        }

        val empty = items.isEmpty()
        views.setViewVisibility(R.id.widget_empty, if (empty) View.VISIBLE else View.GONE)
        if (empty && !canRead) {
            views.setTextViewText(R.id.widget_empty_title, context.getString(R.string.widget_needs_access_title))
            views.setTextViewText(R.id.widget_empty_detail, context.getString(R.string.widget_needs_access_detail))
        } else if (empty) {
            views.setTextViewText(R.id.widget_empty_title, context.getString(R.string.widget_empty_title))
            views.setTextViewText(R.id.widget_empty_detail, context.getString(R.string.widget_empty_detail))
        }

        val hidden = items.size - shown.size
        views.setViewVisibility(R.id.widget_more, if (hidden > 0) View.VISIBLE else View.GONE)
        if (hidden > 0) views.setTextViewText(R.id.widget_more, "+$hidden more in the app")

        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_root, open)
        return views
    }

    private fun bind(context: Context, views: RemoteViews, row: Row, item: DigestItem) {
        views.setImageViewResource(row.dot, dotFor(item))
        views.setTextViewText(row.title, item.title)
        views.setTextViewText(row.detail, item.detail)
        views.setViewVisibility(row.detail, if (item.detail.isBlank()) View.GONE else View.VISIBLE)
        val amount = item.amount?.let { DisplayFormatters.rupees(it) }
        views.setTextViewText(row.amount, amount.orEmpty())
        views.setViewVisibility(row.amount, if (amount == null) View.GONE else View.VISIBLE)
        views.setTextColor(row.amount, ContextCompat.getColor(context, if (item.urgent) R.color.widget_urgent else R.color.widget_text))
        views.setContentDescription(row.container, listOfNotNull(item.title, amount, item.detail.takeIf { it.isNotBlank() }).joinToString(", "))
    }

    private fun dotFor(item: DigestItem): Int = when {
        item.urgent -> R.drawable.widget_dot_urgent
        item.kind == DigestKind.BILL || item.kind == DigestKind.DEBIT || item.kind == DigestKind.REMINDER -> R.drawable.widget_dot_due
        item.kind == DigestKind.EVENT -> R.drawable.widget_dot_event
        else -> R.drawable.widget_dot_info
    }

    private class Row(val container: Int, val dot: Int, val title: Int, val detail: Int, val amount: Int)

    companion object {
        private const val MAX_ROWS = 4
        private const val SHORT_DP = 110
        private const val MEDIUM_DP = 170
        private const val NARROW_DP = 200

        private val ROWS = listOf(
            Row(R.id.widget_row_1, R.id.widget_dot_1, R.id.widget_title_1, R.id.widget_detail_1, R.id.widget_amount_1),
            Row(R.id.widget_row_2, R.id.widget_dot_2, R.id.widget_title_2, R.id.widget_detail_2, R.id.widget_amount_2),
            Row(R.id.widget_row_3, R.id.widget_dot_3, R.id.widget_title_3, R.id.widget_detail_3, R.id.widget_amount_3),
            Row(R.id.widget_row_4, R.id.widget_dot_4, R.id.widget_title_4, R.id.widget_detail_4, R.id.widget_amount_4)
        )

        /** Asks every placed widget to redraw, e.g. when the app is opened and the inbox may have changed. */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, DaysAheadWidget::class.java))
            if (ids.isEmpty()) return
            context.sendBroadcast(
                Intent(context, DaysAheadWidget::class.java)
                    .setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            )
        }
    }
}
