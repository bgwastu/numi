package net.wastu.numi

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity

/** Retains Numi's original launcher component while entering the complete native inbox. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, org.fossify.messages.activities.SplashActivity::class.java))
        finish()
    }
}
