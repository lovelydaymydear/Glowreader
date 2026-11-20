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

    private val defaultFolders = listOf("Geral", "Favoritos", "Estudos")

    fun saveBookToLibrary(
        context: Context,
        parsed: ParsedBook,
        format: String,
        coverBitmap: Bitmap? = null,
        folderName: String = "Geral"
    ): SavedBookMeta {
        val id = UUID.randomUUID().toString()
        val booksDir = File(context.filesDir, "books").apply { if (!exists()) mkdirs() }
        val bookFile = File(booksDir, "$id.json")

        var savedCoverPath = ""
        if (coverBitmap != null) {
            try {
                val coversDir = File(context.filesDir, "covers").apply { if (!exists()) mkdirs() }
                val coverFile = File(coversDir, "$id.jpg")
                FileOutputStream(coverFile).use { out ->
                    coverBitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                }
                if (coverFile.exists() && coverFile.length() > 0L) {
                    savedCoverPath = coverFile.absolutePath
                }
            } catch (_: Exception) {
            }
        }

        val chaptersArray = JSONArray()
        parsed.chapters.forEach { ch ->
            chaptersArray.put(JSONObject().apply {
                put("title", ch.title)
                put("content", ch.content)
            })
        }
        val cleanTitle = parsed.title.trim().ifBlank { "Livro" }
        val cleanFolder = folderName.trim().ifBlank { "Geral" }
        val cleanFormat = format.trim().ifBlank { "LIVRO" }

        val rootObj = JSONObject().apply {
            put("title", cleanTitle)
            put("chapters", chaptersArray)
        }
        bookFile.writeText(rootObj.toString(), Charsets.UTF_8)

        val meta = SavedBookMeta(
            id = id,
            title = cleanTitle,
            format = cleanFormat,
            totalChapters = parsed.chapters.size.coerceAtLeast(1),
            coverPath = savedCoverPath,
            folderName = cleanFolder
        )
        val currentList = loadLibrary(context).toMutableList()
        currentList.add(0, meta)
        saveLibraryMeta(context, currentList)
        return meta
    }

    fun loadBookContent(context: Context, bookId: String): ParsedBook? {
        if (bookId.isBlank()) return null
        val bookFile = File(File(context.filesDir, "books"), "$bookId.json")
        if (!bookFile.exists() || !bookFile.isFile) return null
        return try {
            val rootObj = JSONObject(bookFile.readText(Charsets.UTF_8))
            val title = rootObj.optString("title", "Livro").ifBlank { "Livro" }
            val arr = rootObj.optJSONArray("chapters") ?: JSONArray()
            val chapters = mutableListOf<BookChapter>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                chapters.add(
                    BookChapter(
                        title = obj.optString("title", "Capítulo ${i + 1}"),
                        content = obj.optString("content", "")
                    )
                )
            }
            ParsedBook(title, chapters)
        } catch (_: Exception) {
            null
        }
    }

    fun updateReadingProgress(context: Context, bookId: String, chapterIndex: Int, scrollPos: Int) {
        if (bookId.isBlank()) return
        val now = System.currentTimeMillis()
        val list = loadLibrary(context).map {
            if (it.id == bookId) {
                it.copy(
                    lastChapterIndex = chapterIndex.coerceAtLeast(0),
                    lastScrollPosition = scrollPos.coerceAtLeast(0),
                    lastOpened = now
                )
            } else {
                it
            }
        }.sortedByDescending { it.lastOpened }
        saveLibraryMeta(context, list)
    }

    fun moveBookToFolder(context: Context, bookId: String, newFolder: String) {
        val cleanFolder = newFolder.trim().ifBlank { "Geral" }
        addFolder(context, cleanFolder)
        val list = loadLibrary(context).map {
            if (it.id == bookId) it.copy(folderName = cleanFolder) else it
        }
        saveLibraryMeta(context, list)
    }

    fun deleteBook(context: Context, bookId: String) {
        if (bookId.isBlank()) return
        runCatching { File(File(context.filesDir, "books"), "$bookId.json").delete() }
        runCatching { File(File(context.filesDir, "covers"), "$bookId.jpg").delete() }
        val list = loadLibrary(context).filterNot { it.id == bookId }
        saveLibraryMeta(context, list)
    }

    fun loadFolders(context: Context): List<String> {
        val raw = context.getSharedPreferences(PREFS_LIB, Context.MODE_PRIVATE)
            .getString(KEY_FOLDERS, null) ?: return defaultFolders
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val item = arr.optString(i, "").trim()
                if (item.isNotEmpty() && !list.contains(item)) {
                    list.add(item)
                }
            }
            if (list.isEmpty()) defaultFolders else list
        } catch (_: Exception) {
            defaultFolders
        }
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
        if (raw.isBlank()) return emptyList()

        return try {
            val list = mutableListOf<SavedBookMeta>()
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val id = obj.optString("id", "")
                if (id.isBlank()) continue
                list.add(
                    SavedBookMeta(
                        id = id,
                        title = obj.optString("title", "Livro").ifBlank { "Livro" },
                        format = obj.optString("format", "LIVRO").ifBlank { "LIVRO" },
                        totalChapters = obj.optInt("totalChapters", 1).coerceAtLeast(1),
                        coverPath = obj.optString("coverPath", ""),
                        folderName = obj.optString("folderName", "Geral").ifBlank { "Geral" },
                        lastChapterIndex = obj.optInt("lastChapterIndex", 0).coerceAtLeast(0),
                        lastScrollPosition = obj.optInt("lastScrollPosition", 0).coerceAtLeast(0),
                        lastOpened = obj.optLong("lastOpened", 0L)
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
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
