package net.wastu.numi.utils

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log

object SmsRoleManager {

    fun isDefaultSmsApp(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(RoleManager::class.java)
            if (roleManager.isRoleAvailable(RoleManager.ROLE_SMS)) {
                return roleManager.isRoleHeld(RoleManager.ROLE_SMS)
            }
        }
        val currentDefault = Settings.Secure.getString(context.contentResolver, "sms_default_application")
        return currentDefault == context.packageName
    }

    /** Intent to request the default SMS role, or null if already held / unavailable. */
    fun createRequestDefaultSmsIntent(context: Context): Intent? {
        if (isDefaultSmsApp(context)) {
            Log.d("SmsRoleManager", "Numi already holds the default SMS role")
            return null
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(RoleManager::class.java)
            if (roleManager.isRoleAvailable(RoleManager.ROLE_SMS)) {
                roleManager.createRequestRoleIntent(RoleManager.ROLE_SMS)
            } else {
                null
            }
        } else {
            Intent("android.provider.Telephony.ACTION_CHANGE_DEFAULT").apply {
                putExtra("package", context.packageName)
            }
        }
    }
}
