package com.todolist.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import com.todolist.app.reminder.DailyTaskStore
import com.todolist.app.reminder.ReminderDiagnostics
import com.todolist.app.reminder.ReminderNotifier
import com.todolist.app.reminder.ReminderScheduler
import com.todolist.app.reminder.SnoozeStore
import com.todolist.app.service.FloatWindowService
import java.time.LocalDate

class DailyTaskReminderReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_DAILY_TASK = "com.todolist.app.action.DAILY_TASK_REMINDER"
        const val EXTRA_DAILY_TASK_ID = "daily_task_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DAILY_TASK) return
        val app = context.applicationContext
        val id = intent.getLongExtra(EXTRA_DAILY_TASK_ID, -1L)
        if (id <= 0L) return
        val record = DailyTaskStore.get(app, id) ?: return
        val today = LocalDate.now().toString()
        val now = System.currentTimeMillis()
        val snoozeUntil = SnoozeStore.dailyUntil(app, id)
        if (snoozeUntil > now + 750L) {
            ReminderScheduler.scheduleDailyTaskSnooze(app, id, snoozeUntil)
            return
        }
        if (snoozeUntil > 0L) SnoozeStore.clearDaily(app, id)

        // If the user already completed/dismissed today's instance, quietly arm tomorrow.
        if (record.isCompleted(today) || record.isDismissed(today)) {
            ReminderScheduler.scheduleDailyTask(app, record)
            return
        }

        val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ToDo:DailyTask").apply { acquire(20_000L) }

        DailyTaskStore.markFired(app, id, today)
        ReminderScheduler.scheduleDailyTask(app, DailyTaskStore.get(app, id) ?: record)
        ReminderDiagnostics.record(app, "daily_task_alarm_received", id.toString())
        ReminderNotifier.postDailyTask(app, record.id, record.text, record.hour, record.minute, alert = true)
        FloatWindowService.dispatchDailyTaskAlarm(app, id)
    }
}
