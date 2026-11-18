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

enum class ReaderTheme(val bg: Color, val text: Color, val hlBg: Color, val hlText: Color, val bar: Color) {
    LIGHT(Color(0xFFFAFAFA), Color(0xFF1E1E1E), Color(0xFFFFE082), Color(0xFF000000), Color(0xFFFFFFFF)),
    SEPIA(Color(0xFFF4ECD8), Color(0xFF3E3224), Color(0xFFE6C27A), Color(0xFF1F1810), Color(0xFFEBE0C5)),
    DARK(Color(0xFF121212), Color(0xFFE0E0E0), Color(0xFF3B4CCA), Color(0xFFFFFFFF), Color(0xFF1E1E1E))
}

class MainActivity : ComponentActivity() {
    private var tts: TextToSpeech? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val ctx = LocalContext.current
            var theme by remember { mutableStateOf(ReaderTheme.SEPIA) }
            var text by remember { mutableStateOf("Bem-vindo ao GlowReader! Toca na pasta para abrir um ficheiro .txt ou carrega em Play para ouvir com destaque palavra por palavra offline.") }
            var hlStart by remember { mutableIntStateOf(-1) }
            var hlEnd by remember { mutableIntStateOf(-1) }
            var playing by remember { mutableStateOf(false) }
            var rate by remember { mutableFloatStateOf(1.0f) }
            var ready by remember { mutableStateOf(false) }

            DisposableEffect(Unit) {
                val engine = TextToSpeech(ctx) { if (it == TextToSpeech.SUCCESS) { tts?.language = Locale("pt", "PT"); ready = true } }
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) { playing = true }
                    override fun onDone(id: String?) { playing = false; hlStart = -1; hlEnd = -1 }
                    @Deprecated("Deprecated") override fun onError(id: String?) { playing = false }
                    override fun onRangeStart(id: String?, s: Int, e: Int, f: Int) { hlStart = s; hlEnd = e }
                })
                tts = engine
                onDispose { engine.stop(); engine.shutdown() }
            }

            val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
                uri?.let {
                    tts?.stop(); playing = false; hlStart = -1; hlEnd = -1
                    ctx.contentResolver.openInputStream(it)?.use { s -> text = BufferedReader(InputStreamReader(s)).readText() }
                }
            }

            fun playPause() {
                if (!ready) return
                if (playing) { tts?.stop(); playing = false }
                else { tts?.setSpeechRate(rate); tts?.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), "glow") }
            }

            Surface(Modifier.fillMaxSize(), color = theme.bg) {
                Box(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp).verticalScroll(rememberScrollState()).padding(top = 24.dp, bottom = 120.dp)) {
                        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                            Text("GlowReader", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = theme.text.copy(alpha = 0.8f))
                            Row {
                                IconButton(onClick = { theme = ReaderTheme.entries[(theme.ordinal + 1) % 3] }) {
                                    Icon(Icons.Rounded.Palette, "Tema", tint = theme.text)
                                }
                                IconButton(onClick = { picker.launch("text/plain") }) {
                                    Icon(Icons.Rounded.FolderOpen, "Abrir", tint = theme.text)
                                }
                            }
                        }
                        Spacer(Modifier.height(24.dp))
                        val styled = buildAnnotatedString {
                            if (hlStart in 0 until hlEnd && hlEnd <= text.length) {
                                append(text.substring(0, hlStart))
                                withStyle(SpanStyle(background = theme.hlBg, color = theme.hlText, fontWeight = FontWeight.SemiBold)) {
                                    append(text.substring(hlStart, hlEnd))
                                }
                                append(text.substring(hlEnd))
                            } else append(text)
                        }
                        Text(styled, fontSize = 20.sp, lineHeight = 32.sp, fontFamily = FontFamily.Serif, color = theme.text)
                    }
                    Surface(Modifier.align(Alignment.BottomCenter).padding(20.dp).fillMaxWidth(), shape = RoundedCornerShape(32.dp), color = theme.bar, shadowElevation = 8.dp) {
                        Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                            TextButton(onClick = { rate = if (rate >= 2.0f) 1.0f else rate + 0.25f; if (playing) { tts?.stop(); playPause() } }) {
                                Text("${rate}x", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = theme.text)
                            }
                            IconButton(onClick = { playPause() }, Modifier.size(56.dp).clip(CircleShape).background(Color(0xFF4F46E5))) {
                                Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play", tint = Color.White, modifier = Modifier.size(32.dp))
                            }
                            IconButton(onClick = { tts?.stop(); playing = false; hlStart = -1; hlEnd = -1 }) {
                                Icon(Icons.Rounded.Stop, "Parar", tint = theme.text)
                            }
                        }
                    }
                }
            }
        }
    }
}
