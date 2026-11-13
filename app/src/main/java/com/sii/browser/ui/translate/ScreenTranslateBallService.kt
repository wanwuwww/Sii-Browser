// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.ui.translate

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.sii.browser.R
import com.sii.browser.di.AppServices
import com.sii.browser.ui.ShellActivity
import com.sii.browser.ui.overlay.OverlayManager

/**
 * 屏幕翻译常驻悬浮球：开启后显示在屏幕上，单击启动一次截屏翻译，长按关闭。
 * 点击悬浮球时通过 ShellActivity 请求截屏授权（系统限制，必须由 Activity 发起）。
 */
class ScreenTranslateBallService : Service() {

    companion object {
        private const val CHANNEL_ID = "screen_translate_ball"
        private const val NOTIFICATION_ID = 1004

        fun start(context: Context) {
            val intent = Intent(context, ScreenTranslateBallService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ScreenTranslateBallService::class.java))
        }
    }

    /** 本次会话模式；未运行服务时（点球/菜单启动）用它决定启动哪种模式。 */
    private var currentMode =
        if (AppServices.settings.screenTranslateLoop()) "loop" else "once"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // 先满足前台服务时限，再建球；避免反复启动时被系统判为没及时 startForeground。
        startForeground(NOTIFICATION_ID, notification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!OverlayManager.canDraw(this)) {
            AppServices.settings.setScreenTranslateBall(false)
            stopSelf()
            return START_NOT_STICKY
        }
        OverlayManager.showTranslateBall(
            this,
            sizeDp = AppServices.settings.screenTranslateBallSize(),
            alpha = AppServices.settings.screenTranslateBallAlpha(),
            snapEdge = AppServices.settings.screenTranslateSnapEdge(),
            onClick = { onBallTap() },
            onLongClick = { showMenu() }
        )
        return START_NOT_STICKY
    }

    /** 单击：循环会话暂停/恢复循环（暂停时清译文）；单页会话有译文先清除，再次点击才翻译。 */
    private fun onBallTap() {
        if (ScreenTranslateService.isRunning) {
            if (ScreenTranslateService.isLoopMode) {
                ScreenTranslateService.toggleLoop(this)
            } else if (ScreenTranslateService.hasOverlay) {
                // 已有译文：先只清除，回到原文；再次点击才重新翻译。
                ScreenTranslateService.clearOverlay(this)
            } else {
                ScreenTranslateService.retry(this)
            }
            return
        }
        startTranslate(currentMode)
    }

    private fun showMenu() {
        // 菜单自己也是屏幕内容，显示期间暂停画面检测，退出后再重建基准。
        ScreenTranslateService.pauseWatching()
        val items = mutableListOf<Pair<String, () -> Unit>>()
        fun act(label: String, action: () -> Unit) {
            items += label to {
                handler.removeCallbacks(menuTimeout)
                action()
                ScreenTranslateService.resumeWatching()
            }
        }
        act(getString(R.string.screen_translate_once)) {
            AppServices.settings.setScreenTranslateLoop(false)
            currentMode = "once"
            applyMode("once")
        }
        act(getString(R.string.screen_translate_loop_mode)) {
            AppServices.settings.setScreenTranslateLoop(true)
            currentMode = "loop"
            applyMode("loop")
        }
        act(getString(R.string.screen_translate_region)) {
            if (ScreenTranslateService.isRunning) {
                ScreenTranslateService.selectRegion(this)
            } else {
                startTranslate(currentMode, region = true)
            }
        }
        items += getString(R.string.screen_translate_pick) to {
            handler.removeCallbacks(menuTimeout)
            if (ScreenTranslateService.isRunning) {
                // 点选结束后由服务自己恢复检测。
                ScreenTranslateService.pickBlock(this)
            } else {
                android.widget.Toast.makeText(
                    this, R.string.screen_translate_pick_empty, android.widget.Toast.LENGTH_SHORT
                ).show()
                ScreenTranslateService.resumeWatching()
            }
        }
        if (ScreenTranslateService.region != null) {
            act(getString(R.string.screen_translate_region_clear)) {
                ScreenTranslateService.clearRegion(this)
            }
        }
        items += getString(R.string.screen_translate_close_ball) to {
            handler.removeCallbacks(menuTimeout)
            closeBall()
        }
        OverlayManager.showBallMenu(this, items) {
            handler.removeCallbacks(menuTimeout)
            ScreenTranslateService.resumeWatching()
        }
        handler.removeCallbacks(menuTimeout)
        handler.postDelayed(menuTimeout, 10_000L)
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    /** 菜单一直没选时自动收起，别让检测一直暂停。 */
    private val menuTimeout = Runnable {
        OverlayManager.dismissBallMenu()
        ScreenTranslateService.resumeWatching()
    }

    /** 服务已运行时直接切换模式（不弹截屏授权）；否则走透明授权页。 */
    private fun applyMode(mode: String) {
        if (ScreenTranslateService.isRunning) {
            ScreenTranslateService.setMode(this, mode == "loop")
        } else {
            startTranslate(mode)
        }
    }

    private fun startTranslate(mode: String, region: Boolean = false) {
        runCatching {
            startActivity(
                Intent(this, ScreenTranslateLauncherActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("screen_translate_mode", mode)
                    .putExtra("screen_translate_region", region)
            )
        }
    }

    /** 关闭悬浮球即退出屏幕翻译：停服务（onDestroy 会清掉译文层）并关掉开关。 */
    private fun closeBall() {
        AppServices.settings.setScreenTranslateBall(false)
        ScreenTranslateService.stop(this)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(menuTimeout)
        OverlayManager.dismissBall()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.settings_screen_translate),
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    private fun notification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_menu_camera)
        .setContentTitle(getString(R.string.settings_screen_translate))
        .setContentText(getString(R.string.settings_screen_translate_desc))
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .build()
}
