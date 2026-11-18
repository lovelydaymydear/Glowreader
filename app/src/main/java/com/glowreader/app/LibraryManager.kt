package com.glowreader.app

import android.content.Context
import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

data class SavedBookMeta(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val format: String,
    val totalChapters: Int,
    val coverPath: String = "",
    val folderName: String = "Geral",
    val lastChapterIndex: Int = 0,
    val lastScrollPosition: Int = 0,
    val lastOpened: Long = System.currentTimeMillis()
)

object LibraryManager {
    private const val PREFS_LIB = "glowreader_library_prefs"
    private const val KEY_BOOKS = "saved_books_meta"
    private const val KEY_FOLDERS = "saved_folders"

    fun saveBookToLibrary(
        context: Context,
        parsed: ParsedBook,
        format: String,
        coverBitmap: Bitmap? = null,
        folderName: String = "Geral"
    ): SavedBookMeta {
        val id = UUID.randomUUID().toString()
        val booksDir = File(context.filesDir, "books").apply { mkdirs() }
        val bookFile = File(booksDir, "$id.json")

        var savedCoverPath = ""
        if (coverBitmap != null) {
            try {
                val coversDir = File(context.filesDir, "covers").apply { mkdirs() }
                val coverFile = File(coversDir, "$id.jpg")
                FileOutputStream(coverFile).use { out ->
                    coverBitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                }
                savedCoverPath = coverFile.absolutePath
            } catch (_: Exception) {}
        }

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
            totalChapters = parsed.chapters.size,
            coverPath = savedCoverPath,
            folderName = folderName
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

    fun moveBookToFolder(context: Context, bookId: String, newFolder: String) {
        val list = loadLibrary(context).map {
            if (it.id == bookId) it.copy(folderName = newFolder) else it
        }
        saveLibraryMeta(context, list)
    }

    fun deleteBook(context: Context, bookId: String) {
        File(File(context.filesDir, "books"), "$bookId.json").delete()
        File(File(context.filesDir, "covers"), "$bookId.jpg").delete()
        val list = loadLibrary(context).filterNot { it.id == bookId }
        saveLibraryMeta(context, list)
    }

    fun loadFolders(context: Context): List<String> {
        val raw = context.getSharedPreferences(PREFS_LIB, Context.MODE_PRIVATE)
            .getString(KEY_FOLDERS, "[\"Geral\", \"Favoritos\", \"Estudos\"]") ?: "[\"Geral\"]"
        val arr = JSONArray(raw)
        val list = mutableListOf<String>()
        for (i in 0 until arr.length()) list.add(arr.getString(i))
        return list.distinct()
    }

    fun addFolder(context: Context, folderName: String) {
        val clean = folderName.trim()
        if (clean.isEmpty()) return
        val current = loadFolders(context).toMutableList()
        if (!current.contains(clean)) {
            current.add(clean)
            val arr = JSONArray()
            current.forEach { arr.put(it) }
            context.getSharedPreferences(PREFS_LIB, Context.MODE_PRIVATE)
                .edit().putString(KEY_FOLDERS, arr.toString()).apply()
        }
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
                    coverPath = obj.optString("coverPath", ""),
                    folderName = obj.optString("folderName", "Geral"),
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
                put("coverPath", m.coverPath)
                put("folderName", m.folderName)
                put("lastChapterIndex", m.lastChapterIndex)
                put("lastScrollPosition", m.lastScrollPosition)
                put("lastOpened", m.lastOpened)
            })
        }
        context.getSharedPreferences(PREFS_LIB, Context.MODE_PRIVATE)
            .edit().putString(KEY_BOOKS, array.toString()).apply()
    }
}
