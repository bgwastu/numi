package net.wastu.numi.util

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
                val err = process.errorStream.bufferedReader().readText()
                val exitCode = process.waitFor()
                if (exitCode == 0 && out.contains("uid=0")) {
                    return true
                } else {
                    Log.w("RootChecker", "suPath ($suPath) failed: exit=$exitCode, out=$out, err=$err")
                }
            } catch (e: Exception) {
                Log.w("RootChecker", "Failed to exec suPath ($suPath)", e)
            }
        }
        return false
    }
}
