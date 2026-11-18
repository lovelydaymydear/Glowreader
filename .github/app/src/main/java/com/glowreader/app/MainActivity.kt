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
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Locale

enum class ReaderTheme(val label: String, val bg: Color, val text: Color, val highlightBg: Color, val highlightText: Color, val barBg: Color) {
    LIGHT("Claro", Color(0xFFFAFAFA), Color(0xFF1E1E1E), Color(0xFFFFE082), Color(0xFF000000), Color(0xFFFFFFFF)),
    SEPIA("Sépia", Color(0xFFF4ECD8), Color(0xFF3E3224), Color(0xFFE6C27A), Color(0xFF1F1810), Color(0xFFEBE0C5)),
    DARK("Escuro", Color(0xFF121212), Color(0xFFE0E0E0), Color(0xFF3B4CCA), Color(0xFFFFFFFF), Color(0xFF1E1E1E))
}

class MainActivity : ComponentActivity() {
    private var tts: TextToSpeech? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val context = LocalContext.current
            var currentTheme by remember { mutableStateOf(ReaderTheme.SEPIA) }
            var textContent by remember {
                mutableStateOf(
                    "Bem-vindo ao GlowReader! Este é o teu novo leitor moderno com destaque palavra por palavra totalmente offline.\n\n" +
                    "Toca no ícone da pasta no canto superior direito para abrir qualquer ficheiro de texto (.txt) do teu telemóvel, ou carrega no botão Play abaixo para ouvir este texto agora mesmo.\n\n" +
                    "Nas próximas atualizações, vamos adicionar suporte a livros EPUB e PDF, gravação de áudio offline e motores neurais como Kokoro e Edge TTS."
                )
            }
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
                    override fun onStart(utteranceId: String?) {
                        isPlaying = true
                    }

                    override fun onDone(utteranceId: String?) {
                        isPlaying = false
                        highlightStart = -1
                        highlightEnd = -1
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        isPlaying = false
                    }

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

            val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
                uri?.let {
                    tts?.stop()
                    isPlaying = false
                    highlightStart = -1
                    highlightEnd = -1
                    context.contentResolver.openInputStream(it)?.use { stream ->
                        val reader = BufferedReader(InputStreamReader(stream))
                        textContent = reader.readText()
                    }
                }
            }

            fun togglePlay() {
                if (!ttsReady) return
                if (isPlaying) {
                    tts?.stop()
                    isPlaying = false
                } else {
                    tts?.setSpeechRate(speechRate)
                    val params = Bundle()
                    tts?.speak(textContent, TextToSpeech.QUEUE_FLUSH, params, "glow_utterance")
                }
            }

            Surface(
                modifier = Modifier.fillMaxSize(),
                color = currentTheme.bg
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 24.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(top = 24.dp, bottom = 120.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "GlowReader",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                color = currentTheme.text.copy(alpha = 0.8f)
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                IconButton(onClick = {
                                    currentTheme = when (currentTheme) {
                                        ReaderTheme.LIGHT -> ReaderTheme.SEPIA
                                        ReaderTheme.SEPIA -> ReaderTheme.DARK
                                        ReaderTheme.DARK -> ReaderTheme.LIGHT
                                    }
                                }) {
                                    Icon(
                                        imageVector = Icons.Rounded.Palette,
                                        contentDescription = "Mudar Tema",
                                        tint = currentTheme.text
                                    )
                                }
                                IconButton(onClick = { filePicker.launch("text/plain") }) {
                                    Icon(
                                        imageVector = Icons.Rounded.FolderOpen,
                                        contentDescription = "Abrir Ficheiro",
                                        tint = currentTheme.text
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(24.dp))

                        val annotatedText = buildAnnotatedString {
                            if (highlightStart >= 0 && highlightEnd <= textContent.length && highlightStart < highlightEnd) {
                                append(textContent.substring(0, highlightStart))
                                withStyle(
                                    style = SpanStyle(
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
                            fontSize = 20.sp,
                            lineHeight = 32.sp,
                            fontFamily = FontFamily.Serif,
                            color = currentTheme.text
                        )
                    }

                    // Barra de controlo flutuante estilo TomeVoice
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
                            modifier = Modifier
