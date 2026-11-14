// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.ui.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 统一的悬浮窗管理：屏幕翻译结果与 MCP 状态浮窗共用一套窗口和权限检查，
 * 避免各自创建窗口导致叠加、残留或权限引导重复。
 */
object OverlayManager {

    private const val PREFS = "sii_overlay"
    private const val KEY_TRANSLATE_Y = "translate_y"
    private const val KEY_BALL_X = "ball_x"
    private const val KEY_BALL_Y = "ball_y"

    private var translateView: View? = null
    private var mcpView: View? = null
    private var ballView: View? = null
    private var ballParams: WindowManager.LayoutParams? = null
    private var ballMenuView: View? = null
    private var loadingView: View? = null
    private var ballSpin: android.animation.ValueAnimator? = null

    fun canDraw(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun showTranslateResult(
        context: Context,
        title: String,
        original: String,
        translated: String,
        speakLabel: String,
        loopLabel: String,
        closeLabel: String,
        retryLabel: String,
        copyLabel: String,
        looping: Boolean,
        textSize: Float,
        onSpeak: () -> Unit,
        onToggleLoop: () -> Unit,
        onRetry: () -> Unit,
        onCopy: () -> Unit,
        onClose: () -> Unit
    ) {
        if (!canDraw(context)) return
        dismissTranslate()
        val density = context.resources.displayMetrics.density
        val dp = { value: Int -> (value * density).toInt() }
        val accent = 0xFFFFB56B.toInt()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(10))
            background = GradientDrawable().apply {
                setColor(0xF21B1F24.toInt())
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), 0x33FFFFFF)
            }
        }
        card.addView(TextView(context).apply {
            text = title
            setTextColor(0xFFFFFFFF.toInt())
            this.textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
        })
        if (original.isNotBlank()) {
            val originalView = TextView(context).apply {
                text = original
                setTextColor(0xFFB8BEC7.toInt())
                this.textSize = 12f
                maxLines = 2
                setPadding(0, dp(6), 0, 0)
                setOnClickListener {
                    maxLines = if (maxLines == 2) 8 else 2
                }
            }
            card.addView(originalView)
        }
        card.addView(TextView(context).apply {
            text = translated
            setTextColor(0xFFFFFFFF.toInt())
            this.textSize = textSize
            setPadding(0, dp(8), 0, dp(2))
        })
        val scroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
        }
        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        fun action(label: String, callback: () -> Unit) {
            actions.addView(TextView(context).apply {
                text = label
                setTextColor(accent)
                this.textSize = 13f
                setPadding(dp(8), dp(8), dp(8), 0)
                setOnClickListener { callback() }
            })
        }
        action(speakLabel, onSpeak)
        action(if (looping) "✓ $loopLabel" else loopLabel, onToggleLoop)
        action(retryLabel, onRetry)
        action(copyLabel, onCopy)
        action(closeLabel, onClose)
        scroll.addView(actions)
        card.addView(scroll)

        val width = context.resources.displayMetrics.widthPixels - dp(24)
        val params = WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = prefs.getInt(KEY_TRANSLATE_Y, dp(72))
        }
        val manager = context.getSystemService(WindowManager::class.java)
        runCatching { manager.addView(card, params) }
        translateView = card
        attachDrag(context, card, params) {
            prefs.edit().putInt(KEY_TRANSLATE_Y, params.y).apply()
        }
    }

    fun showMcpStatus(
        context: Context,
        text: String,
        onClick: () -> Unit,
        onClose: () -> Unit
    ) {
        if (!canDraw(context)) return
        dismissMcp()
        val density = context.resources.displayMetrics.density
        val dp = { value: Int -> (value * density).toInt() }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                setColor(0xE61B1F24.toInt())
                cornerRadius = dp(22).toFloat()
                setStroke(dp(1), 0x33FFFFFF)
            }
        }
        row.addView(TextView(context).apply {
            this.text = text
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 13f
            setOnClickListener { onClick() }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(TextView(context).apply {
            this.text = "×"
            setTextColor(0xFFB8BEC7.toInt())
            textSize = 18f
            setPadding(dp(12), 0, dp(4), 0)
            setOnClickListener { onClose() }
        })
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = dp(72)
        }
        val manager = context.getSystemService(WindowManager::class.java)
        runCatching { manager.addView(row, params) }
        mcpView = row
        attachDrag(context, row, params)
    }

    fun dismissTranslate() {
        remove(translateView)
        translateView = null
    }

    fun dismissMcp() {
        remove(mcpView)
        mcpView = null
    }

    /** 屏幕翻译常驻悬浮球：单击翻译，长按退出。 */
    /**
     * 悬浮球：单击执行当前模式、长按出菜单、拖动后弹性吸附左右边缘、位置记忆。
     */
    fun showTranslateBall(
        context: Context,
        sizeDp: Int,
        alpha: Int,
        snapEdge: Boolean,
        onClick: () -> Unit,
        onLongClick: () -> Unit
    ) {
        if (!canDraw(context)) return
        dismissBall()
        val density = context.resources.displayMetrics.density
        val dp = { value: Int -> (value * density).toInt() }
        val size = dp(sizeDp.coerceIn(40, 72))
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val screenW = context.resources.displayMetrics.widthPixels
        val ball = TextView(context).apply {
            text = "文"
            this.textSize = 18f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFFFFC07A.toInt(), 0xFFF2984A.toInt())
            ).apply {
                shape = GradientDrawable.OVAL
                setStroke(dp(2), 0x66FFFFFF)
            }
            setShadowLayer(dp(4).toFloat(), 0f, dp(2).toFloat(), 0x55000000)
        }
        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt(KEY_BALL_X, screenW - size - dp(12))
            y = prefs.getInt(KEY_BALL_Y, dp(180))
            this.alpha = alpha.coerceIn(30, 100) / 100f
        }
        val manager = context.getSystemService(WindowManager::class.java)
        runCatching { manager.addView(ball, params) }
        ballView = ball
        ballParams = params

        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        var longFired = false
        var downAt = 0L
        val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
        val longPress = Runnable {
            if (!moved) {
                longFired = true
                onLongClick()
            }
        }
        ball.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    moved = false
                    longFired = false
                    downAt = android.os.SystemClock.elapsedRealtime()
                    handler.postDelayed(longPress, 500L)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (kotlin.math.abs(dx) > slop || kotlin.math.abs(dy) > slop) moved = true
                    if (moved) {
                        params.x = (startX + dx.toInt()).coerceIn(0, screenW - size)
                        params.y = (startY + dy.toInt()).coerceAtLeast(0)
                        runCatching { manager.updateViewLayout(ball, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPress)
                    val elapsed = android.os.SystemClock.elapsedRealtime() - downAt
                    if (!moved && !longFired && event.actionMasked == MotionEvent.ACTION_UP &&
                        elapsed < 500L
                    ) {
                        onClick()
                    } else if (moved && snapEdge) {
                        // 吸附到最近一侧并半藏，拖动时可再拉出。
                        val target = if (params.x + size / 2 < screenW / 2) -size / 2
                        else screenW - size / 2
                        animateBallX(manager, ball, params, target)
                    }
                    prefs.edit()
                        .putInt(KEY_BALL_X, params.x)
                        .putInt(KEY_BALL_Y, params.y)
                        .apply()
                    true
                }
                else -> true
            }
        }
    }

    private fun animateBallX(
        manager: WindowManager,
        view: View,
        params: WindowManager.LayoutParams,
        target: Int
    ) {
        android.animation.ValueAnimator.ofInt(params.x, target).apply {
            duration = 180L
            addUpdateListener { anim ->
                params.x = anim.animatedValue as Int
                runCatching { manager.updateViewLayout(view, params) }
            }
            start()
        }
    }

    fun dismissBall() {
        remove(ballView)
        ballView = null
        ballParams = null
        dismissBallMenu()
    }

    /**
     * 只改现有悬浮球的尺寸/透明度，不重建窗口。
     * 重启服务会让 startForegroundService 与 stopService 竞争，拖动滑杆时会被系统判为超时崩溃。
     */
    fun updateBallStyle(context: Context, sizeDp: Int, alpha: Int) {
        val ball = ballView ?: return
        val params = ballParams ?: return
        val size = (sizeDp.coerceIn(40, 72) * context.resources.displayMetrics.density).toInt()
        params.width = size
        params.height = size
        params.alpha = alpha.coerceIn(30, 100) / 100f
        val manager = context.getSystemService(WindowManager::class.java) ?: return
        runCatching { manager.updateViewLayout(ball, params) }
    }

    /** 点球翻译时给球一个忙碌状态（文字变省略号、变淡、旋转），完成/失败后恢复。 */
    fun setBallBusy(context: Context, busy: Boolean) {
        val ball = ballView as? TextView ?: return
        ball.text = if (busy) "…" else "文"
        ball.alpha = if (busy) 0.7f else 1f
        if (busy) {
            ballSpin?.cancel()
            ballSpin = android.animation.ValueAnimator.ofFloat(0f, 360f).apply {
                duration = 900L
                repeatCount = android.animation.ValueAnimator.INFINITE
                addUpdateListener { anim -> ball.rotation = anim.animatedValue as Float }
                start()
            }
        } else {
            ballSpin?.cancel()
            ballSpin = null
            ball.rotation = 0f
        }
    }

    /** 截图前隐藏悬浮球，避免 OCR 把自己的球识别进去。 */
    fun setBallVisible(visible: Boolean) {
        ballView?.visibility = if (visible) View.VISIBLE else View.GONE
    }

    /** 悬浮球菜单：条目由调用方按当前状态组装；点菜单以外的地方会关闭并回调 onDismiss。 */
    fun showBallMenu(
        context: Context,
        items: List<Pair<String, () -> Unit>>,
        onDismiss: (() -> Unit)? = null
    ) {
        if (!canDraw(context)) return
        dismissBallMenu()
        val density = context.resources.displayMetrics.density
        val dp = { value: Int -> (value * density).toInt() }
        val accent = 0xFFFFB56B.toInt()
        val menu = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                setColor(0xF21B1F24.toInt())
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), 0x33FFFFFF)
            }
        }
        items.forEach { (label, callback) ->
            menu.addView(TextView(context).apply {
                this.text = label
                setTextColor(accent)
                this.textSize = 14f
                setPadding(dp(4), dp(10), dp(24), dp(10))
                setOnClickListener {
                    dismissBallMenu()
                    callback()
                }
            })
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(12)
            y = dp(244)
        }
        menu.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                dismissBallMenu()
                onDismiss?.invoke()
                true
            } else {
                false
            }
        }
        val manager = context.getSystemService(WindowManager::class.java)
        runCatching { manager.addView(menu, params) }
        ballMenuView = menu
    }

    fun dismissBallMenu() {
        remove(ballMenuView)
        ballMenuView = null
    }

    /** 屏幕翻译/识别过程中的小提示条。 */
    fun showLoading(context: Context, text: String) {
        if (!canDraw(context)) return
        hideLoading()
        val density = context.resources.displayMetrics.density
        val dp = { value: Int -> (value * density).toInt() }
        val label = TextView(context).apply {
            this.text = text
            setTextColor(0xFFFFFFFF.toInt())
            this.textSize = 13f
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(0xF21B1F24.toInt())
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(120)
        }
        val manager = context.getSystemService(WindowManager::class.java)
        runCatching { manager.addView(label, params) }
        loadingView = label
    }

    fun hideLoading() {
        remove(loadingView)
        loadingView = null
    }

    private fun remove(view: View?) {
        val target = view ?: return
        runCatching {
            (target.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                .removeView(target)
        }
    }

    private fun attachDrag(
        context: Context,
        view: View,
        params: WindowManager.LayoutParams,
        onMoved: (() -> Unit)? = null
    ) {
        val manager = context.getSystemService(WindowManager::class.java) ?: return
        var downY = 0f
        var startY = 0
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = event.rawY
                    startY = params.y
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val delta = (event.rawY - downY).toInt()
                    if (kotlin.math.abs(delta) > 8) {
                        params.y = (startY + delta).coerceAtLeast(0)
                        runCatching { manager.updateViewLayout(view, params) }
                    }
                    false
                }
                MotionEvent.ACTION_UP -> {
                    onMoved?.invoke()
                    false
                }
                else -> false
            }
        }
    }
}
