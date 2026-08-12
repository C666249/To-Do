package com.todolist.app

import android.Manifest
import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.MimeTypeMap
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
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

    private val noteImagePicker = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(20)
    ) { uris ->
        handlePickedNoteImages(uris)
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
                    pullNativeMutationsIntoWeb()
                    deliverPendingFocus()
                    deliverPendingDailyFocus()
                }
            }
            webChromeClient = WebChromeClient()
            addJavascriptInterface(AndroidBridge(), "AndroidBridge")
            loadUrl("file:///android_asset/todo.html")
        }

        setContentView(webView)
        registerNativeMutationReceiver()

        try {
            ReminderScheduler.rescheduleAll(this, restoreFiredOverdue = true)
            if (ReminderScheduler.hasArmedWork(this)) FloatWindowService.ensureKeeperRunning(this)
        } catch (_: Exception) {}
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
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val id = intent.getLongExtra(EXTRA_FOCUS_TODO_ID, -1L)
        if (id > 0L) pendingFocusTodoId = id
        consumeDailyFocusIntent(intent)
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
