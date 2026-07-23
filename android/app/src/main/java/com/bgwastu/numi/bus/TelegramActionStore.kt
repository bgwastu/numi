package com.bgwastu.numi.bus

import android.content.Context
import org.json.JSONObject
import java.util.UUID

data class PendingSmsAction(
    val id: String = UUID.randomUUID().toString().replace("-", "").take(24),
    val chatId: String,
    val recipient: String,
    val body: String,
    val subscriptionId: Int? = null,
    val simLabel: String? = null,
    val expiresAt: Long = System.currentTimeMillis() + PENDING_SMS_TTL_MS
) {
    companion object {
        const val PENDING_SMS_TTL_MS = 10 * 60 * 1000L
    }
}

data class ReplyTarget(
    val phoneNumber: String,
    val subscriptionId: Int? = null,
    val source: String
)

/** Persists short-lived Telegram interaction state across foreground-service restarts. */
class TelegramActionStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun savePending(action: PendingSmsAction) {
        prefs.edit().putString(pendingKey(action.id), JSONObject().apply {
            put("chatId", action.chatId)
            put("recipient", action.recipient)
            put("body", action.body)
            put("expiresAt", action.expiresAt)
            action.subscriptionId?.let { put("subscriptionId", it) }
            action.simLabel?.let { put("simLabel", it) }
        }.toString()).apply()
    }

    fun getPending(id: String): PendingSmsAction? {
        val raw = prefs.getString(pendingKey(id), null) ?: return null
        return try {
            val json = JSONObject(raw)
            val action = PendingSmsAction(
                id = id,
                chatId = json.getString("chatId"),
                recipient = json.getString("recipient"),
                body = json.getString("body"),
                subscriptionId = json.optInt("subscriptionId", Int.MIN_VALUE)
                    .takeUnless { it == Int.MIN_VALUE },
                simLabel = json.optString("simLabel").takeIf { it.isNotBlank() },
                expiresAt = json.getLong("expiresAt")
            )
            if (action.expiresAt <= System.currentTimeMillis()) {
                removePending(id)
                null
            } else {
                action
            }
        } catch (_: Exception) {
            removePending(id)
            null
        }
    }

    fun removePending(id: String) {
        prefs.edit().remove(pendingKey(id)).apply()
    }

    fun saveReplyTarget(messageId: Int, target: ReplyTarget) {
        prefs.edit().putString(replyKey(messageId), JSONObject().apply {
            put("phoneNumber", target.phoneNumber)
            put("source", target.source)
            target.subscriptionId?.let { put("subscriptionId", it) }
        }.toString()).apply()
    }

    fun getReplyTarget(messageId: Int): ReplyTarget? {
        val raw = prefs.getString(replyKey(messageId), null) ?: return null
        return try {
            val json = JSONObject(raw)
            ReplyTarget(
                phoneNumber = json.getString("phoneNumber"),
                subscriptionId = json.optInt("subscriptionId", Int.MIN_VALUE)
                    .takeUnless { it == Int.MIN_VALUE },
                source = json.optString("source", "event")
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun pendingKey(id: String) = "pending_sms_$id"
    private fun replyKey(messageId: Int) = "telegram_reply_target_$messageId"

    private companion object {
        const val PREFS_NAME = "numi_telegram_actions"
    }
}
