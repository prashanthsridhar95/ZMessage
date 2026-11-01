package com.zyke.message.domain.repository

import com.zyke.message.domain.categories.CustomCategories
import com.zyke.message.domain.categories.CustomCategoryState
import com.zyke.message.domain.categories.NameCheck
import com.zyke.message.domain.diag.DiagLog
import com.zyke.message.domain.model.CustomCategory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What was taken away when a category was deleted, so that Undo can put it all back. */
data class DeletedCategory(val category: CustomCategory, val index: Int, val keys: Set<String>)

/** The categories the person made, and which senders, receivers and payments are filed under them. */
interface CustomCategoryRepository {
    val state: StateFlow<CustomCategoryState>

    /** Makes a category and returns it, or says why the name cannot be used. */
    fun add(name: String, emoji: String?, colourIndex: Int): Result

    /** Changes a category's name, emoji and colour. */
    fun edit(id: String, name: String, emoji: String?, colourIndex: Int): NameCheck

    /** Files [key] (see CategoryKey) under category [id]; null takes the choice away. */
    fun assign(key: String, id: String?)

    /** Deletes the category; whatever was filed under it goes back to automatic. Null when there is no such category. */
    fun delete(id: String): DeletedCategory?

    /** Takes back [delete]. */
    fun restore(deleted: DeletedCategory)

    sealed interface Result {
        data class Created(val category: CustomCategory) : Result
        data class Rejected(val reason: String) : Result
    }
}

object NoCustomCategories : CustomCategoryRepository {
    override val state: StateFlow<CustomCategoryState> = MutableStateFlow(CustomCategoryState())
    override fun add(name: String, emoji: String?, colourIndex: Int) = CustomCategoryRepository.Result.Rejected("Not available")
    override fun edit(id: String, name: String, emoji: String?, colourIndex: Int): NameCheck = NameCheck.Invalid("Not available")
    override fun assign(key: String, id: String?) = Unit
    override fun delete(id: String): DeletedCategory? = null
    override fun restore(deleted: DeletedCategory) = Unit
}

/** The working part, free of Android so it can be tested: each change is made whole under one lock, then saved. */
class CustomCategoryStore(
    initial: CustomCategoryState = CustomCategoryState(),
    private val newId: (List<CustomCategory>) -> String = { CustomCategories.newId(it) },
    private val persist: (CustomCategoryState) -> Unit = {}
) : CustomCategoryRepository {
    private val lock = Any()
    private val _state = MutableStateFlow(initial)
    override val state: StateFlow<CustomCategoryState> = _state.asStateFlow()

    override fun add(name: String, emoji: String?, colourIndex: Int): CustomCategoryRepository.Result = synchronized(lock) {
        val current = _state.value
        if (current.categories.size >= CustomCategories.MAX_COUNT) {
            return CustomCategoryRepository.Result.Rejected("You can have up to ${CustomCategories.MAX_COUNT} categories")
        }
        when (val check = CustomCategories.validate(name, current.categories)) {
            is NameCheck.Invalid -> CustomCategoryRepository.Result.Rejected(check.reason)
            is NameCheck.Valid -> {
                val made = CustomCategory(newId(current.categories), check.name, emoji?.trim()?.ifEmpty { null }, CustomCategories.clampColour(colourIndex))
                commit("add id=${made.id}", current.copy(categories = current.categories + made))
                CustomCategoryRepository.Result.Created(made)
            }
        }
    }

    override fun edit(id: String, name: String, emoji: String?, colourIndex: Int): NameCheck = synchronized(lock) {
        val current = _state.value
        if (current.categories.none { it.id == id }) return NameCheck.Invalid("That category is gone")
        val check = CustomCategories.validate(name, current.categories, ignoreId = id)
        if (check is NameCheck.Valid) {
            commit("edit id=$id", current.copy(categories = current.categories.map {
                if (it.id == id) it.copy(name = check.name, emoji = emoji?.trim()?.ifEmpty { null }, colourIndex = CustomCategories.clampColour(colourIndex)) else it
            }))
        }
        check
    }

    override fun assign(key: String, id: String?) {
        synchronized(lock) { assignLocked(key, id) }
    }

    private fun assignLocked(key: String, id: String?) {
        val current = _state.value
        val next = when {
            id == null -> current.assignments - key
            current.categories.none { it.id == id } -> current.assignments
            else -> current.assignments + (key to id)
        }
        if (next != current.assignments) commit("assign id=${id ?: "none"} key=${com.zyke.message.domain.diag.DiagLog.key(key)}", current.copy(assignments = next))
    }

    override fun delete(id: String): DeletedCategory? = synchronized(lock) {
        val current = _state.value
        val index = current.categories.indexOfFirst { it.id == id }
        if (index < 0) return null
        val keys = current.assignments.filterValues { it == id }.keys
        commit("delete id=$id unassigned=${keys.size}", CustomCategoryState(current.categories.filter { it.id != id }, current.assignments - keys))
        DeletedCategory(current.categories[index], index, keys)
    }

    override fun restore(deleted: DeletedCategory) {
        synchronized(lock) { restoreLocked(deleted) }
    }

    private fun restoreLocked(deleted: DeletedCategory) {
        val current = _state.value
        if (current.categories.any { it.id == deleted.category.id }) return
        val at = deleted.index.coerceIn(0, current.categories.size)
        commit(
            "restore id=${deleted.category.id} reassigned=${deleted.keys.size}",
            CustomCategoryState(
                current.categories.toMutableList().also { it.add(at, deleted.category) },
                // A key the person has filed somewhere else in the meantime stays where it is now.
                current.assignments + deleted.keys.filter { it !in current.assignments }.associateWith { deleted.category.id }
            )
        )
    }

    private fun commit(what: String, next: CustomCategoryState) {
        _state.value = next
        persist(next)
        DiagLog.d("category") { "custom: $what -> ${next.categories.size} categories, ${next.assignments.size} filed" }
    }
}
