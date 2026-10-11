package net.wastu.numi.spam

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import net.wastu.numi.queue.OutboxService
import java.util.concurrent.Executors

class GemmaDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val id = context.getSharedPreferences("numi_settings", Context.MODE_PRIVATE).getLong(GemmaModel.DOWNLOAD_ID, -1)
        if (id <= 0 || intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -2) != id) return
        val pending = goAsync(); val worker = Executors.newSingleThreadExecutor()
        worker.execute {
            try { GemmaSpamFilter.prepare(context); SpamStore(context).retryUnavailable(); OutboxService.wake(context) }
            catch (_: Exception) {
                context.getSharedPreferences("numi_settings", Context.MODE_PRIVATE).edit()
                    .putString("spam_classifier_error", "Gemma download needs a retry. Open the Spam folder.").apply()
            } finally { pending.finish(); worker.shutdown() }
        }
    }
}
