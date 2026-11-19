package com.glowreader.app

import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import java.util.Locale

enum class ReaderTheme(
    val label: String,
    val bg: Color,
    val text: Color,
    val highlightBg: Color,
    val highlightText: Color,
    val barBg: Color
) {
    LIGHT("Claro", Color(0xFFFAFAFA), Color(0xFF1E1E1E), Color(0xFFFFE082), Color(0xFF000000), Color(0xFFFFFFFF)),
    SEPIA("Sépia", Color(0xFFF4ECD8), Color(0xFF3E3224), Color(0xFFE6C27A), Color(0xFF1F1810), Color(0xFFEBE0C5)),
    DARK("Escuro", Color(0xFF121212), Color(0xFFE0E0E0), Color(0xFF3B4CCA), Color(0xFFFFFFFF), Color(0xFF1E1E1E))
}

class MainActivity : ComponentActivity() {
    private var tts: TextToSpeech? = null

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val context = LocalContext.current
            val clipboard = LocalClipboardManager.current
            val scope = rememberCoroutineScope()

            var currentTab by remember { mutableIntStateOf(0) }
            var currentTheme by remember { mutableStateOf(ReaderTheme.SEPIA) }
            var fontSize by remember { mutableFloatStateOf(19f) }
            var currentFont by remember { mutableStateOf(ReaderFont.SERIF) }
            var currentAlign by remember { mutableStateOf(ReaderAlignment.JUSTIFY) }

            var savedBooks by remember { mutableStateOf(LibraryManager.loadLibrary(context)) }
            var activeBookMeta by remember { mutableStateOf<SavedBookMeta?>(null) }
            var activeBookContent by remember { mutableStateOf<ParsedBook?>(null) }
            var currentChapterIndex by remember { mutableIntStateOf(0) }
            var isImporting by remember { mutableStateOf(false) }

            var showWebImportDialog by remember { mutableStateOf(false) }
            var webUrlInput by remember { mutableStateOf("") }
            var webImportError by remember { mutableStateOf<String?>(null) }
            var webImportStatus by remember { mutableStateOf<String?>(null) }

            var annotations by remember { mutableStateOf(ReaderStorage.loadAnnotations(context)) }
            var selectedLocale by remember { mutableStateOf(Locale("pt", "PT")) }
            var availableVoices by remember { mutableStateOf<List<Voice>>(emptyList()) }
            var selectedVoiceName by remember { mutableStateOf("") }
            var dictionaryProvider by remember { mutableStateOf("Google Tradutor") }

            var internalLookupTitle by remember { mutableStateOf<String?>(null) }
            var internalLookupUrl by remember { mutableStateOf<String?>(null) }

            var isPlaying by remember { mutableStateOf(false) }
            var speechRate by remember { mutableFloatStateOf(1.0f) }
            var ttsReady by remember { mutableStateOf(false) }
            var activeSentenceRange by remember { mutableStateOf<IntRange?>(null) }
            var sentenceQueue by remember { mutableStateOf<List<Pair<IntRange, String>>>(emptyList()) }
            var currentSentenceIdx by remember { mutableIntStateOf(0) }

            fun speakNextSentence(index: Int, queue: List<Pair<IntRange, String>>) {
                if (index >= queue.size) {
                    isPlaying = false
                    activeSentenceRange = null
                    return
                }
                currentSentenceIdx = index
                val (range, sentenceText) = queue[index]
                activeSentenceRange = range
                tts?.setSpeechRate(speechRate)
                tts?.speak(sentenceText, TextToSpeech.QUEUE_FLUSH, Bundle(), "sent_$index")
            }

            DisposableEffect(Unit) {
                val engine = TextToSpeech(context) { status ->
                    if (status == TextToSpeech.SUCCESS) {
                        tts?.language = selectedLocale
                        availableVoices = tts?.voices?.toList()?.sortedBy { it.locale.displayLanguage } ?: emptyList()
                        ttsReady = true
                    }
                }
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) { isPlaying = true }
                    override fun onDone(utteranceId: String?) {
                        if (isPlaying) {
                            speakNextSentence(currentSentenceIdx + 1, sentenceQueue)
                        }
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { isPlaying = false }
                })
                tts = engine
                onDispose {
                    engine.stop()
                    engine.shutdown()
                }
            }

            fun stopAudio() {
                isPlaying = false
                tts?.stop()
                activeSentenceRange = null
            }

            fun startAudio(text: String) {
                if (!ttsReady || text.isBlank()) return
                val regex = Regex("[^.!?\\n]+[.!?\\n]*")
                val list = regex.findAll(text)
                    .map { it.range to it.value.trim() }
                    .filter { it.second.isNotEmpty() }
                    .toList()
                if (list.isEmpty()) return
                sentenceQueue = list
                isPlaying = true
                speakNextSentence(0, list)
            }

            fun openDictionaryOrTranslate(query: String) {
                val clean = query.trim()
                val encoded = URLEncoder.encode(clean, "UTF-8")
                when (dictionaryProvider) {
                    "Priberam / Dicio" -> {
                        internalLookupTitle = if (clean.isBlank()) "Dicionário" else "Dicionário: $clean"
                        internalLookupUrl = if (clean.isBlank()) "https://www.dicio.com.br/" else "https://www.dicio.com.br/$encoded/"
                    }
                    "Linguee / DeepL" -> {
                        internalLookupTitle = if (clean.isBlank()) "DeepL Tradutor" else "Traduzir: $clean"
                        internalLookupUrl = "https://www.deepl.com/translator#pt/en/$encoded"
                    }
                    else -> {
                        internalLookupTitle = if (clean.isBlank()) "Tradutor" else "Traduzir: $clean"
                        internalLookupUrl = "https://translate.google.com/?sl=auto&tl=pt&text=$encoded"
                    }
                }
            }

            fun openBookMeta(meta: SavedBookMeta) {
                val loaded = LibraryManager.loadBookContent(context, meta.id)
                if (loaded != null) {
                    activeBookMeta = meta
                    activeBookContent = loaded
                    currentChapterIndex = meta.lastChapterIndex.coerceIn(0, (loaded.chapters.size - 1).coerceAtLeast(0))
                }
            }

            fun triggerWebImport(rawUrl: String) {
                webImportError = null
                webImportStatus = "A descarregar e processar página..."
                isImporting = true
                scope.launch {
                    val result = WebImporter.importFromUrl(rawUrl)
                    if (result.success && result.book != null && result.book.chapters.isNotEmpty()) {
                        withContext(Dispatchers.IO) {
                            LibraryManager.saveBookToLibrary(context, result.book, "WEB")
                        }
                        savedBooks = LibraryManager.loadLibrary(context)
                        webImportStatus = null
                        webImportError = null
                        webUrlInput = ""
                        showWebImportDialog = false
                        currentTab = 1
                    } else {
                        webImportStatus = null
                        webImportError = result.errorMessage ?: "Não foi possível importar a página."
                    }
                    isImporting = false
                }
            }

            val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
                uri?.let {
                    isImporting = true
                    scope.launch {
                        val (parsedBook, detectedFormat) = withContext(Dispatchers.IO) {
                            BookParser.parseBook(context, it)
                        }
                        if (parsedBook.chapters.isNotEmpty()) {
                            val format = detectedFormat.ifBlank {
                                when {
                                    it.toString().endsWith(".epub", true) -> "EPUB"
                                    it.toString().endsWith(".pdf", true) -> "PDF"
                                    else -> "TXT"
                                }
                            }
                            withContext(Dispatchers.IO) {
                                LibraryManager.saveBookToLibrary(context, parsedBook, format)
                            }
                            savedBooks = LibraryManager.loadLibrary(context)
                        }
                        isImporting = false
                    }
                }
            }

            if (showWebImportDialog) {
                AlertDialog(
                    onDismissRequest = {
                        if (!isImporting) {
                            showWebImportDialog = false
                            webImportError = null
                            webImportStatus = null
                        }
                    },
                    title = { Text("Importar página da web") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                text = "Cola o link de um artigo, capítulo ou página web em português para extrair o texto e guardar na biblioteca.",
                                fontSize = 13.sp
                            )
                            OutlinedTextField(
                                value = webUrlInput,
                                onValueChange = {
                                    webUrlInput = it
                                    webImportError = null
                                },
                                label = { Text("https://exemplo.com/artigo") },
                                singleLine = true,
                                enabled = !isImporting,
                                modifier = Modifier.fillMaxWidth()
                            )
                            if (webImportStatus != null) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                    Text(webImportStatus!!, fontSize = 12.sp)
                                }
                            }
                            if (webImportError != null) {
                                Text(
                                    text = webImportError!!,
                                    color = MaterialTheme.colorScheme.error,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = { triggerWebImport(webUrlInput) },
                            enabled = !isImporting && webUrlInput.isNotBlank()
                        ) {
                            Text("Importar link")
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = {
                                showWebImportDialog = false
                                webImportError = null
                                webImportStatus = null
                            },
                            enabled = !isImporting
                        ) {
                            Text("Cancelar")
                        }
                    }
                )
            }

            if (internalLookupUrl != null && internalLookupTitle != null) {
                InAppLookupDialog(
                    title = internalLookupTitle!!,
                    url = internalLookupUrl!!,
                    theme = currentTheme,
                    onDismiss = {
                        internalLookupUrl = null
                        internalLookupTitle = null
                    }
                )
            }

            val currentBook = activeBookContent
            val currentMeta = activeBookMeta

            if (currentBook != null && currentMeta != null) {
                val scrollState = rememberScrollState(initial = currentMeta.lastScrollPosition)
                val activeChapter = currentBook.chapters.getOrElse(currentChapterIndex) { BookChapter("Vazio", "") }
                val textContent = activeChapter.content

                var showTocSheet by remember { mutableStateOf(false) }
                var showTypeSheet by remember { mutableStateOf(false) }
                var showNotebookSheet by remember { mutableStateOf(false) }
                var showNoteDialog by remember { mutableStateOf(false) }
                var draftSelection by remember { mutableStateOf("") }
                var draftNote by remember { mutableStateOf("") }
                var draftColor by remember { mutableStateOf(HighlightColor.YELLOW) }
                var draftUnderline by remember { mutableStateOf(false) }

                DisposableEffect(currentChapterIndex) {
                    onDispose {
                        LibraryManager.updateReadingProgress(context, currentMeta.id, currentChapterIndex, scrollState.value)
                        savedBooks = LibraryManager.loadLibrary(context)
                    }
                }

                LaunchedEffect(activeSentenceRange) {
                    val range = activeSentenceRange
                    if (range != null && textContent.isNotEmpty() && scrollState.maxValue > 0) {
                        val ratio = range.first.toFloat() / textContent.length.toFloat()
                        val targetScroll = (ratio * scrollState.maxValue).toInt()
                        scrollState.animateScrollTo(targetScroll)
                    }
                }

                var lastClipboardText by remember { mutableStateOf(clipboard.getText()?.text ?: "") }
                var showStudyBanner by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    while (true) {
                        kotlinx.coroutines.delay(900)
                        val clipNow = clipboard.getText()?.text ?: ""
                        if (clipNow.isNotBlank() && clipNow != lastClipboardText) {
                            lastClipboardText = clipNow
                            draftSelection = clipNow
                            showStudyBanner = true
                        }
                    }
                }

                if (showTypeSheet) {
                    TypographySheet(
                        theme = currentTheme,
                        fontSize = fontSize,
                        onFontSizeChange = { fontSize = it },
                        currentFont = currentFont,
                        onFontChange = { currentFont = it },
                        currentAlign = currentAlign,
                        onAlignChange = { currentAlign = it },
                        onDismiss = { showTypeSheet = false }
                    )
                }

                if (showNotebookSheet) {
                    NotebookSheet(
                        theme = currentTheme,
                        currentBookTitle = currentBook.title,
                        annotations = annotations,
                        onDeleteAnnotation = { id ->
                            annotations = annotations.filterNot { it.id == id }
                            ReaderStorage.saveAnnotations(context, annotations)
                        },
                        onJumpToChapter = { idx ->
                            stopAudio()
                            currentChapterIndex = idx.coerceIn(0, (currentBook.chapters.size - 1).coerceAtLeast(0))
                            scope.launch { scrollState.scrollTo(0) }
                        },
                        onDismiss = { showNotebookSheet = false }
                    )
                }

                if (showNoteDialog) {
                    AlertDialog(
                        onDismissRequest = { showNoteDialog = false },
                        title = { Text("Marcar Cor, Sublinhar ou Anotar") },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                OutlinedTextField(
                                    value = draftSelection,
                                    onValueChange = { draftSelection = it },
                                    label = { Text("Trecho selecionado") },
                                    maxLines = 3
                                )
                                Text("Escolhe a cor real do marcador:", fontSize = 13.sp)
                                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                    HighlightColor.entries.forEach { c ->
                                        val isSelected = draftColor == c
                                        Box(
                                            modifier = Modifier
                                                .size(36.dp)
                                                .clip(CircleShape)
                                                .background(c.toColor())
                                                .border(
                                                    width = if (isSelected) 3.dp else 1.dp,
                                                    color = if (isSelected) Color.Black else Color.Gray,
                                                    shape = CircleShape
                                                )
                                                .clickable { draftColor = c }
                                        )
                                    }
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(checked = draftUnderline, onCheckedChange = { draftUnderline = it })
                                    Text("Sublinhar texto no livro")
                                }
                                OutlinedTextField(
                                    value = draftNote,
                                    onValueChange = { draftNote = it },
                                    label = { Text("Nota pessoal (opcional)") },
                                    maxLines = 3
                                )
                                if (draftSelection.isNotBlank()) {
                                    TextButton(onClick = { openDictionaryOrTranslate(draftSelection) }) {
                                        Icon(Icons.Rounded.Translate, contentDescription = null)
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Abrir no $dictionaryProvider")
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            Button(onClick = {
                                if (draftSelection.isNotBlank() || draftNote.isNotBlank()) {
                                    val item = StudyAnnotation(
                                        bookTitle = currentBook.title,
                                        chapterIndex = currentChapterIndex,
                                        chapterTitle = activeChapter.title,
                                        selectedText = draftSelection.trim(),
                                        note = draftNote.trim(),
                                        colorName = draftColor.name,
                                        isUnderline = draftUnderline
                                    )
                                    annotations = listOf(item) + annotations
                                    ReaderStorage.saveAnnotations(context, annotations)
                                }
                                showNoteDialog = false
                                showStudyBanner = false
                            }) { Text("Guardar") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showNoteDialog = false }) { Text("Cancelar") }
                        }
                    )
                }

                if (showTocSheet) {
                    ModalBottomSheet(
                        onDismissRequest = { showTocSheet = false },
                        containerColor = currentTheme.barBg
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                            Text(
                                text = "Índice — ${currentBook.title}",
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                color = currentTheme.text
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                                itemsIndexed(currentBook.chapters) { index, chapter ->
                                    val isSelected = index == currentChapterIndex
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(if (isSelected) currentTheme.highlightBg.copy(alpha = 0.4f) else Color.Transparent)
                                            .clickable {
                                                stopAudio()
                                                currentChapterIndex = index
                                                showTocSheet = false
                                                scope.launch { scrollState.scrollTo(0) }
                                            }
                                            .padding(vertical = 12.dp, horizontal = 12.dp)
                                    ) {
                                        Text(
                                            text = chapter.title,
                                            color = currentTheme.text,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            fontSize = 15.sp
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(24.dp))
                        }
                    }
                }

                Surface(modifier = Modifier.fillMaxSize(), color = currentTheme.bg) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 22.dp)
                                .verticalScroll(scrollState)
                                .padding(top = 16.dp, bottom = 140.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(onClick = {
                                    stopAudio()
                                    LibraryManager.updateReadingProgress(context, currentMeta.id, currentChapterIndex, scrollState.value)
                                    savedBooks = LibraryManager.loadLibrary(context)
                                    activeBookContent = null
                                    activeBookMeta = null
                                }) {
                                    Icon(Icons.Rounded.ArrowBack, contentDescription = "Voltar", tint = currentTheme.text)
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = currentBook.title,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = currentTheme.text
                                    )
                                    Text(
                                        text = activeChapter.title,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = currentTheme.text.copy(alpha = 0.65f)
                                    )
                                }
                                Row {
                                    IconButton(onClick = { showTocSheet = true }, modifier = Modifier.size(36.dp)) {
                                        Icon(Icons.Rounded.FormatListBulleted, contentDescription = "Índice", tint = currentTheme.text)
                                    }
                                    IconButton(onClick = {
                                        val bm = StudyAnnotation(
                                            bookTitle = currentBook.title,
                                            chapterIndex = currentChapterIndex,
                                            chapterTitle = activeChapter.title,
                                            selectedText = textContent.take(70) + "...",
                                            isBookmark = true
                                        )
                                        annotations = listOf(bm) + annotations
                                        ReaderStorage.saveAnnotations(context, annotations)
                                    }, modifier = Modifier.size(36.dp)) {
                                        Icon(Icons.Rounded.BookmarkAdd, contentDescription = "Marcador", tint = currentTheme.text)
                                    }
                                    IconButton(onClick = { showNotebookSheet = true }, modifier = Modifier.size(36.dp)) {
                                        Icon(Icons.Rounded.Book, contentDescription = "Notas", tint = currentTheme.text)
                                    }
                                    IconButton(onClick = { showTypeSheet = true }, modifier = Modifier.size(36.dp)) {
                                        Icon(Icons.Rounded.FormatSize, contentDescription = "Fontes", tint = currentTheme.text)
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            val bookAnnotations = annotations.filter {
                                it.bookTitle == currentBook.title &&
                                    it.chapterIndex == currentChapterIndex &&
                                    !it.isBookmark
                            }
                            val annotatedText = buildAnnotatedString {
                                append(textContent)
                                bookAnnotations.forEach { ann ->
                                    if (ann.selectedText.isNotBlank()) {
                                        val idx = textContent.indexOf(ann.selectedText)
                                        if (idx >= 0) {
                                            val c = try {
                                                HighlightColor.valueOf(ann.colorName).toColor()
                                            } catch (_: Exception) {
                                                HighlightColor.YELLOW.toColor()
                                            }
                                            addStyle(
                                                SpanStyle(
                                                    background = c.copy(alpha = 0.6f),
                                                    textDecoration = if (ann.isUnderline) TextDecoration.Underline else TextDecoration.None
                                                ),
                                                idx,
                                                (idx + ann.selectedText.length).coerceAtMost(length)
                                            )
                                        }
                                    }
                                }
                                activeSentenceRange?.let { range ->
                                    if (range.first >= 0 && range.last < textContent.length) {
                                        addStyle(
                                            SpanStyle(
                                                background = currentTheme.highlightBg,
                                                color = currentTheme.highlightText,
                                                fontWeight = FontWeight.Bold
                                            ),
                                            range.first,
                                            range.last + 1
                                        )
                                    }
                                }
                            }

                            SelectionContainer {
                                Text(
                                    text = annotatedText,
                                    fontSize = fontSize.sp,
                                    lineHeight = (fontSize * 1.58f).sp,
                                    fontFamily = currentFont.family,
                                    textAlign = currentAlign.align,
                                    color = currentTheme.text
                                )
                            }
                        }

                        if (showStudyBanner) {
                            Surface(
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .padding(top = 70.dp, start = 16.dp, end = 16.dp),
                                shape = RoundedCornerShape(24.dp),
                                color = Color(0xFF1E1E2E),
                                shadowElevation = 10.dp
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    HighlightColor.entries.forEach { c ->
                                        Box(
                                            modifier = Modifier
                                                .size(24.dp)
                                                .clip(CircleShape)
                                                .background(c.toColor())
                                                .clickable {
                                                    val item = StudyAnnotation(
                                                        bookTitle = currentBook.title,
                                                        chapterIndex = currentChapterIndex,
                                                        chapterTitle = activeChapter.title,
                                                        selectedText = draftSelection.trim(),
                                                        colorName = c.name
                                                    )
                                                    annotations = listOf(item) + annotations
                                                    ReaderStorage.saveAnnotations(context, annotations)
                                                    showStudyBanner = false
                                                }
                                        )
                                    }
                                    IconButton(onClick = { showNoteDialog = true }, modifier = Modifier.size(32.dp)) {
                                        Icon(Icons.Rounded.EditNote, contentDescription = "Nota/Sublinhar", tint = Color.White)
                                    }
                                    IconButton(onClick = {
                                        openDictionaryOrTranslate(draftSelection)
                                        showStudyBanner = false
                                    }, modifier = Modifier.size(32.dp)) {
                                        Icon(Icons.Rounded.Translate, contentDescription = "Traduzir/Dicionário", tint = Color.White)
                                    }
                                    IconButton(onClick = { showStudyBanner = false }, modifier = Modifier.size(28.dp)) {
                                        Icon(Icons.Rounded.Close, contentDescription = "Fechar", tint = Color.Gray)
                                    }
                                }
                            }
                        }

                        Surface(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(16.dp)
                                .fillMaxWidth(),
                            shape = RoundedCornerShape(32.dp),
                            color = currentTheme.barBg,
                            shadowElevation = 8.dp
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TextButton(onClick = {
                                    speechRate = when (speechRate) {
                                        1.0f -> 1.25f
                                        1.25f -> 1.5f
                                        1.5f -> 2.0f
                                        else -> 1.0f
                                    }
                                    if (isPlaying) {
                                        stopAudio()
                                        startAudio(textContent)
                                    }
                                }) {
                                    Text("${speechRate}x", fontWeight = FontWeight.Bold, color = currentTheme.text)
                                }
                                IconButton(
                                    onClick = {
                                        if (currentChapterIndex > 0) {
                                            stopAudio()
                                            currentChapterIndex--
                                            scope.launch { scrollState.scrollTo(0) }
                                        }
                                    },
                                    enabled = currentChapterIndex > 0
                                ) {
                                    Icon(Icons.Rounded.SkipPrevious, contentDescription = "Anterior", tint = currentTheme.text)
                                }
                                IconButton(
                                    onClick = { if (isPlaying) stopAudio() else startAudio(textContent) },
                                    modifier = Modifier
                                        .size(52.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF4F46E5))
                                ) {
                                    Icon(
                                        imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                        contentDescription = "Play/Pause",
                                        tint = Color.White,
                                        modifier = Modifier.size(28.dp)
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        if (currentChapterIndex < currentBook.chapters.size - 1) {
                                            stopAudio()
                                            currentChapterIndex++
                                            scope.launch { scrollState.scrollTo(0) }
                                        }
                                    },
                                    enabled = currentChapterIndex < currentBook.chapters.size - 1
                                ) {
                                    Icon(Icons.Rounded.SkipNext, contentDescription = "Seguinte", tint = currentTheme.text)
                                }
                                IconButton(onClick = { stopAudio() }) {
                                    Icon(Icons.Rounded.Stop, contentDescription = "Parar", tint = currentTheme.text)
                                }
                            }
                        }
                    }
                }
            } else {
                val recentMeta = savedBooks.firstOrNull()
                val recentHomeBook = recentMeta?.let { meta ->
                    val content = LibraryManager.loadBookContent(context, meta.id)
                    val chTitle = content?.chapters?.getOrNull(meta.lastChapterIndex)?.title ?: "Capítulo ${meta.lastChapterIndex + 1}"
                    val totalCh = (content?.chapters?.size ?: 1).coerceAtLeast(1)
                    val prog = ((meta.lastChapterIndex + 1).toFloat() / totalCh.toFloat()).coerceIn(0f, 1f)
                    HomeRecentBook(
                        id = meta.id,
                        title = meta.title,
                        author = meta.format,
                        chapterTitle = chTitle,
                        progressPercent = prog,
                        format = meta.format
                    )
                }

                Scaffold(
                    bottomBar = {
                        NavigationBar(containerColor = currentTheme.barBg) {
                            NavigationBarItem(
                                selected = currentTab == 0,
                                onClick = { currentTab = 0 },
                                icon = { Icon(Icons.Rounded.Home, contentDescription = null) },
                                label = { Text("Início") }
                            )
                            NavigationBarItem(
                                selected = currentTab == 1,
                                onClick = { currentTab = 1 },
                                icon = { Icon(Icons.Rounded.LocalLibrary, contentDescription = null) },
                                label = { Text("Biblioteca") }
                            )
                            NavigationBarItem(
                                selected = currentTab == 2,
                                onClick = { currentTab = 2 },
                               
