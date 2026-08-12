package com.todolist.app.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import com.todolist.app.MainActivity
import com.todolist.app.receiver.DailyTaskReminderReceiver
import com.todolist.app.receiver.MidnightReceiver
import com.todolist.app.receiver.TodoReminderReceiver
import java.util.Calendar

/**
 * AlarmManager is the durable source of timing truth.
 *
 * V1.7 deliberately uses setAlarmClock() whenever exact-alarm access is available. These alarms
 * are the strongest user-facing AlarmManager primitive: RTC_WAKEUP is implied and Android exits
 * low-power idle to deliver them. This is intentionally chosen for a reminder product where the
 * user explicitly picked the time. If exact-alarm access is unavailable we retain a safe
 * setAndAllowWhileIdle() fallback instead of silently dropping the reminder.
 */
object ReminderScheduler {
    private const val TAG = "ReminderScheduler"
    private const val DAILY_REQUEST_CODE = 7001
    private const val TEST_REQUEST_CODE = 7002

    fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return alarmManager.canScheduleExactAlarms()
    }

    fun hasArmedWork(context: Context): Boolean {
        val prefs = context.getSharedPreferences("banner_time", Context.MODE_PRIVATE)
        val daily = prefs.getBoolean("enabled", false) || prefs.contains("hour") || prefs.contains("minute")
        return daily || ReminderStore.getAll(context).isNotEmpty() || DailyTaskStore.getAll(context).isNotEmpty()
    }

    fun scheduleDaily(context: Context) {
        val prefs = context.getSharedPreferences("banner_time", Context.MODE_PRIVATE)
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = dailyPendingIntent(context)
        val configured = prefs.getBoolean("enabled", false) || prefs.contains("hour") || prefs.contains("minute")
        if (!configured) {
            alarmManager.cancel(pi)
            return
        }

        val hour = prefs.getInt("hour", 0).coerceIn(0, 23)
        val minute = prefs.getInt("minute", 6).coerceIn(0, 59)
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (!after(now)) add(Calendar.DAY_OF_MONTH, 1)
        }

        alarmManager.cancel(pi)
        scheduleUserFacingAlarm(alarmManager, target.timeInMillis, pi, context, "daily", null)
        ReminderDiagnostics.record(context, "daily_scheduled", target.timeInMillis.toString())
        Log.i(TAG, "Daily summary scheduled for ${target.time}; exact=${canScheduleExact(context)}")
    }

    fun scheduleDailyTest(context: Context): Long {
        val triggerAt = System.currentTimeMillis() + 10_000L
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = dailyPendingIntent(context, TEST_REQUEST_CODE, isTest = true)
        alarmManager.cancel(pi)
        scheduleUserFacingAlarm(alarmManager, triggerAt, pi, context, "test", null)
        ReminderDiagnostics.record(context, "test_scheduled", triggerAt.toString())
        return triggerAt
    }

    fun scheduleTodo(context: Context, record: TodoReminderRecord) {
        SnoozeStore.clearTodo(context, record.id)
        val triggerAt = if (record.reminderAt <= System.currentTimeMillis()) {
            System.currentTimeMillis() + 2_000L
        } else record.reminderAt
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = todoPendingIntent(context, record.id)
        alarmManager.cancel(pi)
        scheduleUserFacingAlarm(alarmManager, triggerAt, pi, context, "todo", record.id)
        ReminderDiagnostics.record(context, "todo_scheduled", "${record.id}:$triggerAt")
        Log.i(TAG, "Todo ${record.id} scheduled @ $triggerAt; exact=${canScheduleExact(context)}")
    }

    fun scheduleTodoSnooze(context: Context, id: Long, triggerAtMillis: Long) {
        val triggerAt = triggerAtMillis.coerceAtLeast(System.currentTimeMillis() + 1_000L)
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = todoPendingIntent(context, id)
        alarmManager.cancel(pi)
        scheduleUserFacingAlarm(alarmManager, triggerAt, pi, context, "todo", id)
        ReminderDiagnostics.record(context, "todo_snoozed", "$id:$triggerAt")
    }

    fun cancelTodo(context: Context, id: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(todoPendingIntent(context, id))
        SnoozeStore.clearTodo(context, id)
        ReminderDiagnostics.record(context, "todo_cancelled", id.toString())
    }

    fun scheduleDailyTask(context: Context, record: DailyTaskRecord) {
        SnoozeStore.clearDaily(context, record.id)
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = dailyTaskPendingIntent(context, record.id)
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, record.hour.coerceIn(0, 23))
            set(Calendar.MINUTE, record.minute.coerceIn(0, 59))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            val today = java.time.LocalDate.now().toString()
            val blockedToday = record.isCompleted(today) || record.isDismissed(today)
            if (blockedToday || !after(now)) add(Calendar.DAY_OF_MONTH, 1)
        }
        alarmManager.cancel(pi)
        scheduleUserFacingAlarm(alarmManager, target.timeInMillis, pi, context, "daily_task", record.id)
        ReminderDiagnostics.record(context, "daily_task_scheduled", "${record.id}:${target.timeInMillis}")
    }

    fun scheduleDailyTaskSnooze(context: Context, id: Long, triggerAtMillis: Long) {
        val triggerAt = triggerAtMillis.coerceAtLeast(System.currentTimeMillis() + 1_000L)
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = dailyTaskPendingIntent(context, id)
        alarmManager.cancel(pi)
        scheduleUserFacingAlarm(alarmManager, triggerAt, pi, context, "daily_task", id)
        ReminderDiagnostics.record(context, "daily_task_snoozed", "$id:$triggerAt")
    }

    fun cancelDailyTask(context: Context, id: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(dailyTaskPendingIntent(context, id))
        SnoozeStore.clearDaily(context, id)
        ReminderDiagnostics.record(context, "daily_task_cancelled", id.toString())
    }

    fun syncDailyTasks(context: Context, dailyJson: String) {
        val result = DailyTaskStore.syncSnapshot(context, dailyJson)
        result.removedIds.forEach { cancelDailyTask(context, it) }
        val now = System.currentTimeMillis()
        val today = java.time.LocalDate.now().toString()
        result.records.forEach { record ->
            val snoozeUntil = SnoozeStore.dailyUntil(context, record.id)
            val keepSnooze = snoozeUntil > now && record.lastFiredDate == today && !record.isCompleted(today) && !record.isDismissed(today)
            if (keepSnooze) scheduleDailyTaskSnooze(context, record.id, snoozeUntil)
            else scheduleDailyTask(context, record)
        }
    }

    fun syncTodoSnapshot(context: Context, todosJson: String) {
        TodoSnapshotStore.save(context, todosJson)
        val result = ReminderStore.syncSnapshot(context, todosJson)
        result.removedIds.forEach { cancelTodo(context, it) }
        result.records.filter { !it.fired }.forEach { scheduleTodo(context, it) }
    }

    fun rescheduleAll(context: Context, restoreFiredOverdue: Boolean = false) {
        scheduleDaily(context)
        val now = System.currentTimeMillis()
        ReminderStore.getAll(context).forEach { record ->
            val snoozeUntil = SnoozeStore.todoUntil(context, record.id)
            when {
                snoozeUntil > now -> scheduleTodoSnooze(context, record.id, snoozeUntil)
                !record.fired && record.reminderAt > now -> scheduleTodo(context, record)
                restoreFiredOverdue -> scheduleTodo(context, record)
            }
        }
        DailyTaskStore.getAll(context).forEach { record ->
            val snoozeUntil = SnoozeStore.dailyUntil(context, record.id)
            if (snoozeUntil > now) scheduleDailyTaskSnooze(context, record.id, snoozeUntil)
            else scheduleDailyTask(context, record)
        }
    }

    private fun scheduleUserFacingAlarm(
        alarmManager: AlarmManager,
        triggerAtMillis: Long,
        operation: PendingIntent,
        context: Context,
        kind: String,
        todoId: Long?
    ) {
        if (canScheduleExact(context)) {
            val info = AlarmManager.AlarmClockInfo(
                triggerAtMillis,
                alarmInfoPendingIntent(context, kind, todoId)
            )
            alarmManager.setAlarmClock(info, operation)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, operation)
        }
    }

    private fun alarmInfoPendingIntent(context: Context, kind: String, todoId: Long?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = "com.listnote.app.action.OPEN_REMINDER_INFO"
            data = Uri.parse("listnote://alarm-info/$kind/${todoId ?: 0L}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (todoId != null) {
                if (kind == "daily_task") putExtra(MainActivity.EXTRA_FOCUS_DAILY_TASK_ID, todoId)
                else putExtra(MainActivity.EXTRA_FOCUS_TODO_ID, todoId)
            }
        }
        val requestCode = if (todoId == null) {
            if (kind == "test") 9102 else 9101
        } else {
            10000 + ((todoId xor (todoId ushr 32)).toInt() and 0x3fffffff)
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun dailyPendingIntent(
        context: Context,
        requestCode: Int = DAILY_REQUEST_CODE,
        isTest: Boolean = false
    ): PendingIntent {
        val intent = Intent(context, MidnightReceiver::class.java).apply {
            action = MidnightReceiver.ACTION_MIDNIGHT
            data = Uri.parse(if (isTest) "listnote://alarm/test" else "listnote://alarm/daily")
            putExtra(MidnightReceiver.EXTRA_TEST, isTest)
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun todoPendingIntent(context: Context, id: Long): PendingIntent {
        val intent = Intent(context, TodoReminderReceiver::class.java).apply {
            action = TodoReminderReceiver.ACTION_TODO_REMINDER
            data = Uri.parse("listnote://reminder/$id")
            putExtra(TodoReminderReceiver.EXTRA_TODO_ID, id)
        }
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun dailyTaskPendingIntent(context: Context, id: Long): PendingIntent {
        val intent = Intent(context, DailyTaskReminderReceiver::class.java).apply {
            action = DailyTaskReminderReceiver.ACTION_DAILY_TASK
            data = Uri.parse("todo://daily-task/$id")
            putExtra(DailyTaskReminderReceiver.EXTRA_DAILY_TASK_ID, id)
        }
        return PendingIntent.getBroadcast(
            context,
            12000 + ((id xor (id ushr 32)).toInt() and 0x3fffffff),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
