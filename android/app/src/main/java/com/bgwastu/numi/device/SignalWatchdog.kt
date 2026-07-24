package com.bgwastu.numi.device

import android.content.Context
import android.util.Log
import com.bgwastu.numi.database.DbHelper
import com.bgwastu.numi.network.TelegramHtml
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Polls cellular service and notifies Telegram when signal is lost / restored.
 * Debounced so brief blips don't spam.
 */
class SignalWatchdog(
    private val context: Context,
    private val chatIdProvider: () -> String
) {
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    @Volatile private var lastInService: Boolean? = null
    private var lostStreak = 0
    private var okStreak = 0

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread = Thread({
            Log.d(TAG, "SignalWatchdog started")
            // Prime state without notifying.
            lastInService = NetworkMeter.snapshot(context).inService
            while (running.get()) {
                try {
                    tick()
                } catch (e: Exception) {
                    Log.w(TAG, "tick failed", e)
                }
                try {
                    Thread.sleep(POLL_MS)
                } catch (_: InterruptedException) {
                    break
                }
            }
            Log.d(TAG, "SignalWatchdog stopped")
        }, "signal-watchdog").also { it.isDaemon = true; it.start() }
    }

    fun stop() {
        running.set(false)
        thread?.interrupt()
        thread = null
    }

    private fun tick() {
        val snap = NetworkMeter.snapshot(context)
        val prev = lastInService

        if (snap.inService) {
            okStreak++
            lostStreak = 0
            if (prev == false && okStreak >= RESTORE_STREAK) {
                lastInService = true
                notifyRestored(snap)
            } else if (prev == null) {
                lastInService = true
            } else if (prev == true) {
                // stay
            }
        } else {
            lostStreak++
            okStreak = 0
            if (prev != false && lostStreak >= LOST_STREAK) {
                lastInService = false
                notifyLost(snap)
            } else if (prev == null && lostStreak >= LOST_STREAK) {
                lastInService = false
                // Don't notify on cold start if already no signal — only on transitions.
            }
        }
    }

    private fun notifyLost(snap: NetworkMeterSnapshot) {
        val time = now()
        val text = buildString {
            appendLine("📡 ${TelegramHtml.bold("Signal lost")}")
            appendLine("${TelegramHtml.bold("Time:")} ${TelegramHtml.code(time)}")
            appendLine("${TelegramHtml.bold("Detail:")} ${TelegramHtml.code(snap.detail)}")
        }
        enqueue(text)
    }

    private fun notifyRestored(snap: NetworkMeterSnapshot) {
        val time = now()
        val text = buildString {
            appendLine("📡 ${TelegramHtml.bold("Signal restored")}")
            appendLine("${TelegramHtml.bold("Time:")} ${TelegramHtml.code(time)}")
            appendLine(snap.cellularBlockHtml())
        }
        enqueue(text)
    }

    private fun enqueue(text: String) {
        val chat = chatIdProvider().trim()
        if (chat.isEmpty()) {
            Log.w(TAG, "No TARGET_CHAT_ID; skip signal notify")
            return
        }
        val key = "signal:${System.currentTimeMillis() / 1000}"
        val id = DbHelper(context).enqueueMessage("TELEGRAM", "", text, key)
        Log.d(TAG, "Enqueued signal notify row=$id")
    }

    private fun now(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

    companion object {
        private const val TAG = "SignalWatchdog"
        private const val POLL_MS = 15_000L
        private const val LOST_STREAK = 2      // ~30s without signal
        private const val RESTORE_STREAK = 1   // notify as soon as one good poll after loss
    }
}
