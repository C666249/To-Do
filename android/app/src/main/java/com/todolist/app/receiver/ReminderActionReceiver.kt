package com.todolist.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.todolist.app.manager.FloatWindowManager
import com.todolist.app.reminder.ReminderNotifier
import com.todolist.app.reminder.ReminderScheduler
import com.todolist.app.reminder.ReminderStore
import com.todolist.app.service.FloatWindowService

/** Notification actions are handled by a BroadcastReceiver, not a foreground service. */
class ReminderActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_CYCLE_STATUS = "com.listnote.app.action.REMINDER_CYCLE_STATUS"
        const val ACTION_DISMISS = "com.listnote.app.action.REMINDER_DISMISS"
        const val EXTRA_TODO_ID = "todo_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_TODO_ID, -1L)
        if (id <= 0L) return

        when (intent.action) {
            ACTION_CYCLE_STATUS -> {
                val record = ReminderStore.get(context, id) ?: run {
                    ReminderNotifier.cancelTodo(context, id)
                    return
                }
                val next = when (record.status) {
                    "todo" -> "in-progress"
                    "in-progress" -> "completed"
                    else -> "todo"
                }
                ReminderStore.updateStatus(context, id, next)
                FloatWindowManager.updateTodoStatus(id, next)
                ReminderNotifier.postTodo(context, id, record.text, next, alert = false)
                broadcastMutation(context, id, "status", next)
                FloatWindowService.refreshKeeper(context)
            }

            ACTION_DISMISS -> {
                FloatWindowManager.dismissTodoReminder(id, silent = true)
                ReminderStore.dismiss(context, id)
                ReminderScheduler.cancelTodo(context, id)
                ReminderNotifier.cancelTodo(context, id)
                broadcastMutation(context, id, "clearReminder", null)
                FloatWindowService.refreshKeeper(context)
            }
        }
    }

    private fun broadcastMutation(context: Context, id: Long, type: String, status: String?) {
        context.sendBroadcast(Intent(FloatWindowService.ACTION_NATIVE_TODO_MUTATED).apply {
            setPackage(context.packageName)
            putExtra(FloatWindowService.EXTRA_TODO_ID, id)
            putExtra("mutation_type", type)
            if (status != null) putExtra(FloatWindowService.EXTRA_TODO_STATUS, status)
        })
    }
}
