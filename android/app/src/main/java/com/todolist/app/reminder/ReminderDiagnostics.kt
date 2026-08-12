package com.todolist.app.reminder

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Lightweight ring-buffer diagnostics for reminder delivery. No user content is logged. */
object ReminderDiagnostics {
    private const val PREFS = "reminder_diagnostics"
    private const val KEY_EVENTS = "events"
    private const val MAX_EVENTS = 30

    fun record(context: Context, event: String, detail: String = "") {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val old = try { JSONArray(prefs.getString(KEY_EVENTS, "[]") ?: "[]") }
        catch (_: Exception) { JSONArray() }
        val next = JSONArray()
        val start = (old.length() - (MAX_EVENTS - 1)).coerceAtLeast(0)
        for (i in start until old.length()) next.put(old.opt(i))
        next.put(JSONObject().apply {
            put("at", System.currentTimeMillis())
            put("event", event)
            if (detail.isNotBlank()) put("detail", detail)
        })
        prefs.edit().putString(KEY_EVENTS, next.toString()).apply()
    }

    fun json(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_EVENTS, "[]") ?: "[]"
}
