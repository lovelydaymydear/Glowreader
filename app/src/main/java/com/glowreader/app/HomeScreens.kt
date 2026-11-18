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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

@Composable
fun LibraryScreen(
    theme: ReaderTheme,
    books: List<SavedBookMeta>,
    isImporting: Boolean,
    onOpenBook: (SavedBookMeta) -> Unit,
    onDeleteBook: (String) -> Unit,
    onImportFileClick: () -> Unit,
    onImportWebUrl: (String) -> Unit
) {
    var showUrlDialog by remember { mutableStateOf(false) }
    var webUrlInput by remember { mutableStateOf("") }

    if (showUrlDialog) {
        AlertDialog(
            onDismissRequest = { showUrlDialog = false },
            title = { Text("Importar Artigo ou Capítulo Web") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Cola o link da página para extrair o texto limpo sem anúncios:", fontSize = 13.sp)
                    OutlinedTextField(
                        value = webUrlInput,
                        onValueChange = { webUrlInput = it },
                        placeholder = { Text("https://...") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (webUrlInput.isNotBlank()) {
                        onImportWebUrl(webUrlInput.trim())
                        webUrlInput = ""
                        showUrlDialog = false
                    }
                }) { Text("Extrair e Guardar") }
            },
            dismissButton = {
                TextButton(onClick = { showUrlDialog = false }) { Text("Cancelar") }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(theme.bg)
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Minha Biblioteca", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = theme.text)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalIconButton(onClick = { showUrlDialog = true }) {
                    Icon(Icons.Rounded.Language, "Colar Link Web")
                }
                Button(
                    onClick = onImportFileClick,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4F46E5))
                ) {
                    Icon(Icons.Rounded.Add, null, tint = Color.White)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Livro", color = Color.White)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (isImporting) {
            Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color(0xFF4F46E5))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("A processar e guardar livro...", color = theme.text.copy(alpha = 0.7f), fontSize = 13.sp)
                }
            }
        }

        if (books.isEmpty() && !isImporting) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                    Icon(Icons.Rounded.AutoStories, null, modifier = Modifier.size(64.dp), tint = theme.text.copy(alpha = 0.3f))
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("A tua estante está vazia", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = theme.text)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        "Toca em \"+ Livro\" para importar um EPUB, PDF ou TXT, ou no ícone do globo para colar um link da internet.",
                        color = theme.text.copy(alpha = 0.65f),
                        fontSize = 14.sp
                    )
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
                items(books) { book ->
                    val progressPct = if (book.totalChapters > 0) {
                        (((book.lastChapterIndex + 1).toFloat() / book.totalChapters) * 100).toInt()
                    } else 0

                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = theme.barBg,
                        shadowElevation = 2.dp,
                        modifier = Modifier.fillMaxWidth().clickable { onOpenBook(book) }
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Color(0xFF4F46E5).copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(book.format, fontWeight = FontWeight.Bold, fontSize = 11.sp, color = Color(0xFF4F46E5))
                                }
                                Spacer(modifier = Modifier.width(14.dp))
                                Column {
                                    Text(
                                        text = book.title,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 16.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = theme.text
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "Cap. ${book.lastChapterIndex + 1} de ${book.totalChapters} • $progressPct% lido",
                                        fontSize = 12.sp,
                                        color = theme.text.copy(alpha = 0.65f)
                                    )
                                }
                            }
                            IconButton(onClick = { onDeleteBook(book.id) }) {
                                Icon(Icons.Rounded.DeleteOutline, "Remover", tint = theme.text.copy(alpha = 0.5f))
                            }
                        }
                    }
                }
            }
        }
    }
}
@Composable
fun NotebookScreen(
    theme: ReaderTheme,
    annotations: List<StudyAnnotation>,
    onDeleteAnnotation: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(theme.bg)
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        Text("Caderno de Estudos", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = theme.text)
        Text("Todas as tuas notas, sublinhados e marcadores", fontSize = 13.sp, color = theme.text.copy(alpha = 0.65f))
        Spacer(modifier = Modifier.height(16.dp))

        if (annotations.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Ainda não guardaste nenhuma nota ou marcador.", color = theme.text.copy(alpha = 0.6f))
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(annotations) { item ->
                    val badgeColor = try {
                        HighlightColor.valueOf(item.colorName).toColor()
                    } catch (_: Exception) {
                        HighlightColor.YELLOW.toColor()
                    }
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = theme.barBg,
                        shadowElevation = 2.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Box(modifier = Modifier.size(14.dp).clip(CircleShape).background(badgeColor))
                                    Text(
                                        text = "${item.bookTitle} • ${item.chapterTitle}",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = theme.text
                                    )
                                }
                                IconButton(onClick = { onDeleteAnnotation(item.id) }, modifier = Modifier.size(24.dp)) {
                                    Icon(Icons.Rounded.DeleteOutline, "Apagar", tint = theme.text.copy(alpha = 0.5f))
                                }
                            }
                            if (item.selectedText.isNotBlank()) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text("\"${item.selectedText}\"", fontSize = 14.sp, color = theme.text.copy(alpha = 0.9f))
                            }
                            if (item.note.isNotBlank()) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text("Nota: ${item.note}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF4F46E5))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(
    context: Context,
    theme: ReaderTheme,
    onThemeChange: (ReaderTheme) -> Unit,
    selectedLocale: Locale,
    onLocaleChange: (Locale) -> Unit,
    availableVoices: List<Voice>,
    selectedVoiceName: String,
    onVoiceSelect: (Voice) -> Unit,
    dictionaryProvider: String,
    onDictionaryChange: (String) -> Unit
) {
    val languages = listOf(
        "Português (PT)" to Locale("pt", "PT"),
        "Português (BR)" to Locale("pt", "BR"),
        "Inglês (US)" to Locale.US,
        "Espanhol" to Locale("es", "ES"),
        "Francês" to Locale.FRANCE
    )
    val dictionaries = listOf("Google Tradutor", "Priberam / Dicio", "Linguee / DeepL")

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(theme.bg)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text("Definições", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = theme.text)
        }

        item {
            Surface(shape = RoundedCornerShape(16.dp), color = theme.barBg, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Tema Visual do App", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = theme.text)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ReaderTheme.values().forEach { t ->
                            val selected = theme == t
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .border(if (selected) 2.dp else 1.dp, if (selected) Color(0xFF4F46E5) else Color.Gray.copy(0.3f), RoundedCornerShape(12.dp))
                                    .clickable { onThemeChange(t) }
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Box(modifier = Modifier.size(18.dp).clip(CircleShape).background(t.bg).border(1.dp, Color.Gray, CircleShape))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(t.label, fontSize = 13.sp, color = theme.text)
                            }
                        }
                    }
                }
            }
        }

        item {
            Surface(shape = RoundedCornerShape(16.dp), color = theme.barBg, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Idioma e Vozes Offline", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = theme.text)
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

                    OutlinedButton(
                        onClick = {
                            val intent = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            try { context.startActivity(intent) } catch (_: Exception) {}
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Rounded.Download, null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Baixar Modelos de Voz Offline")
                    }
                }
            }
        }

        if (availableVoices.isNotEmpty()) {
            item {
                Text("Vozes Disponíveis no Aparelho", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = theme.text)
            }
            items(availableVoices.take(20)) { voice ->
                val isSelected = voice.name == selectedVoiceName
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (isSelected) theme.highlightBg.copy(alpha = 0.4f) else theme.barBg,
                    modifier = Modifier.fillMaxWidth().clickable { onVoiceSelect(voice) }
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("${voice.locale.displayLanguage} • ${voice.name.takeLast(16)}", fontSize = 13.sp, color = theme.text)
                        if (isSelected) Icon(Icons.Rounded.Check, null, tint = Color(0xFF4F46E5))
                    }
                }
            }
        }

        item {
            Surface(shape = RoundedCornerShape(16.dp), color = theme.barBg, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Dicionário e Tradutor Padrão", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = theme.text)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                      dictionaries.forEach { dict ->
                        FilterChip(
                          selected = dictionaryProvider == dict,
                          onClick = { onDictionaryChange(dict) },
                          label = { Text(dict, fontSize = 11.sp) }
                        )
}
}
}
}
}
}
}
