package com.glowreader.app

import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
            val scope = rememberCoroutineScope()
            val scrollState = rememberScrollState()

            var currentTheme by remember { mutableStateOf(ReaderTheme.SEPIA) }
            var currentBook by remember {
                mutableStateOf(
                    ParsedBook(
                        title = "GlowReader 2.0",
                        chapters = listOf(
                            BookChapter(
                                title = "Boas-vindas",
                                content = "O GlowReader agora suporta livros reais em EPUB, PDF e TXT com índice fiel aos capítulos!\n\n" +
                                        "Toca no ícone da pasta no canto superior direito para escolheres um livro do teu telemóvel, ou toca no ícone de lista para abrir o índice."
                            )
                        )
                    )
                )
            }
            var currentChapterIndex by remember { mutableIntStateOf(0) }
            var showTocSheet by remember { mutableStateOf(false) }
            var isLoadingBook by remember { mutableStateOf(false) }

            val activeChapter = currentBook.chapters.getOrElse(currentChapterIndex) {
                BookChapter("Vazio", "")
            }
            val textContent = activeChapter.content

            var highlightStart by remember { mutableIntStateOf(-1) }
            var highlightEnd by remember { mutableIntStateOf(-1) }
            var isPlaying by remember { mutableStateOf(false) }
            var speechRate by remember { mutableFloatStateOf(1.0f) }
            var ttsReady by remember { mutableStateOf(false) } 
            DisposableEffect(Unit) {  
                val engine = TextToSpeech(context) { status ->
                    if (status == TextToSpeech.SUCCESS) {
                        tts?.language = Locale("pt", "PT")
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
                        highlightStart = start
                        highlightEnd = end
                    }
                })
                tts = engine
                onDispose {
                    engine.stop()
                    engine.shutdown()
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
                    tts?.setSpeechRate(speechRate)
                    val chunk = if (textContent.length > 3800) textContent.substring(0, 3800) else textContent
                    tts?.speak(chunk, TextToSpeech.QUEUE_FLUSH, Bundle(), "glow_utterance")
                }
            }

            val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
                uri?.let {
                    stopAudio()
                    isLoadingBook = true
                    scope.launch {
                        val parsed = withContext(Dispatchers.IO) {
                            BookParser.parseBook(context, it)
                        }
                        currentBook = parsed
                        currentChapterIndex = 0
                        scrollState.scrollTo(0)
                        isLoadingBook = false
                    }
                }
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
                                        .padding(vertical = 12.dp, horizontal = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
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
                            .padding(horizontal = 24.dp)
                            .verticalScroll(scrollState)
                            .padding(top = 20.dp, bottom = 130.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = currentBook.title,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = currentTheme.text
                                )
                                Text(
                                    text = activeChapter.title,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = currentTheme.text.copy(alpha = 0.65f)
                                )
                            }
                            Row {
                                IconButton(onClick = { showTocSheet = true }) {
                                    Icon(Icons.Rounded.FormatListBulleted, "Índice", tint = currentTheme.text)
                                }
                                IconButton(onClick = {
                                    currentTheme = when (currentTheme) {
                                        ReaderTheme.LIGHT -> ReaderTheme.SEPIA
                                        ReaderTheme.SEPIA -> ReaderTheme.DARK
                                        ReaderTheme.DARK -> ReaderTheme.LIGHT
                                    }
                                }) {
                                    Icon(Icons.Rounded.Palette, "Tema", tint = currentTheme.text)
                                }
                                IconButton(onClick = {
                                    filePicker.launch(arrayOf("application/epub+zip", "application/pdf", "text/plain"))
                                }) {
                                    Icon(Icons.Rounded.FolderOpen, "Abrir Livro", tint = currentTheme.text)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        if (isLoadingBook) {
                            Box(modifier = Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = Color(0xFF4F46E5))
                            }
                        } else {
                            val annotatedText = buildAnnotatedString {
                                if (highlightStart >= 0 && highlightEnd <= textContent.length && highlightStart < highlightEnd) {
                                    append(textContent.substring(0, highlightStart))
                                    withStyle(
                                        SpanStyle(
                                            background = currentTheme.highlightBg,
                                            color = currentTheme.highlightText,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    ) {
                                        append(textContent.substring(highlightStart, highlightEnd))
                                    }
                                    append(textContent.substring(highlightEnd))
                                } else {
                                    append(textContent)
                                }
                            }

                            Text(
                                text = annotatedText,
                                fontSize = 19.sp,
                                lineHeight = 31.sp,
                                fontFamily = FontFamily.Serif,
                                color = currentTheme.text
                            )
                        }
                    }

                                        Surface(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(20.dp)
                            .fillMaxWidth(),
                        shape = RoundedCornerShape(32.dp),
                        color = currentTheme.barBg,
                        shadowElevation = 8.dp
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
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
                                modifier = Modifier.size(54.dp).clip(CircleShape).background(Color(0xFF4F46E5))
                            ) {
                                Icon(
                                    imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                    contentDescription = "Play/Pause",
                                    tint = Color.White,
                                    modifier = Modifier.size(30.dp)
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
