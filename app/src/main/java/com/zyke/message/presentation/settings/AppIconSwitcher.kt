package com.zyke.message.presentation.settings

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.zyke.message.domain.model.AppIconChoice

/**
 * Switches the launcher icon colour by enabling one `<activity-alias>` and disabling the rest (Android cannot recolour
 * an icon any other way). The new alias is enabled first, so there is never a moment with no launcher entry.
 * Launchers differ: some take a few seconds to notice, some close the app, and some drop the icon from the home screen.
 */
object AppIconSwitcher {
    /** [apply] on a background thread; failures are swallowed (the icon just stays as it was). */
    fun applyAsync(context: Context, choice: AppIconChoice) {
        val app = context.applicationContext
        Thread({ apply(app, choice) }, "app-icon").start()
    }

    /** Makes [choice] the one enabled alias. Idempotent: does nothing when it already is. */
    fun apply(context: Context, choice: AppIconChoice) {
        // Demo mode must not change the real launcher icon.
        if (com.zyke.message.data.demo.DemoMode.active) return
        try {
            val pm = context.packageManager
            val target = ComponentName(context, choice.aliasName)
            if (pm.getComponentEnabledSetting(target) != PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                pm.setComponentEnabledSetting(target, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
            }
            AppIconChoice.entries.filter { it != choice }.forEach { other ->
                val name = ComponentName(context, other.aliasName)
                if (pm.getComponentEnabledSetting(name) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                    pm.setComponentEnabledSetting(name, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
                }
            }
        } catch (_: Exception) {
            // A launcher or device that refuses: keep whatever icon is there.
        }
    }

    /**
     * After a restore or a fresh install the manifest may show a different alias than the saved choice: apply the saved one.
     * Cheap when they agree (reads only).
     */
    fun reconcile(context: Context, stored: AppIconChoice) {
        // The demo's saved icon is not the person's: leave the real one alone.
        if (com.zyke.message.data.demo.DemoMode.active) return
        try {
            val pm = context.packageManager
            val enabled = AppIconChoice.entries.filter { choice ->
                val state = pm.getComponentEnabledSetting(ComponentName(context, choice.aliasName))
                // DEFAULT means "as the manifest says": only the default alias is enabled there.
                state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
                    (state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && choice == AppIconChoice.DEFAULT)
            }
            if (enabled != listOf(stored)) apply(context, stored)
        } catch (_: Exception) {
        }
    }
}
