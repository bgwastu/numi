package net.wastu.numi.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class MmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        Log.d("NumiMmsReceiver", "MMS received. Intent action: ${intent?.action}")
    }
}
