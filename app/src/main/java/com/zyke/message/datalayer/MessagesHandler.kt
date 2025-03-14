package com.zyke.message.datalayer

import android.content.Context
import android.database.Cursor
import android.net.Uri


class MessagesHandler {
    fun getSmsMessages(context: Context, callback: (List<ZMessage>) -> Unit) {
        val uri = Uri.parse("content://sms/inbox")
        val cursor: Cursor? =
            context.contentResolver.query(uri, null, null, null, null)
        val messages = arrayListOf<ZMessage>()

        if (cursor != null) {
            while (cursor.moveToNext()) {
                val address = cursor.getString(cursor.getColumnIndexOrThrow("address"))
                val body = cursor.getString(cursor.getColumnIndexOrThrow("body"))
                val person = cursor.getString(cursor.getColumnIndexOrThrow("person"))
                val read = cursor.getString(cursor.getColumnIndexOrThrow("read"))
                val date = cursor.getString(cursor.getColumnIndexOrThrow("date"))
                val status = cursor.getString(cursor.getColumnIndexOrThrow("status"))
                val type = cursor.getString(cursor.getColumnIndexOrThrow("type"))
                val creator = cursor.getString(cursor.getColumnIndexOrThrow("creator"))

                messages.add(
                    ZMessage(
                        address,
                        body,
                        date.toLong(),
                        if (read == "1") ReadStatus.READ else ReadStatus.UNREAD
                    )
                )

//                println("@234 :: ADDRESS :: ${address}\n PERSON :: ${person} \n  BODY :: ${body} \n STATUS :: ${status} \n TYPE :: ${type} \n CREATOR :: ${creator} \n READ :: ${read}")

                val str = StringBuilder()
                cursor.columnNames.forEach {
                    str.append(it).append(",")
                }
                println("@234 :: ${str}")
            }
            cursor.close()
        } else {
            println("@234 :: CURSOR NULL")
        }

        callback(messages)
    }
}