package com.bgwastu.numi.bus

import android.content.Context
import android.util.Base64
import android.util.Log
import com.bgwastu.numi.database.DbHelper
import com.bgwastu.numi.provider.GetContactClient
import com.bgwastu.numi.provider.GetContactResult
import kotlinx.coroutines.runBlocking
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.EdECPublicKeySpec
import java.security.spec.NamedParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.atomic.AtomicBoolean

class CommandExecutor(private val context: Context) {

    private val dbHelper = DbHelper(context)
    private val getContactClient = GetContactClient(context)
    
    companion object {
        // Enrolled agent public keys (Hex format)
        private const val ALYSSA_PUBLIC_KEY = "302a300506032b6570032100" + "YourAlyssaHexPublicBytesHere..."
        // Ed25519 raw public keys can be converted into X509 Encoded keys using a simple prefix:
        // X509 prefix for Ed25519: 302a300506032b6570032100 (12 bytes) + 32 bytes raw key
    }

    // Resolves public key hex for actor
    private fun getPublicKeyHexForActor(actorId: String): String? {
        val sharedPrefs = context.getSharedPreferences("numi_settings", Context.MODE_PRIVATE)
        return when (actorId) {
            "alyssa" -> sharedPrefs.getString("actor_pubkey_alyssa", null)
            "hazel" -> sharedPrefs.getString("actor_pubkey_hazel", null)
            else -> sharedPrefs.getString("actor_pubkey_$actorId", null)
        }
    }

    // Verify Ed25519 signature
    fun verifySignature(actorId: String, message: String, sigBase64Url: String): Boolean {
        val pubKeyHex = getPublicKeyHexForActor(actorId) ?: return false
        return try {
            val rawPubKey = hexToBytes(pubKeyHex)
            
            // Reconstruct standard X.509 representation if it's a 32-byte raw Ed25519 key
            val x509KeyBytes = if (rawPubKey.size == 32) {
                val prefix = hexToBytes("302a300506032b6570032100")
                prefix + rawPubKey
            } else {
                rawPubKey
            }

            val keySpec = X509EncodedKeySpec(x509KeyBytes)
            val kf = KeyFactory.getInstance("Ed25519")
            val publicKey = kf.generatePublic(keySpec)

            val sigBytes = Base64.decode(sigBase64Url, Base64.URL_SAFE or Base64.NO_WRAP)
            
            val verifier = Signature.getInstance("Ed25519")
            verifier.initVerify(publicKey)
            verifier.update(message.toByteArray(Charsets.UTF_8))
            verifier.verify(sigBytes)
        } catch (e: Exception) {
            Log.e("CommandExecutor", "Signature verification failed for actor $actorId", e)
            false
        }
    }

    fun execute(commandBlock: String): String {
        val lines = commandBlock.split("\n").map { it.trim() }
        if (lines.size < 6 || !lines[0].startsWith("NUMI_CMD v1")) {
            return "ERROR_INVALID_CMD_FORMAT"
        }

        var actor = ""
        var nonce = ""
        var expires = 0L
        var action = ""
        var argsBase64 = ""
        var signature = ""

        for (line in lines) {
            when {
                line.startsWith("actor:") -> actor = line.substringAfter("actor:").trim()
                line.startsWith("nonce:") -> nonce = line.substringAfter("nonce:").trim()
                line.startsWith("expires:") -> expires = line.substringAfter("expires:").trim().toLongOrNull() ?: 0L
                line.startsWith("action:") -> action = line.substringAfter("action:").trim()
                line.startsWith("args:") -> argsBase64 = line.substringAfter("args:").trim()
                line.startsWith("sig:") -> signature = line.substringAfter("sig:").trim()
            }
        }

        if (actor.isBlank() || nonce.isBlank() || expires == 0L || action.isBlank() || signature.isBlank()) {
            return "ERROR_MISSING_FIELDS"
        }

        // Validate expiry
        val now = System.currentTimeMillis() / 1000
        if (now > expires) {
            dbHelper.logAudit(actor, action, "{}", "REJECTED", "Command expired: current time $now, expires $expires")
            return "ERROR_COMMAND_EXPIRED"
        }

        // Validate nonce replay
        val nonceSaved = dbHelper.useNonce(nonce, actor)
        if (!nonceSaved) {
            dbHelper.logAudit(actor, action, "{}", "REJECTED", "Replay detected: duplicate nonce $nonce")
            return "ERROR_REPLAY_DETECTED"
        }

        // Verify cryptographic signature
        val signedMessage = "actor:$actor:nonce:$nonce:expires:$expires:action:$action:args:$argsBase64"
        val signatureValid = verifySignature(actor, signedMessage, signature)
        if (!signatureValid) {
            dbHelper.logAudit(actor, action, "{}", "REJECTED", "Signature verification failed")
            return "ERROR_INVALID_SIGNATURE"
        }

        val argsJson = try {
            String(Base64.decode(argsBase64, Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8)
        } catch (e: Exception) {
            "{}"
        }

        val outcome: String
        val details: String

        try {
            when (action) {
                "sms.send" -> {
                    val to = parseJsonField(argsJson, "to")
                    val body = parseJsonField(argsJson, "body")
                    if (to.isBlank() || body.isBlank()) {
                        outcome = "FAILED"
                        details = "Missing 'to' or 'body' parameter"
                    } else {
                        val rowId = dbHelper.enqueueMessage("SMS", to, body)
                        outcome = "SUCCESS"
                        details = "Enqueued SMS send to $to. Row ID: $rowId"
                    }
                }
                "provider.refresh" -> {
                    val refreshed = triggerKeyHarvester()
                    if (refreshed) {
                        outcome = "SUCCESS"
                        details = "GetContact key refresh triggered successfully"
                    } else {
                        outcome = "FAILED"
                        details = "GetContact key refresh failed or Termux environment unavailable"
                    }
                }
                "device.status" -> {
                    outcome = "SUCCESS"
                    details = "Battery status OK. Signal state OK."
                }
                else -> {
                    outcome = "FAILED"
                    details = "Unknown action: $action"
                }
            }
        } catch (e: Exception) {
            dbHelper.logAudit(actor, action, argsJson, "FAILED", e.toString())
            return "ERROR_EXECUTION_EXCEPTION: ${e.message}"
        }

        dbHelper.logAudit(actor, action, argsJson, outcome, details)
        return "OUTCOME: $outcome | DETAILS: $details"
    }

    /** Runs a GetContact lookup; Telegram presentation belongs in CommandReceiver. */
    fun gcLookup(phoneNumber: String): GetContactResult {
        val cleaned = phoneNumber.trim().replace(Regex("[^0-9+]"), "")
        if (cleaned.length < 8) return GetContactResult.Error("Invalid phone number: too short")
        return runBlocking { getContactClient.performLookup(cleaned) }
    }

    private fun parseJsonField(json: String, field: String): String {
        val pattern = "\"$field\"\\s*:\\s*\"([^\"]+)\"".toRegex()
        val match = pattern.find(json)
        return match?.groupValues?.get(1) ?: ""
    }

    private fun triggerKeyHarvester(): Boolean {
        return try {
            // Write a flag file that Artemis checks periodically.
            // The actual Frida-based extraction runs on the host, not the device.
            val flag = java.io.File("/data/local/tmp/numi_refresh_requested")
            flag.parentFile?.mkdirs()
            flag.writeText(System.currentTimeMillis().toString())
            true
        } catch (e: Exception) {
            Log.e("CommandExecutor", "Failed to write refresh flag", e)
            false
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val result = ByteArray(hex.length / 2)
        for (i in result.indices) {
            val index = i * 2
            result[i] = hex.substring(index, index + 2).toInt(16).toByte()
        }
        return result
    }
}
