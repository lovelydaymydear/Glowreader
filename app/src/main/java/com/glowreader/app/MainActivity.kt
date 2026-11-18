package com.glowreader.app

import android.content.Intent
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import java.util.Locale

enum class ReaderTheme(val label: String, val bg: Color, val text: Color, val highlightBg: Color, val highlightText: Color, val barBg: Color) {
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
            val scrollState = rememberScrollState()

            var currentTheme by remember { mutableStateOf(ReaderTheme.SEPIA) }
            var fontSize by remember { mutableFloatStateOf(19f) }
            var currentFont by remember { mutableStateOf(ReaderFont.SERIF) }
            var currentAlign by remember { mutableStateOf(ReaderAlignment.JUSTIFY) }

            var currentBook by remember {
                mutableStateOf(
                    ParsedBook(
                        title = "GlowReader 3.0",
                        chapters = listOf(
                            BookChapter(
                                title = "Estudo e Leitura",
                                content = "Bem-vindo ao GlowReader 3.0! Agora podes justificar ou alinhar o texto, trocar fontes, selecionar qualquer palavra para traduzir ou abrir o dicionário, criar notas coloridas e acompanhar o highlight em tempo real.\n\n" +
                                        "Experimenta carregar no botão Play abaixo para ver cada palavra iluminada enquanto ouves, ou copia um trecho do texto e toca no ícone do lápis para guardar uma nota."
                            )
                        )
                    )
                )
            }
            var currentChapterIndex by remember { mutableIntStateOf(0) }
            var isLoadingBook by remember { mutableStateOf(false) }

            var annotations by remember { mutableStateOf(ReaderStorage.loadAnnotations(context)) }
            var showTocSheet by remember { mutableStateOf(false) }
            var showTypeSheet by remember { mutableStateOf(false) }
            var showNotebookSheet by remember { mutableStateOf(false) }
            var showSettingsSheet by remember { mutableStateOf(false) }
            var showNoteDialog by remember { mutableStateOf(false) }

            var draftSelection by remember { mutableStateOf("") }
            var draftNote by remember { mutableStateOf("") }
            var draftColor by remember { mutableStateOf(HighlightColor.YELLOW) }
            var draftUnderline by remember { mutableStateOf(false) }

            var selectedLocale by remember { mutableStateOf(Locale("pt", "PT")) }
            var availableVoices by remember { mutableStateOf<List<Voice>>(emptyList()) }
            var selectedVoiceName by remember { mutableStateOf("") }
            var dictionaryProvider by remember { mutableStateOf("Google Tradutor") }

            val activeChapter = currentBook.chapters.getOrElse(currentChapterIndex) { BookChapter("Vazio", "") }
            val textContent = activeChapter.content

            var highlightStart by remember { mutableIntStateOf(-1) }
            var highlightEnd by remember { mutableIntStateOf(-1) }
            var isPlaying by remember { mutableStateOf(false) }
            var speechRate by remember { mutableFloatStateOf(1.0f) }
            var ttsReady by remember { mutableStateOf(false) }
            var nativeRangeWorked by remember { mutableStateOf(false) }

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
                        isPlaying = false
                        highlightStart = -1
                        highlightEnd = -1
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { isPlaying = false }
                    override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                        nativeRangeWorked = true
                        highlightStart = start
                        highlightEnd = end
                    }
                })
                tts = engine
                onDispose { engine.stop(); engine.shutdown() }
            }

            LaunchedEffect(isPlaying, textContent, speechRate) {
                if (isPlaying && textContent.isNotBlank()) {
                    delay(450)
                    if (!nativeRangeWorked && isPlaying) {
                        val regex = Regex("\\S+")
                        val matches = regex.findAll(textContent.take(3800)).toList()
                        for (m in matches) {
                            if (!isPlaying) break
                            highlightStart = m.range.first
                            highlightEnd = m.range.last + 1
                            val wordDelay = ((260 + m.value.length * 38) / speechRate).toLong()
                            delay(wordDelay)
                        }
                    }
                }
            }

            fun stopAudio() {
                tts?.stop()
                isPlaying = false
                highlightStart = -1
                highlightEnd = -1
            }

            fun togglePlay() {
                if (!ttsReady || textContent.isBlank()) return
                if (isPlaying) {
                    stopAudio()
                } else {
                    nativeRangeWorked = false
                    tts?.setSpeechRate(speechRate)
                    val chunk = if (textContent.length > 3800) textContent.substring(0, 3800) else textContent
                    tts?.speak(chunk, TextToSpeech.QUEUE_FLUSH, Bundle(), "glow_utterance")
                }
            }

            fun openDictionaryOrTranslate(query: String) {
                val encoded = URLEncoder.encode(query.trim(), "UTF-8")
                val url = when (dictionaryProvider) {
                    "Priberam / Dicio" -> "https://priberam.org"
                    "Linguee / DeepL" -> "https://deepl.com"
                    else -> "https://google.com"
                }
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (_: Exception) {}
            }

            val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
                uri?.let {
                    stopAudio()
                    isLoadingBook = true
                    scope.launch {
                        currentBook = withContext(Dispatchers.IO) { BookParser.parseBook(context, it) }
                        currentChapterIndex = 0
                        scrollState.scrollTo(0)
                        isLoadingBook = false
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
                        currentChapterIndex = idx.coerceIn(0, currentBook.chapters.size - 1)
                        scope.launch { scrollState.scrollTo(0) }
                    },
                    onDismiss = { showNotebookSheet = false }
                )
            }

            if (showSettingsSheet) {
                SettingsSheet(
                    context = context,
                    theme = currentTheme,
                    selectedLocale = selectedLocale,
                    onLocaleChange = { loc ->
                        selectedLocale = loc
                        tts?.language = loc
                    },
                    availableVoices = availableVoices,
                    selectedVoiceName = selectedVoiceName,
                    onVoiceSelect = { voice ->
                        selectedVoiceName = voice.name
                        tts?.voice = voice
                    },
                    dictionaryProvider = dictionaryProvider,
                    onDictionaryChange = { dictionaryProvider = it },
                    onDismiss = { showSettingsSheet = false }
                )
            }

            if (showNoteDialog) {
                AlertDialog(
                    onDismissRequest = { showNoteDialog = false },
                    title = { Text("Destacar, Traduzir ou Anotar") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedTextField(
                                value = draftSelection,
                                onValueChange = { draftSelection = it },
                                label = { Text("Trecho selecionado") },
                                maxLines = 3
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                HighlightColor.values().forEach { c ->
                                    FilterChip(
                                        selected = draftColor == c,
                                        onClick = { draftColor = c },
                                        label = { Text(c.label, fontSize = 11.sp) }
                                    )
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = draftUnderline, onCheckedChange = { draftUnderline = it })
                                Text("Sublinhar texto no capítulo")
                            }
                            OutlinedTextField(
                                value = draftNote,
                                onValueChange = { draftNote = it },
                                label = { Text("Nota pessoal (opcional)") },
                                maxLines = 3
                            )
                            if (draftSelection.isNotBlank()) {
                                TextButton(onClick = { openDictionaryOrTranslate(draftSelection) }) {
                                    Icon(Icons.Rounded.Translate, null)
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
                        }) { Text("Guardar") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showNoteDialog = false }) { Text("Cancelar") }
                    }
                )
            }

            if (showTocSheet) {
                ModalBottomSheet(onDismissRequest = { showTocSheet = false }, containerColor = currentTheme.barBg) {
                    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                        Text("Índice — ${currentBook.title}", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = currentTheme.text)
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
                            .padding(top = 16.dp, bottom = 135.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = currentBook.title,
                                    fontSize = 17.sp,
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
                                    Icon(Icons.Rounded.FormatListBulleted, "Índice", tint = currentTheme.text)
                                }
                                IconButton(onClick = {
                                    val bm = StudyAnnotation(
                                        bookTitle = currentBook.title,
                                        chapterIndex = currentChapterIndex,
                                        chapterTitle = activeChapter.title,
                                        selectedText = textContent.take(80) + "...",
                                        isBookmark = true
                                    )
                                    annotations = listOf(bm) + annotations
                                    ReaderStorage.saveAnnotations(context, annotations)
                                }, modifier = Modifier.size(36.dp)) {
                                    Icon(Icons.Rounded.BookmarkAdd, "Marcador", tint = currentTheme.text)
                                }
                                IconButton(onClick = { showNotebookSheet = true }, modifier = Modifier.size(36.dp)) {
                                    Icon(Icons.Rounded.Book, "Caderno de Notas", tint = currentTheme.text)
                                }
                                IconButton(onClick = { showTypeSheet = true }, modifier = Modifier.size(36.dp)) {
                                    Icon(Icons.Rounded.FormatSize, "Fontes e Alinhamento", tint = currentTheme.text)
                                }
                                IconButton(onClick = {
                                    currentTheme = when (currentTheme) {
                                        ReaderTheme.LIGHT -> ReaderTheme.SEPIA
                                        ReaderTheme.SEPIA -> ReaderTheme.DARK
                                        ReaderTheme.DARK -> ReaderTheme.LIGHT
                                    }
                                }, modifier = Modifier.size(36.dp)) {
                                    Icon(Icons.Rounded.Palette, "Tema", tint = currentTheme.text)
                                }
                                IconButton(onClick = { showSettingsSheet = true }, modifier = Modifier.size(36.dp)) {
                                    Icon(Icons.Rounded.Settings, "Definições", tint = currentTheme.text)
                                }
                                IconButton(onClick = {
                                    filePicker.launch(arrayOf("application/epub+zip", "application/pdf", "text/plain"))
                                }, modifier = Modifier.size(36.dp)) {
                                    Icon(Icons.Rounded.FolderOpen, "Abrir Livro", tint = currentTheme.text)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(18.dp))

                        if (isLoadingBook) {
                            Box(modifier = Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = Color(0xFF4F46E5))
                            }
                        } else {
                            val bookAnnotations = annotations.filter { it.bookTitle == currentBook.title && it.chapterIndex == currentChapterIndex && !it.isBookmark }

                            val annotatedText = buildAnnotatedString {
                                append(textContent)
                                bookAnnotations.forEach { ann ->
                                    if (ann.selectedText.isNotBlank()) {
                                        val idx = textContent.indexOf(ann.selectedText)
                                        if (idx >= 0) {
                                            val c = try { HighlightColor.valueOf(ann.colorName).toColor() } catch (_: Exception) { HighlightColor.YELLOW.toColor() }
                                            addStyle(
                                                SpanStyle(
                                                    background = c.copy(alpha = 0.55f),
                                                    textDecoration = if (ann.isUnderline) TextDecoration.Underline else TextDecoration.None
                                                ),
                                                idx,
                                                idx + ann.selectedText.length
                                            )
                                        }
                                    }
                                }
                                if (highlightStart >= 0 && highlightEnd <= textContent.length && highlightStart < highlightEnd) {
                                    addStyle(
                                        SpanStyle(
                                            background = currentTheme.highlightBg,
                                            color = currentTheme.highlightText,
                                            fontWeight = FontWeight.Bold
                                        ),
                                        highlightStart,
                                        highlightEnd
                                    )
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
                    }

                    Surface(
                        modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp).fillMaxWidth(),
                        shape = RoundedCornerShape(32.dp),
                        color = currentTheme.barBg,
                        shadowElevation = 8.dp
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
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
                                if (isPlaying) { stopAudio(); togglePlay() }
                            }) {
                                Text("${speechRate}x", fontWeight = FontWeight.Bold, color = currentTheme.text)
                            }

                            IconButton(onClick = {
                                draftSelection = clipboard.getText()?.text ?: ""
                                draftNote = ""
                                showNoteDialog = true
                            }) {
                                Icon(Icons.Rounded.EditNote, "Anotar ou Traduzir Seleção", tint = currentTheme.text)
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
                                Icon(Icons.Rounded.SkipPrevious, "Anterior", tint = currentTheme.text)
                            }

                            IconButton(
                                onClick = { togglePlay() },
                                modifier = Modifier.size(52.dp).clip(CircleShape).background(Color(0xFF4F46E5))
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
                                Icon(Icons.Rounded.SkipNext, "Seguinte", tint = currentTheme.text)
                            }

                            IconButton(onClick = { stopAudio() }) {
                                Icon(Icons.Rounded.Stop, "Parar", tint = currentTheme.text)
                            }
                        }
                    }
                }
            }
        }
    }
}
