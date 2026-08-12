package com.todolist.app.receiver

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.todolist.app.reminder.ReminderScheduler
import com.todolist.app.service.FloatWindowService

class BootReceiver : BroadcastReceiver() {
    companion object { private const val TAG = "BootReceiver" }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_TIME_CHANGED ||
            action == Intent.ACTION_TIMEZONE_CHANGED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED
        ) {
            try {
                ReminderScheduler.rescheduleAll(context, restoreFiredOverdue = true)
                if (ReminderScheduler.hasArmedWork(context)) {
                    FloatWindowService.ensureKeeperRunning(context)
                }
                Log.i(TAG, "Rescheduled reminders and keeper after $action")
            } catch (e: Exception) {
                Log.w(TAG, "Reschedule failed after $action: ${e.message}")
            }
        }
    }
}
