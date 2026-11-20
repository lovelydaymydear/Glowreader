package com.glowreader.app

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class HighlightColor(val label: String, val hex: Long) {
    YELLOW("Amarelo", 0xFFFFE082),
    GREEN("Verde", 0xFFA5D6A7),
    BLUE("Azul", 0xFF90CAF9),
    PINK("Rosa", 0xFFF48FB1);

    fun toColor(): Color = Color(hex)
}

enum class ReaderAlignment(val label: String, val align: TextAlign) {
    JUSTIFY("Justificado", TextAlign.Justify),
    LEFT("Esquerda", TextAlign.Left),
    CENTER("Centro", TextAlign.Center),
    RIGHT("Direita", TextAlign.Right)
}

enum class ReaderFont(val label: String, val family: FontFamily) {
    SERIF("Clássica", FontFamily.Serif),
    SANS("Moderna", FontFamily.SansSerif),
    MONO("Foco", FontFamily.Monospace)
}

data class StudyAnnotation(
    val id: String = UUID.randomUUID().toString(),
    val bookTitle: String,
    val chapterIndex: Int,
    val chapterTitle: String,
    val selectedText: String,
    val note: String = "",
    val colorName: String = HighlightColor.YELLOW.name,
    val isUnderline: Boolean = false,
    val isBookmark: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)

object ReaderStorage {
    private const val PREFS_NAME = "glowreader_prefs"
    private const val KEY_ANNOTATIONS = "annotations_list"

    fun saveAnnotations(context: Context, list: List<StudyAnnotation>) {
        val array = JSONArray()
        list.forEach { item ->
            val obj = JSONObject().apply {
                put("id", item.id.ifBlank { UUID.randomUUID().toString() })
                put("bookTitle", item.bookTitle)
                put("chapterIndex", item.chapterIndex)
                put("chapterTitle", item.chapterTitle)
                put("selectedText", item.selectedText)
                put("note", item.note)
                put("colorName", item.colorName.ifBlank { HighlightColor.YELLOW.name })
                put("isUnderline", item.isUnderline)
                put("isBookmark", item.isBookmark)
                put("timestamp", item.timestamp)
            }
            array.put(obj)
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ANNOTATIONS, array.toString())
            .apply()
    }

    fun loadAnnotations(context: Context): List<StudyAnnotation> {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ANNOTATIONS, "[]") ?: "[]"
        if (raw.isBlank()) return emptyList()

        return try {
            val array = JSONArray(raw)
            val list = mutableListOf<StudyAnnotation>()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val id = obj.optString("id", "").ifBlank { UUID.randomUUID().toString() }
                val bookTitle = obj.optString("bookTitle", "")
                val chapterIndex = obj.optInt("chapterIndex", 0).coerceAtLeast(0)
                val chapterTitle = obj.optString("chapterTitle", "")
                val selectedText = obj.optString("selectedText", "")
                val note = obj.optString("note", "")
                val colorName = obj.optString("colorName", HighlightColor.YELLOW.name)
                    .ifBlank { HighlightColor.YELLOW.name }
                val isUnderline = obj.optBoolean("isUnderline", false)
                val isBookmark = obj.optBoolean("isBookmark", false)
                val timestamp = obj.optLong("timestamp", 0L)

                if (bookTitle.isNotBlank() || selectedText.isNotBlank() || note.isNotBlank() || isBookmark) {
                    list.add(
                        StudyAnnotation(
                            id = id,
                            bookTitle = bookTitle,
                            chapterIndex = chapterIndex,
                            chapterTitle = chapterTitle,
                            selectedText = selectedText,
                            note = note,
                            colorName = colorName,
                            isUnderline = isUnderline,
                            isBookmark = isBookmark,
                            timestamp = timestamp
                        )
                    )
                }
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }
}
