package com.glowreader.app

import android.net.Uri
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import java.net.URLEncoder
import kotlin.math.roundToInt

data class HomeRecentBook(
    val id: String,
    val title: String,
    val author: String,
    val chapterTitle: String,
    val progressPercent: Float,
    val format: String = "EPUB"
)

@Composable
fun HomeScreen(
    theme: ReaderTheme,
    recentBook: HomeRecentBook?,
    totalBooksCount: Int,
    totalNotesCount: Int,
    onContinueReading: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenNotebook: () -> Unit,
    onOpenSettings: () -> Unit,
    onImportBook: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    var lookupQuery by remember { mutableStateOf("") }
    var activeLookupTitle by remember { mutableStateOf<String?>(null) }
    var activeLookupUrl by remember { mutableStateOf<String?>(null) }

    fun openInAppLookup(mode: String, rawQuery: String) {
        val cleaned = rawQuery.trim()
        val encoded = URLEncoder.encode(cleaned, "UTF-8")
        when (mode) {
            "dicionario" -> {
                val term = if (encoded.isBlank()) "leitura" else encoded
                activeLookupTitle = if (cleaned.isBlank()) "Dicionário" else "Dicionário: $cleaned"
                activeLookupUrl = "https://www.dicio.com.br/$term/"
            }
            "traduzir" -> {
                activeLookupTitle = if (cleaned.isBlank()) "Tradutor" else "Traduzir: $cleaned"
                activeLookupUrl = "https://translate.google.com/?sl=auto&tl=pt&text=$encoded"
            }
            else -> {
                val searchTerm = if (cleaned.isBlank()) "livros em português" else "$cleaned livro"
                val q = URLEncoder.encode(searchTerm, "UTF-8")
                activeLookupTitle = if (cleaned.isBlank()) "Pesquisa na App" else "Pesquisa: $cleaned"
                activeLookupUrl = "https://www.google.com/search?q=$q"
            }
        }
    }

    if (activeLookupUrl != null && activeLookupTitle != null) {
        InAppLookupDialog(
            title = activeLookupTitle!!,
            url = activeLookupUrl!!,
            theme = theme,
            onDismiss = {
                activeLookupUrl = null
                activeLookupTitle = null
            }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(theme.bg)
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Cabeçalho de boas-vindas
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "GlowReader",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = theme.text
            )
            Text(
                text = "Página inicial — continua a ler, organiza o teu estudo e consulta tudo sem sair da app.",
                fontSize = 14.sp,
                color = theme.text.copy(alpha = 0.75f)
            )
        }

        // Resumo rápido da biblioteca e caderno
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatSummaryCard(
                label = "Na biblioteca",
                value = totalBooksCount.toString(),
                icon = Icons.Rounded.LocalLibrary,
                theme = theme,
                modifier = Modifier.weight(1f),
                onClick = onOpenLibrary
            )
            StatSummaryCard(
                label = "Notas e marcas",
                value = totalNotesCount.toString(),
                icon = Icons.Rounded.AutoStories,
                theme = theme,
                modifier = Modifier.weight(1f),
                onClick = onOpenNotebook
            )
        }

        // Cartão de continuação de leitura
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = theme.barBg),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PlayCircleFilled,
                        contentDescription = null,
                        tint = Color(0xFF4F46E5)
                    )
                    Text(
                        text = "Continuar leitura",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = theme.text
                    )
                }

                if (recentBook != null) {
                    val pct = (recentBook.progressPercent.coerceIn(0f, 1f) * 100).roundToInt()
                    Text(
                        text = recentBook.title,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = theme.text
                    )
                    Text(
                        text = "${recentBook.author} • ${recentBook.chapterTitle} • ${recentBook.format}",
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = theme.text.copy(alpha = 0.7f)
                    )
                    LinearProgressIndicator(
                        progress = { recentBook.progressPercent.coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = Color(0xFF4F46E5),
                        trackColor = theme.text.copy(alpha = 0.12f)
                    )
                    Text(
                        text = "Progresso: $pct%",
                        fontSize = 12.sp,
                        color = theme.text.copy(alpha = 0.75f)
                    )
                    Button(
                        onClick = onContinueReading,
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4F46E5)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Rounded.MenuBook, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Continuar a ler")
                    }
                } else {
                    Text(
                        text = "Ainda não abriste nenhum livro recentemente. Importa um ficheiro ou escolhe um na biblioteca.",
                        fontSize = 14.sp,
                        color = theme.text.copy(alpha = 0.75f)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = onImportBook,
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4F46E5)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Rounded.FileOpen, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Importar")
                        }
                        OutlinedButton(
                            onClick = onOpenLibrary,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Biblioteca", color = theme.text)
                        }
                    }
                }
            }
        }

        // Atalhos rápidos principais
        Text(
            text = "Atalhos rápidos",
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            color = theme.text
        )

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ShortcutActionCard(
                    title = "Biblioteca",
                    subtitle = "Livros e artigos",
                    icon = Icons.Rounded.LocalLibrary,
                    theme = theme,
                    modifier = Modifier.weight(1f),
                    onClick = onOpenLibrary
                )
                ShortcutActionCard(
                    title = "Importar livro",
                    subtitle = "EPUB, PDF ou TXT",
                    icon = Icons.Rounded.UploadFile,
                    theme = theme,
                    modifier = Modifier.weight(1f),
                    onClick = onImportBook
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ShortcutActionCard(
                    title = "Caderno",
                    subtitle = "Destaques e notas",
                    icon = Icons.Rounded.EditNote,
                    theme = theme,
                    modifier = Modifier.weight(1f),
                    onClick = onOpenNotebook
                )
                ShortcutActionCard(
                    title = "Definições",
                    subtitle = "Leitura, voz e app",
                    icon = Icons.Rounded.Settings,
                    theme = theme,
                    modifier = Modifier.weight(1f),
                    onClick = onOpenSettings
                )
            }
        }

        // Consulta interna dentro do app (pesquisar, dicionário, traduzir)
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = theme.barBg),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Consulta na app (sem sair)",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = theme.text
                )
                Text(
                    text = "Escreve uma palavra, expressão ou tema e abre o dicionário, tradutor ou pesquisa numa janela interna.",
                    fontSize = 13.sp,
                    color = theme.text.copy(alpha = 0.75f)
                )

                OutlinedTextField(
                    value = lookupQuery,
                    onValueChange = { lookupQuery = it },
                    label = { Text("Palavra, frase ou tema") },
                    singleLine = true,
                    trailingIcon = {
                        if (lookupQuery.isNotEmpty()) {
                            IconButton(onClick = { lookupQuery = "" }) {
                                Icon(Icons.Rounded.Clear, contentDescription = "Limpar texto")
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalButton(
                        onClick = { openInAppLookup("dicionario", lookupQuery) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Dicionário", maxLines = 1, softWrap = false)
                    }
                    FilledTonalButton(
                        onClick = { openInAppLookup("traduzir", lookupQuery) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Traduzir", maxLines = 1, softWrap = false)
                    }
                    FilledTonalButton(
                        onClick = { openInAppLookup("pesquisar", lookupQuery) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Pesquisar", maxLines = 1, softWrap = false)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun StatSummaryCard(
    label: String,
    value: String,
    icon: ImageVector,
    theme: ReaderTheme,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = theme.barBg),
        modifier = modifier.clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(icon, contentDescription = null, tint = theme.text)
            Column {
                Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = theme.text)
                Text(label, fontSize = 12.sp, color = theme.text.copy(alpha = 0.7f))
            }
        }
    }
}

@Composable
private fun ShortcutActionCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    theme: ReaderTheme,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = theme.barBg),
        modifier = modifier.clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(icon, contentDescription = null, tint = Color(0xFF4F46E5))
            Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = theme.text)
            Text(subtitle, fontSize = 12.sp, color = theme.text.copy(alpha = 0.7f))
        }
    }
}

@Composable
fun InAppLookupDialog(
    title: String,
    url: String,
    theme: ReaderTheme,
    onDismiss: () -> Unit
) {
    val safeUrl = remember(url) {
        val parsed = Uri.parse(url)
        if (parsed.scheme == "https" || parsed.scheme == "http") url else "https://www.google.com"
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.86f),
            shape = RoundedCornerShape(22.dp),
            color = theme.barBg
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = title,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = theme.text,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Fechar janela interna",
                            tint = theme.text
                        )
                    }
                }
                HorizontalDivider(color = theme.text.copy(alpha = 0.12f))
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            webViewClient = WebViewClient()
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            loadUrl(safeUrl)
                        }
                    },
                    update = { webView ->
                        if (webView.url != safeUrl) {
                            webView.loadUrl(safeUrl)
                        }
                    }
                )
            }
        }
    }
}
