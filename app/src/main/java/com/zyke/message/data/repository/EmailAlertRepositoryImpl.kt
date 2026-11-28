package com.zyke.message.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.zyke.message.domain.email.EmailAlert
import com.zyke.message.domain.email.EmailAlertKind
import com.zyke.message.domain.email.EmailAlertRepository
import com.zyke.message.domain.email.EmailAlertRules
import com.zyke.message.domain.email.EmailAlertSettings
import com.zyke.message.domain.email.EmailAlertState
import com.zyke.message.domain.email.EmailSyncEvery
import com.zyke.message.domain.email.SyncOutcome
import com.zyke.message.domain.model.BankName
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * The choices about email alerts and the alerts read from email, in two private preference files. Both describe this phone and its
 * mailbox, so both are left out of every backup (see `PrefsBackup`, and the backup rules, which take one encrypted file only); the
 * alerts come back from the mailbox on the next sync. `email_alerts_settings` holds the switch, the provider, the person's address
 * and the place reached in the mailbox; `email_alerts` holds the parsed fields and the masked snippet of each alert, at most
 * [EmailAlertRepository.MAX_ALERTS], never an email, a subject or the address an email came from. The app password is not here (see
 * `EmailPasswordVault`). Nothing in this class logs.
 */
class EmailAlertRepositoryImpl(context: Context) : EmailAlertRepository {
    private val alertPrefs: SharedPreferences = context.applicationContext.getSharedPreferences(ALERTS_FILE, Context.MODE_PRIVATE)
    private val settingsPrefs: SharedPreferences = context.applicationContext.getSharedPreferences(SETTINGS_FILE, Context.MODE_PRIVATE)
    private val flow = MutableStateFlow(EmailAlertState(loadSettings(settingsPrefs), loadAlerts(alertPrefs)))
    private val lock = Any()

    override val state: StateFlow<EmailAlertState> = flow

    override fun update(change: (EmailAlertSettings) -> EmailAlertSettings) {
        synchronized(lock) {
            val current = flow.value
            val next = change(current.settings)
            if (next == current.settings) return
            saveSettings(next)
            flow.value = current.copy(settings = next)
        }
    }

    override fun add(alerts: List<EmailAlert>, now: Long) {
        synchronized(lock) {
            val current = flow.value
            val merged = EmailAlertRules.merge(current.alerts, alerts, current.settings.keepMonths, now)
            if (merged == current.alerts) return
            saveAlerts(merged)
            flow.value = current.copy(alerts = merged)
        }
    }

    override fun prune(now: Long) {
        synchronized(lock) {
            val current = flow.value
            val kept = EmailAlertRules.pruned(current.alerts, current.settings.keepMonths, now)
            if (kept.size == current.alerts.size) return
            saveAlerts(kept)
            flow.value = current.copy(alerts = kept)
        }
    }

    override fun clearAlerts() {
        synchronized(lock) {
            val current = flow.value
            alertPrefs.edit().clear().commit()
            val settings = current.settings.copy(uidValidity = 0L, highestUid = 0L, lastAdded = 0)
            saveSettings(settings)
            flow.value = EmailAlertState(settings, emptyList())
        }
    }

    override fun clearAll() {
        synchronized(lock) {
            alertPrefs.edit().clear().commit()
            settingsPrefs.edit().clear().commit()
            flow.value = EmailAlertState()
        }
    }

    private fun saveSettings(s: EmailAlertSettings) {
        settingsPrefs.edit()
            .putBoolean("enabled", s.enabled).putBoolean("explained", s.explained).putString("provider", s.providerId).putString("address", s.address)
            .putBoolean("connected", s.connected).putString("every", s.syncEvery.id).putInt("months", s.keepMonths)
            .putLong("last_sync", s.lastSyncAt).putString("outcome", s.lastOutcome?.name.orEmpty()).putInt("added", s.lastAdded)
            .putLong("validity", s.uidValidity).putLong("highest", s.highestUid).putBoolean("hdfc_notice", s.hdfcNoticeAnswered).putBoolean("prompted", s.emailPrompted)
            .apply()
    }

    private fun saveAlerts(list: List<EmailAlert>) {
        val array = JSONArray()
        list.forEach { a ->
            array.put(
                JSONObject().put("k", a.key).put("s", a.senderId).put("b", a.bank.name).put("n", a.senderName).put("t", a.at).put("d", a.kind.name)
                    .put("a", a.amount).put("p", a.payee).put("v", a.vpa).put("c", a.account).put("r", if (a.isCard) 1 else 0).put("f", a.reference).put("x", a.snippet)
            )
        }
        alertPrefs.edit().putString("alerts", array.toString()).apply()
    }

    private companion object {
        const val ALERTS_FILE = "email_alerts"
        const val SETTINGS_FILE = "email_alerts_settings"

        fun loadSettings(p: SharedPreferences): EmailAlertSettings {
            val d = EmailAlertSettings()
            return EmailAlertSettings(
                enabled = p.getBoolean("enabled", d.enabled),
                explained = p.getBoolean("explained", d.explained),
                providerId = p.getString("provider", "").orEmpty(),
                address = p.getString("address", "").orEmpty(),
                connected = p.getBoolean("connected", d.connected),
                syncEvery = EmailSyncEvery.fromId(p.getString("every", "").orEmpty()),
                keepMonths = p.getInt("months", d.keepMonths).takeIf { it in EmailAlertSettings.KEEP_CHOICES } ?: d.keepMonths,
                lastSyncAt = p.getLong("last_sync", 0L),
                lastOutcome = p.getString("outcome", "").orEmpty().let { name -> SyncOutcome.entries.firstOrNull { it.name == name } },
                lastAdded = p.getInt("added", 0),
                uidValidity = p.getLong("validity", 0L),
                highestUid = p.getLong("highest", 0L),
                hdfcNoticeAnswered = p.getBoolean("hdfc_notice", false),
                emailPrompted = p.getBoolean("prompted", false)
            )
        }

        fun loadAlerts(p: SharedPreferences): List<EmailAlert> {
            val out = ArrayList<EmailAlert>()
            runCatching {
                val array = JSONArray(p.getString("alerts", "[]"))
                for (i in 0 until array.length()) runCatching {
                    val o = array.getJSONObject(i)
                    out += EmailAlert(
                        key = o.getString("k"), senderId = o.getString("s"),
                        bank = BankName.entries.firstOrNull { it.name == o.optString("b") } ?: BankName.OTHERS,
                        senderName = o.getString("n"), at = o.getLong("t"),
                        kind = EmailAlertKind.entries.firstOrNull { it.name == o.optString("d") } ?: EmailAlertKind.UNCLEAR,
                        amount = o.optString("a"), payee = o.optString("p"), vpa = o.optString("v"), account = o.optString("c"),
                        isCard = o.optInt("r") == 1, reference = o.optString("f"), snippet = o.optString("x").take(EmailAlert.SNIPPET_MAX)
                    )
                }
            }
            return out.sortedByDescending { it.at }.take(EmailAlertRepository.MAX_ALERTS)
        }
    }
}
