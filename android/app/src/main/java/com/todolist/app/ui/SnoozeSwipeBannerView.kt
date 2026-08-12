package com.todolist.app.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Shared physical gesture layer for reminder banners.
 *
 * At rest the banner stays a single clean row. Gesture actions only reveal while dragging:
 *  - right: +5 min / +10 min snooze
 *  - left: dismiss this reminder (same semantics as the X button)
 *  - up: complete immediately
 *  - down: open the app (same semantics as tapping the banner body)
 */
abstract class SnoozeSwipeBannerView(context: Context) : FrameLayout(context) {

    private enum class DragMode { NONE, RIGHT, LEFT, UP, DOWN }

    private val density = context.resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val underlay = FrameLayout(context)
    private val gestureHint = TextView(context)
    protected val cardSurface = FrameLayout(context)

    var onSnooze: ((Int) -> Unit)? = null
    var onSwipeDismiss: (() -> Unit)? = null
    var onSwipeComplete: (() -> Unit)? = null
    var onSwipeOpen: (() -> Unit)? = null

    private var downX = 0f
    private var downY = 0f
    private var mode = DragMode.NONE
    private var currentLevel = 0
    private var actionArmed = false
    private var committed = false

    protected fun dp(v: Float) = (v * density).toInt()
    protected fun dpf(v: Float) = v * density

    init {
        clipChildren = true
        clipToPadding = true
        setBackgroundColor(Color.TRANSPARENT)

        applyUnderlay(DragMode.NONE, 0, false)
        addView(
            underlay,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        gestureHint.apply {
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            alpha = 0f
            includeFontPadding = false
            setPadding(dp(16f), dp(8f), dp(16f), dp(8f))
        }
        underlay.addView(
            gestureHint,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        applySurfaceAccent(DragMode.NONE, 0, false)
        cardSurface.elevation = dpf(10f)
        addView(
            cardSurface,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )
        isClickable = true
    }

    protected fun setCardPadding(left: Float, top: Float, right: Float, bottom: Float) {
        cardSurface.setPadding(dp(left), dp(top), dp(right), dp(bottom))
    }

    protected fun setCardElevation(dpValue: Float) {
        cardSurface.elevation = dpf(dpValue)
    }

    /** Child banner may update its real checkbox/status before the upward fade begins. */
    protected open fun showSwipeCompletedState() = Unit

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (committed) return true
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                beginGesture(ev.x, ev.y)
                return false
            }

            MotionEvent.ACTION_MOVE -> {
                if (mode == DragMode.NONE) {
                    mode = resolveMode(ev.x - downX, ev.y - downY)
                    if (mode != DragMode.NONE) {
                        parent?.requestDisallowInterceptTouchEvent(true)
                        return true
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return mode != DragMode.NONE
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (committed) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                beginGesture(event.x, event.y)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (mode == DragMode.NONE) {
                    mode = resolveMode(event.x - downX, event.y - downY)
                    if (mode == DragMode.NONE) return true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                updateDrag(event.x - downX, event.y - downY)
                return true
            }

            MotionEvent.ACTION_UP -> {
                val dx = event.x - downX
                val dy = event.y - downY
                when (mode) {
                    DragMode.RIGHT -> {
                        val level = snoozeLevel(max(0f, dx))
                        if (level == 5 || level == 10) commitSnooze(level) else resetVisuals(true)
                    }
                    DragMode.LEFT -> if (isLeftArmed(dx)) commitDismiss() else resetVisuals(true)
                    DragMode.UP -> if (isVerticalArmed(dy)) commitComplete() else resetVisuals(true)
                    DragMode.DOWN -> if (isVerticalArmed(dy)) commitOpen() else resetVisuals(true)
                    DragMode.NONE -> resetVisuals(true)
                }
                parent?.requestDisallowInterceptTouchEvent(false)
                mode = DragMode.NONE
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                mode = DragMode.NONE
                resetVisuals(true)
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun beginGesture(x: Float, y: Float) {
        downX = x
        downY = y
        mode = DragMode.NONE
        currentLevel = 0
        actionArmed = false
        cardSurface.animate().cancel()
        gestureHint.animate().cancel()
        animate().cancel()
    }

    private fun resolveMode(dx: Float, dy: Float): DragMode {
        if (abs(dx) <= touchSlop && abs(dy) <= touchSlop) return DragMode.NONE
        return when {
            abs(dx) > abs(dy) * 1.14f -> if (dx >= 0f) DragMode.RIGHT else DragMode.LEFT
            abs(dy) > abs(dx) * 1.14f -> if (dy >= 0f) DragMode.DOWN else DragMode.UP
            else -> DragMode.NONE
        }
    }

    private fun updateDrag(dx: Float, dy: Float) {
        when (mode) {
            DragMode.RIGHT -> updateRight(max(0f, dx))
            DragMode.LEFT -> updateLeft(min(0f, dx))
            DragMode.UP -> updateVertical(min(0f, dy), DragMode.UP)
            DragMode.DOWN -> updateVertical(max(0f, dy), DragMode.DOWN)
            DragMode.NONE -> Unit
        }
    }

    private fun updateRight(rawDx: Float) {
        val w = width.takeIf { it > 0 }?.toFloat() ?: return
        val cap = w * 0.62f
        val resisted = if (rawDx <= cap) rawDx else cap + (rawDx - cap) * 0.22f
        cardSurface.translationX = min(resisted, w * 0.78f)
        cardSurface.translationY = 0f

        val nextLevel = snoozeLevel(rawDx)
        val newlyArmed = nextLevel != 0
        if (nextLevel != currentLevel) {
            currentLevel = nextLevel
            if (nextLevel != 0) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
        actionArmed = newlyArmed
        applySurfaceAccent(DragMode.RIGHT, nextLevel, newlyArmed)
        applyUnderlay(DragMode.RIGHT, nextLevel, newlyArmed)

        when (nextLevel) {
            10 -> {
                gestureHint.text = "+10 min"
                gestureHint.setTextColor(Color.parseColor("#318DB1"))
                gestureHint.alpha = 1f
                gestureHint.scaleX = 1.04f
                gestureHint.scaleY = 1.04f
            }
            5 -> {
                gestureHint.text = "+5 min"
                gestureHint.setTextColor(Color.parseColor("#32A587"))
                gestureHint.alpha = 1f
                gestureHint.scaleX = 1f
                gestureHint.scaleY = 1f
            }
            else -> {
                gestureHint.text = "右滑延后"
                gestureHint.setTextColor(Color.parseColor("#8EA89F"))
                gestureHint.alpha = min(0.72f, rawDx / max(1f, w * 0.18f))
                gestureHint.scaleX = 0.96f
                gestureHint.scaleY = 0.96f
            }
        }
    }

    private fun updateLeft(rawDx: Float) {
        val w = width.takeIf { it > 0 }?.toFloat() ?: return
        val distance = abs(rawDx)
        val threshold = leftThreshold(w)
        val wasArmed = actionArmed
        actionArmed = distance >= threshold
        if (actionArmed && !wasArmed) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)

        val cap = w * 0.58f
        val resisted = if (distance <= cap) distance else cap + (distance - cap) * 0.20f
        cardSurface.translationX = -min(resisted, w * 0.76f)
        cardSurface.translationY = 0f

        applySurfaceAccent(DragMode.LEFT, 0, actionArmed)
        applyUnderlay(DragMode.LEFT, 0, actionArmed)
        gestureHint.text = if (actionArmed) "关闭提醒  ×" else "左滑关闭"
        gestureHint.setTextColor(Color.parseColor(if (actionArmed) "#D45E66" else "#B88D90"))
        gestureHint.gravity = Gravity.CENTER_VERTICAL or Gravity.END
        gestureHint.alpha = min(1f, 0.28f + distance / max(1f, threshold) * 0.72f)
        gestureHint.scaleX = if (actionArmed) 1.04f else 0.97f
        gestureHint.scaleY = gestureHint.scaleX
    }

    private fun updateVertical(rawDy: Float, dragMode: DragMode) {
        val h = height.takeIf { it > 0 }?.toFloat() ?: dpf(64f)
        val distance = abs(rawDy)
        val threshold = verticalThreshold(h)
        val wasArmed = actionArmed
        actionArmed = distance >= threshold
        if (actionArmed && !wasArmed) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)

        val cap = max(dpf(56f), h * 0.72f)
        val resisted = if (distance <= cap) distance else cap + (distance - cap) * 0.18f
        val signed = if (dragMode == DragMode.UP) -resisted else resisted
        cardSurface.translationY = signed
        cardSurface.translationX = 0f

        applySurfaceAccent(dragMode, 0, actionArmed)
        applyUnderlay(dragMode, 0, actionArmed)
        if (dragMode == DragMode.UP) {
            gestureHint.text = if (actionArmed) "✓  完成" else "上滑完成"
            gestureHint.setTextColor(Color.parseColor(if (actionArmed) "#26996F" else "#7BAE98"))
            gestureHint.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        } else {
            gestureHint.text = if (actionArmed) "打开应用  ↓" else "下滑打开"
            gestureHint.setTextColor(Color.parseColor(if (actionArmed) "#687FB4" else "#929DB8"))
            gestureHint.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        }
        gestureHint.alpha = min(1f, 0.25f + distance / max(1f, threshold) * 0.75f)
        gestureHint.scaleX = if (actionArmed) 1.03f else 0.97f
        gestureHint.scaleY = gestureHint.scaleX
    }

    private fun snoozeLevel(distance: Float): Int {
        val w = width.takeIf { it > 0 }?.toFloat() ?: return 0
        return when {
            distance >= w * 0.38f -> 10
            distance >= w * 0.18f -> 5
            else -> 0
        }
    }

    private fun leftThreshold(w: Float): Float = max(dpf(68f), w * 0.22f)

    private fun isLeftArmed(dx: Float): Boolean {
        val w = width.takeIf { it > 0 }?.toFloat() ?: return false
        return dx <= -leftThreshold(w)
    }

    private fun verticalThreshold(h: Float): Float = max(dpf(40f), h * 0.44f)

    private fun isVerticalArmed(dy: Float): Boolean {
        val h = height.takeIf { it > 0 }?.toFloat() ?: dpf(64f)
        return abs(dy) >= verticalThreshold(h)
    }

    private fun applySurfaceAccent(dragMode: DragMode, level: Int, armed: Boolean) {
        val stroke = when (dragMode) {
            DragMode.RIGHT -> when (level) {
                10 -> "#CFE7F0"
                5 -> "#D2ECE3"
                else -> "#EEE7DE"
            }
            DragMode.LEFT -> if (armed) "#F0C5C8" else "#F1DCDD"
            DragMode.UP -> if (armed) "#C7E8D9" else "#DCECE5"
            DragMode.DOWN -> if (armed) "#D3DCF1" else "#E3E7F0"
            DragMode.NONE -> "#EEE7DE"
        }
        cardSurface.background = GradientDrawable().apply {
            setColor(Color.parseColor("#FFFEFB"))
            cornerRadius = dpf(18f)
            setStroke(dp(1f).coerceAtLeast(1), Color.parseColor(stroke))
        }
    }

    private fun applyUnderlay(dragMode: DragMode, level: Int, armed: Boolean) {
        val colors = when (dragMode) {
            DragMode.RIGHT -> if (level == 10) {
                intArrayOf(Color.parseColor("#EEF7FA"), Color.parseColor("#E9F4F8"))
            } else {
                intArrayOf(Color.parseColor("#EEF9F5"), Color.parseColor("#EEF6FA"))
            }
            DragMode.LEFT -> intArrayOf(
                Color.parseColor(if (armed) "#FFF0F1" else "#FBF5F3"),
                Color.parseColor("#FFF7F4")
            )
            DragMode.UP -> intArrayOf(
                Color.parseColor(if (armed) "#ECF9F3" else "#F4F9F6"),
                Color.parseColor("#F5FBF8")
            )
            DragMode.DOWN -> intArrayOf(
                Color.parseColor(if (armed) "#F0F3FA" else "#F7F8FB"),
                Color.parseColor("#F8F7FB")
            )
            DragMode.NONE -> intArrayOf(Color.parseColor("#F8F5F2"), Color.parseColor("#F8F5F2"))
        }
        underlay.background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, colors).apply {
            cornerRadius = dpf(18f)
            setStroke(dp(1f).coerceAtLeast(1), Color.parseColor("#E8E1DA"))
        }
        gestureHint.gravity = when (dragMode) {
            DragMode.RIGHT -> Gravity.CENTER_VERTICAL or Gravity.START
            DragMode.LEFT -> Gravity.CENTER_VERTICAL or Gravity.END
            DragMode.UP -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            DragMode.DOWN -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
            DragMode.NONE -> Gravity.CENTER
        }
    }

    /**
     * Every user-triggered disappearance uses one path: opacity only.
     * We freeze the card at the exact finger-release position, fade the whole overlay,
     * hold one fully transparent compositor frame, then let the manager detach it.
     */
    protected fun fadeOutForAction(
        startDelayMs: Long = 0L,
        durationMs: Long = 190L,
        action: () -> Unit
    ) {
        if (committed) return
        committed = true
        cardSurface.animate().cancel()
        gestureHint.animate().cancel()
        animate().cancel()
        setLayerType(View.LAYER_TYPE_HARDWARE, null)
        animate()
            .alpha(0f)
            .setStartDelay(startDelayMs)
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

    private fun commitSnooze(minutes: Int) {
        if (committed) return
        gestureHint.text = "+$minutes min  ✓"
        gestureHint.alpha = 1f
        fadeOutForAction(durationMs = 190L) { onSnooze?.invoke(minutes) }
    }

    private fun commitDismiss() {
        if (committed) return
        gestureHint.text = "关闭提醒  ✓"
        gestureHint.alpha = 1f
        fadeOutForAction(durationMs = 185L) { onSwipeDismiss?.invoke() }
    }

    private fun commitComplete() {
        if (committed) return
        showSwipeCompletedState()
        gestureHint.text = "✓  已完成"
        gestureHint.alpha = 1f
        fadeOutForAction(startDelayMs = 70L, durationMs = 190L) { onSwipeComplete?.invoke() }
    }

    private fun commitOpen() {
        if (committed) return
        gestureHint.text = "打开应用  ✓"
        gestureHint.alpha = 1f
        fadeOutForAction(durationMs = 165L) { onSwipeOpen?.invoke() }
    }

    private fun resetVisuals(animated: Boolean) {
        currentLevel = 0
        actionArmed = false
        applySurfaceAccent(DragMode.NONE, 0, false)
        applyUnderlay(DragMode.NONE, 0, false)
        if (animated) {
            cardSurface.animate()
                .translationX(0f)
                .translationY(0f)
                .alpha(1f)
                .setDuration(210L)
                .start()
            gestureHint.animate().alpha(0f).setDuration(145L).start()
        } else {
            cardSurface.translationX = 0f
            cardSurface.translationY = 0f
            cardSurface.alpha = 1f
            gestureHint.alpha = 0f
            alpha = 1f
        }
    }
}
