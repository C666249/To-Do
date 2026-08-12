package com.todolist.app.reminder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import com.todolist.app.MainActivity
import com.todolist.app.R
import com.todolist.app.receiver.DailyTaskActionReceiver
import com.todolist.app.receiver.ReminderActionReceiver

/**
 * Alarm arrival safety net.
 *
 * The notification is posted directly from the alarm BroadcastReceiver before the app attempts
 * to start the overlay foreground service. This guarantees a user-visible reminder even when an
 * OEM blocks/delays the foreground-service/overlay leg of the chain.
 */
object ReminderNotifier {
    const val CHANNEL_ID = "listnote_alarm_alerts_v1"
    const val CHANNEL_NAME = "To-Do 定时提醒"

    private const val DAILY_ID = 42000
    private const val TODO_BASE_ID = 43000
    private const val DAILY_TASK_BASE_ID = 76000

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH).apply {
                description = "To-Do 到点提醒与后台兜底通知"
                enableVibration(true)
                setShowBadge(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
    }

    fun postDaily(
        context: Context,
        percent: Float,
        completed: Int,
        total: Int
    ): Boolean = try {
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context,
            DAILY_ID,
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = "今日完成 ${"%.0f".format(percent)}% · $completed/$total 项"
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_todo)
            .setLargeIcon(BitmapFactory.decodeResource(context.resources, R.drawable.ic_notification_large))
            .setContentTitle("To-Do 每日总结")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setPriority(Notification.PRIORITY_HIGH)
            .setTimeoutAfter(60_000L)
            .build()
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(DAILY_ID, notification)
        true
    } catch (_: Exception) {
        false
    }

    fun postTodo(
        context: Context,
        id: Long,
        text: String,
        status: String,
        alert: Boolean = true
    ): Boolean = try {
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context,
            todoNotificationId(id),
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(MainActivity.EXTRA_FOCUS_TODO_ID, id)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val cycle = PendingIntent.getBroadcast(
            context,
            (id.hashCode() xor 0x51A7),
            Intent(context, ReminderActionReceiver::class.java).apply {
                action = ReminderActionReceiver.ACTION_CYCLE_STATUS
                putExtra(ReminderActionReceiver.EXTRA_TODO_ID, id)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val dismiss = PendingIntent.getBroadcast(
            context,
            (id.hashCode() xor 0x71D5),
            Intent(context, ReminderActionReceiver::class.java).apply {
                action = ReminderActionReceiver.ACTION_DISMISS
                putExtra(ReminderActionReceiver.EXTRA_TODO_ID, id)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val statusLabel = when (status) {
            "in-progress" -> "进行中"
            "completed" -> "已完成"
            else -> "未完成"
        }
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_todo)
            .setLargeIcon(BitmapFactory.decodeResource(context.resources, R.drawable.ic_notification_large))
            .setContentTitle("待办提醒 · $statusLabel")
            .setContentText(text.ifBlank { "待办提醒" })
            .setStyle(Notification.BigTextStyle().bigText(text.ifBlank { "待办提醒" }))
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_rotate, "切换状态", cycle).build())
            .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "关闭提醒", dismiss).build())
            .setOngoing(true)
            .setOnlyAlertOnce(!alert)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setPriority(Notification.PRIORITY_HIGH)
            .build()
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(todoNotificationId(id), notification)
        true
    } catch (_: Exception) {
        false
    }

    fun postDailyTask(
        context: Context,
        id: Long,
        text: String,
        hour: Int,
        minute: Int,
        alert: Boolean = true
    ): Boolean = try {
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context,
            dailyTaskNotificationId(id),
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(MainActivity.EXTRA_FOCUS_DAILY_TASK_ID, id)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val complete = PendingIntent.getBroadcast(
            context,
            (id.hashCode() xor 0x33C1),
            Intent(context, DailyTaskActionReceiver::class.java).apply {
                action = DailyTaskActionReceiver.ACTION_COMPLETE
                putExtra(DailyTaskActionReceiver.EXTRA_DAILY_TASK_ID, id)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val dismiss = PendingIntent.getBroadcast(
            context,
            (id.hashCode() xor 0x5A91),
            Intent(context, DailyTaskActionReceiver::class.java).apply {
                action = DailyTaskActionReceiver.ACTION_DISMISS
                putExtra(DailyTaskActionReceiver.EXTRA_DAILY_TASK_ID, id)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val time = "%02d:%02d".format(hour, minute)
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_todo)
            .setLargeIcon(BitmapFactory.decodeResource(context.resources, R.drawable.ic_notification_large))
            .setContentTitle("Daily · $time")
            .setContentText(text.ifBlank { "每日事项" })
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_agenda, "完成", complete).build())
            .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "忽略", dismiss).build())
            .setOngoing(true)
            .setOnlyAlertOnce(!alert)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setPriority(Notification.PRIORITY_HIGH)
            .build()
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(dailyTaskNotificationId(id), notification)
        true
    } catch (_: Exception) {
        false
    }

    fun cancelDailyTask(context: Context, id: Long) {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .cancel(dailyTaskNotificationId(id))
    }

    fun cancelDaily(context: Context) {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(DAILY_ID)
    }

    fun cancelTodo(context: Context, id: Long) {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .cancel(todoNotificationId(id))
    }

    private fun todoNotificationId(id: Long): Int = TODO_BASE_ID + (id.hashCode() and 0x3fff)
    private fun dailyTaskNotificationId(id: Long): Int = DAILY_TASK_BASE_ID + (id.hashCode() and 0x3fff)
}
