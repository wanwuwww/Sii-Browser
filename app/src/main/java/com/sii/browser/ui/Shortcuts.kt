// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.ui

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.sii.browser.R
import com.sii.browser.ui.translate.ScreenTranslateLauncherActivity

/**
 * 动态注册桌面快捷方式：包名取当前 applicationId，debug/正式/共存包都能正确启动，
 * 不依赖 shortcuts.xml 里对包名资源的解析。
 */
object Shortcuts {

    fun register(context: Context) {
        val intent = Intent(context, ScreenTranslateLauncherActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            // 独立任务启动并在结束时整任务移除，避免回到浏览器。
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra("sii_action", "screen_translate")
        }
        val shortcut = ShortcutInfoCompat.Builder(context, "screen_translate")
            .setShortLabel(context.getString(R.string.shortcut_screen_translate))
            .setLongLabel(context.getString(R.string.settings_screen_translate))
            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_launcher_sii))
            .setIntent(intent)
            .build()
        runCatching {
            ShortcutManagerCompat.setDynamicShortcuts(context, listOf(shortcut))
        }
    }

    /** 在桌面创建一个「屏幕翻译」快捷方式（部分桌面会弹确认）。
     *  Android 12–17 都用 ShortcutManagerCompat 的固定快捷方式通道：
     *  先判断桌面是否支持，已存在则不重复创建，失败时明确提示。 */
    fun pinScreenTranslate(context: Context): Boolean {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
            toast(context, R.string.shortcut_unsupported)
            return false
        }
        val exists = runCatching {
            ShortcutManagerCompat.getShortcuts(
                context,
                ShortcutManagerCompat.FLAG_MATCH_PINNED
            ).any { it.id == PIN_ID }
        }.getOrDefault(false)
        if (exists) {
            toast(context, R.string.shortcut_exists)
            return true
        }
        val intent = Intent(context, ScreenTranslateLauncherActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            addCategory(Intent.CATEGORY_LAUNCHER)
            // 独立任务启动并在结束时整任务移除，避免回到浏览器。
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra("sii_action", "screen_translate")
        }
        val shortcut = ShortcutInfoCompat.Builder(context, PIN_ID)
            .setShortLabel(context.getString(R.string.shortcut_screen_translate))
            .setLongLabel(context.getString(R.string.settings_screen_translate))
            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_launcher_sii))
            .setIntent(intent)
            .setRank(0)
            .build()
        val ok = runCatching {
            ShortcutManagerCompat.requestPinShortcut(context, shortcut, null)
        }.getOrDefault(false)
        if (!ok) toast(context, R.string.shortcut_unsupported)
        return ok
    }

    private const val PIN_ID = "screen_translate_pin"

    private fun toast(context: Context, resId: Int) {
        android.widget.Toast.makeText(context, resId, android.widget.Toast.LENGTH_SHORT).show()
    }
}
