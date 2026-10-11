package net.wastu.numi.spam

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import java.io.File
import java.security.MessageDigest

object GemmaModel {
    const val FILE_NAME = "embeddinggemma-2-text-270m.litertlm"
    const val SIZE = 164_626_432L
    const val SHA256 = "2d079ee2f6f066b1f368e8d7c819f55214eaef1d0513b312321901f30ab286fb"
    const val DOWNLOAD_ID = "GEMMA_SPAM_DOWNLOAD_ID"
    const val URL = "https://huggingface.co/litert-community/embeddinggemma-2-text-270m-litert-lm/resolve/9be6e8b90982095dc05c2bd162e4b954ee4dbac7/$FILE_NAME"

    fun file(context: Context) = File(context.filesDir, "gemma/$FILE_NAME")
    private fun staging(context: Context) = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "$FILE_NAME.download")
    private fun prefs(context: Context) = context.getSharedPreferences("numi_settings", Context.MODE_PRIVATE)

    fun present(context: Context) = file(context).let { it.isFile && it.length() == SIZE }

    fun checksum(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    @Synchronized fun verify(context: Context): File {
        val model = file(context)
        check(present(context)) { "Download Gemma to finish checking messages. Local protection rules are active." }
        val signature = "$SHA256:${model.lastModified()}:${model.length()}"
        val marker = File(context.filesDir, "gemma/verified")
        if (!marker.isFile || marker.readText() != signature) {
            check(checksum(model) == SHA256) { "Gemma download is damaged. Download it again." }
            val temporary = File(marker.parentFile, "verified.tmp")
            temporary.writeText(signature); check(temporary.renameTo(marker))
        }
        return model
    }

    fun download(context: Context) {
        val manager = context.getSystemService(DownloadManager::class.java)
        val previous = prefs(context).getLong(DOWNLOAD_ID, -1)
        if (previous > 0) manager.remove(previous)
        staging(context).delete()
        val id = manager.enqueue(DownloadManager.Request(Uri.parse(URL))
            .setTitle("Numi Gemma spam filter").setDescription("165 MB · Wi-Fi download · runs on this phone")
            .setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, staging(context).name))
        prefs(context).edit().putLong(DOWNLOAD_ID, id).remove("spam_classifier_error").apply()
    }

    /** Never replace a working model until every byte of the new one is verified. */
    @Synchronized fun finishDownload(context: Context): Boolean {
        val id = prefs(context).getLong(DOWNLOAD_ID, -1)
        if (id <= 0) return false
        val manager = context.getSystemService(DownloadManager::class.java)
        val successful = manager.query(DownloadManager.Query().setFilterById(id))?.use { c ->
            c.moveToFirst() && c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) == DownloadManager.STATUS_SUCCESSFUL
        } == true
        if (!successful) return false
        val partial = staging(context)
        check(partial.length() == SIZE && checksum(partial) == SHA256) { "Gemma download is damaged. Download it again." }
        val target = file(context); check(target.parentFile!!.mkdirs() || target.parentFile!!.isDirectory)
        val temporary = File(target.parentFile, "$FILE_NAME.tmp")
        partial.copyTo(temporary, overwrite = true)
        check(temporary.renameTo(target))
        partial.delete(); manager.remove(id)
        prefs(context).edit().remove(DOWNLOAD_ID).remove("spam_classifier_error").apply()
        return true
    }

    fun downloadStatus(context: Context): String? {
        val id = prefs(context).getLong(DOWNLOAD_ID, -1)
        if (id <= 0) return null
        return context.getSystemService(DownloadManager::class.java).query(DownloadManager.Query().setFilterById(id))?.use { c ->
            if (!c.moveToFirst()) return@use "Gemma download needs a retry."
            val state = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val bytes = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            when (state) {
                DownloadManager.STATUS_FAILED -> "Gemma download failed. Retry on Wi-Fi."
                DownloadManager.STATUS_SUCCESSFUL -> "Verifying the Gemma download…"
                else -> "Downloading Gemma: ${(bytes * 100 / SIZE).coerceIn(0, 100)}%. Waiting for Wi-Fi if needed."
            }
        }
    }
}
