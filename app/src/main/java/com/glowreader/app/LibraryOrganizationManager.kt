package com.glowreader.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class LibrarySortOption(val label: String) {
    RECENT_ORDER("Ordem atual"),
    TITLE_ASC("Título (A–Z)"),
    FORMAT("Formato"),
    PROGRESS("Progresso")
}

data class BookOrganizationMeta(
    val bookId: String,
    val category: String = "A ler",
    val isSecret: Boolean = false,
    val addedAt: Long = 0L
)

data class DuplicateBookGroup(
    val normalizedKey: String,
    val books: List<SavedBookMeta>
)

object LibraryOrganizationManager {

    private const val PREFS_NAME = "glowreader_library_org_prefs"
    private const val KEY_ORG_JSON = "org_meta_json"
    private const val KEY_CUSTOM_CATEGORIES = "custom_categories_json"
    private const val KEY_SECRET_PIN = "secret_shelf_pin"
    private const val KEY_SECRET_UNLOCKED = "secret_shelf_unlocked"

    val defaultCategories = listOf("A ler", "Mais tarde", "Artigos")

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadAllCategories(context: Context): List<String> {
        val raw = prefs(context).getString(KEY_CUSTOM_CATEGORIES, null) ?: return defaultCategories
        return try {
            val arr = JSONArray(raw)
            val custom = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val item = arr.optString(i).trim()
                if (item.isNotEmpty() && item !in defaultCategories && item !in custom) {
                    custom.add(item)
                }
            }
            defaultCategories + custom
        } catch (_: Exception) {
            defaultCategories
        }
    }

    fun addCustomCategory(context: Context, name: String): List<String> {
        val clean = name.trim()
        if (clean.isEmpty()) return loadAllCategories(context)
        val current = loadAllCategories(context).toMutableList()
        if (clean !in current) {
            current.add(clean)
            val customOnly = current.filterNot { it in defaultCategories }
            prefs(context).edit().putString(KEY_CUSTOM_CATEGORIES, JSONArray(customOnly).toString()).apply()
        }
        return loadAllCategories(context)
    }

    fun loadMetaMap(context: Context): Map<String, BookOrganizationMeta> {
        val raw = prefs(context).getString(KEY_ORG_JSON, null) ?: return emptyMap()
        return try {
            val arr = JSONArray(raw)
            val map = mutableMapOf<String, BookOrganizationMeta>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val id = obj.optString("bookId")
                if (id.isNotBlank()) {
                    map[id] = BookOrganizationMeta(
                        bookId = id,
                        category = obj.optString("category", "A ler").ifBlank { "A ler" },
                        isSecret = obj.optBoolean("isSecret", false),
                        addedAt = obj.optLong("addedAt", 0L)
                    )
                }
            }
            map
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun saveMetaMap(context: Context, map: Map<String, BookOrganizationMeta>) {
        val arr = JSONArray()
        map.values.forEach { meta ->
            arr.put(
                JSONObject().apply {
                    put("bookId", meta.bookId)
                    put("category", meta.category)
                    put("isSecret", meta.isSecret)
                    put("addedAt", meta.addedAt)
                }
            )
        }
        prefs(context).edit().putString(KEY_ORG_JSON, arr.toString()).apply()
    }

    fun ensureBooksRegistered(context: Context, books: List<SavedBookMeta>): Map<String, BookOrganizationMeta> {
        val current = loadMetaMap(context).toMutableMap()
        var changed = false
        books.forEachIndexed { idx, book ->
            if (!current.containsKey(book.id)) {
                val inferredCategory = if (book.format.equals("WEB", ignoreCase = true)) "Artigos" else "A ler"
                current[book.id] = BookOrganizationMeta(
                    bookId = book.id,
                    category = inferredCategory,
                    isSecret = false,
                    addedAt = System.currentTimeMillis() - idx * 1000L
                )
                changed = true
            }
        }
        if (changed) {
            saveMetaMap(context, current)
        }
        return current
    }

    fun setBookCategory(context: Context, bookId: String, category: String): Map<String, BookOrganizationMeta> {
        val current = loadMetaMap(context).toMutableMap()
        val existing = current[bookId] ?: BookOrganizationMeta(bookId = bookId)
        current[bookId] = existing.copy(category = category.ifBlank { "A ler" })
        saveMetaMap(context, current)
        return current
    }

    fun toggleBookSecret(context: Context, bookId: String): Map<String, BookOrganizationMeta> {
        val current = loadMetaMap(context).toMutableMap()
        val existing = current[bookId] ?: BookOrganizationMeta(bookId = bookId)
        current[bookId] = existing.copy(isSecret = !existing.isSecret)
        saveMetaMap(context, current)
        return current
    }

    fun hasSecretPin(context: Context): Boolean {
        return !prefs(context).getString(KEY_SECRET_PIN, null).isNullOrBlank()
    }

    fun setSecretPin(context: Context, pin: String): Boolean {
        val clean = pin.trim()
        if (clean.length < 4) return false
        prefs(context).edit()
            .putString(KEY_SECRET_PIN, clean)
            .putBoolean(KEY_SECRET_UNLOCKED, true)
            .apply()
        return true
    }

    fun verifySecretPin(context: Context, pin: String): Boolean {
        val saved = prefs(context).getString(KEY_SECRET_PIN, null) ?: return false
        val ok = saved == pin.trim()
        if (ok) {
            prefs(context).edit().putBoolean(KEY_SECRET_UNLOCKED, true).apply()
        }
        return ok
    }

    fun isSecretUnlocked(context: Context): Boolean {
        if (!hasSecretPin(context)) return true
        return prefs(context).getBoolean(KEY_SECRET_UNLOCKED, false)
    }

    fun lockSecretShelf(context: Context) {
        prefs(context).edit().putBoolean(KEY_SECRET_UNLOCKED, false).apply()
    }

    fun normalizeTitle(title: String): String {
        return title
            .lowercase()
            .replace(Regex("[^a-zà-ú0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun findDuplicateGroups(books: List<SavedBookMeta>): List<DuplicateBookGroup> {
        val grouped = books.groupBy { normalizeTitle(it.title) }
            .filter { (key, list) -> key.isNotBlank() && list.size > 1 }
        return grouped.map { (key, list) ->
            DuplicateBookGroup(normalizedKey = key, books = list)
        }
    }

    fun filterAndSortBooks(
        context: Context,
        books: List<SavedBookMeta>,
        selectedCategory: String,
        showSecretShelf: Boolean,
        searchQuery: String,
        formatFilter: String,
        sortOption: LibrarySortOption
    ): List<SavedBookMeta> {
        val metaMap = ensureBooksRegistered(context, books)
        val query = searchQuery.trim().lowercase()

        val filtered = books.filter { book ->
            val meta = metaMap[book.id] ?: BookOrganizationMeta(bookId = book.id)
            if (showSecretShelf) {
                if (!meta.isSecret) return@filter false
            } else {
                if (meta.isSecret) return@filter false
                if (selectedCategory != "Todos" && meta.category != selectedCategory) {
                    return@filter false
                }
            }

            if (formatFilter != "Todos" && !book.format.equals(formatFilter, ignoreCase = true)) {
                return@filter false
            }

            if (query.isNotEmpty()) {
                val matchTitle = book.title.lowercase().contains(query)
                val matchFormat = book.format.lowercase().contains(query)
                val matchCategory = meta.category.lowercase().contains(query)
                if (!matchTitle && !matchFormat && !matchCategory) return@filter false
            }
            true
        }

        return when (sortOption) {
            LibrarySortOption.RECENT_ORDER -> filtered
            LibrarySortOption.TITLE_ASC -> filtered.sortedBy { it.title.lowercase() }
            LibrarySortOption.FORMAT -> filtered.sortedBy { it.format.lowercase() }
            LibrarySortOption.PROGRESS -> filtered.sortedByDescending { book ->
                val content = LibraryManager.loadBookContent(context, book.id)
                val total = (content?.chapters?.size ?: 1).coerceAtLeast(1)
                (book.lastChapterIndex + 1).toFloat() / total.toFloat()
            }
        }
    }

    fun estimateBookProgress(context: Context, book: SavedBookMeta): Float {
        val content = LibraryManager.loadBookContent(context, book.id) ?: return 0f
        val total = content.chapters.size.coerceAtLeast(1)
        return ((book.lastChapterIndex + 1).toFloat() / total.toFloat()).coerceIn(0f, 1f)
    }

    fun deleteAudioFileIfExists(path: String) {
        try {
            val f = File(path)
            if (f.exists()) f.delete()
        } catch (_: Exception) {
        }
    }
}
