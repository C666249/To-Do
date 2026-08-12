package com.todolist.app.reminder

import android.content.Context

/**
 * Ephemeral reminder snooze state. This is deliberately separate from WebView-owned Todo/Daily
 * data so +5/+10 minute snoozes never mutate the user's original reminder time or Daily schedule.
 */
object SnoozeStore {
    private const val PREFS = "reminder_snooze_v1"
    private const val TODO_PREFIX = "todo_"
    private const val DAILY_PREFIX = "daily_"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun todoUntil(context: Context, id: Long): Long = prefs(context).getLong("$TODO_PREFIX$id", 0L)
    fun dailyUntil(context: Context, id: Long): Long = prefs(context).getLong("$DAILY_PREFIX$id", 0L)

    fun setTodo(context: Context, id: Long, untilMillis: Long) {
        prefs(context).edit().putLong("$TODO_PREFIX$id", untilMillis).apply()
    }

    fun setDaily(context: Context, id: Long, untilMillis: Long) {
        prefs(context).edit().putLong("$DAILY_PREFIX$id", untilMillis).apply()
    }

    fun clearTodo(context: Context, id: Long) {
        prefs(context).edit().remove("$TODO_PREFIX$id").apply()
    }

    fun clearDaily(context: Context, id: Long) {
        prefs(context).edit().remove("$DAILY_PREFIX$id").apply()
    }
}
