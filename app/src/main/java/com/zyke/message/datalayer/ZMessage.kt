package com.zyke.message.datalayer

data class ZMessage(
    val sender: String,
    val messageContent: String,
    val date: Long,
    val readStatus: ReadStatus,
    val isRcs: Boolean = false
)

enum class ReadStatus {
    READ, UNREAD
}
