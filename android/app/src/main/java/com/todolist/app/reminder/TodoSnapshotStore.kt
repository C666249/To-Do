package com.todolist.app.reminder

import android.content.Context
import org.json.JSONArray
import java.util.Calendar

data class DailyTodoSnapshot(
    val total: Int,
    val completed: Int,
    val percent: Float,
    val inProgress: ArrayList<String>,
    val todo: ArrayList<String>,
    val completedItems: ArrayList<String>
)

/**
 * Small native mirror of WebView todo data used only by background reminder components.
 * localStorage remains the product source of truth; this mirror lets an alarm build the current
 * day's summary without needing to launch a WebView process first.
 */
object TodoSnapshotStore {
    private const val PREFS = "todo_snapshot_native"
    private const val KEY_JSON = "todos"

    fun save(context: Context, todosJson: String) {
        // Validate before storing so a malformed bridge call cannot destroy the last good mirror.
        try {
            JSONArray(todosJson)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_JSON, todosJson).apply()
        } catch (_: Exception) {
            // Keep the previous valid snapshot.
        }
    }

    fun todaySummary(context: Context, nowMillis: Long = System.currentTimeMillis()): DailyTodoSnapshot? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_JSON, null) ?: return null

        return try {
            val dayStart = Calendar.getInstance().apply {
                timeInMillis = nowMillis
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            val nextDay = Calendar.getInstance().apply {
                timeInMillis = dayStart
                add(Calendar.DAY_OF_MONTH, 1)
            }.timeInMillis

            val inProgress = arrayListOf<String>()
            val todo = arrayListOf<String>()
            val completed = arrayListOf<String>()
            var total = 0

            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val createdAt = obj.optLong("createdAt", 0L)
                if (createdAt < dayStart || createdAt >= nextDay) continue
                val text = obj.optString("text", "").trim()
                if (text.isEmpty()) continue
                total++
                when (obj.optString("status", "todo")) {
                    "completed" -> completed.add(text)
                    "in-progress" -> inProgress.add(text)
                    else -> todo.add(text)
                }
            }
            val completedCount = completed.size
            DailyTodoSnapshot(
                total = total,
                completed = completedCount,
                percent = if (total > 0) completedCount * 100f / total else 0f,
                inProgress = inProgress,
                todo = todo,
                completedItems = completed
            )
        } catch (_: Exception) {
            null
        }
    }
}
