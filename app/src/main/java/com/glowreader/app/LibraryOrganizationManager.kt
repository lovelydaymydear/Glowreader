package com.glowreader.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer

enum class LibrarySortOption(val label: String) {
    RECENT("Recentes"),
    TITLE_ASC("Título (A-Z)"),
    TITLE_DESC("Título (Z-A)"),
    PROGRESS_DESC("Progresso"),
    FORMAT("Formato")
}

data class BookOrganizationMeta(
    val bookId: String,
    val author: String = "",
    val collectionName: String = "",
    val tags: List<String> = emptyList(),
    val isFavorite: Boolean = false
)

data class DuplicateBookGroup(
    val normalizedKey: String,
    val books: List<SavedBookMeta>
)

object LibraryOrganizationManager {

    private const val PREFS_NAME = "glowreader_org_prefs"
    private const val KEY_META_LIST = "book_org_meta_json"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadAllMeta(context: Context): Map<String, BookOrganizationMeta> {
        val raw = prefs(context).getString(KEY_META_LIST, null) ?: return emptyMap()
        if (raw.isBlank()) return emptyMap()

        return try {
            val arr = JSONArray(raw)
            val map = mutableMapOf<String, BookOrganizationMeta>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val bookId = obj.optString("bookId", "").trim()
                if (bookId.isEmpty()) continue
                val tagsArr = obj.optJSONArray("tags") ?: JSONArray()
                val tags = mutableListOf<String>()
                for (t in 0 until tagsArr.length()) {
                    val tag = tagsArr.optString(t, "").trim()
                    if (tag.isNotEmpty() && !tags.any { it.equals(tag, ignoreCase = true) }) {
                        tags.add(tag)
                    }
                }
                map[bookId] = BookOrganizationMeta(
                    bookId = bookId,
                    author = obj.optString("author", "").trim(),
                    collectionName = obj.optString("collectionName", "").trim(),
                    tags = tags,
                    isFavorite = obj.optBoolean("isFavorite", false)
                )
            }
            map
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun saveAllMeta(context: Context, map: Map<String, BookOrganizationMeta>) {
        val arr = JSONArray()
        map.values.forEach { meta ->
            val tagsArr = JSONArray()
            meta.tags.forEach { tagsArr.put(it) }
            arr.put(
                JSONObject().apply {
                    put("bookId", meta.bookId)
                    put("author", meta.author)
                    put("collectionName", meta.collectionName)
                    put("tags", tagsArr)
                    put("isFavorite", meta.isFavorite)
                }
            )
        }
        prefs(context).edit().putString(KEY_META_LIST, arr.toString()).apply()
    }

    fun getMetaForBook(context: Context, bookId: String): BookOrganizationMeta {
        return loadAllMeta(context)[bookId] ?: BookOrganizationMeta(bookId = bookId)
    }

    fun upsertBookMeta(context: Context, meta: BookOrganizationMeta): Map<String, BookOrganizationMeta> {
        val current = loadAllMeta(context).toMutableMap()
        current[meta.bookId] = meta.copy(
            author = meta.author.trim(),
            collectionName = meta.collectionName.trim(),
            tags = meta.tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        )
        saveAllMeta(context, current)
        return current
    }

    fun toggleFavorite(context: Context, bookId: String): Map<String, BookOrganizationMeta> {
        val current = loadAllMeta(context).toMutableMap()
        val existing = current[bookId] ?: BookOrganizationMeta(bookId = bookId)
        current[bookId] = existing.copy(isFavorite = !existing.isFavorite)
        saveAllMeta(context, current)
        return current
    }

    fun removeBookMeta(context: Context, bookId: String) {
        val current = loadAllMeta(context).toMutableMap()
        if (current.remove(bookId) != null) {
            saveAllMeta(context, current)
        }
    }

    fun computeProgressFraction(book: SavedBookMeta): Float {
        val total = book.totalChapters.coerceAtLeast(1)
        val current = (book.lastChapterIndex + 1).coerceIn(0, total)
        return (current.toFloat() / total.toFloat()).coerceIn(0f, 1f)
    }

    fun normalizeTitleKey(title: String): String {
        val decomposed = Normalizer.normalize(title.lowercase(), Normalizer.Form.NFD)
        return decomposed
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
    }

    fun findDuplicateGroups(books: List<SavedBookMeta>): List<DuplicateBookGroup> {
        return books
            .mapNotNull { book ->
                val key = normalizeTitleKey(book.title)
                if (key.length < 3) null else key to book
            }
            .groupBy({ it.first }, { it.second })
            .filter { it.value.size > 1 }
            .map { (key, groupBooks) ->
                DuplicateBookGroup(
                    normalizedKey = key,
                    books = groupBooks.sortedByDescending { it.lastOpened }
                )
            }
            .sortedByDescending { it.books.size }
    }

    fun filterAndSortBooks(
        books: List<SavedBookMeta>,
        metaMap: Map<String, BookOrganizationMeta>,
        query: String = "",
        folderFilter: String = "Todos",
        formatFilter: String = "TODOS",
        collectionFilter: String = "Todas",
        tagFilter: String = "Todas",
        onlyFavorites: Boolean = false,
        sortOption: LibrarySortOption = LibrarySortOption.RECENT
    ): List<SavedBookMeta> {
        val cleanQuery = normalizeTitleKey(query)

        val filtered = books.filter { book ->
            val meta = metaMap[book.id]

            val matchesFolder = when {
                folderFilter.isBlank() || folderFilter == "Todos" -> true
                folderFilter.equals("Favoritos", ignoreCase = true) ->
                    book.folderName.equals("Favoritos", ignoreCase = true) || (meta?.isFavorite == true)
                else -> book.folderName.equals(folderFilter, ignoreCase = true)
            }

            val matchesFormat = formatFilter.isBlank() ||
                formatFilter.equals("TODOS", ignoreCase = true) ||
                book.format.equals(formatFilter, ignoreCase = true)

            val matchesCollection = collectionFilter.isBlank() ||
                collectionFilter == "Todas" ||
                (meta?.collectionName.orEmpty().equals(collectionFilter, ignoreCase = true))

            val matchesTag = tagFilter.isBlank() ||
                tagFilter == "Todas" ||
                (meta?.tags?.any { it.equals(tagFilter, ignoreCase = true) } == true)

            val matchesFavorite = !onlyFavorites || (meta?.isFavorite == true) ||
                book.folderName.equals("Favoritos", ignoreCase = true)

            val matchesQuery = if (cleanQuery.isEmpty()) {
                true
            } else {
                val hayTitle = normalizeTitleKey(book.title).contains(cleanQuery)
                val hayAuthor = normalizeTitleKey(meta?.author.orEmpty()).contains(cleanQuery)
                val hayCollection = normalizeTitleKey(meta?.collectionName.orEmpty()).contains(cleanQuery)
                val hayTags = meta?.tags?.any { normalizeTitleKey(it).contains(cleanQuery) } == true
                hayTitle || hayAuthor || hayCollection || hayTags
            }

            matchesFolder && matchesFormat && matchesCollection && matchesTag && matchesFavorite && matchesQuery
        }

        return when (sortOption) {
            LibrarySortOption.RECENT -> filtered.sortedByDescending { it.lastOpened }
            LibrarySortOption.TITLE_ASC -> filtered.sortedBy { normalizeTitleKey(it.title) }
            LibrarySortOption.TITLE_DESC -> filtered.sortedByDescending { normalizeTitleKey(it.title) }
            LibrarySortOption.PROGRESS_DESC -> filtered.sortedWith(
                compareByDescending<SavedBookMeta> { computeProgressFraction(it) }
                    .thenByDescending { it.lastOpened }
            )
            LibrarySortOption.FORMAT -> filtered.sortedWith(
                compareBy<SavedBookMeta> { it.format.uppercase() }
                    .thenBy { normalizeTitleKey(it.title) }
            )
        }
    }
}
