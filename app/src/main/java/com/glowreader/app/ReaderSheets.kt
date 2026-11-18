package com.glowreader.app

import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TypographySheet(
    theme: ReaderTheme,
    fontSize: Float,
    onFontSizeChange: (Float) -> Unit,
    currentFont: ReaderFont,
    onFontChange: (ReaderFont) -> Unit,
    currentAlign: ReaderAlignment,
    onAlignChange: (ReaderAlignment) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = theme.barBg) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Aparência e Texto", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = theme.text)

            Text("Tamanho da Letra: ${fontSize.toInt()}sp", fontSize = 14.sp, color = theme.text.copy(alpha = 0.8f))
            Slider(
                value = fontSize,
                onValueChange = onFontSizeChange,
                valueRange = 14f..30f
            )

            Text("Tipo de Fonte", fontSize = 14.sp, color = theme.text.copy(alpha = 0.8f))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReaderFont.values().forEach { font ->
                    FilterChip(
                        selected = currentFont == font,
                        onClick = { onFontChange(font) },
                        label = { Text(font.label, fontFamily = font.family) }
                    )
                }
            }

            Text("Alinhamento", fontSize = 14.sp, color = theme.text.copy(alpha = 0.8f))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReaderAlignment.values().forEach { align ->
                    FilterChip(
                        selected = currentAlign == align,
                        onClick = { onAlignChange(align) },
                        label = { Text(align.label, fontSize = 12.sp) }
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotebookSheet(
    theme: ReaderTheme,
    currentBookTitle: String,
    annotations: List<StudyAnnotation>,
    onDeleteAnnotation: (String) -> Unit,
    onJumpToChapter: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var showAllBooks by remember { mutableStateOf(false) }
    val filtered = remember(annotations, showAllBooks, currentBookTitle) {
        if (showAllBooks) annotations else annotations.filter { it.bookTitle == currentBookTitle }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = theme.barBg) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Caderno de Notas e Marcadores", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = theme.text)
                FilterChip(
                    selected = showAllBooks,
                    onClick = { showAllBooks = !showAllBooks },
                    label = { Text(if (showAllBooks) "Todos os Livros" else "Este Livro", fontSize = 12.sp) }
                )
            }
            Spacer(modifier = Modifier.height(12.dp))

            if (filtered.isEmpty()) {
                Text(
                    "Ainda não tens notas ou marcadores guardados aqui. Seleciona texto ou toca no ícone de marcador no topo!",
                    color = theme.text.copy(alpha = 0.7f),
                    modifier = Modifier.padding(vertical = 24.dp)
                )
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(filtered) { item ->
                        val badgeColor = try {
                            HighlightColor.valueOf(item.colorName).toColor()
                        } catch (e: Exception) {
                            HighlightColor.YELLOW.toColor()
                        }
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = theme.bg,
                            modifier = Modifier.fillMaxWidth().clickable {
                                if (item.bookTitle == currentBookTitle) {
                                    onJumpToChapter(item.chapterIndex)
                                    onDismiss()
                                }
                            }
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(badgeColor))
                                        Text(
                                            text = if (item.isBookmark) "Marcador • ${item.chapterTitle}" else item.chapterTitle,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp,
                                            color = theme.text
                                        )
                                    }
                                    IconButton(onClick = { onDeleteAnnotation(item.id) }, modifier = Modifier.size(24.dp)) {
                                        Icon(Icons.Rounded.DeleteOutline, "Apagar", tint = theme.text.copy(alpha = 0.6f))
                                    }
                                }
                                if (item.selectedText.isNotBlank()) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text("\"${item.selectedText}\"", fontSize = 14.sp, color = theme.text.copy(alpha = 0.9f))
                                }
                                if (item.note.isNotBlank()) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text("Nota: ${item.note}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF4F46E5))
                                }
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    context: Context,
    theme: ReaderTheme,
    selectedLocale: Locale,
    onLocaleChange: (Locale) -> Unit,
    availableVoices: List<Voice>,
    selectedVoiceName: String,
    onVoiceSelect: (Voice) -> Unit,
    dictionaryProvider: String,
    onDictionaryChange: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val languages = listOf(
        "Português (PT)" to Locale("pt", "PT"),
        "Português (BR)" to Locale("pt", "BR"),
        "Inglês (US)" to Locale.US,
        "Espanhol" to Locale("es", "ES"),
        "Francês" to Locale.FRANCE
    )
    val dictionaries = listOf("Google Tradutor", "Priberam / Dicio", "Linguee / DeepL")

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = theme.barBg) {
        LazyColumn(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp).heightIn(max = 520.dp)) {
            item {
                Text("Definições do GlowReader", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = theme.text)
                Spacer(modifier = Modifier.height(16.dp))

                Text("1. Idioma da Leitura", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = theme.text)
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    languages.take(3).forEach { (label, loc) ->
                        FilterChip(
                            selected = selectedLocale.language == loc.language && selectedLocale.country == loc.country,
                            onClick = { onLocaleChange(loc) },
                            label = { Text(label, fontSize = 11.sp) }
                        )
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    languages.drop(3).forEach { (label, loc) ->
                        FilterChip(
                            selected = selectedLocale.language == loc.language,
                            onClick = { onLocaleChange(loc) },
                            label = { Text(label, fontSize = 11.sp) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Text("2. Vozes e Modelos Offline", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = theme.text)
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedButton(
                    onClick = {
                        val intent = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)
                        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        try { context.startActivity(intent) } catch (_: Exception) {}
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Rounded.Download, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Baixar Novas Vozes Offline (Android TTS)")
                }

                Text(
                    "Motores Neurais Avançados (Kokoro 8 idiomas, Supertonic, Pocket e Edge TTS) serão ativados na próxima atualização.",
                    fontSize = 12.sp,
                    color = theme.text.copy(alpha = 0.65f),
                    modifier = Modifier.padding(vertical = 6.dp)
                )
            }

            if (availableVoices.isNotEmpty()) {
                items(availableVoices.take(15)) { voice ->
                    val isSelected = voice.name == selectedVoiceName
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSelected) theme.highlightBg.copy(alpha = 0.35f) else Color.Transparent)
                            .clickable { onVoiceSelect(voice) }
                            .padding(vertical = 8.dp, horizontal = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${voice.locale.displayLanguage} (${voice.name.takeLast(14)})",
                            fontSize = 13.sp,
                            color = theme.text,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                        if (isSelected) Icon(Icons.Rounded.Check, null, tint = Color(0xFF4F46E5))
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(16.dp))
                Text("3. Dicionário Preferido", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = theme.text)
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    dictionaries.forEach { dict ->
                        FilterChip(
                            selected = dictionaryProvider == dict,
                            onClick = { onDictionaryChange(dict) },
                            label = { Text(dict, fontSize = 11.sp) }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(28.dp))
            }
        }
    }
}
