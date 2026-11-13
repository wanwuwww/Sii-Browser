// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.ui.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView

/** 一个已翻译的文字块：位置来自 OCR boundingBox（屏幕像素）。 */
data class TranslatedBlock(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val translation: String
)

/**
 * 覆盖原文的译文层：全屏透明悬浮窗，在每个 OCR 文字块的原位置绘制译文。
 * 窗口只创建一次，后续翻译只更新子 View，避免反复重建导致闪屏。
 * 触摸屏幕会把译文临时隐藏；抬手后由调用方决定重新翻译还是关闭。
 */
object BlockOverlay {

    private var root: FrameLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var picking = false

    fun canDraw(context: Context): Boolean = OverlayManager.canDraw(context)

    fun show(
        context: Context,
        blocks: List<TranslatedBlock>,
        backgroundAlpha: Int = 255,
        textSizeSp: Float = 13f
    ) {
        if (!OverlayManager.canDraw(context) || blocks.isEmpty()) return
        val density = context.resources.displayMetrics.density
        val container = root ?: FrameLayout(context).also { root = it }
        container.removeAllViews()
        container.visibility = View.VISIBLE
        blocks.forEach { block ->
            val width = (block.right - block.left).coerceAtLeast(24)
            val height = (block.bottom - block.top).coerceAtLeast(12)
            val view = TextView(context).apply {
                text = block.translation
                tag = block
                setTextColor(0xFFFFFFFF.toInt())
                gravity = Gravity.CENTER_VERTICAL
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(3, 0, 3, 0)
                background = GradientDrawable().apply {
                    cornerRadius = 4f * density
                    setColor((backgroundAlpha.coerceIn(0, 255) shl 24) or 0x1B1F24)
                }
            }
            androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                view, 5, 14, 1, android.util.TypedValue.COMPLEX_UNIT_SP
            )
            container.addView(
                view,
                FrameLayout.LayoutParams(width, height).apply {
                    leftMargin = block.left
                    topMargin = block.top
                }
            )
        }
        container.setOnTouchListener(null)
        if (params == null) {
            val screen = physicalSize(context)
            val layoutParams = WindowManager.LayoutParams(
                screen.first,
                screen.second,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    fitInsetsTypes = 0
                }
                if (android.os.Build.VERSION.SDK_INT >= 28) {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                }
            }
            val manager = context.getSystemService(WindowManager::class.java)
            runCatching { manager.addView(container, layoutParams) }
            params = layoutParams
        }
    }

    /** 截图/翻译期间隐藏旧译文，窗口本身保留，避免重建闪烁。 */
    fun setVisible(visible: Boolean) {
        root?.visibility = if (visible) View.VISIBLE else View.INVISIBLE
    }

    fun isPicking(): Boolean = picking

    /**
     * 点选模式：窗口临时可触摸，单击块复制、长按朗读、点空白退出。
     * 进入前调用方要先停掉循环/监控，退出后再恢复，否则自己画层的变化会触发重译。
     */
    fun beginPick(
        onClickBlock: (TranslatedBlock) -> Unit,
        onLongBlock: (TranslatedBlock) -> Unit,
        onExit: () -> Unit
    ): Boolean {
        val container = root ?: return false
        val layout = params ?: return false
        if (container.childCount == 0) return false
        container.visibility = View.VISIBLE
        picking = true
        layout.flags = layout.flags and
            android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        val manager = container.context.getSystemService(WindowManager::class.java)
        runCatching { manager?.updateViewLayout(container, layout) }
        container.isClickable = true
        container.setOnClickListener {
            endPick()
            onExit()
        }
        for (index in 0 until container.childCount) {
            val child = container.getChildAt(index)
            val block = child.tag as? TranslatedBlock ?: continue
            child.isClickable = true
            child.setOnClickListener {
                endPick()
                onClickBlock(block)
            }
            child.setOnLongClickListener {
                endPick()
                onLongBlock(block)
                true
            }
        }
        return true
    }

    /** 退出点选，恢复不可触摸（不挡滚动）。 */
    fun endPick() {
        if (!picking) return
        picking = false
        val container = root ?: return
        val layout = params ?: return
        layout.flags = layout.flags or
            android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        val manager = container.context.getSystemService(WindowManager::class.java)
        runCatching { manager?.updateViewLayout(container, layout) }
        container.isClickable = false
        container.setOnClickListener(null)
        for (index in 0 until container.childCount) {
            val child = container.getChildAt(index)
            child.isClickable = false
            child.setOnClickListener(null)
            child.setOnLongClickListener(null)
        }
    }

    fun dismiss() {
        val target = root ?: return
        root = null
        params = null
        picking = false
        runCatching {
            (target.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                .removeView(target)
        }
    }

    /** 物理屏幕尺寸（含系统栏/挖孔），与 MediaProjection 截图坐标一致。 */
    private fun physicalSize(context: Context): Pair<Int, Int> {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val manager = context.getSystemService(WindowManager::class.java)
            val bounds = manager?.maximumWindowMetrics?.bounds
            if (bounds != null) return bounds.width() to bounds.height()
        }
        val metrics = android.util.DisplayMetrics()
        val display = (context.getSystemService(Context.DISPLAY_SERVICE) as? android.hardware.display.DisplayManager)
            ?.getDisplay(android.view.Display.DEFAULT_DISPLAY)
        display?.getRealMetrics(metrics)
        return metrics.widthPixels to metrics.heightPixels
    }
}
