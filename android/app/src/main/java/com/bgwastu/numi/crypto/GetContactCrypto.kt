package com.bgwastu.numi.crypto

import android.util.Base64
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object GetContactCrypto {

    // AES-256-ECB Decrypt using a hex string key
    fun decryptAes(cipherTextBase64: String, aesKeyHex: String): String {
        val keyBytes = hexToBytes(aesKeyHex)
        val encryptedBytes = Base64.decode(cipherTextBase64, Base64.DEFAULT)
        val secretKey = SecretKeySpec(keyBytes, "AES")
        
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey)
        val decryptedBytes = cipher.doFinal(encryptedBytes)
        return String(decryptedBytes, Charsets.UTF_8)
    }

    // AES-256-ECB Encrypt using a hex string key
    fun encryptAes(plainText: String, aesKeyHex: String): String {
        val keyBytes = hexToBytes(aesKeyHex)
        val secretKey = SecretKeySpec(keyBytes, "AES")
        
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val encryptedBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(encryptedBytes, Base64.NO_WRAP)
    }

    // HMAC-SHA256 signature generation: Base64(HMAC-SHA256(timestamp + "-" + bodyStr))
    fun generateSignature(message: String, hmacKeyHex: String): String {
        val keyBytes = hexToBytes(hmacKeyHex)
        val secretKey = SecretKeySpec(keyBytes, "HmacSHA256")
        
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(secretKey)
        val rawHmac = mac.doFinal(message.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(rawHmac, Base64.NO_WRAP)
    }

    // Helper: Hex string to Byte Array
    private fun hexToBytes(hex: String): ByteArray {
        val result = ByteArray(hex.length / 2)
        for (i in result.indices) {
            val index = i * 2
            val j = hex.substring(index, index + 2).toInt(16)
            result[i] = j.toByte()
        }
        return result
    }
}
