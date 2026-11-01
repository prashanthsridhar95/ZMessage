package com.zyke.message.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.zyke.message.domain.categories.CustomCategories
import com.zyke.message.domain.categories.CustomCategoryState
import com.zyke.message.domain.repository.CustomCategoryRepository
import com.zyke.message.domain.repository.CustomCategoryStore

/**
 * The categories the person made and what is filed under them, in private preferences ("custom_categories"; a build
 * before this stored nothing there, which reads as none made). The changes themselves are in [CustomCategoryStore].
 */
class CustomCategoryRepositoryImpl private constructor(
    store: CustomCategoryStore
) : CustomCategoryRepository by store {

    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences("custom_categories", Context.MODE_PRIVATE).let { prefs ->
            CustomCategoryStore(load(prefs)) { save(prefs, it) }
        }
    )

    private companion object {
        const val CATEGORIES = "categories"
        const val ASSIGNMENTS = "assignments"

        fun load(prefs: SharedPreferences) = CustomCategoryState(
            categories = CustomCategories.decodeCategories(prefs.getString(CATEGORIES, null)),
            assignments = CustomCategories.decodeAssignments(prefs.getString(ASSIGNMENTS, null))
        )

        fun save(prefs: SharedPreferences, state: CustomCategoryState) {
            prefs.edit()
                .putString(CATEGORIES, CustomCategories.encodeCategories(state.categories))
                .putString(ASSIGNMENTS, CustomCategories.encodeAssignments(state.assignments))
                .apply()
        }
    }
}
