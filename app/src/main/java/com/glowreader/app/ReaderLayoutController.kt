package com.glowreader.app

import android.app.Activity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

enum class ReadingViewMode(val label: String) {
    SCROLL("Rolagem"),
    PAGED("Páginas")
}

enum class BarsBehaviorMode(val label: String) {
    ALWAYS_VISIBLE("Sempre visíveis"),
    HIDE_ON_ADVANCE("Ocultar ao avançar"),
    TAP_TO_TOGGLE("Só ao tocar")
}

object ReaderLayoutController {

    fun applyFullscreenSystemBars(activity: Activity?, fullscreen: Boolean) {
        val window = activity?.window ?: return
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (fullscreen) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    fun paginateChapter(content: String, charsPerPage: Int = 1400): List<String> {
        if (content.isBlank()) return listOf("")
        val normalized = content.replace("\r\n", "\n").replace("\r", "\n")
        val safeSize = charsPerPage.coerceIn(500, 5000)
        val pages = mutableListOf<String>()
        var start = 0

        while (start < normalized.length) {
            val rawEnd = (start + safeSize).coerceAtMost(normalized.length)
            var end = rawEnd
            if (rawEnd < normalized.length) {
                val minBreak = start + (safeSize / 2)
                val paragraphBreak = normalized.lastIndexOf("\n\n", startIndex = rawEnd - 1)
                val lineOrSpaceBreak = normalized.lastIndexOfAny(
                    charArrayOf('\n', ' '),
                    startIndex = rawEnd - 1
                )
                end = when {
                    paragraphBreak >= minBreak -> paragraphBreak + 2
                    lineOrSpaceBreak > minBreak -> lineOrSpaceBreak + 1
                    else -> rawEnd
                }
            }
            val pageText = normalized.substring(start, end).trim()
            if (pageText.isNotEmpty()) {
                pages.add(pageText)
            }
            start = if (end > start) end else rawEnd
        }

        return if (pages.isEmpty()) listOf(normalized.trim()) else pages
    }
}
