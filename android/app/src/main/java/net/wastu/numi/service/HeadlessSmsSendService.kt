package net.wastu.numi.service

import android.app.Service
import android.content.Intent
import android.os.IBinder

// Required headless SMS responder service for default SMS role
class HeadlessSmsSendService : Service() {
    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
