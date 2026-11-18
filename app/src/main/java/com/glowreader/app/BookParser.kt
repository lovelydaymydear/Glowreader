package com.glowreader.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.text.Html
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
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
    val chapters: List<BookChapter>
)

object BookParser {

    fun parseBook(context: Context, uri: Uri): ParsedBook {
        return try {
            val fileName = getFileName(context, uri)
            val lowerName = fileName.lowercase()
            when {
                lowerName.endsWith(".pdf") -> parsePdf(context, uri, fileName)
                lowerName.endsWith(".epub") -> parseEpub(context, uri, fileName)
                else -> parseTxt(context, uri, fileName)
            }
        } catch (e: Exception) {
            ParsedBook(
                title = "Erro ao abrir",
                chapters = listOf(BookChapter("Aviso", "Não foi possível processar este ficheiro: ${e.message}"))
            )
        }
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
        return ParsedBook(title.substringBeforeLast("."), listOf(BookChapter("Texto Completo", text)))
    }

    private fun parsePdf(context: Context, uri: Uri, title: String): ParsedBook {
        PDFBoxResourceLoader.init(context)
        val chapters = mutableListOf<BookChapter>()
        context.contentResolver.openInputStream(uri)?.use { stream ->
            PDDocument.load(stream, MemoryUsageSetting.setupTempFileOnly()).use { document ->
                val stripper = PDFTextStripper()
                val totalPages = document.numberOfPages.coerceAtMost(300)
                for (page in 1..totalPages) {
                    stripper.startPage = page
                    stripper.endPage = page
                    val pageText = stripper.getText(document).trim()
                    if (pageText.isNotEmpty()) {
                        val firstLine = pageText.lines().firstOrNull { it.trim().length in 3..45 }?.trim()
                        val chapterTitle = if (firstLine != null) "Pág. $page - $firstLine" else "Página $page"
                        chapters.add(BookChapter(chapterTitle, pageText))
                    }
                }
            }
        }
        if (chapters.isEmpty()) {
            chapters.add(BookChapter("Página 1", "Este PDF parece ser composto apenas por imagens digitalizadas sem texto selecionável."))
        }
        return ParsedBook(title.substringBeforeLast("."), chapters)
    }

    private fun parseEpub(context: Context, uri: Uri, title: String): ParsedBook {
        val htmlFiles = mutableMapOf<String, String>()
        val spineOrder = mutableListOf<String>()
        val manifestMap = mutableMapOf<String, String>()
        val tocTitles = mutableMapOf<String, String>()

        context.contentResolver.openInputStream(uri)?.use { stream ->
            ZipInputStream(stream).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val entryName = entry.name
                    val isTextFile = entryName.endsWith(".opf", true) ||
                            entryName.endsWith(".ncx", true) ||
                            entryName.endsWith(".html", true) ||
                            entryName.endsWith(".xhtml", true) ||
                            entryName.endsWith(".htm", true)

                    if (!entry.isDirectory && isTextFile) {
                        val contentStr = zip.bufferedReader(Charsets.UTF_8).readText()
                        when {
                            entryName.endsWith(".opf", true) -> parseOpf(contentStr, manifestMap, spineOrder)
                            entryName.endsWith(".ncx", true) -> parseNcx(contentStr, tocTitles)
                            else -> {
                                val cleanName = entryName.substringAfterLast("/")
                                val plainText = Html.fromHtml(contentStr, Html.FROM_HTML_MODE_COMPACT).toString().trim()
                                if (plainText.isNotBlank()) {
                                    htmlFiles[cleanName] = plainText
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

        return ParsedBook(title.substringBeforeLast("."), chapters)
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
