package com.todolist.app.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class BannerView(context: Context) : FrameLayout(context) {

    private val density: Float = context.resources.displayMetrics.density
    private val bg: GradientDrawable
    private val capsuleRow: LinearLayout
    private val summaryText: TextView
    private val detailContainer: LinearLayout
    private val detailScroll: ScrollView
    private val expandHeader: LinearLayout
    private val expandTitle: TextView
    private val expandLayout: LinearLayout
    private var isExpanded = false
    private var autoDismissRunnable: Runnable? = null
    private var exitCommitted = false
    private val handler = Handler(Looper.getMainLooper())
    var onOpenApp: (() -> Unit)? = null
    var onDismiss: (() -> Unit)? = null

    private fun dpf(f: Float): Float = f * density
    private fun dpi(f: Float): Int = (f * density).toInt()

    init {
        bg = GradientDrawable().apply {
            setColors(intArrayOf(
                Color.parseColor("#fff8f0"),
                Color.parseColor("#f5ecd8")
            ))
            orientation = GradientDrawable.Orientation.TOP_BOTTOM
            cornerRadius = dpf(22f)
        }
        background = bg
        elevation = dpf(8f)

        // --- Capsule row (compact) ---
        capsuleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(14f), dpi(8f), dpi(14f), dpi(8f))
        }
        summaryText = TextView(context).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#5c4d3c"))
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        capsuleRow.addView(summaryText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        capsuleRow.setOnClickListener {
            if (!isExpanded) doExpand()
        }
        addView(capsuleRow, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))

        // --- Expanded: header row + scrollable list ---
        expandLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }

        expandHeader = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(14f), dpi(10f), dpi(10f), dpi(6f))
        }
        expandTitle = TextView(context).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#5c4d3c"))
            typeface = Typeface.DEFAULT_BOLD
        }
        expandHeader.addView(expandTitle, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        // × close button
        expandHeader.addView(makeIconBtn("✕", "#b0a090") {
            fadeOutForAction { onDismiss?.invoke() }
        })
        // □ open app button
        expandHeader.addView(makeIconBtn("⤢", "#00d4aa") {
            fadeOutForAction(durationMs = 170L) { onOpenApp?.invoke() }
        })

        expandLayout.addView(expandHeader)

        detailScroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
        }
        detailContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(14f), 0, dpi(14f), dpi(12f))
        }
        detailScroll.addView(detailContainer)
        expandLayout.addView(detailScroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dpi(200f)
        ))

        addView(expandLayout, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
    }

    private fun makeIconBtn(symbol: String, color: String, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            text = symbol
            textSize = 16f
            setTextColor(Color.parseColor(color))
            gravity = Gravity.CENTER
            setPadding(dpi(10f), dpi(6f), dpi(10f), dpi(6f))
            setOnClickListener { onClick() }
        }
    }

    fun setSummary(percent: Float, completed: Int, total: Int) {
        val pct = "%.0f".format(percent)
        val text = "📝 今日 $pct% · $completed/$total 项"
        summaryText.text = text
        expandTitle.text = text
    }

    fun setItems(inProgress: List<String>, todo: List<String>, completed: List<String>) {
        detailContainer.removeAllViews()
        for (text in completed) {
            detailContainer.addView(makeItem(text, "●", "#00d4aa"))
        }
        for (text in inProgress) {
            detailContainer.addView(makeItem(text, "●", "#f59e0b"))
        }
        for (text in todo) {
            detailContainer.addView(makeItem(text, "●", "#ff8787"))
        }
    }

    private fun makeItem(text: String, dot: String, color: String): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(8f), dpi(4f), dpi(8f), dpi(4f))
            val itemBg = GradientDrawable().apply {
                setColor(Color.parseColor("#0d00d4aa"))
                cornerRadius = dpf(6f)
            }
            background = itemBg
            addView(TextView(context).apply {
                this.text = dot
                textSize = 14f
                setTextColor(Color.parseColor(color))
                setPadding(0, 0, dpi(8f), 0)
            })
            addView(TextView(context).apply {
                this.text = text
                textSize = 12f
                setTextColor(Color.parseColor("#5c4d3c"))
                maxLines = 1
            })
        }
    }

    private fun doExpand() {
        isExpanded = true
        cancelAutoDismiss()
        capsuleRow.visibility = View.GONE
        expandLayout.visibility = View.VISIBLE
        bg.cornerRadius = dpf(16f)
        requestLayout()
    }

    fun collapse() {
        isExpanded = false
        capsuleRow.visibility = View.VISIBLE
        expandLayout.visibility = View.GONE
        bg.cornerRadius = dpf(22f)
        requestLayout()
        scheduleAutoDismiss()
    }

    fun scheduleAutoDismiss() {
        cancelAutoDismiss()
        autoDismissRunnable = Runnable {
            fadeOutForAction(durationMs = 220L) { onDismiss?.invoke() }
        }
        handler.postDelayed(autoDismissRunnable!!, 5000)
    }

    private fun fadeOutForAction(durationMs: Long = 200L, action: () -> Unit) {
        if (exitCommitted) return
        exitCommitted = true
        cancelAutoDismiss()
        animate().cancel()
        setLayerType(View.LAYER_TYPE_HARDWARE, null)
        animate()
            .alpha(0f)
            .setDuration(durationMs)
            .withLayer()
            .withEndAction {
                alpha = 0f
                visibility = View.INVISIBLE
                postOnAnimation {
                    action()
                    setLayerType(View.LAYER_TYPE_NONE, null)
                }
            }
            .start()
    }

    fun cancelAutoDismiss() { autoDismissRunnable?.let { handler.removeCallbacks(it) } }

    fun slideIn() {
        translationY = -100f * density
        alpha = 0f
        animate().translationY(0f).alpha(1f).setDuration(450)
            .setInterpolator(OvershootInterpolator(0.7f)).start()
        scheduleAutoDismiss()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancelAutoDismiss()
    }
}
