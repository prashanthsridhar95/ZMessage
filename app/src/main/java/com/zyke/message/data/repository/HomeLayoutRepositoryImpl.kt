package com.zyke.message.data.repository

import android.content.Context
import com.zyke.message.domain.home.HomeLayout
import com.zyke.message.domain.home.HomeLayoutRepository
import com.zyke.message.domain.home.HomeSection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which optional parts of Home are shown, in private preferences (`home_layout`). Only choices that differ from the calm default are
 * written. The backup copies every preference file, so this goes with it; in Demo mode the preferences are in memory.
 */
class HomeLayoutRepositoryImpl(context: Context) : HomeLayoutRepository {

    private val prefs = context.applicationContext.getSharedPreferences("home_layout", Context.MODE_PRIVATE)

    private val _layout = MutableStateFlow(read())
    override val layout: StateFlow<HomeLayout> = _layout.asStateFlow()

    private fun read(): HomeLayout {
        val choices = HashMap<String, Boolean>()
        for (section in HomeSection.entries) {
            if (prefs.contains(PREFIX + section.id)) choices[section.id] = prefs.getBoolean(PREFIX + section.id, section.defaultOn)
        }
        return HomeLayout(choices).normalized()
    }

    @Synchronized
    override fun save(layout: HomeLayout) {
        val clean = layout.normalized()
        _layout.value = clean
        val edit = prefs.edit().clear()
        clean.choices.forEach { (id, on) -> edit.putBoolean(PREFIX + id, on) }
        edit.apply()
    }

    private companion object {
        const val PREFIX = "show_"
    }
}
