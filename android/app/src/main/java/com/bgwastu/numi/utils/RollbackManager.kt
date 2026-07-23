package com.bgwastu.numi.utils

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log

object RollbackManager {

    private const val TELEGRAM_SMS_PKG = "com.qwe7002.telegram_sms"

    // Request the user (or automation) to make this app default SMS
    fun requestDefaultSmsRole(context: Context, activity: android.app.Activity, requestCode: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(RoleManager::class.java)
            if (roleManager.isRoleAvailable(RoleManager.ROLE_SMS)) {
                if (!roleManager.isRoleHeld(RoleManager.ROLE_SMS)) {
                    val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_SMS)
                    activity.startActivityForResult(intent, requestCode)
                } else {
                    Log.d("RollbackManager", "Numi already holds the default SMS role")
                }
            }
        } else {
            val currentDefault = Settings.Secure.getString(context.contentResolver, "sms_default_application")
            if (currentDefault != context.packageName) {
                val intent = Intent(Settings.Action.SUB_INITIAL_STATUS_ROUTING).apply {
                    action = "android.provider.Telephony.ACTION_CHANGE_DEFAULT"
                    putExtra("package", context.packageName)
                }
                activity.startActivity(intent)
            }
        }
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
