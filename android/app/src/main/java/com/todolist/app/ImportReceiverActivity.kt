package com.todolist.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.concurrent.thread

/**
 * Ephemeral share/import target.
 *
 * This activity is intentionally excluded from Recents and never becomes a user-visible workspace.
 * It copies granted content URIs into To-Do's private note_files directory, queues the payload, then
 * brings the single MainActivity task back to the foreground. This prevents WeChat/QQ share flows
 * from creating a second To-Do card in the system Recents screen.
 */
class ImportReceiverActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        consume(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consume(intent)
    }

    private fun consume(sourceIntent: Intent) {
        val uris = extractUris(sourceIntent)
        if (uris.isEmpty()) {
            finishImport(false)
            return
        }
        val noteId = getSharedPreferences("note_external_import", MODE_PRIVATE).getLong("note_id", -1L)
        thread {
            val payload = JSONArray()
            uris.take(30).forEachIndexed { index, uri ->
                copyIntoPrivateStorage(noteId, uri, index)?.let(payload::put)
            }
            if (payload.length() > 0) {
                getSharedPreferences("note_external_import_queue", MODE_PRIVATE).edit()
                    .putLong("note_id", noteId)
                    .putString("payload", payload.toString())
                    .putLong("received_at", System.currentTimeMillis())
                    .apply()
            }
            runOnUiThread { finishImport(payload.length() > 0) }
        }
    }

    @Suppress("DEPRECATION")
    private fun extractUris(intent: Intent): List<Uri> {
        val out = linkedSetOf<Uri>()
        when (intent.action) {
            Intent.ACTION_SEND -> {
                intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let(out::add)
                intent.data?.let(out::add)
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.forEach(out::add)
            }
            Intent.ACTION_VIEW -> intent.data?.let(out::add)
        }
        intent.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let(out::add)
        }
        return out.filter { it.scheme == "content" || it.scheme == "file" }
    }

    private fun noteFileDir(): File = File(filesDir, "note_files").apply { mkdirs() }

    private fun queryMeta(uri: Uri): Pair<String, Long> {
        var displayName = "附件"
        var size = -1L
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (ni >= 0) displayName = c.getString(ni)?.takeIf { it.isNotBlank() } ?: displayName
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        } catch (_: Exception) {}
        if (displayName == "附件") uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }?.let { displayName = it }
        return displayName to size
    }

    private fun guessMime(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: when (ext) {
            "md", "markdown" -> "text/markdown"
            "json" -> "application/json"
            "csv" -> "text/csv"
            "log" -> "text/plain"
            "apk" -> "application/vnd.android.package-archive"
            else -> "application/octet-stream"
        }
    }

    private fun extension(displayName: String, mime: String): String {
        val ext = displayName.substringAfterLast('.', "").lowercase()
            .takeIf { it.matches(Regex("[a-z0-9]{1,10}")) }
        return ext ?: MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
            ?.lowercase()?.takeIf { it.matches(Regex("[a-z0-9]{1,10}")) } ?: "bin"
    }

    private fun copyIntoPrivateStorage(noteId: Long, uri: Uri, index: Int): JSONObject? {
        return try {
            val (displayName, reportedSize) = queryMeta(uri)
            val rawMime = contentResolver.getType(uri) ?: "application/octet-stream"
            val mime = if (rawMime == "application/octet-stream") guessMime(displayName) else rawMime
            val safeNoteId = noteId.takeIf { it > 0L } ?: 0L
            val fileName = "note_file_${safeNoteId}_${System.currentTimeMillis()}_${index}_${UUID.randomUUID().toString().take(8)}.${extension(displayName, mime)}"
            val dst = File(noteFileDir(), fileName)
            contentResolver.openInputStream(uri)?.use { input ->
                dst.outputStream().buffered().use { output -> input.copyTo(output) }
            } ?: return null
            if (dst.length() <= 0L) { dst.delete(); return null }
            JSONObject()
                .put("name", fileName)
                .put("displayName", displayName)
                .put("mime", mime)
                .put("size", if (reportedSize > 0L) reportedSize else dst.length())
        } catch (_: Exception) {
            null
        }
    }

    private fun finishImport(hasPayload: Boolean) {
        if (hasPayload) {
            try {
                startActivity(
                    Intent(this, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    }
                )
            } catch (_: Exception) {}
        }
        try { finishAndRemoveTask() } catch (_: Exception) { finish() }
    }
}
