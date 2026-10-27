package com.zyke.message.notifications

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.zyke.message.R
import com.zyke.message.domain.insights.DigestSummary
import com.zyke.message.presentation.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** A home-screen widget with the same short digest as the morning notification. */
class DaysAheadWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val digest = DigestProvider.load(context)
                appWidgetIds.forEach { manager.updateAppWidget(it, views(context, digest)) }
            } finally {
                pending.finish()
            }
        }
    }

    private fun views(context: Context, digest: DigestSummary?): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_days_ahead)
        val lines = digest?.lines ?: listOf("Nothing due or on today")
        views.setTextViewText(R.id.widget_title, digest?.title ?: "Your day")
        LINE_IDS.forEachIndexed { index, id ->
            val text = lines.getOrNull(index)
            views.setTextViewText(id, text.orEmpty())
            views.setViewVisibility(id, if (text == null) android.view.View.GONE else android.view.View.VISIBLE)
        }
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_root, open)
        return views
    }

    companion object {
        private val LINE_IDS = listOf(R.id.widget_line_1, R.id.widget_line_2, R.id.widget_line_3, R.id.widget_line_4)

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
