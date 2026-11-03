package com.zyke.message.domain.nickname

/** The sentence that says what a bulk edit is about to do, before it is done. */
object BulkEditText {
    private fun payments(count: Int) = if (count == 1) "1 payment" else "$count payments"

    /**
     * @param nickname the new name, or blank to leave names alone
     * @param categoryLabel the category chosen, or the one the name brings; blank when none
     * @param fromName true when [categoryLabel] comes from the name's other payments rather than a choice
     */
    fun preview(count: Int, nickname: String, categoryLabel: String, fromName: Boolean = false): String {
        val name = nickname.trim()
        val them = if (count == 1) "it" else "them"
        return when {
            count <= 0 -> "Nothing selected"
            name.isNotEmpty() && categoryLabel.isNotEmpty() ->
                "Will rename ${payments(count)} to \"$name\" and file $them under $categoryLabel" + if (fromName) " like your other \"$name\" payments" else ""
            name.isNotEmpty() -> "Will rename ${payments(count)} to \"$name\""
            categoryLabel.isNotEmpty() -> "Will file ${payments(count)} under $categoryLabel"
            else -> "Choose a name or a category"
        }
    }

    fun done(count: Int) = "Updated ${payments(count)}"
}
