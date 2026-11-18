package com.glowreader.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class SavedBookMeta(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val format: String,
    val totalChapters: Int,
    val lastChapterIndex: Int = 0,
    val lastScrollPosition: Int = 0,
    val lastOpened: Long = System.currentTimeMillis()
)

object LibraryManager {
    private const val PREFS_LIB = "glowreader_library_prefs"
    private const val KEY_BOOKS = "saved_books_meta"

    fun saveBookToLibrary(context: Context, parsed: ParsedBook, format: String): SavedBookMeta {
        val id = UUID.randomUUID().toString()
        val booksDir = File(context.filesDir, "books").apply { mkdirs() }
        val bookFile = File(booksDir, "$id.json")

        val chaptersArray = JSONArray()
        parsed.chapters.forEach { ch ->
            chaptersArray.put(JSONObject().apply {
                put("title", ch.title)
                put("content", ch.content)
            })
        }
        val rootObj = JSONObject().apply {
            put("title", parsed.title)
            put("chapters", chaptersArray)
        }
        bookFile.writeText(rootObj.toString())

        val meta = SavedBookMeta(
            id = id,
            title = parsed.title,
            format = format,
            totalChapters = parsed.chapters.size
        )
        val currentList = loadLibrary(context).toMutableList()
        currentList.add(0, meta)
        saveLibraryMeta(context, currentList)
        return meta
    }

    fun loadBookContent(context: Context, bookId: String): ParsedBook? {
        val bookFile = File(File(context.filesDir, "books"), "$bookId.json")
        if (!bookFile.exists()) return null
        return try {
            val rootObj = JSONObject(bookFile.readText())
            val title = rootObj.optString("title", "Livro")
            val arr = rootObj.getJSONArray("chapters")
            val chapters = mutableListOf<BookChapter>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                chapters.add(BookChapter(obj.optString("title"), obj.optString("content")))
            }
            ParsedBook(title, chapters)
        } catch (_: Exception) {
            null
        }
    }

    fun updateReadingProgress(context: Context, bookId: String, chapterIndex: Int, scrollPos: Int) {
        val list = loadLibrary(context).map {
            if (it.id == bookId) {
                it.copy(
                    lastChapterIndex = chapterIndex,
                    lastScrollPosition = scrollPos,
                    lastOpened = System.currentTimeMillis()
                )
            } else it
        }.sortedByDescending { it.lastOpened }
        saveLibraryMeta(context, list)
    }

    fun deleteBook(context: Context, bookId: String) {
        File(File(context.filesDir, "books"), "$bookId.json").delete()
        val list = loadLibrary(context).filterNot { it.id == bookId }
        saveLibraryMeta(context, list)
    }

    fun loadLibrary(context: Context): List<SavedBookMeta> {
        val raw = context.getSharedPreferences(PREFS_LIB, Context.MODE_PRIVATE)
            .getString(KEY_BOOKS, "[]") ?: "[]"
        val list = mutableListOf<SavedBookMeta>()
        val array = JSONArray(raw)
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            list.add(
                SavedBookMeta(
                    id = obj.optString("id"),
                    title = obj.optString("title"),
                    format = obj.optString("format", "LIVRO"),
                    totalChapters = obj.optInt("totalChapters", 1),
                    lastChapterIndex = obj.optInt("lastChapterIndex", 0),
                    lastScrollPosition = obj.optInt("lastScrollPosition", 0),
                    lastOpened = obj.optLong("lastOpened", 0L)
                )
            )
        }
        return list
    }

    private fun saveLibraryMeta(context: Context, list: List<SavedBookMeta>) {
        val array = JSONArray()
        list.forEach { m ->
            array.put(JSONObject().apply {
                put("id", m.id)
                put("title", m.title)
                put("format", m.format)
                put("totalChapters", m.totalChapters)
                put("lastChapterIndex", m.lastChapterIndex)
                put("lastScrollPosition", m.lastScrollPosition)
                put("lastOpened", m.lastOpened)
            })
        }
        context.getSharedPreferences(PREFS_LIB, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_BOOKS, array.toString())
            .apply()
    }
}
