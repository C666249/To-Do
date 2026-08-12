package com.todolist.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import com.todolist.app.reminder.ReminderDiagnostics
import com.todolist.app.reminder.ReminderNotifier
import com.todolist.app.reminder.ReminderScheduler
import com.todolist.app.reminder.TodoSnapshotStore
import com.todolist.app.service.FloatWindowService

class MidnightReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "MidnightReceiver"
        const val ACTION_MIDNIGHT = "com.listnote.app.action.MIDNIGHT"
        const val EXTRA_TEST = "is_test"

        fun schedule(context: Context) = ReminderScheduler.scheduleDaily(context)
        fun scheduleTest(context: Context): Long = ReminderScheduler.scheduleDailyTest(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_MIDNIGHT) return
        val app = context.applicationContext
        val isTest = intent.getBooleanExtra(EXTRA_TEST, false)
        val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ListNote:DailyReminder")
        wakeLock.acquire(20_000L)

        ReminderDiagnostics.record(app, if (isTest) "test_alarm_received" else "daily_alarm_received")
        Log.i(TAG, "Daily reminder alarm triggered; test=$isTest")

        if (!isTest) ReminderScheduler.scheduleDaily(app)

        // Receiver-level safety signal. The foreground service removes it once the overlay exists.
        val summary = TodoSnapshotStore.todaySummary(app)
        if (summary != null) ReminderNotifier.postDaily(app, summary.percent, summary.completed, summary.total)
        else ReminderNotifier.postDaily(app, 0f, 0, 0)

        FloatWindowService.dispatchDailyAlarm(app, isTest)
    }
}
