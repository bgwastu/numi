package com.bgwastu.numi.network

object TelegramHtml {
    fun escape(text: String): String =
        text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

    fun bold(text: String): String = "<b>${escape(text)}</b>"

    fun code(text: String): String = "<code>${escape(text)}</code>"

    fun pre(text: String): String = "<pre>${escape(text)}</pre>"
}
