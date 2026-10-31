package com.zyke.message.notifications

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.Toast
import com.zyke.message.R
import com.zyke.message.ZykeMessagesApplication
import com.zyke.message.domain.ledger.ManualEntry
import com.zyke.message.domain.ledger.ManualKind
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.presentation.MainActivity
import com.zyke.message.presentation.home.EntryRequests
import java.math.BigDecimal
import java.time.LocalDate

/**
 * "Quick add": two cash presets that record an entry at once (no screen), a button that opens the entry sheet, and one that
 * opens it set to income. Everything stays on the phone; the presets are written to the same private store as any entry.
 */
class QuickAddWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        val views = RemoteViews(context.packageName, R.layout.widget_quick_add).apply {
            setOnClickPendingIntent(R.id.quick_add_small, preset(context, SMALL))
            setOnClickPendingIntent(R.id.quick_add_large, preset(context, LARGE))
            setOnClickPendingIntent(R.id.quick_add_custom, open(context, ManualKind.EXPENSE, 2))
            setOnClickPendingIntent(R.id.quick_add_income, open(context, ManualKind.INCOME, 3))
        }
        appWidgetIds.forEach { manager.updateAppWidget(it, views) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_PRESET) return super.onReceive(context, intent)
        val amount = intent.getIntExtra(EXTRA_AMOUNT, 0)
        if (amount <= 0) return
        val repo = (context.applicationContext as ZykeMessagesApplication).container.manualEntries
        repo.save(
            ManualEntry(
                id = repo.nextId(), kind = ManualKind.EXPENSE, amount = BigDecimal(amount), title = "Cash spend",
                category = MessageCategory.OTHERS, date = LocalDate.now()
            )
        )
        Toast.makeText(context, context.getString(R.string.quick_add_saved, amount), Toast.LENGTH_SHORT).show()
        DaysAheadWidget.refresh(context)
    }

    private fun preset(context: Context, amount: Int): PendingIntent = PendingIntent.getBroadcast(
        context, amount,
        Intent(context, QuickAddWidget::class.java).setAction(ACTION_PRESET).putExtra(EXTRA_AMOUNT, amount),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun open(context: Context, kind: ManualKind, code: Int): PendingIntent = PendingIntent.getActivity(
        context, code, addEntryIntent(context, kind), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    companion object {
        private const val ACTION_PRESET = "com.zyke.message.action.QUICK_ADD_PRESET"
        private const val EXTRA_AMOUNT = "com.zyke.message.extra.AMOUNT"
        private const val SMALL = 100
        private const val LARGE = 500

        /** The deep link that opens the entry sheet on Home; shared with the quick-settings tile. */
        fun addEntryIntent(context: Context, kind: ManualKind = ManualKind.EXPENSE): Intent =
            Intent(context, MainActivity::class.java).setAction(EntryRequests.ACTION_ADD_ENTRY)
                .putExtra(EntryRequests.EXTRA_KIND, kind.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
