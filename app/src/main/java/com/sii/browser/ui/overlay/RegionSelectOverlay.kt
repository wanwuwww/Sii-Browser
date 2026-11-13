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
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.sii.browser.R

/**
 * 分区翻译的框选层：全屏遮罩 + 拖动选框 + 底部取消/确定。
 * 只在框选期间存在，确认或取消立即移除窗口，不常驻、不拦截后续触摸。
 */
object RegionSelectOverlay {

    private var root: FrameLayout? = null

    fun show(
        context: Context,
        initial: Rect? = null,
        hint: String,
        onConfirm: (Rect) -> Unit,
        onCancel: () -> Unit
    ) {
        if (!OverlayManager.canDraw(context)) return
        dismiss()
        val density = context.resources.displayMetrics.density
        val dp = { value: Int -> (value * density).toInt() }
        val minSize = dp(48)

        val picker = PickerView(context).apply {
            this.hint = hint
            initial?.let { selection.set(it.left, it.top, it.right, it.bottom) }
        }
        val container = FrameLayout(context).apply {
            addView(picker, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
        }
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(16), 0, dp(16), 0)
        }
        fun button(label: String, filled: Boolean, onClick: () -> Unit) =
            TextView(context).apply {
                text = label
                textSize = 15f
                gravity = Gravity.CENTER
                setPadding(dp(28), dp(11), dp(28), dp(11))
                setTextColor(if (filled) 0xFF2A1A08.toInt() else 0xFFFFFFFF.toInt())
                background = GradientDrawable().apply {
                    cornerRadius = dp(22).toFloat()
                    setColor(if (filled) 0xFFFFB56B.toInt() else 0xF21B1F24.toInt())
                    if (!filled) setStroke(dp(1), 0x33FFFFFF)
                }
                setOnClickListener { onClick() }
            }
        bar.addView(button(context.getString(android.R.string.cancel), false) {
            dismiss()
            onCancel()
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        bar.addView(button(context.getString(android.R.string.ok), true) {
            val selected = Rect(picker.selection)
            if (selected.width() < minSize || selected.height() < minSize) {
                android.widget.Toast.makeText(
                    context,
                    R.string.screen_translate_region_tiny,
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                return@button
            }
            selected.set(
                selected.left.coerceIn(0, picker.width),
                selected.top.coerceIn(0, picker.height),
                selected.right.coerceIn(0, picker.width),
                selected.bottom.coerceIn(0, picker.height)
            )
            dismiss()
            onConfirm(selected)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = dp(10) })
        container.addView(bar, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ).apply { bottomMargin = dp(56) })

        val screen = physicalSize(context)
        val params = WindowManager.LayoutParams(
            screen.first,
            screen.second,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= 30) fitInsetsTypes = 0
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
        val manager = context.getSystemService(WindowManager::class.java)
        runCatching { manager.addView(container, params) }
        root = container
    }

    fun dismiss() {
        val target = root ?: return
        root = null
        runCatching {
            (target.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                .removeView(target)
        }
    }

    /** 物理屏幕尺寸（含系统栏/挖孔），与 MediaProjection 截图坐标一致。 */
    private fun physicalSize(context: Context): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= 30) {
            val manager = context.getSystemService(android.view.WindowManager::class.java)
            val bounds = manager?.maximumWindowMetrics?.bounds
            if (bounds != null) return bounds.width() to bounds.height()
        }
        val metrics = android.util.DisplayMetrics()
        val display = (context.getSystemService(Context.DISPLAY_SERVICE) as? android.hardware.display.DisplayManager)
            ?.getDisplay(android.view.Display.DEFAULT_DISPLAY)
        display?.getRealMetrics(metrics)
        return metrics.widthPixels to metrics.heightPixels
    }

    /** 拖动框选：空白处按下画新框，拖框内移动、拖边/角缩放；遮罩只覆盖选框外。 */
    private class PickerView(context: Context) : View(context) {

        val selection = Rect()
        var hint: String = ""

        private var startX = 0f
        private var startY = 0f
        private var dragging = false
        private var mode = MODE_NONE
        private val origin = Rect()
        private val density = context.resources.displayMetrics.density
        private val minSize = (48 * density).toInt()

        companion object {
            private const val MODE_NONE = 0
            private const val MODE_NEW = 1
            private const val MODE_MOVE = 2
            private const val MODE_LEFT = 3
            private const val MODE_TOP = 4
            private const val MODE_RIGHT = 5
            private const val MODE_BOTTOM = 6
            private const val MODE_TOP_LEFT = 7
            private const val MODE_TOP_RIGHT = 8
            private const val MODE_BOTTOM_LEFT = 9
            private const val MODE_BOTTOM_RIGHT = 10
        }
        private val maskPaint = Paint().apply { color = 0x99000000.toInt() }
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = 0xFFFFB56B.toInt()
            strokeWidth = 2f * density
        }
        private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = 0xFFFFB56B.toInt()
            strokeWidth = 4f * density
        }
        private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            textSize = 14f * density
            textAlign = Paint.Align.CENTER
        }
        private val frame = Rect()

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            if (!selection.isEmpty) {
                selection.set(
                    selection.left.coerceIn(0, w),
                    selection.top.coerceIn(0, h),
                    selection.right.coerceIn(0, w),
                    selection.bottom.coerceIn(0, h)
                )
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val x = event.rawX
            val y = event.rawY
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = x
                    startY = y
                    origin.set(selection)
                    mode = hitTest(x, y)
                    if (mode == MODE_NONE) {
                        selection.setEmpty()
                        mode = MODE_NEW
                    }
                    dragging = true
                    invalidate()
                }
                MotionEvent.ACTION_MOVE -> if (dragging) {
                    when (mode) {
                        MODE_MOVE -> move(x, y)
                        MODE_NEW -> update(x, y)
                        else -> resize(x, y)
                    }
                    invalidate()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                    dragging = false
                    mode = MODE_NONE
                    invalidate()
                }
                else -> return false
            }
            return true
        }

        /** 命中已有选框：四角、四边、内部；都不中则开始新框。 */
        private fun hitTest(x: Float, y: Float): Int {
            if (selection.isEmpty) return MODE_NONE
            val t = 24f * density
            val nearLeft = kotlin.math.abs(x - selection.left) <= t
            val nearRight = kotlin.math.abs(x - selection.right) <= t
            val nearTop = kotlin.math.abs(y - selection.top) <= t
            val nearBottom = kotlin.math.abs(y - selection.bottom) <= t
            val inY = y >= selection.top - t && y <= selection.bottom + t
            val inX = x >= selection.left - t && x <= selection.right + t
            return when {
                nearLeft && nearTop -> MODE_TOP_LEFT
                nearRight && nearTop -> MODE_TOP_RIGHT
                nearLeft && nearBottom -> MODE_BOTTOM_LEFT
                nearRight && nearBottom -> MODE_BOTTOM_RIGHT
                nearLeft && inY -> MODE_LEFT
                nearRight && inY -> MODE_RIGHT
                nearTop && inX -> MODE_TOP
                nearBottom && inX -> MODE_BOTTOM
                selection.contains(x.toInt(), y.toInt()) -> MODE_MOVE
                else -> MODE_NONE
            }
        }

        private fun move(x: Float, y: Float) {
            val w = origin.width()
            val h = origin.height()
            val left = (origin.left + (x - startX).toInt())
                .coerceIn(0, (width - w).coerceAtLeast(0))
            val top = (origin.top + (y - startY).toInt())
                .coerceIn(0, (height - h).coerceAtLeast(0))
            selection.set(left, top, left + w, top + h)
        }

        private fun resize(x: Float, y: Float) {
            var left = origin.left
            var top = origin.top
            var right = origin.right
            var bottom = origin.bottom
            when (mode) {
                MODE_LEFT, MODE_TOP_LEFT, MODE_BOTTOM_LEFT ->
                    left = x.toInt().coerceIn(0, (origin.right - minSize).coerceAtLeast(0))
                MODE_RIGHT, MODE_TOP_RIGHT, MODE_BOTTOM_RIGHT ->
                    right = x.toInt().coerceIn((origin.left + minSize).coerceAtMost(width), width)
            }
            when (mode) {
                MODE_TOP, MODE_TOP_LEFT, MODE_TOP_RIGHT ->
                    top = y.toInt().coerceIn(0, (origin.bottom - minSize).coerceAtLeast(0))
                MODE_BOTTOM, MODE_BOTTOM_LEFT, MODE_BOTTOM_RIGHT ->
                    bottom = y.toInt().coerceIn((origin.top + minSize).coerceAtMost(height), height)
            }
            selection.set(left, top, right, bottom)
        }

        private fun update(x: Float, y: Float) {
            val left = minOf(startX, x).toInt().coerceIn(0, width)
            val top = minOf(startY, y).toInt().coerceIn(0, height)
            val right = maxOf(startX, x).toInt().coerceIn(0, width)
            val bottom = maxOf(startY, y).toInt().coerceIn(0, height)
            selection.set(left, top, right, bottom)
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (selection.isEmpty) {
                canvas.drawRect(0f, 0f, w, h, maskPaint)
                val inset = 28f * density
                frame.set(
                    inset.toInt(),
                    (h / 2 - 90f * density).toInt(),
                    (w - inset).toInt(),
                    (h / 2 + 90f * density).toInt()
                )
                canvas.drawRect(frame, borderPaint)
            } else {
                val l = selection.left.toFloat()
                val t = selection.top.toFloat()
                val r = selection.right.toFloat()
                val b = selection.bottom.toFloat()
                canvas.drawRect(0f, 0f, w, t, maskPaint)
                canvas.drawRect(0f, b, w, h, maskPaint)
                canvas.drawRect(0f, t, l, b, maskPaint)
                canvas.drawRect(r, t, w, b, maskPaint)
                canvas.drawRect(l, t, r, b, borderPaint)
                val len = 18f * density
                canvas.drawLine(l, t, l + len, t, cornerPaint)
                canvas.drawLine(l, t, l, t + len, cornerPaint)
                canvas.drawLine(r - len, t, r, t, cornerPaint)
                canvas.drawLine(r, t, r, t + len, cornerPaint)
                canvas.drawLine(l, b - len, l, b, cornerPaint)
                canvas.drawLine(l, b, l + len, b, cornerPaint)
                canvas.drawLine(r - len, b, r, b, cornerPaint)
                canvas.drawLine(r, b - len, r, b, cornerPaint)
            }
            canvas.drawText(hint, w / 2, 64f * density, hintPaint)
        }
    }
}
