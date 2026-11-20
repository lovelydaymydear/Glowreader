package com.glowreader.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

data class WebImportResult(
    val success: Boolean,
    val book: ParsedBook? = null,
    val sourceUrl: String = "",
    val errorMessage: String? = null
)

object WebImporter {

    fun normalizeUrl(rawUrl: String): String? {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return null
        val withScheme = if (
            trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            trimmed
        } else {
            "https://$trimmed"
        }
        return runCatching {
            val u = URL(withScheme)
            val proto = u.protocol.lowercase()
            val host = u.host.orEmpty().trim()
            if ((proto == "http" || proto == "https") && host.contains('.')) {
                withScheme
            } else {
                null
            }
        }.getOrNull()
    }

    suspend fun importFromUrl(rawUrl: String): WebImportResult = withContext(Dispatchers.IO) {
        val normalized = normalizeUrl(rawUrl)
            ?: return@withContext WebImportResult(
                success = false,
                errorMessage = "Link inválido. Verifica o endereço da página web."
            )

        var connection: HttpURLConnection? = null
        try {
            val url = URL(normalized)
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15000
                readTimeout = 20000
                instanceFollowRedirects = true
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 14; GlowReader) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                )
                setRequestProperty("Accept-Language", "pt-PT,pt;q=0.9,en;q=0.8")
            }

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                return@withContext WebImportResult(
                    success = false,
                    sourceUrl = normalized,
                    errorMessage = "Não foi possível carregar a página (código HTTP $responseCode)."
                )
            }

            val html = connection.inputStream.use { stream ->
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                    reader.readText()
                }
            }

            if (html.isBlank()) {
                return@withContext WebImportResult(
                    success = false,
                    sourceUrl = normalized,
                    errorMessage = "A página não devolveu conteúdo legível."
                )
            }

            val fallbackHost = runCatching { URL(normalized).host }.getOrNull()
            val title = extractTitle(html)?.ifBlank { null } ?: (fallbackHost ?: "Artigo da Web")
            val cleanedText = extractReadableText(html)

            if (cleanedText.length < 40) {
                return@withContext WebImportResult(
                    success = false,
                    sourceUrl = normalized,
                    errorMessage = "Pouco texto encontrado na página. Pode exigir JavaScript ou login."
                )
            }

            val chapters = splitIntoChapters(title, cleanedText)
            val parsedBook = ParsedBook(
                title = title,
                chapters = chapters
            )

            WebImportResult(
                success = true,
                book = parsedBook,
                sourceUrl = normalized
            )
        } catch (e: Exception) {
            WebImportResult(
                success = false,
                sourceUrl = normalized,
                errorMessage = "Erro ao importar página: ${e.localizedMessage ?: "falha de rede"}"
            )
        } finally {
            connection?.disconnect()
        }
    }

    private fun extractTitle(html: String): String? {
        val ogRegex = Regex(
            "<meta[^>]+property=[\"']og:title[\"'][^>]+content=[\"']([^\"']+)[\"']",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        val ogMatch = ogRegex.find(html)
        if (ogMatch != null) {
            val cleaned = decodeHtmlEntities(ogMatch.groupValues[1]).trim()
            if (cleaned.isNotEmpty()) return cleaned
        }

        val titleRegex = Regex(
            "<title[^>]*>(.*?)</title>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        val titleMatch = titleRegex.find(html)
        if (titleMatch != null) {
            val cleaned = decodeHtmlEntities(stripAllTags(titleMatch.groupValues[1])).trim()
            if (cleaned.isNotEmpty()) return cleaned
        }
        return null
    }

    private fun extractReadableText(html: String): String {
        var working = html

        val blockTagsToRemove = listOf(
            "script", "style", "noscript", "svg", "nav", "footer", "header", "aside", "form"
        )
        for (tag in blockTagsToRemove) {
            working = working.replace(
                Regex("<$tag\\b[^>]*>.*?</$tag>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)),
                " "
            )
        }

        val articleRegex = Regex(
            "<(article|main)\\b[^>]*>(.*?)</\\1>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        val articleMatch = articleRegex.find(working)
        if (articleMatch != null) {
            val candidate = decodeHtmlEntities(formatBlocksToLines(articleMatch.groupValues[2])).trim()
            if (candidate.length >= 200) {
                working = articleMatch.groupValues[2]
            }
        }

        val formatted = formatBlocksToLines(working)
        val decoded = decodeHtmlEntities(formatted)

        return decoded
            .replace("\r\n", "\n")
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex(" *\\n *"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    private fun formatBlocksToLines(fragment: String): String {
        return fragment
            .replace(
                Regex("<(br|hr)\\s*/?>", RegexOption.IGNORE_CASE),
                "\n"
            )
            .replace(
                Regex("</(p|div|h1|h2|h3|h4|h5|h6|li|blockquote|section|tr)>", RegexOption.IGNORE_CASE),
                "\n\n"
            )
            .replace(Regex("<[^>]+>"), " ")
    }

    private fun stripAllTags(fragment: String): String {
        return fragment.replace(Regex("<[^>]+>"), " ")
    }

    private fun decodeHtmlEntities(text: String): String {
        var out = text
            .replace("&nbsp;", " ", ignoreCase = true)
            .replace("&amp;", "&", ignoreCase = true)
            .replace("&quot;", "\"", ignoreCase = true)
            .replace("&#39;", "'", ignoreCase = true)
            .replace("&apos;", "'", ignoreCase = true)
            .replace("&lt;", "<", ignoreCase = true)
            .replace("&gt;", ">", ignoreCase = true)
            .replace("&mdash;", "—", ignoreCase = true)
            .replace("&ndash;", "–", ignoreCase = true)
            .replace("&hellip;", "…", ignoreCase = true)

        out = Regex("&#(\\d+);").replace(out) { match ->
            val code = match.groupValues[1].toIntOrNull()
            if (code != null && code in 1..0x10FFFF) code.toChar().toString() else match.value
        }

        out = Regex("&#x([0-9a-fA-F]+);").replace(out) { match ->
            val code = match.groupValues[1].toIntOrNull(16)
            if (code != null && code in 1..0x10FFFF) code.toChar().toString() else match.value
        }

        return out
    }

    private fun splitIntoChapters(title: String, fullText: String): List<BookChapter> {
        val paragraphs = fullText.split("\n\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        if (paragraphs.isEmpty()) {
            return listOf(BookChapter(title = title, content = fullText))
        }

        val maxCharsPerPart = 6500
        val chapters = mutableListOf<BookChapter>()
        val currentBuilder = StringBuilder()
        var partIndex = 1

        for (para in paragraphs) {
            val additionLength = para.length + if (currentBuilder.isEmpty()) 0 else 2
            if (currentBuilder.isNotEmpty() && currentBuilder.length + additionLength > maxCharsPerPart) {
                val partTitle = if (partIndex == 1) title else "$title (Parte $partIndex)"
                chapters.add(BookChapter(title = partTitle, content = currentBuilder.toString()))
                currentBuilder.clear()
                partIndex++
            }
            if (currentBuilder.isNotEmpty()) currentBuilder.append("\n\n")
            currentBuilder.append(para)
        }

        if (currentBuilder.isNotEmpty()) {
            val partTitle = if (partIndex == 1) title else "$title (Parte $partIndex)"
            chapters.add(BookChapter(title = partTitle, content = currentBuilder.toString()))
        }

        return chapters
    }
}
