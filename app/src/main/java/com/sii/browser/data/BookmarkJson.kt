// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.data

import com.sii.browser.data.db.BookmarkFolder
import com.sii.browser.data.db.BookmarkItem
import org.json.JSONArray
import org.json.JSONObject

/**
 * 书签 JSON 备份：保留文件夹层级与排序，供 Sii 原样恢复。
 * HTML 是与其它浏览器交换用的通用格式，JSON 是本应用的完整备份格式。
 */
object BookmarkJson {

    private const val FORMAT = "sii-bookmarks"
    private const val VERSION = 1

    data class FolderDto(val id: String, val title: String, val parent: String?, val order: Int)
    data class ItemDto(val title: String, val url: String, val folder: String?, val order: Int)
    data class Document(val folders: List<FolderDto>, val items: List<ItemDto>)

    fun export(items: List<BookmarkItem>, folders: List<BookmarkFolder>): String {
        val folderArray = JSONArray()
        folders.sortedWith(compareBy({ it.parentFolderId ?: "" }, { it.ordering })).forEach { folder ->
            folderArray.put(
                JSONObject()
                    .put("id", folder.id)
                    .put("title", folder.title)
                    .put("parent", folder.parentFolderId ?: JSONObject.NULL)
                    .put("order", folder.ordering)
            )
        }
        val itemArray = JSONArray()
        items.sortedWith(compareBy({ it.folderId ?: "" }, { it.ordering })).forEach { item ->
            itemArray.put(
                JSONObject()
                    .put("title", item.title)
                    .put("url", item.url)
                    .put("folder", item.folderId ?: JSONObject.NULL)
                    .put("order", item.ordering)
            )
        }
        return JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("exportedAt", System.currentTimeMillis())
            .put("folders", folderArray)
            .put("items", itemArray)
            .toString(2)
    }

    /** 内容是否是本应用的 JSON 备份（导入时用于与 HTML 区分）。 */
    fun looksLikeJson(text: String): Boolean {
        val trimmed = text.trimStart()
        if (!trimmed.startsWith("{")) return false
        val json = runCatching { JSONObject(trimmed) }.getOrNull() ?: return false
        return json.optString("format") == FORMAT ||
            json.has("folders") || json.has("items")
    }

    fun parse(text: String): Document? = runCatching {
        val json = JSONObject(text.trimStart())
        val folderArray = json.optJSONArray("folders") ?: JSONArray()
        val itemArray = json.optJSONArray("items") ?: JSONArray()
        val folders = (0 until folderArray.length()).map { index ->
            val obj = folderArray.optJSONObject(index) ?: JSONObject()
            val id = obj.optString("id")
            if (id.isBlank()) return@map null
            FolderDto(
                id = id,
                title = obj.optString("title").ifBlank { id },
                parent = obj.optString("parent").takeIf { it.isNotBlank() && it != "null" },
                order = obj.optInt("order", index)
            )
        }.filterNotNull()
        val items = (0 until itemArray.length()).map { index ->
            val obj = itemArray.optJSONObject(index) ?: JSONObject()
            val url = obj.optString("url").trim()
            if (url.isBlank()) return@map null
            ItemDto(
                title = obj.optString("title").ifBlank { url },
                url = url,
                folder = obj.optString("folder").takeIf { it.isNotBlank() && it != "null" },
                order = obj.optInt("order", index)
            )
        }.filterNotNull()
        if (folders.isEmpty() && items.isEmpty()) null else Document(folders, items)
    }.getOrNull()
}
