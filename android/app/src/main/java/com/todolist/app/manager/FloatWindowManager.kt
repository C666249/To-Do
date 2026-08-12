package com.todolist.app.manager

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.TextView
import com.todolist.app.ui.BannerView
import com.todolist.app.ui.DailyTaskBannerView
import com.todolist.app.ui.TodoReminderBannerView

object FloatWindowManager {

    private var windowManager: WindowManager? = null
    private var appContext: Context? = null
    private var dailyBannerView: BannerView? = null
    private val todoBannerViews = linkedMapOf<Long, TodoReminderBannerView>()
    private val dailyTaskBannerViews = linkedMapOf<Long, DailyTaskBannerView>()
    private var snoozeToastView: TextView? = null

    var onDailyDismissed: (() -> Unit)? = null

    private fun ensureWindowManager(context: Context): WindowManager {
        appContext = context.applicationContext
        return windowManager ?: (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).also {
            windowManager = it
        }
    }

    fun canShowOverlay(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun showBanner(
        context: Context,
        percent: Float,
        completed: Int,
        total: Int,
        inProgress: List<String>,
        todo: List<String>,
        completedItems: List<String>,
        onOpenApp: () -> Unit
    ) {
        if (!canShowOverlay(context)) return
        val wm = ensureWindowManager(context)

        dismissDailyBanner(silent = true)
        val openCb = onOpenApp
        dailyBannerView = BannerView(context).apply {
            setSummary(percent, completed, total)
            setItems(inProgress, todo, completedItems)
            this.onOpenApp = {
                openCb()
                dismissDailyBanner()
            }
            this.onDismiss = { dismissDailyBanner() }
        }

        val screenWidth = context.resources.displayMetrics.widthPixels
        val bannerWidth = (screenWidth * 0.82).toInt()
        val params = makeParams(context, wm, bannerWidth, yDp = 10f)
        wm.addView(dailyBannerView, params)
        dailyBannerView?.slideIn()
        repositionTodoBanners()
    }

    fun showTodoReminder(
        context: Context,
        id: Long,
        text: String,
        status: String,
        reminderAt: Long?,
        onSnooze: (Int) -> Unit,
        onStatusChanged: (String) -> Unit,
        onDismiss: () -> Unit,
        onOpenApp: () -> Unit
    ) {
        if (!canShowOverlay(context)) return
        val wm = ensureWindowManager(context)

        todoBannerViews[id]?.let { existing ->
            existing.setTodo(text, status, reminderAt)
            return
        }

        val statusChangedCb = onStatusChanged
        val dismissCb = onDismiss
        val openCb = onOpenApp
        val snoozeCb = onSnooze

        val view = TodoReminderBannerView(context, id, text, status, reminderAt).apply {
            this.onSnooze = { minutes ->
                dismissTodoReminder(id, silent = true)
                snoozeCb(minutes)
            }
            this.onStatusChanged = statusChangedCb
            this.onOpenApp = openCb
            this.onDismiss = {
                dismissTodoReminder(id, silent = true)
                dismissCb()
            }
            this.onSwipeDismiss = {
                dismissTodoReminder(id, silent = true)
                dismissCb()
            }
            this.onSwipeComplete = {
                // Up-swipe is an explicit "complete now" gesture for Todo.
                // Persist completed first, then clear the reminder just like X would.
                setStatus("completed")
                statusChangedCb("completed")
                dismissTodoReminder(id, silent = true)
                dismissCb()
            }
            this.onSwipeOpen = openCb
        }
        todoBannerViews[id] = view

        val screenWidth = context.resources.displayMetrics.widthPixels
        val width = (screenWidth * 0.94).toInt()
        wm.addView(view, makeParams(context, wm, width, yDp = 10f))
        view.alpha = 0f
        view.translationY = -48f * context.resources.displayMetrics.density
        view.animate().alpha(1f).translationY(0f).setDuration(280).start()
        repositionTodoBanners()
        view.post { repositionTodoBanners() }
    }

    fun showDailyTask(
        context: Context,
        id: Long,
        text: String,
        hour: Int,
        minute: Int,
        onSnooze: (Int) -> Unit,
        onComplete: () -> Unit,
        onDismiss: () -> Unit,
        onOpenApp: () -> Unit
    ) {
        if (!canShowOverlay(context)) return
        val wm = ensureWindowManager(context)
        if (dailyTaskBannerViews.containsKey(id)) return

        val dailySnoozeCb = onSnooze
        val completeCb = onComplete
        val dailyDismissCb = onDismiss
        val dailyOpenCb = onOpenApp

        val view = DailyTaskBannerView(context, id, text, hour, minute).apply {
            this.onSnooze = { minutes ->
                dismissDailyTask(id, silent = true)
                dailySnoozeCb(minutes)
            }
            this.onComplete = {
                dismissDailyTask(id, silent = true)
                completeCb()
            }
            this.onDismiss = {
                dismissDailyTask(id, silent = true)
                dailyDismissCb()
            }
            this.onOpenApp = {
                dismissDailyTask(id, silent = true)
                dailyOpenCb()
            }
            this.onSwipeDismiss = {
                dismissDailyTask(id, silent = true)
                dailyDismissCb()
            }
            this.onSwipeComplete = {
                dismissDailyTask(id, silent = true)
                completeCb()
            }
            this.onSwipeOpen = {
                dismissDailyTask(id, silent = true)
                dailyOpenCb()
            }
        }
        dailyTaskBannerViews[id] = view
        val screenWidth = context.resources.displayMetrics.widthPixels
        val width = (screenWidth * 0.94).toInt()
        wm.addView(view, makeParams(context, wm, width, yDp = 10f))
        view.alpha = 0f
        view.translationY = -42f * context.resources.displayMetrics.density
        view.animate().alpha(1f).translationY(0f).setDuration(250).start()
        repositionTodoBanners()
        view.post { repositionTodoBanners() }
    }

    fun dismissDailyTask(id: Long, silent: Boolean = false) {
        val view = dailyTaskBannerViews.remove(id) ?: return
        try { windowManager?.removeView(view) } catch (_: Exception) {}
        if (!silent) view.onDismiss?.invoke()
        repositionTodoBanners()
        clearWindowManagerIfIdle()
    }

    fun updateTodoStatus(id: Long, status: String) {
        todoBannerViews[id]?.setStatus(status)
    }

    fun updateTodo(id: Long, text: String, status: String, reminderAt: Long?) {
        todoBannerViews[id]?.setTodo(text, status, reminderAt)
    }

    fun dismissTodoReminder(id: Long, silent: Boolean = false) {
        val view = todoBannerViews.remove(id) ?: return
        try { windowManager?.removeView(view) } catch (_: Exception) {}
        if (!silent) view.onDismiss?.invoke()
        repositionTodoBanners()
        clearWindowManagerIfIdle()
    }

    fun dismissDailyBanner(silent: Boolean = false) {
        val view = dailyBannerView ?: return
        view.cancelAutoDismiss()
        view.onDismiss = null
        view.onOpenApp = null
        try { windowManager?.removeView(view) } catch (_: Exception) {}
        dailyBannerView = null
        repositionTodoBanners()
        if (!silent) onDailyDismissed?.invoke()
        onDailyDismissed = null
        clearWindowManagerIfIdle()
    }

    fun dismissAllSilently() {
        dailyBannerView?.let { view ->
            view.cancelAutoDismiss()
            view.onDismiss = null
            view.onOpenApp = null
            try { windowManager?.removeView(view) } catch (_: Exception) {}
        }
        dailyBannerView = null
        todoBannerViews.values.forEach { view ->
            view.onDismiss = null
            view.onOpenApp = null
            view.onStatusChanged = null
            view.onSnooze = null
            view.onSwipeDismiss = null
            view.onSwipeComplete = null
            view.onSwipeOpen = null
            try { windowManager?.removeView(view) } catch (_: Exception) {}
        }
        todoBannerViews.clear()
        dailyTaskBannerViews.values.forEach { view ->
            view.onDismiss = null
            view.onOpenApp = null
            view.onComplete = null
            view.onSnooze = null
            view.onSwipeDismiss = null
            view.onSwipeComplete = null
            view.onSwipeOpen = null
            try { windowManager?.removeView(view) } catch (_: Exception) {}
        }
        dailyTaskBannerViews.clear()
        snoozeToastView?.let { view -> try { windowManager?.removeView(view) } catch (_: Exception) {} }
        snoozeToastView = null
        onDailyDismissed = null
        windowManager = null
        appContext = null
    }

    fun showSnoozeToast(context: Context, minutes: Int) {
        if (!canShowOverlay(context)) return
        val wm = ensureWindowManager(context)

        // If the user snoozes again while a toast is still visible, remove the previous overlay
        // only after making it invisible. This avoids a one-frame compositor flash on some OEMs.
        snoozeToastView?.let { old ->
            old.animate().cancel()
            old.alpha = 0f
            old.visibility = View.INVISIBLE
            try { wm.removeViewImmediate(old) } catch (_: Exception) {}
        }

        val density = context.resources.displayMetrics.density
        val toast = TextView(context).apply {
            text = "$minutes 分钟后再提醒"
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#4B655C"))
            gravity = Gravity.CENTER
            setPadding((16f*density).toInt(), (9f*density).toInt(), (16f*density).toInt(), (9f*density).toInt())
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F4FBF8"))
                cornerRadius = 999f
                setStroke((1f*density).toInt().coerceAtLeast(1), Color.parseColor("#D8EEE6"))
            }
            elevation = 10f * density
            alpha = 0f
            translationY = 5f * density
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
        }
        snoozeToastView = toast
        val lp = makeParams(context, wm, WindowManager.LayoutParams.WRAP_CONTENT, yDp = 14f).apply {
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = (72f * density).toInt()
        }
        try {
            wm.addView(toast, lp)
        } catch (_: Exception) {
            snoozeToastView = null
            return
        }

        // Entrance keeps the tiny bit of lift, but exit is opacity-only. Translating an overlay
        // while its WindowManager surface is being removed can flash on some MagicOS/Android builds.
        toast.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(170L)
            .withLayer()
            .start()

        toast.postDelayed({
            if (snoozeToastView !== toast) return@postDelayed
            toast.animate().cancel()
            toast.animate()
                .alpha(0f)
                .setDuration(220L)
                .withLayer()
                .withEndAction {
                    if (snoozeToastView !== toast) return@withEndAction

                    // Hold one fully transparent frame before detaching the overlay surface.
                    // This prevents the final frame from briefly being re-composited at full alpha.
                    toast.alpha = 0f
                    toast.visibility = View.INVISIBLE
                    toast.postOnAnimation {
                        if (snoozeToastView !== toast) return@postOnAnimation
                        try { windowManager?.removeViewImmediate(toast) } catch (_: Exception) {}
                        toast.setLayerType(View.LAYER_TYPE_NONE, null)
                        snoozeToastView = null
                        clearWindowManagerIfIdle()
                    }
                }
                .start()
        }, 1450L)
    }

    fun hasAnyOverlay(): Boolean = dailyBannerView != null || todoBannerViews.isNotEmpty() || dailyTaskBannerViews.isNotEmpty() || snoozeToastView != null
    fun hasTodoOverlays(): Boolean = todoBannerViews.isNotEmpty()
    fun hasDailyTaskOverlays(): Boolean = dailyTaskBannerViews.isNotEmpty()

    private fun repositionTodoBanners() {
        val context = appContext ?: return
        val wm = windowManager ?: return
        val density = context.resources.displayMetrics.density
        val topInsetDp = if (dailyBannerView != null) 62f else 10f
        var currentY = (topInsetDp * density).toInt()
        val stackGap = (10f * density).toInt()

        dailyTaskBannerViews.values.forEach { view ->
            val lp = view.layoutParams as? WindowManager.LayoutParams ?: return@forEach
            lp.y = currentY
            try { wm.updateViewLayout(view, lp) } catch (_: Exception) {}
            val measured = view.height.takeIf { it > 0 } ?: ((62f * density).toInt())
            currentY += measured + stackGap
        }

        todoBannerViews.values.forEach { view ->
            val lp = view.layoutParams as? WindowManager.LayoutParams ?: return@forEach
            lp.y = currentY
            try { wm.updateViewLayout(view, lp) } catch (_: Exception) {}
            val measured = view.height.takeIf { it > 0 } ?: ((82f * density).toInt())
            currentY += measured + stackGap
        }
    }

    private fun makeParams(
        context: Context,
        wm: WindowManager,
        widthPx: Int,
        yDp: Float
    ): WindowManager.LayoutParams {
        val density = context.resources.displayMetrics.density
        return WindowManager.LayoutParams().apply {
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            width = widthPx
            height = WindowManager.LayoutParams.WRAP_CONTENT
            y = safeTopInset(context, wm) + (yDp * density).toInt()
        }
    }

    // Same reliable positioning pattern used by the user's FlowLedger overlay: attach with the
    // application WindowManager and account for status-bar/display-cutout insets explicitly.
    private fun safeTopInset(context: Context, wm: WindowManager): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return runCatching {
                wm.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout()
                ).top
            }.getOrDefault((30f * context.resources.displayMetrics.density).toInt())
        }
        @Suppress("DiscouragedApi")
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) context.resources.getDimensionPixelSize(id)
        else (24f * context.resources.displayMetrics.density).toInt()
    }

    private fun clearWindowManagerIfIdle() {
        if (!hasAnyOverlay()) {
            windowManager = null
            appContext = null
        }
    }
}
