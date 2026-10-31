package com.zyke.message.domain.repository

import com.zyke.message.domain.teach.LearnedTemplate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Message formats the person taught the app, in the order taught (the first that reads a message wins). Stored on the phone only. */
interface LearnedTemplateRepository {
    val templates: StateFlow<List<LearnedTemplate>>

    /** Adds [template] at [index] (default: last). A template with the same id is replaced, not duplicated. */
    fun add(template: LearnedTemplate, index: Int = Int.MAX_VALUE)

    fun remove(id: String)
}

object NoLearnedTemplates : LearnedTemplateRepository {
    override val templates: StateFlow<List<LearnedTemplate>> = MutableStateFlow(emptyList())
    override fun add(template: LearnedTemplate, index: Int) = Unit
    override fun remove(id: String) = Unit
}
