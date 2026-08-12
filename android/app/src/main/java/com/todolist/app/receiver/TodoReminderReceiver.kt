package com.todolist.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import com.todolist.app.reminder.ReminderDiagnostics
import com.todolist.app.reminder.ReminderNotifier
import com.todolist.app.reminder.ReminderStore
import com.todolist.app.reminder.ReminderScheduler
import com.todolist.app.reminder.SnoozeStore
import com.todolist.app.service.FloatWindowService

class TodoReminderReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "TodoReminderReceiver"
        const val ACTION_TODO_REMINDER = "com.listnote.app.action.TODO_REMINDER"
        const val EXTRA_TODO_ID = "todo_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_TODO_REMINDER) return
        val app = context.applicationContext
        val id = intent.getLongExtra(EXTRA_TODO_ID, -1L)
        if (id <= 0L) return
        val record = ReminderStore.get(app, id) ?: return
        val now = System.currentTimeMillis()
        val snoozeUntil = SnoozeStore.todoUntil(app, id)
        if (snoozeUntil > now + 750L) {
            ReminderScheduler.scheduleTodoSnooze(app, id, snoozeUntil)
            return
        }
        if (snoozeUntil > 0L) SnoozeStore.clearTodo(app, id)

        val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ListNote:TodoReminder").apply {
            acquire(20_000L)
        }

        ReminderStore.markFired(app, id, true)
        ReminderDiagnostics.record(app, "todo_alarm_received", id.toString())
        ReminderNotifier.postTodo(app, record.id, record.text, record.status, alert = true)
        Log.i(TAG, "Todo reminder alarm received: $id")

        FloatWindowService.dispatchTodoAlarm(app, id)
    }
}
