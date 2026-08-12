package com.todolist.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.todolist.app.manager.FloatWindowManager
import com.todolist.app.reminder.DailyTaskStore
import com.todolist.app.reminder.ReminderNotifier
import com.todolist.app.reminder.ReminderScheduler
import com.todolist.app.service.FloatWindowService

class DailyTaskActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_COMPLETE = "com.todolist.app.action.DAILY_TASK_COMPLETE"
        const val ACTION_DISMISS = "com.todolist.app.action.DAILY_TASK_DISMISS"
        const val EXTRA_DAILY_TASK_ID = "daily_task_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_DAILY_TASK_ID, -1L)
        if (id <= 0L) return
        when (intent.action) {
            ACTION_COMPLETE -> {
                DailyTaskStore.markCompletedToday(context, id, enqueue = true)
                FloatWindowManager.dismissDailyTask(id, silent = true)
                ReminderNotifier.cancelDailyTask(context, id)
                DailyTaskStore.get(context, id)?.let { ReminderScheduler.scheduleDailyTask(context, it) }
                broadcastMutation(context)
                FloatWindowService.refreshKeeper(context)
            }
            ACTION_DISMISS -> {
                DailyTaskStore.markDismissed(context, id)
                FloatWindowManager.dismissDailyTask(id, silent = true)
                ReminderNotifier.cancelDailyTask(context, id)
                DailyTaskStore.get(context, id)?.let { ReminderScheduler.scheduleDailyTask(context, it) }
                FloatWindowService.refreshKeeper(context)
            }
        }
    }

    private fun broadcastMutation(context: Context) {
        context.sendBroadcast(Intent(FloatWindowService.ACTION_NATIVE_TODO_MUTATED).apply {
            setPackage(context.packageName)
        })
    }
}
