package com.glowreader.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.net.Uri
import android.provider.OpenableColumns
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.rendering.PDFRenderer
import com.tom_roush.pdfbox.text.PDFTextStripperByArea
import org.jsoup.Jsoup
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.zip.ZipInputStream

data class BookChapter(
    val title: String,
    val content: String
)

data class ParsedBook(
    val title: String,
    val chapters: List<BookChapter>,
    val coverBitmap: Bitmap? = null
)

object BookParser {

    fun parseWebUrl(url: String): ParsedBook {
        return try {
            val doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                .timeout(15000)
                .get()

            doc.select("script, style, nav, header, footer, aside, .ads, .menu, .comments").remove()
            val pageTitle = doc.title().ifBlank { "Artigo Web" }

            val paragraphs = doc.select("article p, main p, .chapter-content p, .entry-content p, p")
                .map { it.text().trim() }
                .filter { it.length > 25 }
                .distinct()

            val cleanBody = if (paragraphs.isNotEmpty()) {
                paragraphs.joinToString("\n\n")
            } else {
                doc.body().text()
            }

            ParsedBook(
                title = pageTitle.take(60),
                chapters = listOf(BookChapter(pageTitle, cleanTextNoise(cleanBody)))
            )
        } catch (e: Exception) {
            ParsedBook(
                title = "Erro no Link",
                chapters = listOf(BookChapter("Falha ao carregar", "Não foi possível extrair o texto deste link: ${e.message}"))
            )
        }
    }

    fun parseBook(context: Context, uri: Uri): Pair<ParsedBook, String> {
        return try {
            val fileName = getFileName(context, uri)
            val mimeType = context.contentResolver.getType(uri)?.lowercase() ?: ""
            val lowerName = fileName.lowercase()

            when {
                lowerName.endsWith(".pdf") || mimeType.contains("pdf") ->
                    parsePdf(context, uri, fileName) to "PDF"
                lowerName.endsWith(".epub") || mimeType.contains("epub") ->
                    parseEpub(context, uri, fileName) to "EPUB"
                else ->
                    parseTxt(context, uri, fileName) to "TXT"
            }
        } catch (e: Exception) {
            ParsedBook(
                title = "Erro ao abrir",
                chapters = listOf(BookChapter("Aviso", "Erro ao processar ficheiro: ${e.message}"))
            ) to "ERRO"
        }
    }

    private fun cleanTextNoise(raw: String): String {
        return raw.lines()
            .map { it.trim() }
            .filter { line ->
                line.isNotEmpty() && !line.matches(Regex("^\\d+$")) && !line.matches(Regex("^(Pág\\.\vert{}Página\vert{}Page)\\s*\\d+.*$", RegexOption.IGNORE_CASE))
            }
            .joinToString("\n")
    }

    private fun getFileName(context: Context, uri: Uri): String {
        var name = "Livro"
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex >= 0) {
                name = cursor.getString(nameIndex)
            }
        }
        return name
    }

    private fun parseTxt(context: Context, uri: Uri, title: String): ParsedBook {
        val text = context.contentResolver.openInputStream(uri)?.use { stream ->
            BufferedReader(InputStreamReader(stream)).readText()
        } ?: ""
        return ParsedBook(title.substringBeforeLast("."), listOf(BookChapter("Texto Completo", cleanTextNoise(text))))
    }

    private fun parsePdf(context: Context, uri: Uri, title: String): ParsedBook {
        PDFBoxResourceLoader.init(context)
        val chapters = mutableListOf<BookChapter>()
        var coverBitmap: Bitmap? = null

        context.contentResolver.openInputStream(uri)?.use { stream ->
            PDDocument.load(stream, MemoryUsageSetting.setupTempFileOnly()).use { document ->
                if (document.numberOfPages > 0) {
                    try {
                        val renderer = PDFRenderer(document)
                        coverBitmap = renderer.renderImageWithDPI(0, 72f)
                    } catch (_: Exception) {}
                }

                val stripper = PDFTextStripperByArea()
                val totalPages = document.numberOfPages.coerceAtMost(350)
                for (i in 0 until totalPages) {
                    val page = document.getPage(i)
                    val box = page.mediaBox
                    val topMargin = box.height * 0.08f
                    val bottomMargin = box.height * 0.08f
                    val contentRect = RectF(0f, topMargin, box.width, box.height - bottomMargin)
                    stripper.addRegion("body", contentRect)
                    stripper.extractRegions(page)
                    val rawText = stripper.getTextForRegion("body").trim()
                    val cleaned = cleanTextNoise(rawText)
                    if (cleaned.isNotEmpty()) {
                        val firstLine = cleaned.lines().firstOrNull { it.length in 3..45 }
                        val chTitle = if (firstLine != null) "Pág. ${i + 1} • $firstLine" else "Página ${i + 1}"
                        chapters.add(BookChapter(chTitle, cleaned))
                    }
                }
            }
        }
        if (chapters.isEmpty()) {
            chapters.add(BookChapter("Página 1", "Este PDF contém apenas imagens sem texto selecionável."))
        }
        return ParsedBook(title.substringBeforeLast("."), chapters, coverBitmap)
    }

    private fun parseEpub(context: Context, uri: Uri, title: String): ParsedBook {
        val htmlFiles = mutableMapOf<String, String>()
        val spineOrder = mutableListOf<String>()
        val manifestMap = mutableMapOf<String, String>()
        val tocTitles = mutableMapOf<String, String>()
        var coverBitmap: Bitmap? = null
        var fallbackCoverBitmap: Bitmap? = null

        context.contentResolver.openInputStream(uri)?.use { stream ->
            ZipInputStream(stream).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val entryName = entry.name
                    val lower = entryName.lowercase()
                    val isTextFile = lower.endsWith(".opf") || lower.endsWith(".ncx") ||
                        lower.endsWith(".html") || lower.endsWith(".xhtml") || lower.endsWith(".htm")
                    val isImageFile = lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")
                    val isExplicitCover = lower.contains("cover") || lower.contains("capa")

                    if (!entry.isDirectory) {
                        if (isImageFile && (coverBitmap == null || fallbackCoverBitmap == null)) {
                            try {
                                val imgBytes = zip.readBytes()
                                val decoded = BitmapFactory.decodeByteArray(imgBytes, 0, imgBytes.size)
                                if (decoded != null && decoded.width >= 32 && decoded.height >= 32) {
                                    if (isExplicitCover && coverBitmap == null) {
                                        coverBitmap = decoded
                                    } else if (fallbackCoverBitmap == null) {
                                        fallbackCoverBitmap = decoded
                                    }
                                }
                            } catch (_: Exception) {}
                        } else if (isTextFile) {
                            val contentStr = zip.bufferedReader(Charsets.UTF_8).readText()
                            when {
                                lower.endsWith(".opf") -> parseOpf(contentStr, manifestMap, spineOrder)
                                lower.endsWith(".ncx") -> parseNcx(contentStr, tocTitles)
                                else -> {
                                    val cleanName = entryName.substringAfterLast("/")
                                    val doc = Jsoup.parse(contentStr)
                                    doc.select("header, footer, nav, sup, .pagenum").remove()
                                    val plainText = doc.select("p, h1, h2, h3, h4, div").eachText()
                                        .filter { it.isNotBlank() }
                                        .joinToString("\n\n")
                                        .ifBlank { doc.body().text() }
                                    val cleaned = cleanTextNoise(plainText)
                                    if (cleaned.isNotBlank()) {
                                        htmlFiles[cleanName] = cleaned
                                    }
                                }
                            }
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }

        val chapters = mutableListOf<BookChapter>()
        if (spineOrder.isNotEmpty()) {
            spineOrder.forEachIndexed { index, idRef ->
                val href = manifestMap[idRef]?.substringAfterLast("/")?.substringBefore("#")
                val text = if (href != null) htmlFiles[href] else null
                if (!text.isNullOrBlank()) {
                    val chapterTitle = tocTitles[href] ?: "Capítulo ${index + 1}"
                    chapters.add(BookChapter(chapterTitle, text))
                }
            }
        }

        if (chapters.isEmpty()) {
            htmlFiles.entries.forEachIndexed { index, (fileName, text) ->
                val chapterTitle = tocTitles[fileName] ?: "Capítulo ${index + 1}"
                chapters.add(BookChapter(chapterTitle, text))
            }
        }

        if (chapters.isEmpty()) {
            chapters.add(BookChapter("Início", "Não foi possível extrair capítulos deste EPUB."))
        }

        return ParsedBook(
            title = title.substringBeforeLast("."),
            chapters = chapters,
            coverBitmap = coverBitmap ?: fallbackCoverBitmap
        )
    }

    private fun parseOpf(xml: String, manifest: MutableMap<String, String>, spine: MutableList<String>) {
        val factory = XmlPullParserFactory.newInstance()
        val parser = factory.newPullParser()
        parser.setInput(xml.reader())
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "item" -> {
                        val id = parser.getAttributeValue(null, "id")
                        val href = parser.getAttributeValue(null, "href")
                        if (id != null && href != null) manifest[id] = href
                    }
                    "itemref" -> {
                        val idref = parser.getAttributeValue(null, "idref")
                        if (idref != null) spine.add(idref)
                    }
                }
            }
            event = parser.next()
        }
    }

    private fun parseNcx(xml: String, tocTitles: MutableMap<String, String>) {
        val factory = XmlPullParserFactory.newInstance()
        val parser = factory.newPullParser()
        parser.setInput(xml.reader())
        var event = parser.eventType
        var currentLabel = ""
        var inText = false
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "text") inText = true
                    if (parser.name == "content") {
                        val src = parser.getAttributeValue(null, "src")?.substringAfterLast("/")?.substringBefore("#")
                        if (src != null && currentLabel.isNotBlank() && !tocTitles.containsKey(src)) {
                            tocTitles[src] = currentLabel.trim()
                        }
                    }
                }
                XmlPullParser.TEXT -> {
                    if (inText) currentLabel = parser.text ?: ""
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "text") inText = false
                }
            }
            event = parser.next()
        }
    }
}
