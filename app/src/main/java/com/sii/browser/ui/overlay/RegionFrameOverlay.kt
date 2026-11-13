// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.ui.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.abs

/**
 * 分区翻译的常驻选框：边框层不拦触摸（框内照常操作），左上角一个拖动手柄，
 * 按住手柄移动整个选区，松手后通知调用方重译。
 */
object RegionFrameOverlay {

    private var outlineView: View? = null
    private var outlineParams: WindowManager.LayoutParams? = null
    private var handleView: View? = null
    private var handleParams: WindowManager.LayoutParams? = null
    private var manager: WindowManager? = null
    private var screenW = 0
    private var screenH = 0

    fun show(context: Context, area: Rect, onMoved: (Rect) -> Unit) {
        if (!OverlayManager.canDraw(context) || area.width() <= 0 || area.height() <= 0) return
        dismiss()
        val density = context.resources.displayMetrics.density
        val dp = { value: Int -> (value * density).toInt() }
        val wm = context.getSystemService(WindowManager::class.java) ?: return
        manager = wm
        val screen = physicalSize(context)
        screenW = screen.first
        screenH = screen.second

        val outline = OutlineView(context)
        val op = WindowManager.LayoutParams(
            area.width(),
            area.height(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = area.left
            y = area.top
            if (Build.VERSION.SDK_INT >= 30) fitInsetsTypes = 0
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
        runCatching { wm.addView(outline, op) }
        outlineView = outline
        outlineParams = op

        val size = dp(44)
        val handle = TextView(context).apply {
            text = "✥"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xE6FFB56B.toInt())
                setStroke(dp(2), 0x66FFFFFF)
            }
        }
        val hp = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = area.left
            y = area.top
            alpha = 0.9f
        }
        runCatching { wm.addView(handle, hp) }
        handleView = handle
        handleParams = hp

        var startX = 0f
        var startY = 0f
        var originLeft = 0
        var originTop = 0
        var moved = false
        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
                    originLeft = outlineParams?.x ?: 0
                    originTop = outlineParams?.y ?: 0
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val params = outlineParams ?: return@setOnTouchListener true
                    val dx = (event.rawX - startX).toInt()
                    val dy = (event.rawY - startY).toInt()
                    if (abs(dx) > 4 || abs(dy) > 4) moved = true
                    val nx = (originLeft + dx).coerceIn(0, (screenW - params.width).coerceAtLeast(0))
                    val ny = (originTop + dy).coerceIn(0, (screenH - params.height).coerceAtLeast(0))
                    params.x = nx
                    params.y = ny
                    runCatching { wm.updateViewLayout(outline, params) }
                    handleParams?.let {
                        it.x = nx
                        it.y = ny
                        runCatching { wm.updateViewLayout(handle, it) }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (moved) {
                        val params = outlineParams
                        if (params != null) {
                            onMoved(Rect(params.x, params.y, params.x + params.width, params.y + params.height))
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    /** 外部（例如重新框选）同步选区位置。 */
    fun update(area: Rect) {
        val params = outlineParams ?: return
        val wm = manager ?: return
        params.width = area.width()
        params.height = area.height()
        params.x = area.left
        params.y = area.top
        runCatching { outlineView?.let { wm.updateViewLayout(it, params) } }
        handleParams?.let {
            it.x = area.left
            it.y = area.top
            runCatching { handleView?.let { view -> wm.updateViewLayout(view, it) } }
        }
    }

    /** 截图/识别期间隐藏，避免选框线条进入 OCR 与画面指纹。 */
    fun setVisible(visible: Boolean) {
        outlineView?.visibility = if (visible) View.VISIBLE else View.GONE
        handleView?.visibility = if (visible) View.VISIBLE else View.GONE
    }

    fun dismiss() {
        val wm = manager
        val outline = outlineView
        val handle = handleView
        outlineView = null
        outlineParams = null
        handleView = null
        handleParams = null
        manager = null
        runCatching { if (wm != null && outline != null) wm.removeView(outline) }
        runCatching { if (wm != null && handle != null) wm.removeView(handle) }
    }

    /** 物理屏幕尺寸（含系统栏/挖孔），与 MediaProjection 截图坐标一致。 */
    private fun physicalSize(context: Context): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= 30) {
            val wm = context.getSystemService(WindowManager::class.java)
            val bounds = wm?.maximumWindowMetrics?.bounds
            if (bounds != null) return bounds.width() to bounds.height()
        }
        val metrics = android.util.DisplayMetrics()
        val display = (context.getSystemService(Context.DISPLAY_SERVICE) as? android.hardware.display.DisplayManager)
            ?.getDisplay(android.view.Display.DEFAULT_DISPLAY)
        display?.getRealMetrics(metrics)
        return metrics.widthPixels to metrics.heightPixels
    }

    private class OutlineView(context: Context) : View(context) {
        private val density = context.resources.displayMetrics.density
        private val accent = 0xFFFFB56B.toInt()
        private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = accent
            strokeWidth = 2f * density
        }
        private val corner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = accent
            strokeWidth = 4f * density
        }

        override fun onDraw(canvas: Canvas) {
            val inset = border.strokeWidth / 2
            canvas.drawRect(inset, inset, width - inset, height - inset, border)
            val len = 16f * density
            val l = inset
            val t = inset
            val r = width - inset
            val b = height - inset
            canvas.drawLine(l, t, l + len, t, corner)
            canvas.drawLine(l, t, l, t + len, corner)
            canvas.drawLine(r - len, t, r, t, corner)
            canvas.drawLine(r, t, r, t + len, corner)
            canvas.drawLine(l, b - len, l, b, corner)
            canvas.drawLine(l, b, l + len, b, corner)
            canvas.drawLine(r - len, b, r, b, corner)
            canvas.drawLine(r, b - len, r, b, corner)
        }
    }
}
