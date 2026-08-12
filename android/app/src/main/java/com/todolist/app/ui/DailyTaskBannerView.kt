package com.todolist.app.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** One-line recurring Daily banner: checkbox + title + "Daily · HH:mm" + close. */
class DailyTaskBannerView(
    context: Context,
    val dailyTaskId: Long,
    text: String,
    hour: Int,
    minute: Int
) : SnoozeSwipeBannerView(context) {

    private val density = context.resources.displayMetrics.density
    private val checkbox: TextView
    private var completionCommitted = false

    var onComplete: (() -> Unit)? = null
    var onDismiss: (() -> Unit)? = null
    var onOpenApp: (() -> Unit)? = null

    init {
        setCardElevation(11f)
        setCardPadding(11f, 8f, 6f, 8f)

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        checkbox = TextView(context).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                cornerRadius = dpf(7f)
                setStroke(dp(2f), Color.parseColor("#D3D8E0"))
            }
            setOnClickListener {
                if (completionCommitted) return@setOnClickListener
                completionCommitted = true
                isClickable = false
                renderCompletedCheck(animated = true)

                // Let the user see the green check, then use the same opacity-only exit
                // as every swipe action so the overlay never flashes on detach.
                this@DailyTaskBannerView.postDelayed({
                    fadeOutForAction(durationMs = 180L) { onComplete?.invoke() }
                }, 320L)
            }
        }
        row.addView(checkbox, LinearLayout.LayoutParams(dp(28f), dp(28f)).apply { marginEnd = dp(9f) })

        val title = TextView(context).apply {
            this.text = text.ifBlank { "每日事项" }
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#2E2925"))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setOnClickListener { fadeOutForAction(durationMs = 165L) { onOpenApp?.invoke() } }
        }
        row.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val meta = TextView(context).apply {
            this.text = "Daily · %02d:%02d".format(hour, minute)
            textSize = 11.5f
            setTextColor(Color.parseColor("#8D8278"))
            setPadding(dp(8f), 0, dp(3f), 0)
            setOnClickListener { fadeOutForAction(durationMs = 165L) { onOpenApp?.invoke() } }
        }
        row.addView(meta)

        val close = TextView(context).apply {
            this.text = "✕"
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#A0958B"))
            setOnClickListener { fadeOutForAction(durationMs = 175L) { onDismiss?.invoke() } }
        }
        row.addView(close, LinearLayout.LayoutParams(dp(38f), dp(38f)))

        cardSurface.addView(row, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        cardSurface.setOnClickListener { fadeOutForAction(durationMs = 165L) { onOpenApp?.invoke() } }
    }

    override fun showSwipeCompletedState() {
        completionCommitted = true
        checkbox.isClickable = false
        renderCompletedCheck(animated = true)
    }

    private fun renderCompletedCheck(animated: Boolean) {
        checkbox.text = "✓"
        checkbox.textSize = 15f
        checkbox.typeface = Typeface.DEFAULT_BOLD
        checkbox.setTextColor(Color.WHITE)
        checkbox.background = GradientDrawable().apply {
            setColor(Color.parseColor("#26BE98"))
            cornerRadius = dpf(7f)
            setStroke(dp(1f), Color.parseColor("#1FAE8B"))
        }
        if (animated) {
            checkbox.scaleX = 0.82f
            checkbox.scaleY = 0.82f
            checkbox.animate()
                .scaleX(1.10f)
                .scaleY(1.10f)
                .setDuration(150)
                .withEndAction {
                    checkbox.animate().scaleX(1f).scaleY(1f).setDuration(130).start()
                }
                .start()
        } else {
            checkbox.scaleX = 1f
            checkbox.scaleY = 1f
        }
    }
}
