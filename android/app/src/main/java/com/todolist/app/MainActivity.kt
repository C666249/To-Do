package com.todolist.app

import android.Manifest
import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ClipData
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.provider.OpenableColumns
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import android.view.inputmethod.InputMethodManager
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.todolist.app.manager.FloatWindowManager
import com.todolist.app.receiver.MidnightReceiver
import com.todolist.app.reminder.DailyTaskStore
import com.todolist.app.reminder.ReminderDiagnostics
import com.todolist.app.reminder.ReminderNotifier
import com.todolist.app.reminder.ReminderScheduler
import com.todolist.app.reminder.TodoReminderRecord
import com.todolist.app.reminder.ReminderStore
import com.todolist.app.service.FloatWindowService
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.util.UUID

class MainActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FOCUS_TODO_ID = "focus_todo_id"
        const val EXTRA_FOCUS_DAILY_TASK_ID = "focus_daily_task_id"
        private const val REQ_NOTIFICATIONS = 901
    }

    private lateinit var webView: WebView
    private var pageReady = false
    private var backDispatchInFlight = false
    private var pendingFocusTodoId: Long = -1L
    private var pendingFocusDailyTaskId: Long = -1L
    private var pendingNoteImageNoteId: Long = -1L
    private var pendingNoteFileNoteId: Long = -1L
    private var pendingExternalNotePayload: Pair<Long, String>? = null
    private var externalImportLaunched = false
    private var externalImportWentBackground = false
    private var externalImportShareHandled = false

    private val noteImagePicker = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(20)
    ) { uris ->
        handlePickedNoteImages(uris)
    }

    private val noteFilePicker = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        handlePickedNoteFiles(uris)
    }

    private var permissionFlowActive = false
    private var notificationAttempted = false
    private var overlayAttempted = false
    private var exactAlarmAttempted = false

    private val nativeMutationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == FloatWindowService.ACTION_NATIVE_TODO_MUTATED) {
                pullNativeMutationsIntoWeb()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingFocusTodoId = intent.getLongExtra(EXTRA_FOCUS_TODO_ID, -1L)
        consumeDailyFocusIntent(intent)

        webView = WebView(this).apply {
            settings.apply {
                javaScriptEnabled = true
                allowFileAccess = true
                domStorageEnabled = true
                loadWithOverviewMode = true
                useWideViewPort = true
                setSupportZoom(false)
                builtInZoomControls = false
            }

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    val uri = request?.url
                    if (uri?.scheme == "https" && uri.host == "note.local" && uri.path?.startsWith("/image/") == true) {
                        val fileName = uri.lastPathSegment?.takeIf { isSafeNoteImageName(it) } ?: return emptyImageResponse()
                        val file = File(noteImageDir(), fileName)
                        if (!file.exists() || !file.isFile) return emptyImageResponse()
                        val mime = guessImageMime(fileName)
                        return try {
                            WebResourceResponse(mime, null, FileInputStream(file))
                        } catch (_: Exception) {
                            emptyImageResponse()
                        }
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    pageReady = true
                    ViewCompat.requestApplyInsets(webView)
                    pullNativeMutationsIntoWeb()
                    deliverPendingFocus()
                    deliverPendingDailyFocus()
                    consumeExternalImportQueue()
                    deliverPendingExternalNoteFiles()
                }
            }
            webChromeClient = WebChromeClient()
            addJavascriptInterface(AndroidBridge(), "AndroidBridge")
            loadUrl("file:///android_asset/todo.html")
        }

        setContentView(webView)
        installImeInsetsBridge()
        registerNativeMutationReceiver()

        try {
            ReminderScheduler.rescheduleAll(this, restoreFiredOverdue = true)
            if (ReminderScheduler.hasArmedWork(this)) FloatWindowService.ensureKeeperRunning(this)
        } catch (_: Exception) {}
        consumeExternalImportQueue()
    }

    override fun onResume() {
        super.onResume()
        try {
            ReminderScheduler.rescheduleAll(this, restoreFiredOverdue = false)
            if (ReminderScheduler.hasArmedWork(this)) FloatWindowService.refreshKeeper(this)
        } catch (_: Exception) {}
        if (permissionFlowActive) {
            webView.postDelayed({ continueReminderPermissionFlow() }, 250)
        }
        pullNativeMutationsIntoWeb()
        deliverPendingFocus()
        deliverPendingDailyFocus()
        consumeExternalImportQueue()
        deliverPendingExternalNoteFiles()
        if (externalImportLaunched && externalImportWentBackground && !externalImportShareHandled) {
            webView.postDelayed({
                if (externalImportLaunched && !externalImportShareHandled) {
                    val noteId = externalImportPrefs().getLong("note_id", -1L)
                    clearExternalImportState()
                    if (noteId > 0L && pageReady) {
                        webView.evaluateJavascript(
                            "window.__onExternalNoteImportCancelled && window.__onExternalNoteImportCancelled($noteId);",
                            null
                        )
                    }
                }
            }, 650)
        }
    }

    override fun onPause() {
        if (externalImportLaunched) externalImportWentBackground = true
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val id = intent.getLongExtra(EXTRA_FOCUS_TODO_ID, -1L)
        if (id > 0L) pendingFocusTodoId = id
        consumeDailyFocusIntent(intent)
        consumeExternalImportQueue()
        pullNativeMutationsIntoWeb()
        deliverPendingFocus()
        deliverPendingDailyFocus()
    }

    override fun onDestroy() {
        try { unregisterReceiver(nativeMutationReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!pageReady) {
            if (webView.canGoBack()) webView.goBack() else finish()
            return
        }
        if (backDispatchInFlight) return

        backDispatchInFlight = true
        webView.evaluateJavascript(
            "(function(){return !!(window.__handleSystemBack && window.__handleSystemBack());})()"
        ) { raw ->
            backDispatchInFlight = false
            val handledByWeb = raw.trim('"') == "true"
            if (!handledByWeb) {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIFICATIONS && permissionFlowActive) {
            webView.postDelayed({ continueReminderPermissionFlow() }, 150)
        }
    }

    private fun installImeInsetsBridge() {
        ViewCompat.setOnApplyWindowInsetsListener(webView) { _, insets ->
            val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            val imeBottom = if (imeVisible) insets.getInsets(WindowInsetsCompat.Type.ime()).bottom else 0
            if (pageReady) {
                webView.post {
                    webView.evaluateJavascript(
                        "window.__onNativeImeInset && window.__onNativeImeInset(${imeBottom},${if (imeVisible) "true" else "false"});",
                        null
                    )
                }
            }
            insets
        }
        ViewCompat.requestApplyInsets(webView)
    }

    private fun hideSoftKeyboard() {
        try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(webView.windowToken, 0)
        } catch (_: Exception) {}
    }

    private fun registerNativeMutationReceiver() {
        val filter = IntentFilter(FloatWindowService.ACTION_NATIVE_TODO_MUTATED)
        ContextCompat.registerReceiver(
            this,
            nativeMutationReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private fun beginReminderPermissionFlow() {
        if (permissionFlowActive) return
        permissionFlowActive = true
        notificationAttempted = false
        overlayAttempted = false
        exactAlarmAttempted = false
        continueReminderPermissionFlow()
    }

    private fun continueReminderPermissionFlow() {
        if (!permissionFlowActive || isFinishing) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !notificationAttempted
        ) {
            notificationAttempted = true
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQ_NOTIFICATIONS
            )
            return
        }

        if (!Settings.canDrawOverlays(this) && !overlayAttempted) {
            overlayAttempted = true
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
                return
            } catch (_: Exception) {}
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !ReminderScheduler.canScheduleExact(this) &&
            !exactAlarmAttempted
        ) {
            exactAlarmAttempted = true
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                        Uri.parse("package:$packageName")
                    )
                )
                return
            } catch (_: Exception) {}
        }

        permissionFlowActive = false
        try { ReminderScheduler.rescheduleAll(this, restoreFiredOverdue = true) } catch (_: Exception) {}
        notifyWebPermissionState()
    }

    private fun reminderPermissionState(): String {
        val parts = mutableListOf<String>()
        if (!Settings.canDrawOverlays(this)) parts += "overlay"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !ReminderScheduler.canScheduleExact(this)) {
            parts += "exact_alarm"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            parts += "notification"
        }
        return if (parts.isEmpty()) "ok" else parts.joinToString(",")
    }

    private fun notifyWebPermissionState() {
        if (!pageReady) return
        val state = reminderPermissionState().replace("'", "\\'")
        webView.post {
            webView.evaluateJavascript(
                "window.__onNativeReminderPermissionState && window.__onNativeReminderPermissionState('$state');",
                null
            )
        }
    }

    private fun pullNativeMutationsIntoWeb() {
        if (!pageReady) return
        webView.post {
            webView.evaluateJavascript(
                "window.__pullNativeTodoMutations && window.__pullNativeTodoMutations(); window.__pullNativeDailyTaskMutations && window.__pullNativeDailyTaskMutations();",
                null
            )
        }
    }

    private fun deliverPendingFocus() {
        if (!pageReady || pendingFocusTodoId <= 0L) return
        val id = pendingFocusTodoId
        pendingFocusTodoId = -1L
        webView.post {
            webView.evaluateJavascript(
                "window.__focusTodoFromNative && window.__focusTodoFromNative($id);",
                null
            )
        }
    }

    private fun consumeDailyFocusIntent(intent: Intent) {
        val id = intent.getLongExtra(EXTRA_FOCUS_DAILY_TASK_ID, -1L)
        if (id <= 0L) return
        pendingFocusDailyTaskId = id
        // AlarmClockInfo's "show alarm" intent is only an informational shortcut. It must not
        // count as ignoring today's Daily item before the reminder has actually fired.
        if (intent.action == "com.listnote.app.action.OPEN_REMINDER_INFO") return
        try {
            DailyTaskStore.markDismissed(this, id)
            ReminderNotifier.cancelDailyTask(this, id)
            DailyTaskStore.get(this, id)?.let { ReminderScheduler.scheduleDailyTask(this, it) }
        } catch (_: Exception) {}
    }

    private fun deliverPendingDailyFocus() {
        if (!pageReady || pendingFocusDailyTaskId <= 0L) return
        val id = pendingFocusDailyTaskId
        pendingFocusDailyTaskId = -1L
        webView.post {
            webView.evaluateJavascript(
                "window.__focusDailyFromNative && window.__focusDailyFromNative($id);",
                null
            )
        }
    }

    private fun noteImageDir(): File = File(filesDir, "note_images").apply { mkdirs() }

    private fun isSafeNoteImageName(name: String): Boolean =
        name.matches(Regex("[A-Za-z0-9._-]{1,180}")) && !name.contains("..")

    private fun guessImageMime(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "image/*"
    }

    private fun emptyImageResponse(): WebResourceResponse =
        WebResourceResponse("image/png", null, java.io.ByteArrayInputStream(ByteArray(0)))

    private fun copyPickedImage(noteId: Long, source: Uri, index: Int): JSONObject? {
        return try {
            val resolver = contentResolver
            val mime = resolver.getType(source) ?: "image/jpeg"
            val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
                ?.lowercase()
                ?.takeIf { it.matches(Regex("[a-z0-9]{2,8}")) }
                ?: "jpg"
            val fileName = "note_${noteId}_${System.currentTimeMillis()}_${index}_${UUID.randomUUID().toString().take(8)}.$extension"
            val destination = File(noteImageDir(), fileName)
            resolver.openInputStream(source)?.use { input ->
                destination.outputStream().buffered().use { output -> input.copyTo(output) }
            } ?: return null
            if (destination.length() <= 0L) {
                destination.delete()
                return null
            }
            JSONObject()
                .put("name", fileName)
                .put("url", "https://note.local/image/$fileName")
        } catch (_: Exception) {
            null
        }
    }

    private fun handlePickedNoteImages(uris: List<Uri>) {
        val noteId = pendingNoteImageNoteId
        pendingNoteImageNoteId = -1L
        // Copying several screenshots can be expensive; keep it off the UI thread.
        Thread {
            val payload = JSONArray()
            if (noteId > 0L) {
                uris.take(20).forEachIndexed { index, uri ->
                    copyPickedImage(noteId, uri, index)?.let { payload.put(it) }
                }
            }
            if (!pageReady) return@Thread
            val json = payload.toString()
            webView.post {
                webView.evaluateJavascript(
                    "window.__onNoteImagesPicked && window.__onNoteImagesPicked($json);",
                    null
                )
            }
        }.start()
    }

    private fun noteFileDir(): File = File(filesDir, "note_files").apply { mkdirs() }

    private fun isSafeNoteFileName(name: String): Boolean =
        name.matches(Regex("[A-Za-z0-9._-]{1,180}")) && !name.contains("..")

    private fun queryDocumentMeta(uri: Uri): Pair<String, Long> {
        var displayName = "附件"
        var size = -1L
        try {
            contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex >= 0) displayName = cursor.getString(nameIndex)?.takeIf { it.isNotBlank() } ?: displayName
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                }
            }
        } catch (_: Exception) {}
        return displayName to size
    }

    private fun safeDocumentExtension(displayName: String, mime: String): String {
        val fromName = displayName.substringAfterLast('.', "")
            .lowercase()
            .takeIf { it.matches(Regex("[a-z0-9]{1,10}")) }
        if (fromName != null) return fromName
        return MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
            ?.lowercase()
            ?.takeIf { it.matches(Regex("[a-z0-9]{1,10}")) }
            ?: "bin"
    }

    private fun guessNoteFileMime(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: when (ext) {
            "md", "markdown" -> "text/markdown"
            "json" -> "application/json"
            "csv" -> "text/csv"
            "log" -> "text/plain"
            else -> "application/octet-stream"
        }
    }

    private fun copyPickedNoteFile(noteId: Long, source: Uri, index: Int): JSONObject? {
        return try {
            val resolver = contentResolver
            val rawMime = resolver.getType(source) ?: "application/octet-stream"
            val (displayName, reportedSize) = queryDocumentMeta(source)
            val mime = if (rawMime == "application/octet-stream") guessNoteFileMime(displayName) else rawMime
            val extension = safeDocumentExtension(displayName, mime)
            val fileName = "note_file_${noteId}_${System.currentTimeMillis()}_${index}_${UUID.randomUUID().toString().take(8)}.$extension"
            val destination = File(noteFileDir(), fileName)
            resolver.openInputStream(source)?.use { input ->
                destination.outputStream().buffered().use { output -> input.copyTo(output) }
            } ?: return null
            if (destination.length() <= 0L) {
                destination.delete()
                return null
            }
            JSONObject()
                .put("name", fileName)
                .put("displayName", displayName)
                .put("mime", mime)
                .put("size", if (reportedSize > 0L) reportedSize else destination.length())
        } catch (_: Exception) {
            null
        }
    }

    private fun handlePickedNoteFiles(uris: List<Uri>) {
        val noteId = pendingNoteFileNoteId
        pendingNoteFileNoteId = -1L
        Thread {
            val payload = JSONArray()
            if (noteId > 0L) {
                uris.take(30).forEachIndexed { index, uri ->
                    copyPickedNoteFile(noteId, uri, index)?.let { payload.put(it) }
                }
            }
            if (!pageReady) return@Thread
            val json = payload.toString()
            webView.post {
                webView.evaluateJavascript(
                    "window.__onNoteFilesPicked && window.__onNoteFilesPicked($json);",
                    null
                )
            }
        }.start()
    }

    private fun getNoteFile(fileName: String): File? {
        if (!isSafeNoteFileName(fileName)) return null
        val file = File(noteFileDir(), fileName)
        return file.takeIf { it.exists() && it.isFile }
    }

    private fun getNoteFileUri(file: File): Uri =
        FileProvider.getUriForFile(this, "$packageName.fileprovider", file)

    private fun buildNoteFileShareIntent(file: File): Intent {
        val uri = getNoteFileUri(file)
        return Intent(Intent.ACTION_SEND).apply {
            type = guessNoteFileMime(file.name)
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("note_attachment", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun resolvePreferredSharePackage(intent: Intent, target: String): String? {
        val directPackage = when (target) {
            "wechat" -> "com.tencent.mm"
            "qq" -> "com.tencent.mobileqq"
            else -> null
        }
        if (directPackage != null) {
            val probe = Intent(intent).setPackage(directPackage)
            if (probe.resolveActivity(packageManager) != null) return directPackage
        }
        val candidates = try { packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY) } catch (_: Exception) { emptyList() }
        return candidates.firstOrNull { info ->
            val label = try { info.loadLabel(packageManager).toString() } catch (_: Exception) { "" }
            when (target) {
                "wechat" -> label.contains("微信", ignoreCase = true) || label.contains("WeChat", ignoreCase = true)
                "qq" -> label.equals("QQ", ignoreCase = true) || label.contains("QQ", ignoreCase = true)
                else -> false
            }
        }?.activityInfo?.packageName
    }

    private fun externalImportPrefs() = getSharedPreferences("note_external_import", Context.MODE_PRIVATE)
    private fun externalImportQueuePrefs() = getSharedPreferences("note_external_import_queue", Context.MODE_PRIVATE)

    private fun consumeExternalImportQueue() {
        val prefs = externalImportQueuePrefs()
        val payload = prefs.getString("payload", null) ?: return
        val noteId = prefs.getLong("note_id", -1L)
        prefs.edit().clear().apply()
        externalImportShareHandled = true
        externalImportLaunched = false
        externalImportWentBackground = false
        externalImportPrefs().edit().clear().apply()
        pendingExternalNotePayload = noteId to payload
        deliverPendingExternalNoteFiles()
    }

    private fun clearExternalImportState() {
        externalImportLaunched = false
        externalImportWentBackground = false
        externalImportShareHandled = false
        externalImportPrefs().edit().clear().apply()
    }

    private fun pendingExternalNoteId(): Long = externalImportPrefs().getLong("note_id", -1L)

    @Suppress("DEPRECATION")
    private fun extractIncomingNoteUris(intent: Intent): List<Uri> {
        val result = linkedSetOf<Uri>()
        when (intent.action) {
            Intent.ACTION_SEND -> {
                intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { result += it }
                intent.data?.let { result += it }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.forEach { result += it }
            }
            Intent.ACTION_VIEW -> intent.data?.let { result += it }
        }
        intent.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { result += it }
        }
        return result.filter { it.scheme == "content" || it.scheme == "file" }
    }

    private fun handleIncomingNoteShareIntent(intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE && action != Intent.ACTION_VIEW) return
        val uris = extractIncomingNoteUris(intent)
        if (uris.isEmpty()) return
        val noteId = pendingExternalNoteId().takeIf { it > 0L } ?: return
        externalImportShareHandled = true
        externalImportLaunched = false
        externalImportWentBackground = false
        externalImportPrefs().edit().clear().apply()
        Thread {
            val payload = JSONArray()
            uris.take(30).forEachIndexed { index, uri ->
                copyPickedNoteFile(noteId, uri, index)?.let { payload.put(it) }
            }
            val json = payload.toString()
            pendingExternalNotePayload = noteId to json
            deliverPendingExternalNoteFiles()
        }.start()
    }

    private fun deliverPendingExternalNoteFiles() {
        if (!pageReady) return
        val pending = pendingExternalNotePayload ?: return
        pendingExternalNotePayload = null
        val noteId = pending.first
        val json = pending.second
        webView.post {
            webView.evaluateJavascript(
                "window.__onExternalNoteFilesPicked && window.__onExternalNoteFilesPicked($noteId,$json);",
                null
            )
        }
    }

    private fun launchExternalImportTarget(noteId: Long, target: String): String {
        val packageId = when (target) {
            "wechat" -> "com.tencent.mm"
            "qq" -> "com.tencent.mobileqq"
            else -> return "invalid"
        }
        val launch = packageManager.getLaunchIntentForPackage(packageId) ?: return "unavailable"
        externalImportPrefs().edit()
            .putLong("note_id", noteId)
            .putString("target", target)
            .putLong("started_at", System.currentTimeMillis())
            .apply()
        externalImportLaunched = true
        externalImportWentBackground = false
        externalImportShareHandled = false
        runOnUiThread {
            try {
                Toast.makeText(
                    this,
                    if (target == "wechat") "选好微信文件后，分享给「添加到 To-Do」" else "选好 QQ 文件后，分享给「添加到 To-Do」",
                    Toast.LENGTH_LONG
                ).show()
                startActivity(launch)
            } catch (_: Exception) {
                clearExternalImportState()
            }
        }
        return "ok"
    }

    private fun cloneStoredNoteFile(sourceName: String, noteId: Long, displayName: String, mime: String): JSONObject? {
        val source = getNoteFile(sourceName) ?: return null
        return try {
            val extension = safeDocumentExtension(displayName, mime)
            val newName = "note_file_${noteId}_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}.$extension"
            val destination = File(noteFileDir(), newName)
            source.inputStream().buffered().use { input ->
                destination.outputStream().buffered().use { output -> input.copyTo(output) }
            }
            if (destination.length() <= 0L) { destination.delete(); return null }
            JSONObject()
                .put("name", newName)
                .put("displayName", displayName.ifBlank { sourceName })
                .put("mime", mime.ifBlank { guessNoteFileMime(displayName) })
                .put("size", destination.length())
        } catch (_: Exception) { null }
    }

    private fun openNoteFileWithExternalApp(file: File, displayName: String, mime: String): String {
        return try {
            val uri = getNoteFileUri(file)
            val resolvedMime = mime.takeUnless { it.isBlank() || it == "application/octet-stream" }
                ?: guessNoteFileMime(displayName.ifBlank { file.name })
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, resolvedMime)
                clipData = ClipData.newRawUri(displayName.ifBlank { "note_attachment" }, uri)
                putExtra(Intent.EXTRA_TITLE, displayName)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            if (intent.resolveActivity(packageManager) == null) return "unavailable"
            runOnUiThread {
                try { startActivity(Intent.createChooser(intent, "使用其他应用打开")) } catch (_: Exception) {}
            }
            "ok"
        } catch (_: Exception) { "failed" }
    }

    inner class AndroidBridge {
        @JavascriptInterface
        fun pickNoteImages(noteIdRaw: String): String {
            val noteId = noteIdRaw.toLongOrNull() ?: return "invalid"
            if (noteId <= 0L) return "invalid"
            pendingNoteImageNoteId = noteId
            runOnUiThread {
                try {
                    noteImagePicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                } catch (_: Exception) {
                    pendingNoteImageNoteId = -1L
                    if (pageReady) {
                        webView.evaluateJavascript(
                            "window.__onNoteImagesPicked && window.__onNoteImagesPicked([]);",
                            null
                        )
                    }
                }
            }
            return "ok"
        }

        @JavascriptInterface
        fun deleteNoteImage(fileName: String): String {
            if (!isSafeNoteImageName(fileName)) return "invalid"
            return try {
                val file = File(noteImageDir(), fileName)
                if (file.exists()) file.delete()
                "ok"
            } catch (_: Exception) {
                "failed"
            }
        }

        @JavascriptInterface
        fun pickNoteFiles(noteIdRaw: String): String {
            val noteId = noteIdRaw.toLongOrNull() ?: return "invalid"
            if (noteId <= 0L) return "invalid"
            pendingNoteFileNoteId = noteId
            runOnUiThread {
                try {
                    noteFilePicker.launch(arrayOf("*/*"))
                } catch (_: Exception) {
                    pendingNoteFileNoteId = -1L
                    if (pageReady) {
                        webView.evaluateJavascript(
                            "window.__onNoteFilesPicked && window.__onNoteFilesPicked([]);",
                            null
                        )
                    }
                }
            }
            return "ok"
        }

        @JavascriptInterface
        fun deleteNoteFile(fileName: String): String {
            val file = getNoteFile(fileName) ?: return "invalid"
            return try {
                file.delete()
                "ok"
            } catch (_: Exception) {
                "failed"
            }
        }

        @JavascriptInterface
        fun hideNoteKeyboard(): String {
            runOnUiThread { hideSoftKeyboard() }
            return "ok"
        }

        @JavascriptInterface
        fun beginExternalNoteImport(noteIdRaw: String, target: String): String {
            val noteId = noteIdRaw.toLongOrNull() ?: return "invalid"
            if (noteId <= 0L) return "invalid"
            return launchExternalImportTarget(noteId, target)
        }

        @JavascriptInterface
        fun cloneNoteFile(sourceName: String, noteIdRaw: String, displayName: String, mime: String): String {
            val noteId = noteIdRaw.toLongOrNull() ?: return "{}"
            if (noteId <= 0L) return "{}"
            return cloneStoredNoteFile(sourceName, noteId, displayName, mime)?.toString() ?: "{}"
        }

        @JavascriptInterface
        fun previewNoteFile(fileName: String, displayName: String, mime: String): String {
            val file = getNoteFile(fileName) ?: return "missing"
            val resolvedMime = mime.takeUnless { it.isBlank() || it == "application/octet-stream" }
                ?: guessNoteFileMime(displayName.ifBlank { file.name })
            if (!NoteFileViewerActivity.supportsInternalPreview(displayName.ifBlank { file.name }, resolvedMime)) {
                return openNoteFileWithExternalApp(file, displayName, resolvedMime)
            }
            return try {
                runOnUiThread {
                    try {
                        startActivity(
                            Intent(this@MainActivity, NoteFileViewerActivity::class.java).apply {
                                putExtra(NoteFileViewerActivity.EXTRA_FILE_PATH, file.absolutePath)
                                putExtra(NoteFileViewerActivity.EXTRA_DISPLAY_NAME, displayName.ifBlank { file.name })
                                putExtra(NoteFileViewerActivity.EXTRA_MIME, resolvedMime)
                            }
                        )
                    } catch (_: Exception) {}
                }
                "ok"
            } catch (_: Exception) { "failed" }
        }

        @JavascriptInterface
        fun openNoteFileExternally(fileName: String, displayName: String, mime: String): String {
            val file = getNoteFile(fileName) ?: return "missing"
            return openNoteFileWithExternalApp(file, displayName, mime)
        }

        // Backward-compatible alias retained for any existing note content / older UI callbacks.
        @JavascriptInterface
        fun openNoteFile(fileName: String): String {
            val file = getNoteFile(fileName) ?: return "missing"
            return openNoteFileWithExternalApp(file, file.name, guessNoteFileMime(file.name))
        }

        @JavascriptInterface
        fun shareNoteFile(fileName: String, target: String): String {
            val file = getNoteFile(fileName) ?: return "missing"
            return try {
                val baseIntent = buildNoteFileShareIntent(file)
                val packageName = resolvePreferredSharePackage(baseIntent, target) ?: return "unavailable"
                val directIntent = Intent(baseIntent).setPackage(packageName)
                runOnUiThread {
                    try { startActivity(directIntent) } catch (_: Exception) {}
                }
                "ok"
            } catch (_: Exception) {
                "failed"
            }
        }

        @JavascriptInterface
        fun syncDailyData(
            percent: Float,
            completed: Int,
            total: Int,
            inProgressJson: String,
            todoJson: String,
            completedJson: String
        ) {
            fun parseList(json: String): String {
                val arr = JSONArray(json)
                return (0 until arr.length()).map { arr.getString(it) }.joinToString("|||")
            }
            val prefs = getSharedPreferences("daily_banner", Context.MODE_PRIVATE)
            prefs.edit()
                .putFloat("percent", percent)
                .putInt("completed", completed)
                .putInt("total", total)
                .putString("in_progress", parseList(inProgressJson))
                .putString("todo", parseList(todoJson))
                .putString("completed_items", parseList(completedJson))
                .apply()
        }

        @JavascriptInterface
        fun syncTodoSnapshot(todosJson: String) {
            try {
                ReminderScheduler.syncTodoSnapshot(this@MainActivity, todosJson)
                ReminderStore.getAll(this@MainActivity)
                    .filter { it.fired }
                    .forEach { FloatWindowManager.updateTodo(it.id, it.text, it.status, it.reminderAt) }
                if (ReminderScheduler.hasArmedWork(this@MainActivity)) {
                    FloatWindowService.ensureKeeperRunning(this@MainActivity)
                    FloatWindowService.refreshKeeper(this@MainActivity)
                }
            } catch (_: Exception) {}
        }

        @JavascriptInterface
        fun syncDailyTasks(dailyJson: String) {
            try {
                val beforeIds = DailyTaskStore.getAll(this@MainActivity).map { it.id }.toSet()
                ReminderScheduler.syncDailyTasks(this@MainActivity, dailyJson)
                val afterIds = DailyTaskStore.getAll(this@MainActivity).map { it.id }.toSet()
                (beforeIds - afterIds).forEach {
                    FloatWindowManager.dismissDailyTask(it, silent = true)
                    ReminderNotifier.cancelDailyTask(this@MainActivity, it)
                }
                val today = java.time.LocalDate.now().toString()
                DailyTaskStore.getAll(this@MainActivity).filter { it.isCompleted(today) || it.isDismissed(today) }.forEach {
                    FloatWindowManager.dismissDailyTask(it.id, silent = true)
                    ReminderNotifier.cancelDailyTask(this@MainActivity, it.id)
                }
                if (ReminderScheduler.hasArmedWork(this@MainActivity)) {
                    FloatWindowService.ensureKeeperRunning(this@MainActivity)
                    FloatWindowService.refreshKeeper(this@MainActivity)
                }
            } catch (_: Exception) {}
        }

        @JavascriptInterface
        fun getDailyTaskMutations(): String = DailyTaskStore.peekMutations(this@MainActivity)

        @JavascriptInterface
        fun ackDailyTaskMutations() {
            DailyTaskStore.clearMutations(this@MainActivity)
        }

        @JavascriptInterface
        fun scheduleTodoReminder(idRaw: String, text: String, status: String, reminderAtRaw: String): String {
            return try {
                val id = idRaw.toLong()
                val reminderAt = reminderAtRaw.toLong()
                if (id <= 0L || reminderAt <= System.currentTimeMillis()) return "invalid"
                val record = TodoReminderRecord(id, text, status, reminderAt, fired = false)
                ReminderStore.upsert(this@MainActivity, record)
                FloatWindowService.ensureKeeperRunning(this@MainActivity)
                ReminderScheduler.scheduleTodo(this@MainActivity, record)
                FloatWindowService.refreshKeeper(this@MainActivity)
                ReminderDiagnostics.record(this@MainActivity, "todo_bridge_armed", "$id:$reminderAt")
                "ok"
            } catch (e: Exception) {
                "失败: ${e.message ?: e.javaClass.simpleName}"
            }
        }

        @JavascriptInterface
        fun cancelTodoReminder(idRaw: String): String {
            return try {
                val id = idRaw.toLong()
                ReminderScheduler.cancelTodo(this@MainActivity, id)
                ReminderStore.remove(this@MainActivity, id, enqueueClearMutation = false)
                FloatWindowManager.dismissTodoReminder(id, silent = true)
                FloatWindowService.refreshKeeper(this@MainActivity)
                "ok"
            } catch (e: Exception) {
                "失败: ${e.message ?: e.javaClass.simpleName}"
            }
        }

        @JavascriptInterface
        fun getReminderDiagnostics(): String = ReminderDiagnostics.json(this@MainActivity)

        @JavascriptInterface
        fun getTodoMutations(): String = ReminderStore.peekMutations(this@MainActivity)

        @JavascriptInterface
        fun ackTodoMutations() {
            ReminderStore.clearMutations(this@MainActivity)
        }

        @JavascriptInterface
        fun requestReminderPermissions(): String {
            runOnUiThread { beginReminderPermissionFlow() }
            return reminderPermissionState()
        }

        @JavascriptInterface
        fun getReminderPermissionState(): String = reminderPermissionState()

        @JavascriptInterface
        fun checkBatteryOptimization(): String {
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            return if (pm.isIgnoringBatteryOptimizations(packageName)) "ok" else "battery_optimizing"
        }

        @JavascriptInterface
        fun requestBatteryOptimization() {
            runOnUiThread {
                try {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (_: Exception) {}
            }
        }

        @JavascriptInterface
        fun scheduleTestAlarm(): String {
            runOnUiThread { beginReminderPermissionFlow() }
            return try {
                FloatWindowService.ensureKeeperRunning(this@MainActivity)
                val triggerAt = MidnightReceiver.scheduleTest(this@MainActivity)
                FloatWindowService.armTestFallback(this@MainActivity, triggerAt)
                "ok"
            } catch (e: Exception) {
                "失败: ${e.message}"
            }
        }

        @JavascriptInterface
        fun syncBannerTime(hour: Int, minute: Int): String {
            val prefs = getSharedPreferences("banner_time", Context.MODE_PRIVATE)
            prefs.edit()
                .putInt("hour", hour.coerceIn(0, 23))
                .putInt("minute", minute.coerceIn(0, 59))
                .putBoolean("enabled", true)
                .apply()
            try {
                FloatWindowService.ensureKeeperRunning(this@MainActivity)
                ReminderScheduler.scheduleDaily(this@MainActivity)
                FloatWindowService.refreshKeeper(this@MainActivity)
            } catch (_: Exception) {}
            runOnUiThread { beginReminderPermissionFlow() }
            return reminderPermissionState()
        }

        @JavascriptInterface
        fun showDailyBanner(
            percent: Float,
            completed: Int,
            total: Int,
            inProgressJson: String,
            todoJson: String,
            completedJson: String
        ): String {
            if (!Settings.canDrawOverlays(this@MainActivity)) {
                runOnUiThread { beginReminderPermissionFlow() }
                return "悬浮窗权限未授予"
            }

            fun parseArr(json: String): List<String> {
                val arr = JSONArray(json)
                return (0 until arr.length()).map { arr.getString(it) }
            }
            val inProgress = parseArr(inProgressJson)
            val todo = parseArr(todoJson)
            val completedItems = parseArr(completedJson)

            return try {
                runOnUiThread {
                    FloatWindowManager.showBanner(
                        this@MainActivity,
                        percent,
                        completed,
                        total,
                        inProgress,
                        todo,
                        completedItems
                    ) {
                        val launchIntent = Intent(this@MainActivity, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        }
                        startActivity(launchIntent)
                    }
                }
                "ok"
            } catch (e: Exception) {
                "弹窗失败: ${e.message}"
            }
        }
    }
}
