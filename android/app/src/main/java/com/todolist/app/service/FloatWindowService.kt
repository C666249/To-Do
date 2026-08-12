package com.todolist.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.todolist.app.MainActivity
import com.todolist.app.R
import com.todolist.app.manager.FloatWindowManager
import com.todolist.app.reminder.DailyTaskStore
import com.todolist.app.reminder.ReminderDiagnostics
import com.todolist.app.reminder.ReminderNotifier
import com.todolist.app.reminder.ReminderScheduler
import com.todolist.app.reminder.ReminderStore
import com.todolist.app.reminder.SnoozeStore
import com.todolist.app.reminder.TodoSnapshotStore
import java.util.Calendar

/**
 * V1.7 reminder runtime.
 *
 * Unlike the previous fire-and-start design, this service is armed while the user still has the
 * app in the foreground. It stays alive at low, silent notification importance whenever a daily reminder
 * or item reminder exists. AlarmManager remains the durable wake-up mechanism, while a Handler
 * inside this already-running service acts as a second, in-process delivery path. This mirrors the
 * reliability characteristic of FlowLedger's already-alive overlay host without requiring
 * accessibility or notification-listener privileges in To-Do.
 */
class FloatWindowService : Service() {

    companion object {
        const val CHANNEL_ID = "listnote_reminder_keeper_v3"
        const val ALERT_CHANNEL_ID = "todo_reminder_alerts_v2"
        const val CHANNEL_NAME = "To-Do 提醒守护"
        const val ALERT_CHANNEL_NAME = "To-Do 重要提醒"
        const val NOTIFICATION_ID = 1001

        const val ACTION_KEEP_ALIVE = "com.listnote.app.action.KEEP_REMINDER_RUNTIME"
        const val ACTION_REFRESH = "com.listnote.app.action.REFRESH_REMINDER_RUNTIME"
        const val ACTION_ALARM_DAILY = "com.listnote.app.action.ALARM_DAILY"
        const val ACTION_ALARM_TODO = "com.listnote.app.action.ALARM_TODO"
        const val ACTION_ALARM_DAILY_TASK = "com.todolist.app.action.ALARM_DAILY_TASK"
        const val ACTION_ARM_TEST_FALLBACK = "com.listnote.app.action.ARM_TEST_FALLBACK"
        const val ACTION_SHOW = "com.listnote.app.action.SHOW_BANNER"
        const val ACTION_SHOW_TODO_REMINDER = "com.listnote.app.action.SHOW_TODO_REMINDER"
        const val ACTION_CYCLE_TODO_STATUS = "com.listnote.app.action.CYCLE_TODO_STATUS"
        const val ACTION_DISMISS_TODO_REMINDER = "com.listnote.app.action.DISMISS_TODO_REMINDER"

        const val EXTRA_PERCENT = "percent"
        const val EXTRA_COMPLETED = "completed"
        const val EXTRA_TOTAL = "total"
        const val EXTRA_IN_PROGRESS = "in_progress"
        const val EXTRA_TODO = "todo"
        const val EXTRA_COMPLETED_ITEMS = "completed_items"
        const val EXTRA_TODO_ID = "todo_id"
        const val EXTRA_DAILY_TASK_ID = "daily_task_id"
        const val EXTRA_TODO_TEXT = "todo_text"
        const val EXTRA_TODO_STATUS = "todo_status"
        const val EXTRA_REMINDER_AT = "reminder_at"
        const val EXTRA_TEST = "is_test"
        const val EXTRA_TRIGGER_AT = "trigger_at"
        const val ACTION_NATIVE_TODO_MUTATED = "com.listnote.app.action.NATIVE_TODO_MUTATED"

        fun ensureKeeperRunning(context: Context) {
            startCompat(context.applicationContext, Intent(context.applicationContext, FloatWindowService::class.java).apply {
                action = ACTION_KEEP_ALIVE
            })
        }

        fun refreshKeeper(context: Context) {
            startCompat(context.applicationContext, Intent(context.applicationContext, FloatWindowService::class.java).apply {
                action = ACTION_REFRESH
            })
        }

        fun armTestFallback(context: Context, triggerAt: Long) {
            startCompat(context.applicationContext, Intent(context.applicationContext, FloatWindowService::class.java).apply {
                action = ACTION_ARM_TEST_FALLBACK
                putExtra(EXTRA_TRIGGER_AT, triggerAt)
            })
        }

        fun dispatchDailyAlarm(context: Context, isTest: Boolean) {
            startCompat(context.applicationContext, Intent(context.applicationContext, FloatWindowService::class.java).apply {
                action = ACTION_ALARM_DAILY
                putExtra(EXTRA_TEST, isTest)
            })
        }

        fun dispatchTodoAlarm(context: Context, id: Long) {
            startCompat(context.applicationContext, Intent(context.applicationContext, FloatWindowService::class.java).apply {
                action = ACTION_ALARM_TODO
                putExtra(EXTRA_TODO_ID, id)
            })
        }

        fun dispatchDailyTaskAlarm(context: Context, id: Long) {
            startCompat(context.applicationContext, Intent(context.applicationContext, FloatWindowService::class.java).apply {
                action = ACTION_ALARM_DAILY_TASK
                putExtra(EXTRA_DAILY_TASK_ID, id)
            })
        }

        private fun startCompat(context: Context, intent: Intent) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
                else context.startService(intent)
            } catch (e: Exception) {
                ReminderDiagnostics.record(context, "service_start_failed", e.javaClass.simpleName)
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val todoFallbacks = linkedMapOf<Long, Runnable>()
    private val dailyTaskFallbacks = linkedMapOf<Long, Runnable>()
    private var dailyFallback: Runnable? = null
    private var testFallback: Runnable? = null
    private var lastDailyDispatchAt = 0L

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        startForeground(NOTIFICATION_ID, buildServiceNotification("提醒守护已开启"))
        ReminderDiagnostics.record(this, "keeper_created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildServiceNotification("提醒守护已开启"))
        ReminderDiagnostics.record(this, "keeper_command", intent?.action ?: "null")

        when (intent?.action) {
            ACTION_KEEP_ALIVE, ACTION_REFRESH -> {
                restorePersistentReminderOverlays()
                armInProcessFallbacks()
            }
            ACTION_ARM_TEST_FALLBACK -> {
                val triggerAt = intent?.getLongExtra(EXTRA_TRIGGER_AT, 0L) ?: 0L
                if (triggerAt > 0L) armTestFallback(triggerAt)
                armInProcessFallbacks()
            }
            ACTION_ALARM_DAILY -> {
                handleDailyReminder(intent?.getBooleanExtra(EXTRA_TEST, false) ?: false, "alarm")
                armInProcessFallbacks()
            }
            ACTION_ALARM_TODO -> {
                handleTodoReminder(intent?.getLongExtra(EXTRA_TODO_ID, -1L) ?: -1L, "alarm")
                armInProcessFallbacks()
            }
            ACTION_ALARM_DAILY_TASK -> {
                handleDailyTaskReminder(intent?.getLongExtra(EXTRA_DAILY_TASK_ID, -1L) ?: -1L, "alarm")
                armInProcessFallbacks()
            }
            // Backward-compatible entry points kept for existing notification/actions and old alarms.
            ACTION_SHOW -> intent?.let { showDailySummaryFromIntent(it) }
            ACTION_SHOW_TODO_REMINDER -> handleTodoReminder(intent?.getLongExtra(EXTRA_TODO_ID, -1L) ?: -1L, "legacy")
            ACTION_CYCLE_TODO_STATUS -> cycleTodoStatus(intent?.getLongExtra(EXTRA_TODO_ID, -1L) ?: -1L)
            ACTION_DISMISS_TODO_REMINDER -> dismissTodoReminder(intent?.getLongExtra(EXTRA_TODO_ID, -1L) ?: -1L)
            null -> {
                restorePersistentReminderOverlays()
                runCatching { ReminderScheduler.rescheduleAll(this, restoreFiredOverdue = true) }
                armInProcessFallbacks()
            }
        }

        return if (shouldStayAlive()) START_STICKY else {
            stopSelf()
            START_NOT_STICKY
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        ReminderDiagnostics.record(this, "task_removed")
        runCatching { ReminderScheduler.rescheduleAll(this, restoreFiredOverdue = false) }
        armInProcessFallbacks()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        clearFallbacks()
        FloatWindowManager.dismissAllSilently()
        ReminderDiagnostics.record(this, "keeper_destroyed")
        super.onDestroy()
    }

    private fun handleDailyReminder(isTest: Boolean, source: String) {
        val now = System.currentTimeMillis()
        // Alarm + in-process fallback can arrive almost together. One visible banner is enough.
        if (now - lastDailyDispatchAt < 3_000L) return
        lastDailyDispatchAt = now
        ReminderDiagnostics.record(this, if (isTest) "test_render" else "daily_render", source)
        if (!isTest) runCatching { ReminderScheduler.scheduleDaily(this) }

        val summary = TodoSnapshotStore.todaySummary(this)
        if (summary != null) {
            showDailySummary(
                summary.percent,
                summary.completed,
                summary.total,
                summary.inProgress,
                summary.todo,
                summary.completedItems
            )
        } else {
            val prefs = getSharedPreferences("daily_banner", Context.MODE_PRIVATE)
            showDailySummary(
                prefs.getFloat("percent", 0f),
                prefs.getInt("completed", 0),
                prefs.getInt("total", 0),
                splitList(prefs.getString("in_progress", "")),
                splitList(prefs.getString("todo", "")),
                splitList(prefs.getString("completed_items", ""))
            )
        }
    }

    private fun showDailySummaryFromIntent(intent: Intent) {
        showDailySummary(
            intent.getFloatExtra(EXTRA_PERCENT, 0f),
            intent.getIntExtra(EXTRA_COMPLETED, 0),
            intent.getIntExtra(EXTRA_TOTAL, 0),
            intent.getStringArrayListExtra(EXTRA_IN_PROGRESS) ?: arrayListOf(),
            intent.getStringArrayListExtra(EXTRA_TODO) ?: arrayListOf(),
            intent.getStringArrayListExtra(EXTRA_COMPLETED_ITEMS) ?: arrayListOf()
        )
    }

    private fun showDailySummary(
        percent: Float,
        completed: Int,
        total: Int,
        inProgress: List<String>,
        todo: List<String>,
        completedItems: List<String>
    ) {
        if (!Settings.canDrawOverlays(this)) {
            ReminderNotifier.postDaily(this, percent, completed, total)
            return
        }
        runCatching {
            FloatWindowManager.showBanner(this, percent, completed, total, inProgress, todo, completedItems) {
                openApp()
            }
            ReminderNotifier.cancelDaily(this)
            FloatWindowManager.onDailyDismissed = { reevaluateLifecycle() }
            ReminderDiagnostics.record(this, "daily_overlay_attached")
        }.onFailure {
            ReminderDiagnostics.record(this, "daily_overlay_failed", it.javaClass.simpleName)
            ReminderNotifier.postDaily(this, percent, completed, total)
        }
    }

    private fun handleTodoReminder(id: Long, source: String) {
        if (id <= 0L) return
        val record = ReminderStore.get(this, id) ?: return
        val now = System.currentTimeMillis()
        val snoozeUntil = SnoozeStore.todoUntil(this, id)
        if (snoozeUntil > now + 750L) {
            ReminderScheduler.scheduleTodoSnooze(this, id, snoozeUntil)
            ReminderDiagnostics.record(this, "todo_snooze_suppressed", "$id:$source:$snoozeUntil")
            return
        }
        if (snoozeUntil > 0L) SnoozeStore.clearTodo(this, id)
        if (!record.fired) ReminderStore.markFired(this, id, true)
        ReminderDiagnostics.record(this, "todo_render", "$id:$source")

        if (!Settings.canDrawOverlays(this)) {
            ReminderNotifier.postTodo(this, id, record.text, record.status, alert = source != "fallback")
            return
        }

        runCatching {
            FloatWindowManager.showTodoReminder(
                context = this,
                id = id,
                text = record.text,
                status = record.status,
                reminderAt = record.reminderAt,
                onSnooze = { minutes ->
                    val until = System.currentTimeMillis() + minutes * 60_000L
                    SnoozeStore.setTodo(this, id, until)
                    ReminderScheduler.scheduleTodoSnooze(this, id, until)
                    ReminderNotifier.cancelTodo(this, id)
                    FloatWindowManager.showSnoozeToast(this, minutes)
                    ReminderDiagnostics.record(this, "todo_snooze_committed", "$id:$minutes:$until")
                    refreshFallbacksAndLifecycle()
                },
                onStatusChanged = { newStatus ->
                    ReminderStore.updateStatus(this, id, newStatus)
                    broadcastNativeMutation(id, "status", newStatus)
                },
                onDismiss = {
                    ReminderStore.dismiss(this, id)
                    ReminderScheduler.cancelTodo(this, id)
                    ReminderNotifier.cancelTodo(this, id)
                    broadcastNativeMutation(id, "clearReminder", null)
                    refreshFallbacksAndLifecycle()
                },
                onOpenApp = { openApp(id) }
            )
            ReminderNotifier.cancelTodo(this, id)
            ReminderDiagnostics.record(this, "todo_overlay_attached", id.toString())
        }.onFailure {
            ReminderDiagnostics.record(this, "todo_overlay_failed", "$id:${it.javaClass.simpleName}")
            ReminderNotifier.postTodo(this, id, record.text, record.status, alert = false)
        }
    }

    private fun handleDailyTaskReminder(id: Long, source: String) {
        if (id <= 0L) return
        val record = DailyTaskStore.get(this, id) ?: return
        val today = java.time.LocalDate.now().toString()
        val now = System.currentTimeMillis()
        val snoozeUntil = SnoozeStore.dailyUntil(this, id)
        if (snoozeUntil > now + 750L) {
            ReminderScheduler.scheduleDailyTaskSnooze(this, id, snoozeUntil)
            ReminderDiagnostics.record(this, "daily_task_snooze_suppressed", "$id:$source:$snoozeUntil")
            return
        }
        if (snoozeUntil > 0L) SnoozeStore.clearDaily(this, id)
        if (record.isCompleted(today) || record.isDismissed(today)) {
            ReminderScheduler.scheduleDailyTask(this, record)
            return
        }
        if (record.lastFiredDate != today) DailyTaskStore.markFired(this, id, today)
        val current = DailyTaskStore.get(this, id) ?: record
        ReminderDiagnostics.record(this, "daily_task_render", "$id:$source")
        ReminderScheduler.scheduleDailyTask(this, current)

        if (!Settings.canDrawOverlays(this)) {
            ReminderNotifier.postDailyTask(this, id, current.text, current.hour, current.minute, alert = source != "fallback")
            return
        }

        runCatching {
            FloatWindowManager.showDailyTask(
                context = this,
                id = id,
                text = current.text,
                hour = current.hour,
                minute = current.minute,
                onSnooze = { minutes ->
                    val until = System.currentTimeMillis() + minutes * 60_000L
                    SnoozeStore.setDaily(this, id, until)
                    ReminderScheduler.scheduleDailyTaskSnooze(this, id, until)
                    ReminderNotifier.cancelDailyTask(this, id)
                    FloatWindowManager.showSnoozeToast(this, minutes)
                    ReminderDiagnostics.record(this, "daily_task_snooze_committed", "$id:$minutes:$until")
                    refreshFallbacksAndLifecycle()
                },
                onComplete = {
                    SnoozeStore.clearDaily(this, id)
                    DailyTaskStore.markCompletedToday(this, id, enqueue = true)
                    ReminderNotifier.cancelDailyTask(this, id)
                    DailyTaskStore.get(this, id)?.let { ReminderScheduler.scheduleDailyTask(this, it) }
                    broadcastNativeMutation(-1L, "daily", null)
                    refreshFallbacksAndLifecycle()
                },
                onDismiss = {
                    SnoozeStore.clearDaily(this, id)
                    DailyTaskStore.markDismissed(this, id)
                    ReminderNotifier.cancelDailyTask(this, id)
                    DailyTaskStore.get(this, id)?.let { ReminderScheduler.scheduleDailyTask(this, it) }
                    refreshFallbacksAndLifecycle()
                },
                onOpenApp = {
                    SnoozeStore.clearDaily(this, id)
                    DailyTaskStore.markDismissed(this, id)
                    ReminderNotifier.cancelDailyTask(this, id)
                    DailyTaskStore.get(this, id)?.let { ReminderScheduler.scheduleDailyTask(this, it) }
                    openDaily(id)
                    refreshFallbacksAndLifecycle()
                }
            )
            ReminderNotifier.cancelDailyTask(this, id)
            ReminderDiagnostics.record(this, "daily_task_overlay_attached", id.toString())
        }.onFailure {
            ReminderDiagnostics.record(this, "daily_task_overlay_failed", "$id:${it.javaClass.simpleName}")
            ReminderNotifier.postDailyTask(this, id, current.text, current.hour, current.minute, alert = false)
        }
    }

    private fun cycleTodoStatus(id: Long) {
        if (id <= 0L) return
        val record = ReminderStore.get(this, id) ?: return
        val next = when (record.status) {
            "todo" -> "in-progress"
            "in-progress" -> "completed"
            else -> "todo"
        }
        ReminderStore.updateStatus(this, id, next)
        FloatWindowManager.updateTodoStatus(id, next)
        broadcastNativeMutation(id, "status", next)
        if (!Settings.canDrawOverlays(this)) ReminderNotifier.postTodo(this, id, record.text, next, alert = false)
    }

    private fun dismissTodoReminder(id: Long) {
        if (id <= 0L) return
        FloatWindowManager.dismissTodoReminder(id, silent = true)
        ReminderStore.dismiss(this, id)
        ReminderScheduler.cancelTodo(this, id)
        ReminderNotifier.cancelTodo(this, id)
        broadcastNativeMutation(id, "clearReminder", null)
        refreshFallbacksAndLifecycle()
    }

    private fun restorePersistentReminderOverlays() {
        if (!Settings.canDrawOverlays(this)) return
        ReminderStore.getAll(this).filter { it.fired }.forEach { record ->
            handleTodoReminder(record.id, "restore")
        }
        val today = java.time.LocalDate.now().toString()
        DailyTaskStore.getAll(this).filter { it.isVisibleAfterFire(today) }.forEach { record ->
            handleDailyTaskReminder(record.id, "restore")
        }
    }

    private fun armInProcessFallbacks() {
        dailyFallback?.let { mainHandler.removeCallbacks(it) }
        dailyFallback = null
        todoFallbacks.values.forEach { mainHandler.removeCallbacks(it) }
        todoFallbacks.clear()
        dailyTaskFallbacks.values.forEach { mainHandler.removeCallbacks(it) }
        dailyTaskFallbacks.clear()

        val prefs = getSharedPreferences("banner_time", Context.MODE_PRIVATE)
        if (prefs.getBoolean("enabled", false) || prefs.contains("hour") || prefs.contains("minute")) {
            val hour = prefs.getInt("hour", 0).coerceIn(0, 23)
            val minute = prefs.getInt("minute", 6).coerceIn(0, 59)
            val now = Calendar.getInstance()
            val target = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (!after(now)) add(Calendar.DAY_OF_MONTH, 1)
            }.timeInMillis
            val task = Runnable {
                handleDailyReminder(false, "fallback")
                armInProcessFallbacks()
            }
            dailyFallback = task
            mainHandler.postDelayed(task, (target - System.currentTimeMillis()).coerceAtLeast(1_000L))
        }

        val nowMs = System.currentTimeMillis()
        ReminderStore.getAll(this).forEach { record ->
            val snoozeUntil = SnoozeStore.todoUntil(this, record.id)
            val triggerAt = when {
                snoozeUntil > nowMs -> snoozeUntil
                !record.fired && record.reminderAt > nowMs -> record.reminderAt
                else -> 0L
            }
            if (triggerAt <= 0L) return@forEach
            val task = Runnable {
                handleTodoReminder(record.id, "fallback")
                todoFallbacks.remove(record.id)
            }
            todoFallbacks[record.id] = task
            mainHandler.postDelayed(task, (triggerAt - nowMs).coerceAtLeast(1_000L))
        }

        val today = java.time.LocalDate.now().toString()
        DailyTaskStore.getAll(this).forEach { record ->
            val snoozeUntil = SnoozeStore.dailyUntil(this, record.id)
            val target = if (snoozeUntil > nowMs) snoozeUntil else {
                val now = Calendar.getInstance()
                Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, record.hour)
                    set(Calendar.MINUTE, record.minute)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                    if (record.isCompleted(today) || record.isDismissed(today) || !after(now)) add(Calendar.DAY_OF_MONTH, 1)
                }.timeInMillis
            }
            val task = Runnable {
                handleDailyTaskReminder(record.id, "fallback")
                dailyTaskFallbacks.remove(record.id)
                armInProcessFallbacks()
            }
            dailyTaskFallbacks[record.id] = task
            mainHandler.postDelayed(task, (target - System.currentTimeMillis()).coerceAtLeast(1_000L))
        }
    }

    private fun armTestFallback(triggerAt: Long) {
        testFallback?.let { mainHandler.removeCallbacks(it) }
        val task = Runnable {
            handleDailyReminder(true, "fallback")
            testFallback = null
        }
        testFallback = task
        mainHandler.postDelayed(task, (triggerAt - System.currentTimeMillis()).coerceAtLeast(500L))
    }

    private fun clearFallbacks() {
        dailyFallback?.let { mainHandler.removeCallbacks(it) }
        testFallback?.let { mainHandler.removeCallbacks(it) }
        todoFallbacks.values.forEach { mainHandler.removeCallbacks(it) }
        dailyTaskFallbacks.values.forEach { mainHandler.removeCallbacks(it) }
        dailyFallback = null
        testFallback = null
        todoFallbacks.clear()
        dailyTaskFallbacks.clear()
    }

    private fun refreshFallbacksAndLifecycle() {
        armInProcessFallbacks()
        reevaluateLifecycle()
    }

    private fun shouldStayAlive(): Boolean =
        ReminderScheduler.hasArmedWork(this) || FloatWindowManager.hasAnyOverlay() || testFallback != null

    private fun reevaluateLifecycle() {
        if (!shouldStayAlive()) stopSelf()
    }

    private fun splitList(raw: String?): ArrayList<String> =
        ArrayList(raw.orEmpty().split("|||").filter { it.isNotEmpty() })

    private fun openDaily(id: Long) {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(MainActivity.EXTRA_FOCUS_DAILY_TASK_ID, id)
        }
        runCatching { startActivity(launchIntent) }
    }

    private fun openApp(todoId: Long? = null) {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            todoId?.let { putExtra(MainActivity.EXTRA_FOCUS_TODO_ID, it) }
        }
        runCatching { startActivity(launchIntent) }
    }

    private fun broadcastNativeMutation(id: Long, type: String, status: String?) {
        sendBroadcast(Intent(ACTION_NATIVE_TODO_MUTATED).apply {
            setPackage(packageName)
            putExtra(EXTRA_TODO_ID, id)
            putExtra("mutation_type", type)
            if (status != null) putExtra(EXTRA_TODO_STATUS, status)
        })
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW).apply {
                description = "保持用户已设置的 To-Do 定时提醒可靠运行"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
        )
    }

    private fun buildServiceNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_todo)
            .setLargeIcon(BitmapFactory.decodeResource(resources, R.drawable.ic_notification_large))
            .setContentTitle("To-Do")
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
}
