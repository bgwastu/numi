package net.wastu.numi.spam

import android.app.ActivityManager
import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EmbeddingEngine
import com.google.ai.edge.litertlm.EmbeddingEngineConfig
import com.google.ai.edge.litertlm.EmbeddingOptions
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.InputData
import com.google.ai.edge.litertlm.LogSeverity
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Native crashes, deadlines and memory limits are confined to this process. */
class GemmaClassifierService : Service() {
    private val worker = Executors.newSingleThreadExecutor()
    private val guard = Executors.newSingleThreadScheduledExecutor()
    private val clients = ConcurrentHashMap<String, Messenger>()
    private val peakRss = AtomicLong(0)
    private var engine: EmbeddingEngine? = null
    private var head: GemmaSpamHead? = null

    override fun onCreate() {
        super.onCreate()
        Engine.setNativeMinLogSeverity(LogSeverity.ERROR)
        guard.scheduleWithFixedDelay({
            val rss = kilobytes("/proc/self/status", "VmRSS:")
            val available = kilobytes("/proc/meminfo", "MemAvailable:")
            peakRss.accumulateAndGet(rss, ::maxOf)
            if (rss > 512 * 1024 || available in 1 until 256 * 1024) {
                clients.forEach { (id, target) -> reply(target, id, SpamDecision(SpamVerdict.UNKNOWN,
                    "Gemma paused to protect phone memory. Messages are waiting for a retry.", "unavailable")) }
                Process.killProcess(Process.myPid())
            }
        }, 50, 50, TimeUnit.MILLISECONDS)
    }

    private val messenger = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            when (message.what) {
                0 -> reply(message.replyTo, "", null)
                1 -> {
                    val target = message.replyTo ?: return
                    val id = message.data.getString("id").orEmpty()
                    val body = message.data.getString("body").orEmpty()
                    clients[id] = target
                    worker.execute {
                        val result = try {
                            if (engine == null) {
                                val memory = ActivityManager.MemoryInfo()
                                getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
                                check(memory.availMem >= 512L * 1024 * 1024 && !memory.lowMemory) {
                                    "Gemma is waiting for free memory. Local protection rules are active."
                                }
                                head = GemmaSpamFilter.loadHead(this@GemmaClassifierService)
                                val created = EmbeddingEngine(EmbeddingEngineConfig(
                                    modelPath = GemmaModel.verify(this@GemmaClassifierService).absolutePath,
                                    backend = Backend.CPU(threadCount = 2), maxInputLength = 128,
                                    cacheDir = cacheDir.resolve("embedding-gemma-spam").apply { mkdirs() }.absolutePath))
                                try { created.initialize(); engine = created }
                                catch (exception: Exception) { runCatching { created.close() }; throw exception }
                            }
                            val active = checkNotNull(engine)
                            val vectors = GemmaTextInput.chunks(body).map { chunk ->
                                active.computeEmbedding(listOf(InputData.Text(chunk)), EmbeddingOptions(normalize = true, outputSize = 768)).embedding
                            }
                            checkNotNull(head).classify(GemmaTextInput.pool(vectors))
                        } catch (exception: Exception) {
                            SpamDecision(SpamVerdict.UNKNOWN, if (exception is IllegalStateException) exception.message.orEmpty().take(160)
                                else "Gemma could not check this message (${exception.javaClass.simpleName}). Waiting to retry.", "unavailable")
                        }
                        reply(target, id, result)
                        clients.remove(id)
                    }
                }
                2 -> Process.killProcess(Process.myPid())
            }
        }
    })

    private fun kilobytes(path: String, key: String): Long = runCatching {
        File(path).useLines { lines -> lines.firstOrNull { it.startsWith(key) }?.split(Regex("\\s+"))?.getOrNull(1)?.toLongOrNull() ?: 0 }
    }.getOrDefault(0)

    private fun reply(target: Messenger?, id: String, result: SpamDecision?) {
        runCatching { target?.send(Message.obtain(null, if (result == null) 0 else 1).apply {
            data = Bundle().apply {
                putString("id", id); putInt("pid", Process.myPid()); putLong("peak_rss_kb", peakRss.get())
                result?.let { putString("verdict", it.verdict.name); putString("reason", it.reason); putString("source", it.source) }
            }
        }) }
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder
    override fun onDestroy() {
        guard.shutdownNow(); worker.shutdownNow()
        // Closing a native encoder can block. The parent terminates this worker
        // after idle use; it never waits for close on the inbox's main thread.
        super.onDestroy()
    }
}
