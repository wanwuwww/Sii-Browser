// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.ui.translate

import android.app.Activity
import android.app.AlertDialog
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.nl.translate.TranslateLanguage
import com.sii.browser.R
import com.sii.browser.core.webview.OfflineTranslator
import com.sii.browser.di.AppServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 离线翻译语言包前置检查：默认翻译模式是离线，首次使用必须先下载中英模型。
 * 缺包时弹「下载 / 改用在线翻译 / 取消」，下载中显示进度，完成后继续原流程。
 */
object OfflinePackGate {

    private val needed = listOf(TranslateLanguage.CHINESE, TranslateLanguage.ENGLISH)
    private var downloading = false

    fun ensure(activity: Activity, onReady: () -> Unit, onCancel: () -> Unit = {}) {
        if (AppServices.settings.translateMode() != 2) {
            onReady()
            return
        }
        val owner = activity as? ComponentActivity
        if (owner == null) {
            onReady()
            return
        }
        owner.lifecycleScope.launch(Dispatchers.Main) {
            val missing = runCatching {
                OfflineTranslator.missingModels(activity, needed)
            }.getOrDefault(emptyList())
            if (missing.isEmpty()) {
                onReady()
                return@launch
            }
            // 文案按实际缺失数量与大小动态生成（每个模型约 30MB）。
            val names = missing.map { code ->
                if (code == TranslateLanguage.CHINESE) "中文" else "英文"
            }.joinToString("、")
            AlertDialog.Builder(activity)
                .setTitle(R.string.translate_offline_pack_needed_title)
                .setMessage(
                    activity.getString(
                        R.string.offline_pack_message_dynamic,
                        names
                    )
                )
                .setPositiveButton(R.string.offline_pack_download) { _, _ ->
                    download(activity, owner, onReady, onCancel)
                }
                .setNeutralButton(R.string.offline_pack_switch_online) { _, _ ->
                    AppServices.settings.setTranslateMode(1)
                    onReady()
                }
                .setNegativeButton(android.R.string.cancel) { _, _ -> onCancel() }
                .setOnCancelListener { onCancel() }
                .show()
        }
    }

    private fun download(
        activity: Activity,
        owner: ComponentActivity,
        onReady: () -> Unit,
        onCancel: () -> Unit
    ) {
        if (downloading) return
        downloading = true
        val progress = AlertDialog.Builder(activity)
            .setTitle(R.string.translate_offline_pack_needed_title)
            .setMessage(activity.getString(R.string.offline_pack_progress, 0, 1, needed.size))
            .setCancelable(false)
            .create()
        progress.show()
        owner.lifecycleScope.launch(Dispatchers.Main) {
            val ok = runCatching {
                OfflineTranslator.downloadModels(
                    activity,
                    needed,
                    onProgress = { percent, index, count, _, _ ->
                        progress.setMessage(
                            activity.getString(R.string.offline_pack_progress, percent, index, count)
                        )
                    }
                )
            }.getOrDefault(false)
            downloading = false
            runCatching { progress.dismiss() }
            if (ok) {
                Toast.makeText(activity, R.string.offline_pack_ready, Toast.LENGTH_SHORT).show()
                onReady()
            } else {
                Toast.makeText(activity, R.string.text_translate_pack_failed, Toast.LENGTH_SHORT).show()
                onCancel()
            }
        }
    }
}
