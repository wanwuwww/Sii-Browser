// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.ui.translate

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.sii.browser.R
import com.sii.browser.core.webview.LanguageId
import com.sii.browser.core.webview.OfflineTranslator
import com.sii.browser.core.webview.Translator
import com.sii.browser.di.AppServices
import com.sii.browser.ui.overlay.BlockOverlay
import com.sii.browser.ui.overlay.OverlayManager
import com.sii.browser.ui.overlay.RegionFrameOverlay
import com.sii.browser.ui.overlay.RegionSelectOverlay
import com.sii.browser.ui.overlay.TranslatedBlock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Arrays
import java.util.Locale

/**
 * 屏幕翻译：MediaProjection 截一帧 → ML Kit OCR → 现有 Translator 翻译 → 悬浮窗展示。
 * 循环模式下按画面变化自动重译；结果可朗读。屏幕内容只在内存处理，不落盘。
 */
class ScreenTranslateService : Service() {

    companion object {
        const val ACTION_START = "com.sii.browser.screen.START"
        const val ACTION_STOP = "com.sii.browser.screen.STOP"
        const val ACTION_TOGGLE_LOOP = "com.sii.browser.screen.TOGGLE_LOOP"
        const val ACTION_RETRY = "com.sii.browser.screen.RETRY"
        const val ACTION_CLEAR = "com.sii.browser.screen.CLEAR"
        const val ACTION_SET_MODE = "com.sii.browser.screen.SET_MODE"
        const val ACTION_SELECT_REGION = "com.sii.browser.screen.SELECT_REGION"
        const val ACTION_CLEAR_REGION = "com.sii.browser.screen.CLEAR_REGION"
        /** 帧差异占比低于该值视为小变化（滚动词条/进度条/光标），不收起译文。 */
        private const val MINOR_CHANGE_RATIO = 0.15f
        /** 小变化连续超过该时长仍按页面切换处理（ms）。 */
        private const val MINOR_CHANGE_MAX_MS = 8_000L
        const val ACTION_PICK_BLOCK = "com.sii.browser.screen.PICK_BLOCK"

        /** 分区翻译的选区（屏幕像素）；null 表示整屏。跟随服务会话，停止即清空。 */
        @Volatile
        var region: android.graphics.Rect? = null

        /** 当前是否有译文/提示层（点球时用来决定“清除”还是“翻译”）。 */
        @Volatile
        var hasOverlay = false

        /** 服务是否在运行（悬浮球单击时用来判断“启停循环”还是“新开一次翻译”）。 */
        @Volatile
        var isRunning = false

        /** 本次会话是否为自动循环模式（悬浮球单击时据此决定暂停/恢复循环还是重译）。 */
        @Volatile
        var isLoopMode = false

        /** 球菜单/点选等自绘浮层操作期间暂停画面检测，避免自己的 UI 变化被当成内容变化。 */
        @Volatile
        var watchPaused = false

        /** 恢复检测时先重建“带浮层”基准，否则恢复后的第一帧会误判变化。 */
        @Volatile
        var rebaseRequested = false

        fun pauseWatching() {
            watchPaused = true
        }

        fun resumeWatching() {
            watchPaused = false
            rebaseRequested = true
        }

        fun toggleLoop(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, ScreenTranslateService::class.java)
                        .setAction(ACTION_TOGGLE_LOOP)
                )
            }
        }

        /** 重新翻译当前屏幕（服务已在运行时使用，不需要重新授权）。 */
        fun retry(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, ScreenTranslateService::class.java)
                        .setAction(ACTION_RETRY)
                )
            }
        }

        /** 清除当前译文层（单页模式点球恢复原文）。 */
        fun clearOverlay(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, ScreenTranslateService::class.java)
                        .setAction(ACTION_CLEAR)
                )
            }
        }

        /** 进入框选页面，选完后只翻译框内文字。 */
        fun selectRegion(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, ScreenTranslateService::class.java)
                        .setAction(ACTION_SELECT_REGION)
                )
            }
        }

        /** 取消选区，恢复整屏翻译。 */
        fun clearRegion(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, ScreenTranslateService::class.java)
                        .setAction(ACTION_CLEAR_REGION)
                )
            }
        }

        /** 进入点选模式：单击译文块复制、长按朗读。 */
        fun pickBlock(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, ScreenTranslateService::class.java)
                        .setAction(ACTION_PICK_BLOCK)
                )
            }
        }

        /** 服务已运行时切换单页/循环，不重新请求截屏授权。 */
        fun setMode(context: Context, loop: Boolean) {
            runCatching {
                context.startService(
                    Intent(context, ScreenTranslateService::class.java)
                        .setAction(ACTION_SET_MODE)
                        .putExtra("loop", loop)
                )
            }
        }
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"
        private const val EXTRA_LOOP_OVERRIDE = "loop_override"
        private const val EXTRA_REGION_SELECT = "region_select"
        private const val CHANNEL_ID = "screen_translate"
        private const val NOTIFICATION_ID = 1003
        private const val LOOP_INTERVAL_MS = 2_000L
        private const val MANUAL_UTTERANCE = "sii-screen-manual"

        fun start(
            context: Context,
            resultCode: Int,
            data: Intent,
            loopOverride: Boolean? = null,
            regionSelect: Boolean = false
        ) {
            val intent = Intent(context, ScreenTranslateService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
                putExtra(EXTRA_REGION_SELECT, regionSelect)
                loopOverride?.let { putExtra(EXTRA_LOOP_OVERRIDE, it) }
            }
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ScreenTranslateService::class.java))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var loopJob: Job? = null
    private var changeWatchJob: Job? = null
    private var loopEnabled = false
    private var translating = false
    private var modeIsLoop = false
    private var lastFrameHash = 0
    private var lastProcessedHash = 0
    private var stableSince = 0L
    private var lastAutoTranslateAt = 0L
    /** 上一帧采样点阵（与 captureSampled 同规格），用于计算差异比例而非整帧哈希。 */
    private var lastFramePixels: IntArray? = null
    /** 小变化连续超过该时长仍视为页面切换（避免滚动列表让译文永远盖住已滚走的原文）。 */
    private var minorChangeSince = 0L

    /** 自绘浮层（译文层/提示卡）显示时的画面 hash，用来把自己画的层和真实画面变化区分开。 */
    private var overlayHash = 0
    private var overlayHashPending = false
    private var ttsReady = false
    private var ttsFailed = false
    private var pendingSpeech: String? = null
    private var tts: TextToSpeech? = null
    private var manualSpeaking = false
    private var pickTimeout: Runnable? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var density = 0
    private var regionScreenW = 0
    private var regionScreenH = 0
    private var pendingRegionSelect = false
    private val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        watchPaused = false
        rebaseRequested = false
        createChannel()
        loopEnabled = AppServices.settings.screenTranslateLoop()
        tts = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            ttsFailed = !ttsReady
            if (ttsReady) {
                tts?.setOnUtteranceProgressListener(
                    object : android.speech.tts.UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) = Unit

                        override fun onDone(utteranceId: String?) {
                            if (utteranceId == MANUAL_UTTERANCE) manualSpeaking = false
                        }

                        override fun onError(utteranceId: String?) {
                            if (utteranceId == MANUAL_UTTERANCE) manualSpeaking = false
                        }
                    }
                )
                pendingSpeech?.let { text ->
                    pendingSpeech = null
                    speak(text)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SET_MODE) {
            modeIsLoop = intent.getBooleanExtra("loop", false)
            loopEnabled = modeIsLoop
            isLoopMode = modeIsLoop
            loopJob?.cancel()
            changeWatchJob?.cancel()
            lastProcessedHash = 0
            overlayHash = 0
            overlayHashPending = false
            translateOnce()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_SELECT_REGION) {
            beginRegionSelect()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_CLEAR_REGION) {
            region = null
            regionScreenW = 0
            regionScreenH = 0
            RegionFrameOverlay.dismiss()
            lastProcessedHash = 0
            overlayHash = 0
            overlayHashPending = false
            android.widget.Toast.makeText(
                this, R.string.screen_translate_region_cleared, android.widget.Toast.LENGTH_SHORT
            ).show()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_PICK_BLOCK) {
            beginBlockPick()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_CLEAR) {
            loopJob?.cancel()
            changeWatchJob?.cancel()
            hideOverlay()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_RETRY) {
            translateOnce()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_TOGGLE_LOOP) {
            loopEnabled = !loopEnabled
            if (loopEnabled) {
                // 重新开始循环：清掉上一轮基准，让稳定后立即重译一次。
                lastProcessedHash = 0
                overlayHash = 0
                overlayHashPending = false
                startLoop()
                android.widget.Toast.makeText(
                    this, R.string.screen_translate_loop_on, android.widget.Toast.LENGTH_SHORT
                ).show()
            } else {
                // 暂停循环时连译文一起清掉，回到未翻译的原始屏幕。
                loopJob?.cancel()
                changeWatchJob?.cancel()
                hideOverlay()
                android.widget.Toast.makeText(
                    this, R.string.screen_translate_loop_off, android.widget.Toast.LENGTH_SHORT
                ).show()
            }
            refreshNotification()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra(EXTRA_RESULT_DATA) as? Intent
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        loopEnabled = if (intent?.hasExtra(EXTRA_LOOP_OVERRIDE) == true) {
            intent.getBooleanExtra(EXTRA_LOOP_OVERRIDE, false)
        } else {
            AppServices.settings.screenTranslateLoop()
        }
        modeIsLoop = loopEnabled
        isLoopMode = loopEnabled
        pendingRegionSelect = intent?.getBooleanExtra(EXTRA_REGION_SELECT, false) ?: false
        density = resources.displayMetrics.densityDpi
        startAsForeground { started ->
            if (started) startProjection(resultCode, data) else failAndStop()
        }
        return START_NOT_STICKY
    }

    /**
     * Android 14+ 部分 ROM（HyperOS/MIUI）在授权回调后立即 startForeground 会抛
     * SecurityException（app-op 授权尚未落地），捕获后 200ms 重试一次。
     */
    private fun startAsForeground(onResult: (Boolean) -> Unit) {
        val tryStart = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(NOTIFICATION_ID, notification())
            }
        }
        try {
            tryStart()
            onResult(true)
        } catch (_: SecurityException) {
            handler.postDelayed({
                try {
                    tryStart()
                    onResult(true)
                } catch (_: Exception) {
                    onResult(false)
                }
            }, 200L)
        }
    }

    private fun notification(): android.app.Notification {
        val stop = PendingIntent.getService(
            this,
            0,
            Intent(this, ScreenTranslateService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val loop = PendingIntent.getService(
            this,
            1,
            Intent(this, ScreenTranslateService::class.java).setAction(ACTION_TOGGLE_LOOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val retry = PendingIntent.getService(
            this,
            2,
            Intent(this, ScreenTranslateService::class.java).setAction(ACTION_RETRY),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val clear = PendingIntent.getService(
            this,
            3,
            Intent(this, ScreenTranslateService::class.java).setAction(ACTION_CLEAR),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val loopLabel = if (loopEnabled) {
            getString(R.string.screen_translate_notify_pause)
        } else {
            getString(R.string.screen_translate_notify_resume)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(getString(R.string.screen_translate_title))
            .setContentText(getString(R.string.screen_translate_running))
            .setOngoing(true)
            .addAction(0, loopLabel, loop)
            .addAction(0, getString(R.string.screen_translate_retry), retry)
            .addAction(0, getString(R.string.screen_translate_clear), clear)
            .addAction(0, getString(R.string.screen_translate_close), stop)
            .build()
    }

    private fun refreshNotification() {
        runCatching {
            getSystemService(NotificationManager::class.java)
                ?.notify(NOTIFICATION_ID, notification())
        }
    }

    private fun startProjection(resultCode: Int, data: Intent) {
        val manager = getSystemService(MediaProjectionManager::class.java) ?: return failAndStop()
        val mp = runCatching { manager.getMediaProjection(resultCode, data) }.getOrNull()
            ?: return failAndStop()
        projection = mp
        runCatching {
            // Android 14 起必须先注册回调，才能创建 VirtualDisplay。
            val callback = object : MediaProjection.Callback() {
                override fun onStop() {
                    cleanupProjection()
                    stopSelf()
                }

                override fun onCapturedContentResize(width: Int, height: Int) {
                    if (width > 0 && height > 0) {
                        handler.post { resizeVirtualDisplay(width, height) }
                    }
                }
            }
            mp.registerCallback(callback, handler)
            projectionCallback = callback
            val size = screenSize()
            createVirtualDisplay(size.first, size.second)
            android.widget.Toast.makeText(
                this,
                R.string.screen_translate_ready,
                android.widget.Toast.LENGTH_SHORT
            ).show()
            if (pendingRegionSelect) {
                pendingRegionSelect = false
                handler.postDelayed({ beginRegionSelect() }, 200L)
            }
            // 截屏就绪后保证悬浮球在屏幕上：快捷方式/失败重试路径可能没把球带起来。
            if (!AppServices.settings.screenTranslateBall()) {
                AppServices.settings.setScreenTranslateBall(true)
            }
            ScreenTranslateBallService.start(this)
        }.onFailure { failAndStop() }
    }

    /** 物理屏幕尺寸（含状态栏区域），与悬浮窗坐标一致。 */
    private fun screenSize(): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val manager = getSystemService(android.view.WindowManager::class.java)
            val bounds = manager?.maximumWindowMetrics?.bounds
            if (bounds != null) return bounds.width() to bounds.height()
        }
        val metrics = android.util.DisplayMetrics()
        val display = getSystemService(android.hardware.display.DisplayManager::class.java)
            ?.getDisplay(android.view.Display.DEFAULT_DISPLAY)
        display?.getRealMetrics(metrics)
        return metrics.widthPixels to metrics.heightPixels
    }

    /** 单应用共享切换应用/旋转时尺寸会变化，用 display.resize 而不是重建虚拟显示。 */
    private fun resizeVirtualDisplay(width: Int, height: Int) {
        val display = virtualDisplay ?: return
        if (width <= 0 || height <= 0) return
        val reader = runCatching {
            ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        }.getOrNull() ?: return
        val resized = runCatching {
            display.setSurface(null)
            display.resize(width, height, density)
            display.setSurface(reader.surface)
        }.isSuccess
        if (resized) {
            runCatching { imageReader?.close() }
            imageReader = reader
        } else {
            reader.close()
        }
    }

    /** 按当前捕获尺寸创建虚拟显示。 */
    private fun createVirtualDisplay(width: Int, height: Int) {
        runCatching { virtualDisplay?.release() }
        runCatching { imageReader?.close() }
        val mp = projection ?: return
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        virtualDisplay = mp.createVirtualDisplay(
            "sii-screen",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            handler
        )
    }

    private fun cleanupProjection() {
        runCatching { virtualDisplay?.release() }
        runCatching { imageReader?.close() }
        virtualDisplay = null
        imageReader = null
        runCatching { projection?.stop() }
        projection = null
    }

    /** 授权/截屏失败：关掉悬浮球设置，避免反复启动崩溃。 */
    private fun failAndStop() {
        runCatching { AppServices.settings.setScreenTranslateBall(false) }
        runCatching { ScreenTranslateBallService.stop(this) }
        OverlayManager.dismissTranslate()
        stopSelf()
    }

    private data class OcrLine(
        val text: String,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    )

    private fun translateOnce() {
        if (translating) return
        translating = true
        // 截图前隐藏悬浮球与旧译文（窗口保留）；识别状态只显示在悬浮球上。
        OverlayManager.setBallVisible(false)
        BlockOverlay.setVisible(false)
        stopSpeaking()
        RegionFrameOverlay.setVisible(false)
        OverlayManager.setBallBusy(this, true)
        scope.launch {
            try {
                delay(120)
                val bitmap = withContext(Dispatchers.Default) { capture() }
                // 截图完成立即把球显示回来（只在截图瞬间隐藏），识别/翻译期间球上旋转提示。
                OverlayManager.setBallVisible(true)
                if (bitmap == null) {
                    showMessage(getString(R.string.screen_translate_failed))
                    return@launch
                }
                val area = effectiveRegion(bitmap.width, bitmap.height)
                if (region != null && area == null) region = null
                val crop = area?.let { r ->
                    runCatching {
                        Bitmap.createBitmap(bitmap, r.left, r.top, r.width(), r.height())
                    }.getOrNull()
                }
                val source = crop ?: bitmap
                val offsetX = if (crop != null) area?.left ?: 0 else 0
                val offsetY = if (crop != null) area?.top ?: 0 else 0
                val lines = try {
                    withContext(Dispatchers.Default) {
                        runCatching {
                            val result = Tasks.await(recognizer.process(InputImage.fromBitmap(source, 0)))
                            result.textBlocks
                                .flatMap { it.lines }
                                .mapNotNull { line ->
                                    val text = line.text.trim()
                                    val box = line.boundingBox
                                    if (box == null || !isTranslatableText(text)) null
                                    else OcrLine(
                                        text,
                                        box.left + offsetX,
                                        box.top + offsetY,
                                        box.right + offsetX,
                                        box.bottom + offsetY
                                    )
                                }
                                .distinctBy { it.text }
                                .take(40)
                        }.getOrDefault(emptyList())
                    }
                } finally {
                    // 全屏位图约 10MB，识别完成立即释放，不等 GC。
                    if (source !== bitmap) source.recycle()
                    bitmap.recycle()
                }
                if (lines.isEmpty()) {
                    showMessage(getString(R.string.screen_translate_no_text))
                    return@launch
                }
                if (AppServices.settings.screenTranslateDisplay() == 1) {
                    val text = lines.joinToString("\n") { it.text }.take(1500)
                    val translated = translateText(text)
                    if (translated.text.isNullOrBlank()) {
                        showMessage(translated.error ?: getString(R.string.screen_translate_failed))
                        return@launch
                    }
                    showResult(text, translated.text)
                    hasOverlay = true
                    if (AppServices.settings.screenTranslateSpeak()) speak(translated.text)
                } else {
                    val blocks = translateBlocks(lines)
                    if (blocks.isEmpty()) {
                        showMessage(getString(R.string.screen_translate_failed))
                        return@launch
                    }
                    BlockOverlay.show(
                        this@ScreenTranslateService,
                        blocks,
                        backgroundAlpha = 255,
                        textSizeSp = AppServices.settings.screenTranslateFont().toFloat()
                    )
                    OverlayManager.dismissTranslate()
                    hasOverlay = true
                    markOverlayFrame()
                    startLoop()
                    if (AppServices.settings.screenTranslateSpeak()) {
                        speak(blocks.joinToString(" ") { it.translation })
                    }
                }
            } catch (_: Exception) {
                showMessage(getString(R.string.screen_translate_failed))
            } finally {
                translating = false
                OverlayManager.setBallBusy(this@ScreenTranslateService, false)
                OverlayManager.setBallVisible(true)
                RegionFrameOverlay.setVisible(true)
            }
        }
    }

    /** 过滤纯数字/符号、过短、以及 OCR 误识别出来的噪声块。 */
    private fun isTranslatableText(text: String): Boolean {
        if (text.length < 2) return false
        if (text.none { it.isLetter() }) return false
        if (text.length > 200) return false
        return true
    }

    /** 逐行翻译并在原位置覆盖显示；并发 4，失败的行跳过。 */
    private suspend fun translateBlocks(lines: List<OcrLine>): List<TranslatedBlock> {
        if (lines.isEmpty()) return emptyList()
        val source = LanguageId.identify(this, lines.joinToString("\n") { it.text })
            ?: if (lines.any { line -> line.text.any { it.code in 0x4E00..0x9FFF } }) "zh" else "en"
        val target = Translator.targetLang(this)
        return coroutineScope {
            lines.chunked(4).flatMap { chunk ->
                chunk.map { line ->
                    async {
                        val result = Translator.translate(
                            this@ScreenTranslateService, line.text, to = target, from = source
                        )
                        val text = result.text
                        if (text.isNullOrBlank()) null
                        else TranslatedBlock(line.left, line.top, line.right, line.bottom, text)
                    }
                }.awaitAll()
            }.filterNotNull()
        }
    }

    /** 重试：复位状态、先显示提示，再延迟取新帧，避免截不到画面时“看起来没反应”。 */
    private fun retryTranslate() {
        translating = false
        showMessage(getString(R.string.screen_translate_working))
        handler.postDelayed({ translateOnce() }, 250L)
    }

    private data class TranslationOutcome(val text: String?, val error: String?)

    /**
     * 屏幕文本经常很长：在线接口有单次长度限制，离线语言包缺失也会直接失败。
     * 这里先检查语言包，再按段切分逐段翻译，失败时返回可读原因。
     */
    private suspend fun translateText(text: String): TranslationOutcome {
        if (AppServices.settings.translateMode() == 2 &&
            !OfflineTranslator.isReady(this)
        ) {
            return TranslationOutcome(null, getString(R.string.screen_translate_need_model))
        }
        val target = Translator.targetLang(this)
        // 离线引擎必须知道源语言（不传会直接返回 offline_source_unknown）。
        val source = LanguageId.identify(this, text)
            ?: if (text.any { it.code in 0x4E00..0x9FFF }) "zh" else "en"
        val builder = StringBuilder()
        for (segment in splitForTranslate(text, 400)) {
            val result = Translator.translate(this, segment, to = target, from = source)
            val part = result.text
            if (part.isNullOrBlank()) {
                val reason = when (result.error) {
                    "offline_model_missing" -> getString(R.string.screen_translate_need_model)
                    "network" -> getString(R.string.screen_translate_network)
                    else -> getString(R.string.screen_translate_failed)
                }
                return TranslationOutcome(null, reason)
            }
            if (builder.isNotEmpty()) builder.append('\n')
            builder.append(part)
        }
        return TranslationOutcome(builder.toString().trim(), null)
    }

    /** 按行/句切分并合并成不超过 [limit] 字符的段，避免单次翻译超长。 */
    private fun splitForTranslate(text: String, limit: Int): List<String> {
        val segments = mutableListOf<String>()
        val current = StringBuilder()
        fun flush() {
            if (current.isNotBlank()) segments.add(current.toString().trim())
            current.setLength(0)
        }
        for (line in text.split('\n')) {
            val pieces = line.split(Regex("(?<=[.。!！?？;；])"))
            for (piece in pieces) {
                val part = piece.trim()
                if (part.isEmpty()) continue
                if (current.length + part.length + 1 > limit) flush()
                if (part.length > limit) {
                    var start = 0
                    while (start < part.length) {
                        val end = (start + limit).coerceAtMost(part.length)
                        flush()
                        segments.add(part.substring(start, end).trim())
                        start = end
                    }
                } else {
                    if (current.isNotEmpty()) current.append(' ')
                    current.append(part)
                }
            }
        }
        flush()
        return segments.ifEmpty { listOf(text.take(limit)) }
    }

    private fun showResult(original: String, translated: String) {
        val copyLabel = getString(R.string.screen_translate_copy)
        OverlayManager.showTranslateResult(
            this,
            getString(R.string.screen_translate_title),
            original,
            translated,
            getString(R.string.screen_translate_speak),
            getString(R.string.screen_translate_loop),
            getString(R.string.screen_translate_close),
            getString(R.string.screen_translate_retry),
            copyLabel,
            looping = loopEnabled,
            textSize = AppServices.settings.screenTranslateFont().toFloat(),
            onSpeak = { speak(translated) },
            onToggleLoop = {
                loopEnabled = !loopEnabled
                modeIsLoop = loopEnabled
                AppServices.settings.setScreenTranslateLoop(loopEnabled)
                showResult(original, translated)
                if (loopEnabled) startLoop() else loopJob?.cancel()
            },
            onRetry = { retryTranslate() },
            onCopy = { copyText(translated) },
            onClose = { stopSelf() }
        )
        markOverlayFrame()
        startLoop()
    }

    /**
     * 浮层刚显示/更新：下一帧采样只用来记下「带着自己这层」的画面，
     * 否则循环会把刚画上去的译文层当成屏幕内容变化，立刻隐藏它并重译。
     */
    private fun markOverlayFrame() {
        overlayHash = 0
        overlayHashPending = true
    }

    /** 恢复检测后重建基准：下一帧只记作“带浮层”画面，不当作内容变化。 */
    private fun consumeRebase(): Boolean {
        if (!rebaseRequested) return false
        rebaseRequested = false
        markOverlayFrame()
        lastProcessedHash = 0
        return true
    }

    /** 移除译文层与结果卡：暂停循环、关闭屏幕翻译、单页清除都走这里。 */
    private fun hideOverlay() {
        BlockOverlay.dismiss()
        OverlayManager.dismissTranslate()
        stopSpeaking()
        hasOverlay = false
        overlayHash = 0
        overlayHashPending = false
        lastProcessedHash = 0
        lastFramePixels = null
        minorChangeSince = 0L
    }

    /** 译文层收起时同步停掉朗读，避免屏幕上没字还在念。 */
    private fun stopSpeaking() {
        pendingSpeech = null
        manualSpeaking = false
        handler.removeCallbacks(manualTimeout)
        runCatching { tts?.stop() }
    }

    /** 点选模式：单击译文块复制、长按朗读，点空白退出；期间暂停自动轮询/监控。 */
    private fun beginBlockPick() {
        if (!BlockOverlay.canDraw(this)) return
        val started = BlockOverlay.beginPick(
            onClickBlock = { block ->
                copyText(block.translation)
                android.widget.Toast.makeText(
                    this, R.string.screen_translate_copied, android.widget.Toast.LENGTH_SHORT
                ).show()
                finishBlockPick()
            },
            onLongBlock = { block ->
                speak(block.translation, manual = true)
                finishBlockPick()
            },
            onExit = { finishBlockPick() }
        )
        if (!started) {
            android.widget.Toast.makeText(
                this, R.string.screen_translate_pick_empty, android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }
        loopJob?.cancel()
        changeWatchJob?.cancel()
        pickTimeout?.let { handler.removeCallbacks(it) }
        pickTimeout = Runnable {
            if (BlockOverlay.isPicking()) {
                BlockOverlay.endPick()
                finishBlockPick()
            }
        }.also { handler.postDelayed(it, 20_000L) }
        android.widget.Toast.makeText(
            this, R.string.screen_translate_pick_hint, android.widget.Toast.LENGTH_LONG
        ).show()
    }

    private fun finishBlockPick() {
        BlockOverlay.endPick()
        pickTimeout?.let { handler.removeCallbacks(it) }
        pickTimeout = null
        resumeWatching()
        markOverlayFrame()
        lastProcessedHash = 0
        if (modeIsLoop && loopEnabled) startLoop() else startChangeWatch()
    }

    /** 进入框选：先停循环与旧译文，选完后按当前模式接着翻译。 */
    private fun beginRegionSelect() {
        if (!OverlayManager.canDraw(this)) {
            OverlayManager.setBallVisible(true)
            return
        }
        val wasLooping = modeIsLoop && loopEnabled
        loopJob?.cancel()
        changeWatchJob?.cancel()
        hideOverlay()
        RegionFrameOverlay.dismiss()
        OverlayManager.setBallVisible(false)
        RegionSelectOverlay.show(
            this,
            region,
            getString(R.string.screen_translate_region_hint),
            onConfirm = { rect ->
                rememberRegion(rect)
                OverlayManager.setBallVisible(true)
                lastProcessedHash = 0
                overlayHash = 0
                overlayHashPending = false
                RegionFrameOverlay.show(this, rect) { moved -> onRegionMoved(moved) }
                android.widget.Toast.makeText(
                    this, R.string.screen_translate_region_applied, android.widget.Toast.LENGTH_SHORT
                ).show()
                translateOnce()
            },
            onCancel = {
                OverlayManager.setBallVisible(true)
                lastProcessedHash = 0
                overlayHash = 0
                overlayHashPending = false
                region?.let { RegionFrameOverlay.show(this, it) { moved -> onRegionMoved(moved) } }
                if (wasLooping) startLoop()
            }
        )
    }

    /** 拖动选框手柄：换位置后重译。 */
    private fun onRegionMoved(rect: android.graphics.Rect) {
        rememberRegion(rect)
        lastProcessedHash = 0
        overlayHash = 0
        overlayHashPending = false
        translateOnce()
    }

    private fun showMessage(message: String) {
        stopSpeaking()
        OverlayManager.showTranslateResult(
            this,
            getString(R.string.screen_translate_title),
            "",
            message,
            getString(R.string.screen_translate_speak),
            getString(R.string.screen_translate_loop),
            getString(R.string.screen_translate_close),
            getString(R.string.screen_translate_retry),
            getString(R.string.screen_translate_copy),
            looping = loopEnabled,
            textSize = AppServices.settings.screenTranslateFont().toFloat(),
            onSpeak = {},
            onToggleLoop = {
                loopEnabled = !loopEnabled
                modeIsLoop = loopEnabled
                AppServices.settings.setScreenTranslateLoop(loopEnabled)
                if (loopEnabled) startLoop() else loopJob?.cancel()
            },
            onRetry = { retryTranslate() },
            onCopy = {},
            onClose = { stopSelf() }
        )
        markOverlayFrame()
        startLoop()
    }

    private fun copyText(text: String) {
        val clipboard = getSystemService(android.content.ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("translation", text))
    }

    private fun startLoop() {
        loopJob?.cancel()
        // 单页模式只盯画面变化，撞到滚动就收起旧译文，不自动重译。
        if (!modeIsLoop || !loopEnabled) {
            startChangeWatch()
            return
        }
        changeWatchJob?.cancel()
        loopJob = scope.launch {
            var delayMs = 200L
            val intervalMs = AppServices.settings.screenTranslateInterval()
                .coerceIn(1, 3) * 1000L
            val maxDelayMs = intervalMs.coerceAtLeast(400L)
            while (true) {
                delay(delayMs)
                if (watchPaused) continue
                if (consumeRebase()) continue
                if (translating) continue
                val pixels = withContext(Dispatchers.Default) { captureSampled() } ?: continue
                val hash = pixels.contentHashCode()
                val now = android.os.SystemClock.elapsedRealtime()
                if (overlayHashPending) {
                    overlayHash = hash
                    lastFramePixels = pixels.copyOf()
                    overlayHashPending = false
                    lastFrameHash = hash
                    stableSince = now
                    continue
                }
                // 与浮层基准一致：只有自己画的译文层，屏幕内容没变。
                if (overlayHash != 0 && hash == overlayHash) continue
                // 小面积变化（滚动词条/进度条/光标）不触发重译，译文保持。
                val ratio = frameChangeRatio(lastFramePixels, pixels)
                lastFramePixels = pixels.copyOf()
                if (ratio <= MINOR_CHANGE_RATIO) {
                    if (minorChangeSince == 0L) minorChangeSince = now
                    if (now - minorChangeSince < MINOR_CHANGE_MAX_MS) continue
                    // 持续小变化超时：按页面切换处理。
                } else {
                    minorChangeSince = 0L
                }
                if (hash != lastFrameHash) {
                    // 画面在变：降频采样，并隐藏旧译文（不拦截触摸），等稳定后再翻译。
                    delayMs = (delayMs * 2).coerceAtMost(maxDelayMs)
                    lastFrameHash = hash
                    stableSince = now
                    overlayHash = 0
                    BlockOverlay.setVisible(false)
                    stopSpeaking()
                    continue
                }
                delayMs = 200L
                if (hash == lastProcessedHash) continue // 已处理过的同一帧，保持当前译文
                if (now - stableSince >= 500L && now - lastAutoTranslateAt >= intervalMs) {
                    lastAutoTranslateAt = now
                    lastProcessedHash = hash
                    translateOnce()
                }
            }
        }
    }

    /**
     * 单页模式：译文显示后盯住屏幕，内容一变（滚动、切页、切应用）就收起译文，
     * 避免译文块停在旧坐标上。只比哈希，不 OCR 不翻译；收起后等用户再点悬浮球。
     */
    private fun startChangeWatch() {
        changeWatchJob?.cancel()
        changeWatchJob = scope.launch {
            while (true) {
                delay(500L)
                if (watchPaused) continue
                if (consumeRebase()) continue
                if (translating) continue
                val pixels = withContext(Dispatchers.Default) { captureSampled() } ?: continue
                if (overlayHashPending) {
                    overlayHash = pixels.contentHashCode()
                    lastFramePixels = pixels.copyOf()
                    overlayHashPending = false
                    continue
                }
                if (overlayHash != 0 && pixels.contentHashCode() == overlayHash) continue
                // 小面积变化（滚动词条/进度条/光标）不算页面切换，译文保持。
                val ratio = frameChangeRatio(lastFramePixels, pixels)
                lastFramePixels = pixels.copyOf()
                if (ratio <= MINOR_CHANGE_RATIO) {
                    if (minorChangeSince == 0L) minorChangeSince = android.os.SystemClock.elapsedRealtime()
                    // 持续小变化超时也收起：原文可能已滚走，译文留在旧坐标没有意义。
                    if (android.os.SystemClock.elapsedRealtime() - minorChangeSince < MINOR_CHANGE_MAX_MS) {
                        continue
                    }
                } else {
                    minorChangeSince = 0L
                }
                hideOverlay()
                return@launch
            }
        }
    }

    /**
     * 只从 ImageReader 的像素缓冲里抽 32×32 个点作为画面指纹，不创建全屏位图。
     * 有选区时只在框内取点，选区外动不影响判定。
     */
    /** 按保存选区时的屏幕尺寸换算到当前画面尺寸；无选区且不排除系统栏时返回 null（整屏）。 */
    private fun effectiveRegion(width: Int, height: Int): android.graphics.Rect? {
        if (width <= 0 || height <= 0) return null
        val excludeBars = AppServices.settings.screenTranslateExcludeBars()
        val saved = region
        if (saved == null) {
            if (!excludeBars) return null
            return cutSystemBars(android.graphics.Rect(0, 0, width, height), width, height)
        }
        val sameSize = regionScreenW == width && regionScreenH == height
        val rect = if (regionScreenW <= 0 || regionScreenH <= 0 || sameSize) {
            saved
        } else {
            android.graphics.Rect(
                (saved.left.toLong() * width / regionScreenW).toInt(),
                (saved.top.toLong() * height / regionScreenH).toInt(),
                (saved.right.toLong() * width / regionScreenW).toInt(),
                (saved.bottom.toLong() * height / regionScreenH).toInt()
            )
        }
        val valid = rect.takeIf {
            it.left >= 0 && it.top >= 0 &&
                it.right <= width && it.bottom <= height &&
                it.width() > 0 && it.height() > 0
        } ?: return null
        return if (excludeBars) cutSystemBars(valid, width, height) else valid
    }

    /** 去掉可见的状态栏/导航栏区域；交集太小就保持原区域。 */
    private fun cutSystemBars(
        rect: android.graphics.Rect,
        width: Int,
        height: Int
    ): android.graphics.Rect {
        val (topInset, bottomInset) = systemBarInsets()
        val cut = android.graphics.Rect(
            rect.left,
            maxOf(rect.top, topInset),
            rect.right,
            minOf(rect.bottom, height - bottomInset)
        )
        return if (cut.width() > 1 && cut.height() > 1) cut else rect
    }

    /** 当前可见状态栏/导航栏高度（隐藏时返回 0）。 */
    private fun systemBarInsets(): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val wm = getSystemService(android.view.WindowManager::class.java)
            val insets = wm?.currentWindowMetrics?.windowInsets
            if (insets != null) {
                val top = insets.getInsets(android.view.WindowInsets.Type.statusBars()).top
                val bottom = insets.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom
                return top to bottom
            }
        }
        val res = resources
        val topId = res.getIdentifier("status_bar_height", "dimen", "android")
        val bottomId = res.getIdentifier("navigation_bar_height", "dimen", "android")
        val top = if (topId > 0) res.getDimensionPixelSize(topId) else 0
        val bottom = if (bottomId > 0) res.getDimensionPixelSize(bottomId) else 0
        return top to bottom
    }

    /** 记录选区与当时的屏幕尺寸，后续分辨率/方向变化时按比例换算。 */
    private fun rememberRegion(rect: android.graphics.Rect) {
        region = rect
        val size = screenSize()
        regionScreenW = size.first
        regionScreenH = size.second
    }

    private fun captureSampled(size: Int = 32): IntArray? {
        val image = runCatching { imageReader?.acquireLatestImage() }.getOrNull() ?: return null
        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val width = image.width
            val height = image.height
            val limit = buffer.limit()
            val area = effectiveRegion(width, height)
            val left = area?.left?.coerceIn(0, width - 1) ?: 0
            val top = area?.top?.coerceIn(0, height - 1) ?: 0
            val right = (area?.right ?: width).coerceIn(left + 1, width)
            val bottom = (area?.bottom ?: height).coerceIn(top + 1, height)
            val spanX = right - left
            val spanY = bottom - top
            val pixels = IntArray(size * size)
            for (y in 0 until size) {
                val rowOffset = (top + y * spanY / size).coerceAtMost(bottom - 1) * rowStride
                for (x in 0 until size) {
                    val offset = rowOffset +
                        (left + x * spanX / size).coerceAtMost(right - 1) * pixelStride
                    if (offset + 2 >= limit) continue
                    val r = buffer.get(offset).toInt() and 0xFF
                    val g = buffer.get(offset + 1).toInt() and 0xFF
                    val b = buffer.get(offset + 2).toInt() and 0xFF
                    val a = if (offset + 3 < limit) buffer.get(offset + 3).toInt() and 0xFF else 0xFF
                    pixels[y * size + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
            return pixels
        } finally {
            runCatching { image.close() }
        }
    }

    /**
     * 帧差异判定：逐点比较与上一帧的采样，返回差异占比（0f~1f）。
     * 视频进度条、滚动词条等小面积变化只影响少数采样点，占比远低于页面切换。
     */
    private fun frameChangeRatio(previous: IntArray?, current: IntArray): Float {
        if (previous == null || previous.size != current.size) return 1f
        var diff = 0
        for (i in current.indices) {
            val a = previous[i]
            val b = current[i]
            // 每通道差值超过阈值才算变化，滤掉压缩/渲染噪声。
            if (kotlin.math.abs((a shr 16) and 0xFF - (b shr 16) and 0xFF) > 24 ||
                kotlin.math.abs((a shr 8) and 0xFF - (b shr 8) and 0xFF) > 24 ||
                kotlin.math.abs(a and 0xFF - b and 0xFF) > 24
            ) diff++
        }
        return diff.toFloat() / current.size
    }

    private fun capture(): Bitmap? {
        val image = runCatching { imageReader?.acquireLatestImage() }.getOrNull() ?: return null
        try {
            val plane = image.planes[0]
            val width = image.width
            val height = image.height
            val rowPadding = plane.rowStride - plane.pixelStride * width
            val padded = Bitmap.createBitmap(
                width + rowPadding / plane.pixelStride,
                height,
                Bitmap.Config.ARGB_8888
            )
            padded.copyPixelsFromBuffer(plane.buffer)
            val cropped = Bitmap.createBitmap(padded, 0, 0, width, height)
            if (cropped !== padded) padded.recycle()
            return cropped
        } finally {
            runCatching { image.close() }
        }
    }

    private fun speak(text: String, manual: Boolean = false) {
        if (text.isBlank()) return
        // 手动点读优先：自动朗读在手读期间不抢话。
        if (!manual && manualSpeaking) return
        val engine = tts ?: return
        if (!ttsReady) {
            if (ttsFailed) {
                android.widget.Toast.makeText(
                    this, R.string.screen_translate_tts_unavailable, android.widget.Toast.LENGTH_SHORT
                ).show()
            } else {
                pendingSpeech = text.take(600)
            }
            return
        }
        if (manual) {
            manualSpeaking = true
            handler.removeCallbacks(manualTimeout)
            handler.postDelayed(manualTimeout, 120_000L)
        }
        runCatching {
            val language = ttsLanguage(text)
            val languageResult = engine.setLanguage(language)
            if (languageResult == TextToSpeech.LANG_MISSING_DATA ||
                languageResult == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                val fallback = engine.setLanguage(Locale.getDefault())
                if (fallback == TextToSpeech.LANG_MISSING_DATA ||
                    fallback == TextToSpeech.LANG_NOT_SUPPORTED
                ) {
                    manualSpeaking = false
                    android.widget.Toast.makeText(
                        this, R.string.screen_translate_tts_unavailable, android.widget.Toast.LENGTH_SHORT
                    ).show()
                    return@runCatching
                }
            }
            engine.setSpeechRate(
                (AppServices.settings.readAloudRate().coerceIn(50, 300)) / 100f
            )
            val utteranceId = if (manual) MANUAL_UTTERANCE else "sii-screen"
            val result = engine.speak(text.take(600), TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            if (result == TextToSpeech.ERROR) {
                manualSpeaking = false
                android.widget.Toast.makeText(
                    this, R.string.screen_translate_tts_unavailable, android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }.onFailure {
            manualSpeaking = false
            android.widget.Toast.makeText(
                this, R.string.screen_translate_tts_unavailable, android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    /** 引擎不回调 onDone 时兜底复位，避免自动朗读永久被压制。 */
    private val manualTimeout = Runnable { manualSpeaking = false }

    /** 按译文目标语言选 TTS 语音；目标语言不可用时回退到文本本身的语言。 */
    private fun ttsLanguage(text: String): Locale {
        val target = Translator.targetLang(this).lowercase()
        return when {
            target.startsWith("zh") -> Locale.SIMPLIFIED_CHINESE
            target.startsWith("en") -> Locale.ENGLISH
            target.startsWith("ja") -> Locale.JAPANESE
            target.startsWith("ko") -> Locale.KOREAN
            else -> if (text.any { it.code in 0x4E00..0x9FFF }) {
                Locale.SIMPLIFIED_CHINESE
            } else {
                Locale.ENGLISH
            }
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.screen_translate_title),
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    override fun onDestroy() {
        isRunning = false
        isLoopMode = false
        loopJob?.cancel()
        changeWatchJob?.cancel()
        scope.cancel()
        runCatching { recognizer.close() }
        runCatching { projectionCallback?.let { projection?.unregisterCallback(it) } }
        projectionCallback = null
        cleanupProjection()
        runCatching { tts?.shutdown() }
        tts = null
        pendingSpeech = null
        RegionSelectOverlay.dismiss()
        RegionFrameOverlay.dismiss()
        region = null
        hideOverlay()
        super.onDestroy()
    }
}
