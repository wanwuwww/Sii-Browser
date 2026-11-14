// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.animation.LinearInterpolator
import androidx.core.graphics.ColorUtils

/**
 * 页面加载进度条，一套绘制核心供两种工具栏使用。
 *
 * 悬浮胶囊：横向渐变填充地址行（左淡右浓），右缘 10dp 柔化，不做硬裁切；
 * 经典工具栏：3dp 细条，两端收圆，右缘 2dp 柔光收尾。
 *
 * 停滞光效只叠在**已填充的主色**上：主色饱和，白色高光对比足够，
 * 不再需要暗带补偿（旧实现白+黑互相找补，浅色胶囊上只会显得脏）。
 *
 * 用自绘 Drawable 而非往 LayerDrawable 塞图层：旧做法里无效化传不到父容器、
 * 子层可见性传播时机不可靠，是「流光不出现」的根因。
 */
class DockProgressDrawable(
    context: Context,
    /** 左缘圆角（跟随胶囊 28dp）；经典细条可传 0。 */
    private val radius: Float,
    night: Boolean,
    /** 是否悬浮胶囊样式：只影响边缘柔化宽度与渐变浓度。 */
    private val dockStyle: Boolean = true
) : Drawable() {

    private val density = context.resources.displayMetrics.density
    private val accent = ThemeAccent.color(context)

    /** 填充基准不透明度：悬浮沿用原来的 0x33/0x3D（观感不变），经典细条做实。 */
    private val baseAlpha = if (dockStyle) {
        if (night) 0x3D else 0x33
    } else {
        0xFF
    }

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glossPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tipPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tipMatrix = Matrix()
    private val path = Path()
    private val rect = RectF()
    private val glossMatrix = Matrix()

    private var progress = 0
    private var running = false
    private var phase = 0f
    private var alphaScale = 255
    private var animator: ValueAnimator? = null

    /**
     * 宿主的重绘对象。
     *
     * 本 Drawable 是塞在 LayerDrawable 里当胶囊背景用的，而 LayerDrawable 不会把子层的
     * invalidateSelf 冒泡成父 View 的 invalidate —— 动画每帧都在跑，屏幕却一帧不刷新，
     * 这就是上一版「流光看不见」的真正原因。把自己的无效化手动转给宿主 View 才能动起来。
     */
    var hostView: android.view.View? = null
        set(value) {
            field = value
            invalidateSelf()
        }

    private fun invalidateHost() {
        invalidateSelf()
        hostView?.invalidate()
    }

    /**
     * 进度条所在的地址行高度：胶囊整体高度会因芯片行显示/隐藏而变，
     * 进度层被 inset 压到地址行之后，用它来重算柔化宽度与圆角上限。
     */
    var hostHeight: Int = 0
        set(value) {
            if (field == value) return
            field = value
            shaderWidth = -1
            invalidateHost()
        }

    /** 渐变按 bounds 宽度铺满，bounds 变化时才重建 shader。 */
    private var shaderWidth = -1

    /** 右缘柔化宽度：悬浮跟着胶囊圆角走，只需极窄一段避免硬边；细条 2dp 收尖。 */
    private val fadeWidth: Float = (if (dockStyle) 2f else 2f) * density

    /** 扫光带宽：悬浮略宽更醒目，细条窄一些避免糊成一片。 */
    private val bandWidth: Float = (if (dockStyle) 56f else 40f) * density

    init {
        // 轨道底：只暗示「这里有进度条」，不能让人以为已经有进度。
        trackPaint.color = ColorUtils.setAlphaComponent(accent, 0x14)
        // 停滞流光：叠在主色填充上，55% 白足够清楚又不刺眼。
        glossPaint.shader = LinearGradient(
            0f, 0f, bandWidth, 0f,
            intArrayOf(0x00FFFFFF, 0x8CFFFFFF.toInt(), 0x00FFFFFF),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        // 尖端柔光：填充末端一小段提亮，指示推进方向。
        tipPaint.shader = LinearGradient(
            0f, 0f, fadeWidth, 0f,
            intArrayOf(0x66FFFFFF, 0x00FFFFFF),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    /** 进度 0–100。 */
    fun setProgress(value: Int) {
        val next = value.coerceIn(0, 100)
        if (next == progress) return
        progress = next
        invalidateHost()
    }

    /** 是否播放停滞流光；由调用方按「进度是否停滞」判断，不要全程常开。 */
    fun setRunning(running: Boolean) {
        if (this.running == running) return
        this.running = running
        if (running) startAnimator() else stopAnimator()
        invalidateHost()
    }

    /** 离开界面时停掉动画，避免 Drawable 被持有后仍在跑帧。 */
    fun release() {
        running = false
        stopAnimator()
    }

    private fun startAnimator() {
        if (animator != null) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1500L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                phase = it.animatedValue as Float
                invalidateHost()
            }
            start()
        }
    }

    private fun stopAnimator() {
        animator?.cancel()
        animator = null
        phase = 0f
        invalidateHost()
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        shaderWidth = -1
    }

    /** 横向渐变填充：悬浮用轻微渐变（保持原纯色的整体浓度），细条左浅右满。 */
    private fun ensureShader(bounds: Rect) {
        val width = bounds.width()
        if (width <= 0 || width == shaderWidth) return
        shaderWidth = width
        // 悬浮原来是均匀的 0x33/0x3D，这里只做轻微层次：0.85 → 1.15，整体浓度基本不变。
        val startRatio = if (dockStyle) 0.85f else 0.70f
        val endRatio = if (dockStyle) 1.15f else 1.00f
        fillPaint.shader = LinearGradient(
            bounds.left.toFloat(), 0f, bounds.left + width.toFloat(), 0f,
            intArrayOf(
                ColorUtils.setAlphaComponent(accent, alphaOf(startRatio)),
                ColorUtils.setAlphaComponent(accent, alphaOf(endRatio))
            ),
            null, Shader.TileMode.CLAMP
        )
        fillPaint.alpha = alphaScale
    }

    private fun alphaOf(ratio: Float): Int =
        (baseAlpha * ratio).toInt().coerceIn(1, 255)

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        val fullWidth = bounds.width()
        val height = bounds.height()
        if (fullWidth <= 0 || height <= 0) return

        val left = bounds.left.toFloat()
        // 悬浮样式保持原有观感：进度就是胶囊背景从左往右铺满，不做成细条。
        // 这次只换配色层次（横向渐变）与停滞流光，形状与占用区域跟原来一致。
        val top = bounds.top.toFloat()
        val bottom = bounds.bottom.toFloat()
        val right = left + fullWidth * progress / 100f
        val half = height / 2f
        val corner = radius.coerceAtMost(half)

        ensureShader(bounds)

        // 悬浮样式不做轨道底：原版是纯 ClipDrawable，胶囊本身已有玻璃底，
// 再铺一层淡色会整块变色，与原来观感不一致。只有经典细条才需要轨道底。
        val save = canvas.save()
        if (!dockStyle) {
            rect.set(left, top, left + fullWidth, bottom)
            path.reset()
            path.addRoundRect(rect, half, half, Path.Direction.CW)
            canvas.drawPath(path, trackPaint)
        }

        if (progress > 0 && right > left) {
            // 填充：左缘跟随形状，右缘用 fadeWidth 柔化收尾，不做硬裁切。
            val fade = fadeWidth.coerceAtMost(right - left)
            val solidRight = right - fade

            // 渐变按整条轨道铺开，再裁到已填充范围内：各段共用同一套浓度，不会每段重新起坡。
            val fillSave = canvas.save()
            canvas.clipRect(left, top, right, bottom)
            rect.set(left, top, right, bottom)
            path.reset()
            if (dockStyle) {
                path.addRoundRect(
                    rect,
                    floatArrayOf(corner, corner, 0f, 0f, 0f, 0f, 0f, 0f),
                    Path.Direction.CW
                )
            } else {
                path.addRoundRect(
                    rect,
                    floatArrayOf(half, half, 0f, 0f, 0f, 0f, half, half),
                    Path.Direction.CW
                )
            }
            canvas.drawPath(path, fillPaint)
            canvas.restoreToCount(fillSave)

            // 右缘柔化：从实心末端渐变到透明，边缘就软了。
            if (fade > 0f) {
                val fadeSave = canvas.save()
                canvas.clipRect(solidRight, top, right, bottom)
                tipMatrix.setTranslate(solidRight, 0f)
                tipPaint.shader?.setLocalMatrix(tipMatrix)
                tipPaint.alpha = alphaScale
                canvas.drawRect(solidRight, top, right, bottom, tipPaint)
                canvas.restoreToCount(fadeSave)
            }

            if (running) {
                // 流光在已填充段内扫过：主色饱和，白光对比够，不需要暗带补偿。
                val band = bandWidth
                val travel = (right - left) + band
                val x = left - band + travel * phase
                glossMatrix.setTranslate(x, 0f)
                glossPaint.shader?.setLocalMatrix(glossMatrix)
                glossPaint.alpha = alphaScale

                val glowSave = canvas.save()
                canvas.clipRect(left, top, right, bottom)
                canvas.drawRect(left, top, right, bottom, glossPaint)
                canvas.restoreToCount(glowSave)
            }
        }
        canvas.restoreToCount(save)
    }

    override fun setAlpha(alpha: Int) {
        val next = alpha.coerceIn(0, 255)
        if (next == alphaScale) return
        alphaScale = next
        fillPaint.alpha = next
        glossPaint.alpha = next
        tipPaint.alpha = next
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
        fillPaint.colorFilter = colorFilter
        glossPaint.colorFilter = colorFilter
        tipPaint.colorFilter = colorFilter
        trackPaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT

    override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
        val changed = super.setVisible(visible, restart)
        if (visible) {
            if (running) startAnimator()
        } else {
            stopAnimator()
        }
        return changed
    }
}