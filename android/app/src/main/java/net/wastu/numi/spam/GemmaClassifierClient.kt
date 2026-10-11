package net.wastu.numi.spam

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class GemmaClassifierClient(context: Context) {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var connected: CompletableDeferred<Messenger>? = null
    private var bound = false
    private var remotePid = 0
    private var failedUntil = 0L
    private var failedKey = ""
    private var lastFailure: SpamDecision? = null
    private val waiting = ConcurrentHashMap<String, CompletableDeferred<SpamDecision>>()
    private val releaseIdle = Runnable { if (waiting.isEmpty()) closeNow() }
    var peakRssKb = 0L
        private set

    private val replies = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            remotePid = message.data.getInt("pid")
            peakRssKb = maxOf(peakRssKb, message.data.getLong("peak_rss_kb"))
            if (message.what != 1) return
            waiting.remove(message.data.getString("id"))?.complete(SpamDecision(
                runCatching { SpamVerdict.valueOf(message.data.getString("verdict").orEmpty()) }.getOrDefault(SpamVerdict.UNKNOWN),
                message.data.getString("reason").orEmpty(), message.data.getString("source").orEmpty()))
        }
    })

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val messenger = Messenger(binder)
            runCatching { messenger.send(Message.obtain(null, 0).apply { replyTo = replies }); connected?.complete(messenger) }
                .onFailure { onServiceDisconnected(name) }
        }
        override fun onServiceDisconnected(name: ComponentName) {
            remotePid = 0
            connected?.completeExceptionally(IllegalStateException("Gemma worker stopped"))
            waiting.values.forEach { it.complete(unavailable("Gemma worker stopped. Messages are waiting for a retry.")) }
            waiting.clear(); connected = null
        }
        override fun onBindingDied(name: ComponentName) = onServiceDisconnected(name)
        override fun onNullBinding(name: ComponentName) = onServiceDisconnected(name)
    }

    suspend fun classify(body: String): SpamDecision {
        if (!GemmaModel.present(context)) return unavailable("Download Gemma to finish checking messages. Local protection rules are active.")
        val prefs = context.getSharedPreferences("numi_settings", Context.MODE_PRIVATE)
        val key = "${GemmaModel.file(context).lastModified()}:${prefs.getLong("spam_model_retry", 0)}"
        lastFailure?.let { if (failedKey == key && SystemClock.elapsedRealtime() < failedUntil) return it }
        val id = UUID.randomUUID().toString()
        try {
            val result = withTimeout(30_000) {
                val future = withContext(Dispatchers.Main) {
                    main.removeCallbacks(releaseIdle)
                    if (connected == null) {
                        if (bound) { context.unbindService(connection); bound = false }
                        connected = CompletableDeferred()
                        // The model is the first process Android can reclaim under
                        // pressure; it never inherits the dialer's importance.
                        bound = context.bindService(Intent(context, GemmaClassifierService::class.java), connection,
                            Context.BIND_AUTO_CREATE or Context.BIND_WAIVE_PRIORITY)
                        check(bound) { "Gemma worker could not start" }
                    }
                    checkNotNull(connected)
                }
                val remote = future.await()
                val reply = CompletableDeferred<SpamDecision>(); waiting[id] = reply
                remote.send(Message.obtain(null, 1).apply {
                    replyTo = replies
                    data = Bundle().apply { putString("id", id); putString("body", body.take(6_000)) }
                })
                reply.await()
            }
            if (result.verdict == SpamVerdict.UNKNOWN) {
                withContext(Dispatchers.Main) { closeNow() }
                return rememberFailure(key, result)
            }
            failedUntil = 0; lastFailure = null
            return result
        } catch (_: TimeoutCancellationException) {
            withContext(Dispatchers.Main) { closeNow() }
            return rememberFailure(key, unavailable("Gemma took too long. Messages are waiting for a retry."))
        } catch (exception: CancellationException) {
            main.post { closeNow() }
            throw exception
        } catch (_: Exception) {
            withContext(Dispatchers.Main) { closeNow() }
            return rememberFailure(key, unavailable("Gemma is unavailable. Messages are waiting for a retry."))
        } finally {
            waiting.remove(id)
            main.post { main.removeCallbacks(releaseIdle); main.postDelayed(releaseIdle, 4_000) }
        }
    }

    private fun rememberFailure(key: String, result: SpamDecision): SpamDecision {
        failedKey = key; failedUntil = SystemClock.elapsedRealtime() + 30_000; lastFailure = result
        return result
    }

    fun close() { main.post { closeNow() } }
    private fun closeNow() {
        main.removeCallbacks(releaseIdle)
        val pid = remotePid.takeIf { it > 0 } ?: if (bound) context.getSystemService(ActivityManager::class.java).runningAppProcesses
            ?.firstOrNull { it.processName == "${context.packageName}:spam" }?.pid ?: 0 else 0
        if (pid > 0 && pid != Process.myPid()) runCatching { Process.killProcess(pid) }
        remotePid = 0
        waiting.values.forEach { it.complete(unavailable("Gemma worker stopped. Messages are waiting for a retry.")) }
        waiting.clear()
        if (bound) runCatching { context.unbindService(connection) }
        bound = false; connected = null
    }

    private fun unavailable(reason: String) = SpamDecision(SpamVerdict.UNKNOWN, reason, "unavailable")
}
