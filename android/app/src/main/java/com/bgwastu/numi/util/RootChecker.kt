package com.bgwastu.numi.util

import android.util.Log

/** Checks whether root access (via KernelSU / Magisk su) is available to this app. */
object RootChecker {

    /** True if `su -c id` returns uid 0 (root). */
    fun isRootAvailable(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val out = process.inputStream.bufferedReader().readText()
            process.waitFor()
            out.contains("uid=0")
        } catch (e: Exception) {
            Log.e("RootChecker", "Root check failed", e)
            false
        }
    }
}
