package com.bgwastu.numi

import android.app.Activity
import android.os.Bundle

// Required dummy activity for default SMS role
class SendToActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
    }
}
