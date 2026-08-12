package com.todolist.app.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persistent per-item reminder overlay.
 * Layout: checkbox + status dot + todo text + right meta "Todo · HH:mm" + close.
 * The content text can wrap so long titles naturally grow the banner height.
 */
class TodoReminderBannerView(
    context: Context,
    val todoId: Long,
    text: String,
    initialStatus: String,
    reminderAt: Long?
) : SnoozeSwipeBannerView(context) {

    private val density = context.resources.displayMetrics.density
    private val checkbox: TextView
    private val statusDot: TextView
    private val todoText: TextView
    private val metaText: TextView
    private var currentStatus = normalize(initialStatus)

    var onStatusChanged: ((String) -> Unit)? = null
    var onDismiss: (() -> Unit)? = null
    var onOpenApp: (() -> Unit)? = null

    init {
        setCardElevation(10f)
        setCardPadding(12f, 10f, 8f, 10f)

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        checkbox = TextView(context).apply {
            gravity = Gravity.CENTER
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setOnClickListener {
                currentStatus = nextStatus(currentStatus)
                renderStatus()
                onStatusChanged?.invoke(currentStatus)
            }
        }
        row.addView(checkbox, LinearLayout.LayoutParams(dp(30f), dp(30f)).apply {
            marginEnd = dp(10f)
        })

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPadding(0, dp(1f), dp(4f), dp(1f))
            setOnClickListener { fadeOutForAction(durationMs = 165L) { onOpenApp?.invoke() } }
        }

        statusDot = TextView(context).apply {
            this.text = "●"
            textSize = 11f
            setPadding(0, dp(2f), dp(7f), 0)
        }
        content.addView(statusDot)

        todoText = TextView(context).apply {
            this.text = text.ifBlank { "待办提醒" }
            textSize = 15f
            setTextColor(Color.parseColor("#2E2925"))
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(0f, 1.08f)
        }
        content.addView(todoText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(content, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        metaText = TextView(context).apply {
            this.text = buildMeta(reminderAt)
            textSize = 11.5f
            setTextColor(Color.parseColor("#8D8278"))
            includeFontPadding = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8f), 0, dp(2f), 0)
            setOnClickListener { fadeOutForAction(durationMs = 165L) { onOpenApp?.invoke() } }
        }
        row.addView(metaText, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP
        })

        row.addView(TextView(context).apply {
            this.text = "✕"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#9D9186"))
            setPadding(dp(8f), dp(2f), dp(8f), dp(2f))
            setOnClickListener { fadeOutForAction(durationMs = 175L) { onDismiss?.invoke() } }
        }, LinearLayout.LayoutParams(dp(42f), dp(42f)).apply {
            gravity = Gravity.TOP
        })

        cardSurface.addView(row, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        renderStatus()
    }

    fun setStatus(status: String) {
        currentStatus = normalize(status)
        renderStatus()
    }

    override fun showSwipeCompletedState() {
        currentStatus = "completed"
        renderStatus()
    }

    fun setTodo(text: String, status: String, reminderAt: Long?) {
        todoText.text = text.ifBlank { "待办提醒" }
        metaText.text = buildMeta(reminderAt)
        setStatus(status)
    }

    private fun buildMeta(reminderAt: Long?): String {
        if (reminderAt == null || reminderAt <= 0L) return "Todo"
        val label = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(reminderAt))
        return "Todo · $label"
    }

    private fun renderStatus() {
        val bg = GradientDrawable().apply { cornerRadius = dpf(8f) }
        todoText.paintFlags = todoText.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
        todoText.setTextColor(Color.parseColor("#2E2925"))

        when (currentStatus) {
            "in-progress" -> {
                bg.setColor(Color.parseColor("#F59E0B"))
                checkbox.text = ""
                checkbox.setTextColor(Color.WHITE)
                statusDot.setTextColor(Color.parseColor("#F59E0B"))
            }
            "completed" -> {
                bg.setColor(Color.parseColor("#7668E8"))
                checkbox.text = "✓"
                checkbox.setTextColor(Color.WHITE)
                statusDot.setTextColor(Color.parseColor("#00C9A7"))
                todoText.paintFlags = todoText.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                todoText.setTextColor(Color.parseColor("#9AA0AA"))
            }
            else -> {
                bg.setColor(Color.TRANSPARENT)
                bg.setStroke(dp(2f), Color.parseColor("#D3D8E0"))
                checkbox.text = ""
                statusDot.setTextColor(Color.parseColor("#FF8787"))
            }
        }
        checkbox.background = bg
    }

    private fun nextStatus(status: String): String = when (status) {
        "todo" -> "in-progress"
        "in-progress" -> "completed"
        else -> "todo"
    }

    private fun normalize(status: String): String = when (status) {
        "in-progress", "completed" -> status
        else -> "todo"
    }
}
