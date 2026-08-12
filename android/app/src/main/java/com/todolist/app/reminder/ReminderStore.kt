package com.todolist.app.reminder

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class TodoReminderRecord(
    val id: Long,
    val text: String,
    val status: String,
    val reminderAt: Long,
    val fired: Boolean = false
)

object ReminderStore {
    private const val PREFS = "todo_reminders"
    private const val KEY_RECORDS = "records"
    private const val MUTATION_PREFS = "todo_native_mutations"
    private const val KEY_MUTATIONS = "mutations"

    data class SyncResult(
        val records: List<TodoReminderRecord>,
        val removedIds: Set<Long>
    )

    fun getAll(context: Context): List<TodoReminderRecord> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_RECORDS, "[]") ?: "[]"
        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val id = obj.optLong("id", -1L)
                    val reminderAt = obj.optLong("reminderAt", 0L)
                    if (id <= 0L || reminderAt <= 0L) continue
                    add(
                        TodoReminderRecord(
                            id = id,
                            text = obj.optString("text", "待办提醒"),
                            status = normalizeStatus(obj.optString("status", "todo")),
                            reminderAt = reminderAt,
                            fired = obj.optBoolean("fired", false)
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun get(context: Context, id: Long): TodoReminderRecord? =
        getAll(context).firstOrNull { it.id == id }

    /**
     * WebView localStorage is still the source of truth. Native only mirrors todo rows that
     * currently have a reminder, so alarms survive process death / reboot.
     */
    fun syncSnapshot(context: Context, todosJson: String): SyncResult {
        val oldRecords = getAll(context).associateBy { it.id }
        val newRecords = linkedMapOf<Long, TodoReminderRecord>()
        val now = System.currentTimeMillis()

        try {
            val array = JSONArray(todosJson)
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val id = obj.optLong("id", -1L)
                if (id <= 0L) continue
                val reminderAt = when {
                    obj.has("reminderAt") && !obj.isNull("reminderAt") -> obj.optLong("reminderAt", 0L)
                    else -> 0L
                }
                if (reminderAt <= 0L) continue

                val old = oldRecords[id]
                // Preserve an already-known reminder whose timestamp has just become overdue.
                // This closes the small recovery race where the app opens, schedules an overdue
                // native alarm, then WebView sync arrives before that alarm has had time to fire.
                val sameKnownReminder = old != null && old.reminderAt == reminderAt
                val preserveFired = sameKnownReminder && old?.fired == true
                // A brand-new reminder is never allowed to be created in the past, but an existing
                // missed reminder remains recoverable and will be surfaced promptly by the scheduler.
                if (reminderAt <= now && !sameKnownReminder) continue

                newRecords[id] = TodoReminderRecord(
                    id = id,
                    text = obj.optString("text", old?.text ?: "待办提醒"),
                    status = normalizeStatus(obj.optString("status", old?.status ?: "todo")),
                    reminderAt = reminderAt,
                    fired = preserveFired
                )
            }
        } catch (_: Exception) {
            return SyncResult(oldRecords.values.toList(), emptySet())
        }

        // Keep active fired reminders even if a stale WebView snapshot temporarily omitted them.
        oldRecords.values.filter { it.fired }.forEach { record ->
            if (!newRecords.containsKey(record.id)) newRecords[record.id] = record
        }

        val removed = oldRecords.keys - newRecords.keys
        saveAll(context, newRecords.values.toList())
        return SyncResult(newRecords.values.toList(), removed)
    }


    fun upsert(context: Context, record: TodoReminderRecord) {
        val normalized = record.copy(status = normalizeStatus(record.status))
        val current = getAll(context).associateBy { it.id }.toMutableMap()
        current[normalized.id] = normalized
        saveAll(context, current.values.sortedBy { it.reminderAt })
    }

    fun remove(context: Context, id: Long, enqueueClearMutation: Boolean = false) {
        saveAll(context, getAll(context).filterNot { it.id == id })
        if (enqueueClearMutation) {
            enqueueMutation(context, JSONObject().apply {
                put("type", "clearReminder")
                put("id", id)
            })
        }
    }

    fun markFired(context: Context, id: Long, fired: Boolean = true) {
        val records = getAll(context).map {
            if (it.id == id) it.copy(fired = fired) else it
        }
        saveAll(context, records)
    }

    fun updateStatus(context: Context, id: Long, status: String) {
        val normalized = normalizeStatus(status)
        val records = getAll(context).map {
            if (it.id == id) it.copy(status = normalized) else it
        }
        saveAll(context, records)
        enqueueMutation(context, JSONObject().apply {
            put("type", "status")
            put("id", id)
            put("status", normalized)
        })
    }

    fun dismiss(context: Context, id: Long) {
        saveAll(context, getAll(context).filterNot { it.id == id })
        enqueueMutation(context, JSONObject().apply {
            put("type", "clearReminder")
            put("id", id)
        })
    }

    private fun saveAll(context: Context, records: List<TodoReminderRecord>) {
        val array = JSONArray()
        records.forEach { record ->
            array.put(JSONObject().apply {
                put("id", record.id)
                put("text", record.text)
                put("status", normalizeStatus(record.status))
                put("reminderAt", record.reminderAt)
                put("fired", record.fired)
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_RECORDS, array.toString()).apply()
    }

    private fun enqueueMutation(context: Context, mutation: JSONObject) {
        val prefs = context.getSharedPreferences(MUTATION_PREFS, Context.MODE_PRIVATE)
        val current = try { JSONArray(prefs.getString(KEY_MUTATIONS, "[]") ?: "[]") }
        catch (_: Exception) { JSONArray() }

        // Coalesce by (type,id). The newest native interaction wins.
        val next = JSONArray()
        for (i in 0 until current.length()) {
            val obj = current.optJSONObject(i) ?: continue
            if (obj.optString("type") == mutation.optString("type") &&
                obj.optLong("id") == mutation.optLong("id")) continue
            next.put(obj)
        }
        next.put(mutation)
        prefs.edit().putString(KEY_MUTATIONS, next.toString()).apply()
    }

    fun peekMutations(context: Context): String =
        context.getSharedPreferences(MUTATION_PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MUTATIONS, "[]") ?: "[]"

    fun clearMutations(context: Context) {
        context.getSharedPreferences(MUTATION_PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_MUTATIONS, "[]").apply()
    }

    private fun normalizeStatus(status: String): String = when (status) {
        "in-progress", "completed" -> status
        else -> "todo"
    }
}
