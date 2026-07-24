package com.bgwastu.numi.util

import android.util.Log

/** Checks whether root access (via KernelSU / Magisk su) is available to this app. */
object RootChecker {

    private val SU_PATHS = arrayOf("/system/bin/su", "/system/xbin/su", "su")

    /** True if `su -c id` returns uid 0 (root). */
    fun isRootAvailable(): Boolean {
        for (suPath in SU_PATHS) {
            try {
                val process = Runtime.getRuntime().exec(arrayOf(suPath, "-c", "id"))
                val out = process.inputStream.bufferedReader().readText()
                val exitCode = process.waitFor()
                if ((exitCode == 0 || out.isNotBlank()) && out.contains("uid=0")) {
                    return true
                }
            } catch (e: Exception) {
                // Try next path
            }
        }
        return false
    }
}
