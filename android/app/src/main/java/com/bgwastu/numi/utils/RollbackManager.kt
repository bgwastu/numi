package com.bgwastu.numi.utils

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log

object RollbackManager {

    private const val TELEGRAM_SMS_PKG = "com.qwe7002.telegram_sms"

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
            Log.d("RollbackManager", "Numi already holds the default SMS role")
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

    // Request the user (or automation) to make this app default SMS
    fun requestDefaultSmsRole(context: Context, activity: android.app.Activity, requestCode: Int) {
        val intent = createRequestDefaultSmsIntent(context) ?: return
        activity.startActivityForResult(intent, requestCode)
    }

    // Helper script command templates for ADB rollback
    fun getAdbRollbackCommands(): String {
        return """
            # Run these commands via ADB to manually roll back to telegram-sms:
            adb shell "settings put global sms_default_application $TELEGRAM_SMS_PKG"
            adb shell "cmd role add-role-holder android.app.role.SMS $TELEGRAM_SMS_PKG"
            adb shell "am start -n $TELEGRAM_SMS_PKG/.MainActivity"
            adb shell "am force-stop com.bgwastu.numi"
        """.trimIndent()
    }
}
