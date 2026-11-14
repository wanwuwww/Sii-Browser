// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.ui.translate

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.sii.browser.R
import com.sii.browser.ui.overlay.OverlayManager

/**
 * 透明授权宿主：先确认悬浮窗权限，再请求系统截屏授权，不显示浏览器主页；
 * 授权完成后启动翻译服务并立即退出，回到用户原来的应用。
 */
class ScreenTranslateLauncherActivity : AppCompatActivity() {

    private val captureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            val loopOverride = when (intent.getStringExtra("screen_translate_mode")) {
                "loop" -> true
                "once" -> false
                else -> null
            }
            val regionSelect = intent.getBooleanExtra("screen_translate_region", false)
            ScreenTranslateService.start(this, result.resultCode, data, loopOverride, regionSelect)
        }
        closeSelf()
    }

    /** 从系统设置返回后继续：授予了就直接进入截屏授权，否则安静退出。 */
    private val overlayLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (OverlayManager.canDraw(this)) proceedCapture() else closeSelf()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!OverlayManager.canDraw(this)) {
            promptOverlayPermission()
            return
        }
        // 离线模式首次使用要先下载中英语言包，下载完成后再请求截屏。
        OfflinePackGate.ensure(
            this,
            onReady = { proceedCapture() },
            onCancel = { closeSelf() }
        )
    }

    /** 快捷方式冷启动时没有浏览器界面，必须先用弹窗说明为什么要悬浮窗权限。 */
    private fun promptOverlayPermission() {
        AlertDialog.Builder(this)
            .setTitle(R.string.screen_translate_title)
            .setMessage(R.string.screen_translate_overlay_required)
            .setPositiveButton(R.string.screen_translate_open_settings) { _, _ ->
                runCatching {
                    overlayLauncher.launch(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                    )
                }.onFailure { closeSelf() }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> closeSelf() }
            .setOnCancelListener { closeSelf() }
            .show()
    }

    private fun proceedCapture() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        if (manager == null) {
            closeSelf()
            return
        }
        val captureIntent = if (Build.VERSION.SDK_INT >= 34) {
            manager.createScreenCaptureIntent(
                android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay()
            )
        } else {
            manager.createScreenCaptureIntent()
        }
        runCatching { captureLauncher.launch(captureIntent) }.onFailure { closeSelf() }
    }

    /** 授权页是独立任务，结束时连任务一起移除，否则会回到浏览器等上一个应用。 */
    private fun closeSelf() {
        finishAndRemoveTask()
        overridePendingTransition(0, 0)
    }
}
