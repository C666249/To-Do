package com.todolist.app.reminder

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

data class DailyTaskRecord(
    val id: Long,
    val text: String,
    val hour: Int,
    val minute: Int,
    val createdAt: Long,
    val completedDates: Set<String> = emptySet(),
    val lastDismissedDate: String? = null,
    val lastFiredDate: String? = null
) {
    fun isCompleted(date: String = LocalDate.now().toString()): Boolean = completedDates.contains(date)
    fun isDismissed(date: String = LocalDate.now().toString()): Boolean = lastDismissedDate == date
    fun isVisibleAfterFire(date: String = LocalDate.now().toString()): Boolean =
        lastFiredDate == date && !isCompleted(date) && !isDismissed(date)
}

/** Native mirror for recurring Daily tasks. WebView localStorage owns task content/history. */
object DailyTaskStore {
    private const val PREFS = "daily_task_reminders"
    private const val KEY_RECORDS = "records"
    private const val MUTATION_PREFS = "daily_task_native_mutations"
    private const val KEY_MUTATIONS = "mutations"

    data class SyncResult(val records: List<DailyTaskRecord>, val removedIds: Set<Long>)

    fun getAll(context: Context): List<DailyTaskRecord> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_RECORDS, "[]") ?: "[]"
        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val id = obj.optLong("id", -1L)
                    if (id <= 0L) continue
                    val dates = linkedSetOf<String>()
                    val dateArray = obj.optJSONArray("completedDates") ?: JSONArray()
                    for (j in 0 until dateArray.length()) {
                        val date = dateArray.optString(j, "")
                        if (date.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) dates += date
                    }
                    add(
                        DailyTaskRecord(
                            id = id,
                            text = obj.optString("text", "每日事项"),
                            hour = obj.optInt("hour", 9).coerceIn(0, 23),
                            minute = obj.optInt("minute", 0).coerceIn(0, 59),
                            createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
                            completedDates = dates,
                            lastDismissedDate = obj.optString("lastDismissedDate", "").ifBlank { null },
                            lastFiredDate = obj.optString("lastFiredDate", "").ifBlank { null }
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun get(context: Context, id: Long): DailyTaskRecord? = getAll(context).firstOrNull { it.id == id }

    fun syncSnapshot(context: Context, json: String): SyncResult {
        val old = getAll(context).associateBy { it.id }
        val next = linkedMapOf<Long, DailyTaskRecord>()
        try {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val id = obj.optLong("id", -1L)
                if (id <= 0L) continue
                val previous = old[id]
                val dates = linkedSetOf<String>()
                val dateArray = obj.optJSONArray("completedDates") ?: JSONArray()
                for (j in 0 until dateArray.length()) {
                    val date = dateArray.optString(j, "")
                    if (date.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) dates += date
                }
                val hour = obj.optInt("hour", previous?.hour ?: 9).coerceIn(0, 23)
                val minute = obj.optInt("minute", previous?.minute ?: 0).coerceIn(0, 59)
                val timingChanged = previous != null && (previous.hour != hour || previous.minute != minute)
                next[id] = DailyTaskRecord(
                    id = id,
                    text = obj.optString("text", previous?.text ?: "每日事项"),
                    hour = hour,
                    minute = minute,
                    createdAt = obj.optLong("createdAt", previous?.createdAt ?: System.currentTimeMillis()),
                    completedDates = dates,
                    lastDismissedDate = if (timingChanged) null else previous?.lastDismissedDate,
                    lastFiredDate = if (timingChanged) null else previous?.lastFiredDate
                )
            }
        } catch (_: Exception) {
            return SyncResult(old.values.toList(), emptySet())
        }
        val removed = old.keys - next.keys
        saveAll(context, next.values.toList())
        return SyncResult(next.values.toList(), removed)
    }

    fun markFired(context: Context, id: Long, date: String = LocalDate.now().toString()) {
        saveAll(context, getAll(context).map { if (it.id == id) it.copy(lastFiredDate = date) else it })
    }

    fun markDismissed(context: Context, id: Long, date: String = LocalDate.now().toString()) {
        saveAll(context, getAll(context).map {
            if (it.id == id) it.copy(lastDismissedDate = date, lastFiredDate = date) else it
        })
    }

    fun markCompletedToday(context: Context, id: Long, enqueue: Boolean = true) {
        val today = LocalDate.now().toString()
        saveAll(context, getAll(context).map {
            if (it.id == id) it.copy(
                completedDates = it.completedDates + today,
                lastDismissedDate = today,
                lastFiredDate = today
            ) else it
        })
        if (enqueue) enqueueMutation(context, JSONObject().apply {
            put("type", "dailyCompleted")
            put("id", id)
            put("date", today)
        })
    }

    fun remove(context: Context, id: Long) {
        saveAll(context, getAll(context).filterNot { it.id == id })
    }

    fun peekMutations(context: Context): String =
        context.getSharedPreferences(MUTATION_PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MUTATIONS, "[]") ?: "[]"

    fun clearMutations(context: Context) {
        context.getSharedPreferences(MUTATION_PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_MUTATIONS, "[]").apply()
    }

    private fun saveAll(context: Context, records: List<DailyTaskRecord>) {
        val array = JSONArray()
        records.forEach { record ->
            array.put(JSONObject().apply {
                put("id", record.id)
                put("text", record.text)
                put("hour", record.hour)
                put("minute", record.minute)
                put("createdAt", record.createdAt)
                put("completedDates", JSONArray(record.completedDates.sorted()))
                put("lastDismissedDate", record.lastDismissedDate ?: "")
                put("lastFiredDate", record.lastFiredDate ?: "")
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_RECORDS, array.toString()).apply()
    }

    private fun enqueueMutation(context: Context, mutation: JSONObject) {
        val prefs = context.getSharedPreferences(MUTATION_PREFS, Context.MODE_PRIVATE)
        val current = try { JSONArray(prefs.getString(KEY_MUTATIONS, "[]") ?: "[]") }
        catch (_: Exception) { JSONArray() }
        val next = JSONArray()
        for (i in 0 until current.length()) {
            val obj = current.optJSONObject(i) ?: continue
            if (obj.optString("type") == mutation.optString("type") &&
                obj.optLong("id") == mutation.optLong("id") &&
                obj.optString("date") == mutation.optString("date")) continue
            next.put(obj)
        }
        next.put(mutation)
        prefs.edit().putString(KEY_MUTATIONS, next.toString()).apply()
    }
}
