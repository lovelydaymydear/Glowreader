package com.glowreader.app

import android.content.Context
import org.json.JSONArray
import java.io.File
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LookupHistoryItem(
    val query: String,
    val provider: String,
    val timestamp: Long
)

data class StudyStatsSummary(
    val totalEntries: Int,
    val notesCount: Int,
    val highlightsCount: Int,
    val underlinesCount: Int,
    val bookmarksCount: Int,
    val annotatedBooksCount: Int
)

object StudyNotebookManager {

    private const val PREFS_NAME = "glowreader_study_notebook_prefs"
    private const val KEY_LOOKUP_HISTORY = "lookup_history_json"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadLookupHistory(context: Context): List<LookupHistoryItem> {
        val raw = prefs(context).getString(KEY_LOOKUP_HISTORY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<LookupHistoryItem>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val q = obj.optString("query").trim()
                if (q.isNotEmpty()) {
                    list.add(
                        LookupHistoryItem(
                            query = q,
                            provider = obj.optString("provider", "Google Tradutor"),
                            timestamp = obj.optLong("timestamp", 0L)
                        )
                    )
                }
            }
            list.sortedByDescending { it.timestamp }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun recordLookup(context: Context, query: String, provider: String): List<LookupHistoryItem> {
        val clean = query.trim()
        if (clean.isEmpty()) return loadLookupHistory(context)
        val existing = loadLookupHistory(context)
            .filterNot { it.query.equals(clean, ignoreCase = true) && it.provider == provider }
        val updated = (listOf(LookupHistoryItem(clean, provider, System.currentTimeMillis())) + existing)
            .take(30)

        val arr = JSONArray()
        updated.forEach { item ->
            arr.put(
                org.json.JSONObject().apply {
                    put("query", item.query)
                    put("provider", item.provider)
                    put("timestamp", item.timestamp)
                }
            )
        }
        prefs(context).edit().putString(KEY_LOOKUP_HISTORY, arr.toString()).apply()
        return updated
    }

    fun buildLookupUrl(query: String, provider: String, targetLang: String = "pt"): Pair<String, String> {
        val clean = query.trim()
        val encoded = URLEncoder.encode(clean, "UTF-8")
        val safeTarget = targetLang.trim().lowercase().ifBlank { "pt" }

        return when (provider) {
            "Priberam / Dicio" -> {
                val title = if (clean.isBlank()) "Dicionário" else "Dicionário: $clean"
                val url = if (clean.isBlank()) {
                    "https://www.dicio.com.br/"
                } else {
                    "https://www.dicio.com.br/$encoded/"
                }
                title to url
            }

            "Linguee / DeepL" -> {
                val title = if (clean.isBlank()) "DeepL Tradutor" else "Traduzir ($safeTarget): $clean"
                val url = "https://www.deepl.com/translator#auto/$safeTarget/$encoded"
                title to url
            }

            else -> {
                val title = if (clean.isBlank()) "Tradutor" else "Traduzir ($safeTarget): $clean"
                val url = "https://translate.google.com/?sl=auto&tl=$safeTarget&text=$encoded"
                title to url
            }
        }
    }

    fun computeStats(annotations: List<StudyAnnotation>): StudyStatsSummary {
        val notesCount = annotations.count { it.note.isNotBlank() }
        val highlightsCount = annotations.count { !it.isBookmark && it.selectedText.isNotBlank() }
        val underlinesCount = annotations.count { it.isUnderline }
        val bookmarksCount = annotations.count { it.isBookmark }
        val annotatedBooksCount = annotations.map { it.bookTitle }.filter { it.isNotBlank() }.distinct().size
        return StudyStatsSummary(
            totalEntries = annotations.size,
            notesCount = notesCount,
            highlightsCount = highlightsCount,
            underlinesCount = underlinesCount,
            bookmarksCount = bookmarksCount,
            annotatedBooksCount = annotatedBooksCount
        )
    }

    fun formatAnnotationsAsMarkdown(
        annotations: List<StudyAnnotation>,
        bookFilter: String? = null
    ): String {
        val filtered = if (!bookFilter.isNullOrBlank() && bookFilter != "Todos") {
            annotations.filter { it.bookTitle.equals(bookFilter, ignoreCase = true) }
        } else {
            annotations
        }

        val sb = StringBuilder()
        sb.appendLine("# Caderno de Estudo — GlowReader")
        if (!bookFilter.isNullOrBlank() && bookFilter != "Todos") {
            sb.appendLine("Livro: $bookFilter")
        }
        sb.appendLine("Total de registos: ${filtered.size}")
        sb.appendLine()

        val grouped = filtered.groupBy { it.bookTitle.ifBlank { "Sem título" } }
        grouped.forEach { (bookTitle, items) ->
            sb.appendLine("## $bookTitle")
            sb.appendLine()
            items.forEach { item ->
                val tag = if (item.isBookmark) "[Marcador]" else "[Destaque/Nota]"
                sb.appendLine("- **$tag** *${item.chapterTitle}*")
                if (item.selectedText.isNotBlank()) {
                    sb.appendLine("  > ${item.selectedText.trim()}")
                }
                if (item.note.isNotBlank()) {
                    sb.appendLine("  - Nota: ${item.note.trim()}")
                }
                if (item.isUnderline) {
                    sb.appendLine("  - Sublinhado: Sim")
                }
                sb.appendLine()
            }
        }
        return sb.toString().trim()
    }

    fun exportAnnotationsToFile(
        context: Context,
        annotations: List<StudyAnnotation>,
        bookFilter: String? = null
    ): Result<File> {
        return try {
            val exportDir = File(context.filesDir, "glowreader_exports")
            if (!exportDir.exists()) exportDir.mkdirs()

            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val slug = (bookFilter ?: "caderno_completo")
                .lowercase()
                .replace(Regex("[^a-z0-9]+"), "_")
                .trim('_')
                .ifBlank { "caderno" }
            val file = File(exportDir, "glowreader_${slug}_$stamp.md")
            file.writeText(formatAnnotationsAsMarkdown(annotations, bookFilter), Charsets.UTF_8)
            Result.success(file)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
