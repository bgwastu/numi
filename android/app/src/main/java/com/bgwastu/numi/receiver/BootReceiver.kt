package com.bgwastu.numi.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.bgwastu.numi.queue.OutboxService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.i("BootReceiver", "Boot completed — starting Numi")
            try {
                val serviceIntent = Intent(context, OutboxService::class.java)
                context.startForegroundService(serviceIntent)
            } catch (e: Exception) {
                Log.e("BootReceiver", "Failed to start service", e)
            }
        }
    }
}
