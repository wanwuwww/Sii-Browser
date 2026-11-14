// SPDX-License-Identifier: LicenseRef-Sii-NC
// Copyright (c) 2026 wanwuwww
package com.sii.browser.data

import com.sii.browser.data.db.BookmarkFolder
import com.sii.browser.data.db.BookmarkItem

/**
 * 书签的 Netscape Bookmark HTML 导入/导出（浏览器通用格式）。
 *
 * 导出保留文件夹层级；导入按 `<DL>` 嵌套恢复层级（Chrome/Edge/Firefox 导出的多层目录不拉平），
 * 同一路径下同名文件夹由调用方复用。
 */
object BookmarkHtml {

    data class ImportedEntry(val folderPath: List<String>, val title: String, val url: String)

    fun export(items: List<BookmarkItem>, folders: List<BookmarkFolder>): String {
        val sb = StringBuilder()
        sb.append("<!DOCTYPE NETSCAPE-Bookmark-file-1>\n")
        sb.append("<META HTTP-EQUIV=\"Content-Type\" CONTENT=\"text/html; charset=UTF-8\">\n")
        sb.append("<TITLE>Bookmarks</TITLE>\n<H1>Bookmarks</H1>\n")
        sb.append("<DL><p>\n")
        renderLevel(sb, "    ", null, folders, items)
        sb.append("</DL><p>\n")
        return sb.toString()
    }

    private fun renderLevel(
        sb: StringBuilder,
        indent: String,
        parentId: String?,
        folders: List<BookmarkFolder>,
        items: List<BookmarkItem>
    ) {
        items.filter { it.folderId == parentId }
            .sortedBy { it.ordering }
            .forEach { item ->
                sb.append(indent)
                    .append("<DT><A HREF=\"").append(escape(item.url)).append("\">")
                    .append(escape(item.title.ifEmpty { item.url }))
                    .append("</A>\n")
            }
        folders.filter { it.parentFolderId == parentId }
            .sortedBy { it.ordering }
            .forEach { folder ->
                sb.append(indent).append("<DT><H3>").append(escape(folder.title)).append("</H3>\n")
                sb.append(indent).append("<DL><p>\n")
                renderLevel(sb, "$indent    ", folder.id, folders, items)
                sb.append(indent).append("</DL><p>\n")
            }
    }

    fun parse(html: String): List<ImportedEntry> {
        val pattern = Regex(
            "(?<dlopen><DL[^>]*>)|(?<dlclose></DL\\s*>)" +
                "|(?<h3><H3[^>]*>(?<h3t>.*?)</H3>)" +
                "|(?<a><A\\s[^>]*?HREF\\s*=\\s*[\"'](?<href>[^\"']+)[\"'][^>]*>(?<at>.*?)</A>)",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        val entries = mutableListOf<ImportedEntry>()
        val stack = ArrayDeque<List<String>>()
        var currentPath: List<String> = emptyList()
        var pendingFolder: String? = null
        pattern.findAll(html).forEach { match ->
            val groups = match.groups
            when {
                groups["dlopen"] != null -> {
                    // 进入子目录：前面的 <H3> 就是这一层的名字。
                    stack.addLast(currentPath)
                    pendingFolder?.let { currentPath = currentPath + it }
                    pendingFolder = null
                }
                groups["dlclose"] != null -> {
                    currentPath = stack.removeLastOrNull() ?: emptyList()
                }
                groups["h3"] != null -> {
                    pendingFolder = unescape(groups["h3t"]?.value.orEmpty()).trim().ifBlank { null }
                }
                groups["href"] != null -> {
                    val href = groups["href"]?.value.orEmpty().trim()
                    if (href.isNotBlank()) {
                        entries.add(
                            ImportedEntry(
                                folderPath = currentPath,
                                title = unescape(groups["at"]?.value.orEmpty()).ifBlank { href },
                                url = href
                            )
                        )
                    }
                }
            }
        }
        return entries
    }

    private fun escape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun unescape(value: String): String = value
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&amp;", "&")
}
