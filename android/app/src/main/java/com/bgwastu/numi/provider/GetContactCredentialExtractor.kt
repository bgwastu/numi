package com.bgwastu.numi.provider

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * Extracts GetContact credentials directly from the GetContact app's data directory
 * using root access.  AES + HMAC keys are stable across app versions; the token and
 * device ID are read fresh from DataStore / SharedPreferences.
 */
object GetContactCredentialExtractor {

    private const val GC_PKG = "app.source.getcontact"
    private const val GC_DATA = "/data/data/$GC_PKG"

    // Stable cryptographic keys (extracted once via Frida, validated across versions).
    private const val AES_KEY = "0000000000000000000000000000000000000000000000000000000000000000"
    private const val HMAC_KEY = "00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000"

    /**
     * Tries to extract fresh credentials. Returns null if extraction fails.
     * Requires root (su) on the device.
     */
    fun extract(): GetContactCredentials? {
        return try {
            val deviceId = extractDeviceId() ?: run {
                Log.w("GCExtractor", "Could not extract device ID")
                return null
            }
            val token = extractToken() ?: run {
                Log.w("GCExtractor", "Could not extract token from DataStore")
                return null
            }
            GetContactCredentials(
                aes_key = AES_KEY,
                hmac_key = HMAC_KEY,
                device_token = token,
                device_id = deviceId,
                updated_at = System.currentTimeMillis()
            )
        } catch (e: Exception) {
            Log.e("GCExtractor", "Extraction failed", e)
            null
        }
    }

    /** Persists extracted credentials to the file Numi reads at startup. */
    fun extractAndSave(context: Context): Boolean {
        val creds = extract() ?: return false
        val file = File(context.filesDir, "providers")
        file.mkdirs()
        val json = JSONObject().apply {
            put("aes_key", creds.aes_key)
            put("hmac_key", creds.hmac_key)
            put("device_token", creds.device_token)
            put("device_id", creds.device_id)
            put("updated_at", creds.updated_at)
        }
        File(file, "getcontact.json").writeText(json.toString())
        Log.i("GCExtractor", "Credentials extracted and saved")
        return true
    }

    /** Checks whether the GetContact app is installed. */
    fun isGetContactInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(GC_PKG, 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Reads the device ID from SharedPreferences XML. */
    private fun extractDeviceId(): String? {
        val xml = runRoot("cat $GC_DATA/shared_prefs/GetContactSettingsPref.xml")
        val match = Regex("""<string name="PREF_KEY_FOR_UNIQ_ID">([^<]+)</string>""").find(xml)
        return match?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
    }

    /** Reads the API token from the Jetpack DataStore protobuf file. */
    private fun extractToken(): String? {
        // The token is around 49 chars, alphanumeric with mixed case.
        val strings = runRoot("strings $GC_DATA/files/datastore/tokens.preferences_pb")
        return strings.lines()
            .map { it.trim() }
            .firstOrNull { it.matches(Regex("^[A-Za-z0-9+/=]{40,60}$")) }
            ?.takeIf { it.isNotBlank() }
    }

    private val SU_PATHS = arrayOf("/system/bin/su", "/system/xbin/su", "su")

    /** Executes a root shell command in the global mount namespace (init's namespace) and returns stdout. */
    private fun runRoot(cmd: String): String {
        val nsenterCmd = "nsenter -t 1 -m $cmd"
        for (suPath in SU_PATHS) {
            try {
                val process = Runtime.getRuntime().exec(arrayOf(suPath, "-c", nsenterCmd))
                process.outputStream.close()
                val out = process.inputStream.bufferedReader().readText()
                val err = process.errorStream.bufferedReader().readText()
                val exitCode = process.waitFor()
                if (exitCode == 0) {
                    return out
                } else {
                    Log.w("GCExtractor", "Command ($suPath -c '$nsenterCmd') returned exit $exitCode. Stderr: $err. Stdout: $out")
                }
            } catch (e: Exception) {
                Log.w("GCExtractor", "Failed to exec $suPath -c '$nsenterCmd'", e)
            }
        }
        Log.e("GCExtractor", "Root command failed for all SU paths: $nsenterCmd")
        return ""
    }
}
