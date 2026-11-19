package com.glowreader.app

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.speech.tts.Voice
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.text.AnnotatedString
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

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val context = LocalContext.current
            val activity = context as? Activity
            val clipboard = LocalClipboardManager.current
            val scope = rememberCoroutineScope()

            val voiceEngine = remember(context) { VoiceEngineManager(context) }

            // 0 = Início, 1 = Biblioteca, 2 = Caderno, 3 = Definições
            var currentTab by remember { mutableIntStateOf(0) }
            var currentTheme by remember { mutableStateOf(ReaderTheme.SEPIA) }
            var fontSize by remember { mutableFloatStateOf(19f) }
            var currentFont by remember { mutableStateOf(ReaderFont.SERIF) }
            var currentAlign by remember { mutableStateOf(ReaderAlignment.JUSTIFY) }

            // Modos de leitura, ecrã inteiro e comportamento das barras
            var readingViewMode by remember { mutableStateOf(ReadingViewMode.SCROLL) }
            var barsBehaviorMode by remember { mutableStateOf(BarsBehaviorMode.ALWAYS_VISIBLE) }
            var isFullscreenMode by remember { mutableStateOf(false) }

            // Reflow ao abrir o livro e cores personalizadas / mistura de cores
            var defaultReflowMode by remember {
                mutableStateOf(ReadingEnhancementManager.loadDefaultReflowMode(context))
            }
            var activeReflowMode by remember { mutableStateOf(defaultReflowMode) }
            var pendingOpenBookMeta by remember { mutableStateOf<SavedBookMeta?>(null) }

            var customColors by remember {
                mutableStateOf(ReadingEnhancementManager.loadCustomColors(context))
            }
            var selectedColorKey by remember { mutableStateOf("preset_yellow") }
            var showColorMixerDialog by remember { mutableStateOf(false) }
            var mixColorA by remember { mutableStateOf(Color(0xFFFFEB3B)) }
            var mixColorB by remember { mutableStateOf(Color(0xFF81D4FA)) }
            var mixRatio by remember { mutableFloatStateOf(0.5f) }
            var rgbRed by remember { mutableFloatStateOf(255f) }
            var rgbGreen by remember { mutableFloatStateOf(224f) }
            var rgbBlue by remember { mutableFloatStateOf(130f) }
            var newCustomColorName by remember { mutableStateOf("") }

            // Tradução de capítulo ou livro inteiro e ferramentas internas (IA / Google / Dicionário)
            var showTranslateSheet by remember { mutableStateOf(false) }
            var translatedChaptersMap by remember { mutableStateOf>(emptyMap()) }
            var useTranslatedText by remember { mutableStateOf(false) }
            var isTranslating by remember { mutableStateOf(false) }
            var translationProgressText by remember { mutableStateOf<String?>(null) }
            var customAiUrl by remember { mutableStateOf(ReadingEnhancementManager.loadCustomAiUrl(context)) }
            var showResearchHubDialog by remember { mutableStateOf(false) }
            var researchQueryInput by remember { mutableStateOf("") }

            var savedBooks by remember { mutableStateOf(LibraryManager.loadLibrary(context)) }
            var orgMetaMap by remember {
                mutableStateOf(LibraryOrganizationManager.ensureBooksRegistered(context, savedBooks))
            }
            var categories by remember { mutableStateOf(LibraryOrganizationManager.loadAllCategories(context)) }
            var selectedCategory by remember { mutableStateOf("Todos") }
            var showSecretShelf by remember { mutableStateOf(false) }
            var librarySearchQuery by remember { mutableStateOf("") }
            var formatFilter by remember { mutableStateOf("Todos") }
            var sortOption by remember { mutableStateOf(LibrarySortOption.RECENT_ORDER) }

            // Diálogos de organização da biblioteca
            var showAddCategoryDialog by remember { mutableStateOf(false) }
            var newCategoryName by remember { mutableStateOf("") }
            var categoryTargetBook by remember { mutableStateOf<SavedBookMeta?>(null) }

            var showSecretPinDialog by remember { mutableStateOf(false) }
            var secretPinInput by remember { mutableStateOf("") }
            var secretPinError by remember { mutableStateOf<String?>(null) }
            var pendingSecretBookToggle by remember { mutableStateOf<SavedBookMeta?>(null) }

            var showDuplicatesSheet by remember { mutableStateOf(false) }

            var activeBookMeta by remember { mutableStateOf<SavedBookMeta?>(null) }
            var activeBookContent by remember { mutableStateOf<ParsedBook?>(null) }
            var currentChapterIndex by remember { mutableIntStateOf(0) }
            var isImporting by remember { mutableStateOf(false) }

            // Importação de página/artigo da web por URL
            var showWebImportDialog by remember { mutableStateOf(false) }
            var webUrlInput by remember { mutableStateOf("") }
            var webImportError by remember { mutableStateOf<String?>(null) }
            var webImportStatus by remember { mutableStateOf<String?>(null) }

            // Caderno, dicionário/tradutor, histórico e exportação
            var annotations by remember { mutableStateOf(ReaderStorage.loadAnnotations(context)) }
            var dictionaryProvider by remember { mutableStateOf("Google Tradutor") }
            var translationTargetLang by remember { mutableStateOf("pt") }
            var lookupQueryInput by remember { mutableStateOf("") }
            var lookupHistory by remember { mutableStateOf(StudyNotebookManager.loadLookupHistory(context)) }
            var exportStatusMessage by remember { mutableStateOf<String?>(null) }

            var internalLookupTitle by remember { mutableStateOf<String?>(null) }
            var internalLookupUrl by remember { mutableStateOf<String?>(null) }

            // Estados do motor de voz, destaque por palavra e áudio sincronizado
            var selectedProvider by remember { mutableStateOf(VoiceProviderType.ANDROID_SYSTEM) }
            var selectedLocale by remember { mutableStateOf(Locale("pt", "PT")) }
            var availableVoices by remember { mutableStateOf<List<Voice>>(emptyList()) }
            var selectedVoiceName by remember { mutableStateOf("") }
            var wordHighlightEnabled by remember { mutableStateOf(true) }
            var customOnlineEndpoint by remember { mutableStateOf("") }
            var onlineTestResult by remember { mutableStateOf<String?>(null) }
            var piperModelsCount by remember { mutableIntStateOf(0) }

            var isPlaying by remember { mutableStateOf(false) }
            var speechRate by remember { mutableFloatStateOf(1.0f) }
            var activeSentenceRange by remember { mutableStateOf<IntRange?>(null) }
            var activeWordRange by remember { mutableStateOf<IntRange?>(null) }

            var savedAudioTracks by remember { mutableStateOf(voiceEngine.listSavedAudioTracks()) }
            var audioStatusMessage by remember { mutableStateOf<String?>(null) }
            var isExportingAudio by remember { mutableStateOf(false) }
            var showSavedAudioSheet by remember { mutableStateOf(false) }

            fun refreshLibraryAndOrg() {
                val loaded = LibraryManager.loadLibrary(context)
                savedBooks = loaded
                orgMetaMap = LibraryOrganizationManager.ensureBooksRegistered(context, loaded)
                categories = LibraryOrganizationManager.loadAllCategories(context)
            }

            LaunchedEffect(Unit) {
                piperModelsCount = voiceEngine.checkLocalPiperModels().size
                voiceEngine.initializeAndroidTts { voices ->
                    availableVoices = voices
                }
            }

            DisposableEffect(voiceEngine) {
                onDispose {
                    ReaderLayoutController.applyFullscreenSystemBars(activity, false)
                    voiceEngine.release()
                }
            }

            fun syncVoiceEngineConfig() {
                voiceEngine.currentProvider = selectedProvider
                voiceEngine.selectedLocale = selectedLocale
                voiceEngine.selectedVoiceName = selectedVoiceName
                voiceEngine.speechRate = speechRate
                voiceEngine.wordHighlightEnabled = wordHighlightEnabled
                voiceEngine.customOnlineEndpointUrl = customOnlineEndpoint
                voiceEngine.applyVoiceAndParams()
            }

            fun stopAudio() {
                voiceEngine.stopPlayback()
                isPlaying = false
                activeSentenceRange = null
                activeWordRange = null
            }

            fun startAudio(text: String, onFinished: () -> Unit = {}) {
                if (text.isBlank()) return
                syncVoiceEngineConfig()
                voiceEngine.startReadingText(
                    text = text,
                    onSentenceRange = { range -> activeSentenceRange = range },
                    onWordRange = { wRange -> activeWordRange = wRange },
                    onPlaybackState = { playing -> isPlaying = playing },
                    onChapterFinished = onFinished
                )
            }

            fun openResearchTarget(target: ResearchToolTarget, query: String) {
                val clean = query.trim()
                if (clean.isNotEmpty()) {
                    lookupHistory = StudyNotebookManager.recordLookup(context, clean, target.label)
                }
                val (title, url) = ReadingEnhancementManager.buildResearchUrl(
                    context = context,
                    target = target,
                    query = clean,
                    targetLang = translationTargetLang
                )
                internalLookupTitle = title
                internalLookupUrl = url
            }

            fun openDictionaryOrTranslate(query: String) {
                val clean = query.trim()
                if (clean.isNotEmpty()) {
                    lookupHistory = StudyNotebookManager.recordLookup(context, clean, dictionaryProvider)
                }
                val (title, url) = StudyNotebookManager.buildLookupUrl(
                    query = clean,
                    provider = dictionaryProvider,
                    targetLang = translationTargetLang
                )
                internalLookupTitle = title
                internalLookupUrl = url
            }

            fun launchBookWithReflow(meta: SavedBookMeta, chosenReflow: ReflowMode) {
                val loaded = LibraryManager.loadBookContent(context, meta.id)
                if (loaded != null) {
                    activeReflowMode = chosenReflow
                    translatedChaptersMap = emptyMap()
                    useTranslatedText = false
                    translationProgressText = null
                    activeBookMeta = meta
                    activeBookContent = loaded
                    currentChapterIndex = meta.lastChapterIndex.coerceIn(0, (loaded.chapters.size - 1).coerceAtLeast(0))
                }
            }

            fun openBookMeta(meta: SavedBookMeta) {
                pendingOpenBookMeta = meta
            }

            fun requestSecretShelfAccess(bookToToggle: SavedBookMeta? = null) {
                if (!LibraryOrganizationManager.hasSecretPin(context)) {
                    pendingSecretBookToggle = bookToToggle
                    secretPinInput = ""
                    secretPinError = null
                    showSecretPinDialog = true
                    return
                }
                if (LibraryOrganizationManager.isSecretUnlocked(context)) {
                    if (bookToToggle != null) {
                        orgMetaMap = LibraryOrganizationManager.toggleBookSecret(context, bookToToggle.id)
                    } else {
                        showSecretShelf = !showSecretShelf
                    }
                } else {
                    pendingSecretBookToggle = bookToToggle
                    secretPinInput = ""
                    secretPinError = null
                    showSecretPinDialog = true
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
                        refreshLibraryAndOrg()
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
                            refreshLibraryAndOrg()
                        }
                        isImporting = false
                    }
                }
            }

            val bookToPromptReflow = pendingOpenBookMeta
            if (bookToPromptReflow != null) {
                val isPdf = bookToPromptReflow.format.equals("PDF", ignoreCase = true)
                AlertDialog(
                    onDismissRequest = { pendingOpenBookMeta = null },
                    title = { Text("Abrir: ${bookToPromptReflow.title}") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                text = if (isPdf) {
                                    "Este livro é um PDF. Queres adaptar o texto ao ecrã (Reflow) para leitura fluida, voz e marcação?"
                                } else {
                                    "Escolhe como queres apresentar este livro ao abrir:"
                                },
                                fontSize = 13.sp
                            )
                            ReflowMode.entries.forEach { mode ->
                                val isRecommended = isPdf && mode == ReflowMode.AUTO_ADAPT
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            val target = bookToPromptReflow
                                            pendingOpenBookMeta = null
                                            launchBookWithReflow(target, mode)
                                        }
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Text(
                                            text = if (isRecommended) "${mode.label} (Recomendado PDF)" else mode.label,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp
                                        )
                                        Text(mode.description, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = {
                        Button(onClick = {
                            val target = bookToPromptReflow
                            pendingOpenBookMeta = null
                            launchBookWithReflow(target, defaultReflowMode)
                        }) {
                            Text("Abrir padrão")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { pendingOpenBookMeta = null }) { Text("Cancelar") }
                    }
                )
            }

            if (showColorMixerDialog) {
                val mixedPreview = ReadingEnhancementManager.mixColors(mixColorA, mixColorB, mixRatio)
                val rgbPreview = ReadingEnhancementManager.buildColorFromRgb(rgbRed.toInt(), rgbGreen.toInt(), rgbBlue.toInt())
                val activePreview = if (newCustomColorName.contains("RGB", ignoreCase = true)) rgbPreview else mixedPreview

                AlertDialog(
                    onDismissRequest = { showColorMixerDialog = false },
                    title = { Text("Criar ou Misturar Cor de Destaque") },
                    text = {
                        Column(
                            modifier = Modifier.verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedTextField(
                                value = newCustomColorName,
                                onValueChange = { newCustomColorName = it },
                                label = { Text("Nome da cor (ex.: Destaque Estudo)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            Text("Mistura de duas cores:", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                listOf(
                                    Color(0xFFFFEB3B),
                                    Color(0xFF81D4FA),
                                    Color(0xFFF48FB1),
                                    Color(0xFFA5D6A7),
                                    Color(0xFFFFCC80)
                                ).forEach { c ->
                                    Box(
                                        modifier = Modifier
                                            .size(28.dp)
                                            .clip(CircleShape)
                                            .background(c)
                                            .border(2.dp, if (mixColorA == c) Color.Black else Color.Transparent, CircleShape)
                                            .clickable { mixColorA = c }
                                    )
                                }
                                Text("+", fontWeight = FontWeight.Bold)
                                listOf(
                                    Color(0xFF81D4FA),
                                    Color(0xFFCE93D8),
                                    Color(0xFFFF8A80),
                                    Color(0xFF80CBC4)
                                ).forEach { c ->
                                    Box(
                                        modifier = Modifier
                                            .size(28.dp)
                                            .clip(CircleShape)
                                            .background(c)
                                            .border(2.dp, if (mixColorB == c) Color.Black else Color.Transparent, CircleShape)
                                            .clickable { mixColorB = c }
                                    )
                                }
                            }
                            Text("Proporção da mistura: ${(mixRatio * 100).toInt()}%", fontSize = 11.sp)
                            Slider(value = mixRatio, onValueChange = { mixRatio = it })

                            Text("Ajuste fino RGB:", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Text("Vermelho: ${rgbRed.toInt()}", fontSize = 11.sp)
                            Slider(value = rgbRed, onValueChange = { rgbRed = it }, valueRange = 0f..255f)
                            Text("Verde: ${rgbGreen.toInt()}", fontSize = 11.sp)
                            Slider(value = rgbGreen, onValueChange = { rgbGreen = it }, valueRange = 0f..255f)
                            Text("Azul: ${rgbBlue.toInt()}", fontSize = 11.sp)
                            Slider(value = rgbBlue, onValueChange = { rgbBlue = it }, valueRange = 0f..255f)

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(mixedPreview)
                                        .border(1.dp, Color.Gray, RoundedCornerShape(8.dp))
                                )
                                Text("Mistura", fontSize = 12.sp)
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(rgbPreview)
                                        .border(1.dp, Color.Gray, RoundedCornerShape(8.dp))
                                )
                                Text("RGB", fontSize = 12.sp)
                            }
                        }
                    },
                    confirmButton = {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = {
                                val savedList = ReadingEnhancementManager.saveCustomColor(
                                    context,
                                    newCustomColorName.ifBlank { "RGB Personalizado" },
                                    rgbPreview
                                )
                                customColors = savedList
                                savedList.firstOrNull()?.let { selectedColorKey = it.id }
                                newCustomColorName = ""
                                showColorMixerDialog = false
                            }) {
                                Text("Guardar RGB")
                            }
                            Button(onClick = {
                                val savedList = ReadingEnhancementManager.saveCustomColor(
                                    context,
                                    newCustomColorName.ifBlank { "Mistura Guardada" },
                                    mixedPreview
                                )
                                customColors = savedList
                                savedList.firstOrNull()?.let { selectedColorKey = it.id }
                                newCustomColorName = ""
                                showColorMixerDialog = false
                            }) {
                                Text("Guardar Mistura")
                            }
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showColorMixerDialog = false }) { Text("Cancelar") }
                    }
                )
            }

            if (showResearchHubDialog) {
                AlertDialog(
                    onDismissRequest = { showResearchHubDialog = false },
                    title = { Text("Pesquisa, IA, Dicionário e Tradutor") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                text = "Abre qualquer ferramenta diretamente dentro do GlowReader sem sair da leitura:",
                                fontSize = 12.sp
                            )
                            OutlinedTextField(
                                value = researchQueryInput,
                                onValueChange = { researchQueryInput = it },
                                label = { Text("Termo, conceito ou pergunta") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            ResearchToolTarget.entries.forEach { target ->
                                OutlinedButton(
                                    onClick = {
                                        showResearchHubDialog = false
                                        openResearchTarget(target, researchQueryInput)
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(target.label)
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showResearchHubDialog = false }) { Text("Fechar") }
                    }
                )
            }

            if (showAddCategoryDialog) {
                AlertDialog(
                    onDismissRequest = { showAddCategoryDialog = false },
                    title = { Text("Nova categoria") },
                    text = {
                        OutlinedTextField(
                            value = newCategoryName,
                            onValueChange = { newCategoryName = it },
                            label = { Text("Ex.: Psicologia, Ficção, Estudos") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    },
                    confirmButton = {
                        Button(onClick = {
                            if (newCategoryName.isNotBlank()) {
                                categories = LibraryOrganizationManager.addCustomCategory(context, newCategoryName)
                                selectedCategory = newCategoryName.trim()
                                newCategoryName = ""
                            }
                            showAddCategoryDialog = false
                        }) {
                            Text("Criar")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showAddCategoryDialog = false }) { Text("Cancelar") }
                    }
                )
            }

            val bookForCat = categoryTargetBook
            if (bookForCat != null) {
                val currentBookCat = orgMetaMap[bookForCat.id]?.category ?: "A ler"
                AlertDialog(
                    onDismissRequest = { categoryTargetBook = null },
                    title = { Text("Categoria — ${bookForCat.title}") },
                    text = {
                        Column {
                            categories.forEach { cat ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            orgMetaMap = LibraryOrganizationManager.setBookCategory(context, bookForCat.id, cat)
                                            categoryTargetBook = null
                                        }
                                        .padding(vertical = 4.dp)
                                ) {
                                    RadioButton(
                                        selected = currentBookCat == cat,
                                        onClick = {
                                            orgMetaMap = LibraryOrganizationManager.setBookCategory(context, bookForCat.id, cat)
                                            categoryTargetBook = null
                                        }
                                    )
                                    Text(cat)
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { categoryTargetBook = null }) { Text("Fechar") }
                    }
                )
            }

            if (showSecretPinDialog) {
                val pinAlreadySet = LibraryOrganizationManager.hasSecretPin(context)
                AlertDialog(
                    onDismissRequest = {
                        showSecretPinDialog = false
                        pendingSecretBookToggle = null
                    },
                    title = {
                        Text(if (pinAlreadySet) "Desbloquear estante secreta" else "Definir PIN da estante secreta")
                    },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = if (pinAlreadySet) {
                                    "Introduz o teu PIN para aceder ou mover itens da estante secreta."
                                } else {
                                    "Cria um PIN de pelo menos 4 dígitos para proteger os teus livros privados."
                                },
                                fontSize = 13.sp
                            )
                            OutlinedTextField(
                                value = secretPinInput,
                                onValueChange = {
                                    secretPinInput = it
                                    secretPinError = null
                                },
                                label = { Text("PIN (mín. 4 dígitos)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            if (secretPinError != null) {
                                Text(secretPinError!!, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                            }
                        }
                    },
                    confirmButton = {
                        Button(onClick = {
                            if (pinAlreadySet) {
                                if (LibraryOrganizationManager.verifySecretPin(context, secretPinInput)) {
                                    val toggleTarget = pendingSecretBookToggle
                                    if (toggleTarget != null) {
                                        orgMetaMap = LibraryOrganizationManager.toggleBookSecret(context, toggleTarget.id)
                                    } else {
                                        showSecretShelf = true
                                    }
                                    pendingSecretBookToggle = null
                                    showSecretPinDialog = false
                                } else {
                                    secretPinError = "PIN incorreto."
                                }
                            } else {
                                if (LibraryOrganizationManager.setSecretPin(context, secretPinInput)) {
                                    val toggleTarget = pendingSecretBookToggle
                                    if (toggleTarget != null) {
                                        orgMetaMap = LibraryOrganizationManager.toggleBookSecret(context, toggleTarget.id)
                                    } else {
                                        showSecretShelf = true
                                    }
                                    pendingSecretBookToggle = null
                                    showSecretPinDialog = false
                                } else {
                                    secretPinError = "Usa pelo menos 4 caracteres."
                                }
                            }
                        }) {
                            Text("Confirmar")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            showSecretPinDialog = false
                            pendingSecretBookToggle = null
                        }) { Text("Cancelar") }
                    }
                )
            }

            if (showDuplicatesSheet) {
                val duplicateGroups = LibraryOrganizationManager.findDuplicateGroups(savedBooks)
                ModalBottomSheet(
                    onDismissRequest = { showDuplicatesSheet = false },
                    containerColor = currentTheme.barBg
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Livros Duplicados (${duplicateGroups.size} grupos)",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = currentTheme.text
                        )
                        if (duplicateGroups.isEmpty()) {
                            Text(
                                text = "Não foram encontrados títulos duplicados na biblioteca.",
                                fontSize = 13.sp,
                                color = currentTheme.text.copy(alpha = 0.75f),
                                modifier = Modifier.padding(vertical = 12.dp)
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier.heightIn(max = 360.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                items(duplicateGroups, key = { it.normalizedKey }) { group ->
                                    Card(
                                        colors = CardDefaults.cardColors(containerColor = currentTheme.bg),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Text(
                                                text = group.books.first().title,
                                                fontWeight = FontWeight.Bold,
                                                color = currentTheme.text
                                            )
                                            group.books.forEach { dup ->
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(
                                                        text = "${dup.format} • Cap. ${dup.lastChapterIndex + 1}",
                                                        fontSize = 12.sp,
                                                        color = currentTheme.text.copy(alpha = 0.75f)
                                                    )
                                                    TextButton(onClick = {
                                                        LibraryManager.deleteBook(context, dup.id)
                                                        refreshLibraryAndOrg()
                                                    }) {
                                                        Text("Remover esta cópia", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(20.dp))
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

            LaunchedEffect(currentBook, isFullscreenMode) {
                ReaderLayoutController.applyFullscreenSystemBars(
                    activity = activity,
                    fullscreen = currentBook != null && isFullscreenMode
                )
            }

            if (currentBook != null && currentMeta != null) {
                val scrollState = rememberScrollState(initial = currentMeta.lastScrollPosition)
                val activeChapter = currentBook.chapters.getOrElse(currentChapterIndex) { BookChapter("Vazio", "") }
                val baseChapterText = remember(activeChapter.content, activeReflowMode) {
                    ReadingEnhancementManager.applyReflow(activeChapter.content, activeReflowMode)
                }
                val textContent = if (useTranslatedText && translatedChaptersMap.containsKey(currentChapterIndex)) {
                    translatedChaptersMap[currentChapterIndex] ?: baseChapterText
                } else {
                    baseChapterText
                }

                val pages = remember(textContent, fontSize) {
                    val dynamicChars = (2200f * (19f / fontSize.coerceIn(12f, 36f))).toInt()
                    ReaderLayoutController.paginateChapter(textContent, dynamicChars)
                }
                var currentPageIndex by remember(currentChapterIndex, pages.size) { mutableIntStateOf(0) }

                var barsVisible by remember(currentChapterIndex, barsBehaviorMode) {
                    mutableStateOf(barsBehaviorMode != BarsBehaviorMode.TAP_TO_TOGGLE)
                }

                LaunchedEffect(scrollState.isScrollInProgress, barsBehaviorMode) {
                    if (scrollState.isScrollInProgress && barsBehaviorMode == BarsBehaviorMode.HIDE_ON_ADVANCE) {
                        barsVisible = false
                    }
                }

                var showTocSheet by remember { mutableStateOf(false) }
                var showTypeSheet by remember { mutableStateOf(false) }
                var showNotebookSheet by remember { mutableStateOf(false) }
                var showNoteDialog by remember { mutableStateOf(false) }
                var draftSelection by remember { mutableStateOf("") }
                var draftNote by remember { mutableStateOf("") }
                var draftUnderline by remember { mutableStateOf(false) }

                DisposableEffect(currentChapterIndex) {
                    onDispose {
                        LibraryManager.updateReadingProgress(context, currentMeta.id, currentChapterIndex, scrollState.value)
                        refreshLibraryAndOrg()
                    }
                }

                val highlightAnchor = activeWordRange?.first ?: activeSentenceRange?.first
                LaunchedEffect(highlightAnchor) {
                    val pos = highlightAnchor
                    if (pos != null && textContent.isNotEmpty()) {
                        if (readingViewMode == ReadingViewMode.SCROLL && scrollState.maxValue > 0) {
                            val ratio = pos.toFloat() / textContent.length.toFloat()
                            val targetScroll = (ratio * scrollState.maxValue).toInt()
                            scrollState.animateScrollTo(targetScroll)
                        } else if (readingViewMode == ReadingViewMode.PAGED && pages.size > 1) {
                            val pageIdx = ((pos.toFloat() / textContent.length.toFloat()) * pages.size)
                                .toInt()
                                .coerceIn(0, pages.lastIndex)
                            currentPageIndex = pageIdx
                        }
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

                if (showTranslateSheet) {
                    ModalBottomSheet(
                        onDismissRequest = { if (!isTranslating) showTranslateSheet = false },
                        containerColor = currentTheme.barBg
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = "Traduzir Capítulo ou Livro Inteiro",
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                color = currentTheme.text
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("pt", "en", "es", "fr").forEach { lang ->
                                    FilterChip(
                                        selected = translationTargetLang == lang,
                                        onClick = { translationTargetLang = lang },
                                        label = { Text("→ ${lang.uppercase()}") }
                                    )
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = {
                                        isTranslating = true
                                        translationProgressText = "A traduzir capítulo atual..."
                                        scope.launch {
                                            val res = ReadingEnhancementManager.translateTextBlock(
                                                text = baseChapterText,
                                                targetLang = translationTargetLang
                                            )
                                            isTranslating = false
                                            if (res.isSuccess) {
                                                translatedChaptersMap = translatedChaptersMap + (currentChapterIndex to (res.getOrNull() ?: ""))
                                                useTranslatedText = true
                                                translationProgressText = "Capítulo traduzido!"
                                            } else {
                                                translationProgressText = res.exceptionOrNull()?.message ?: "Erro na tradução."
                                            }
                                        }
                                    },
                                    enabled = !isTranslating
                                ) {
                                    Text("Traduzir capítulo")
                                }
                                OutlinedButton(
                                    onClick = {
                                        isTranslating = true
                                        scope.launch {
                                            val updatedMap = translatedChaptersMap.toMutableMap()
                                            currentBook.chapters.forEachIndexed { idx, ch ->
                                                translationProgressText = "A traduzir capítulo ${idx + 1} de ${currentBook.chapters.size}..."
                                                val reflowed = ReadingEnhancementManager.applyReflow(ch.content, activeReflowMode)
                                                val res = ReadingEnhancementManager.translateTextBlock(
                                                    text = reflowed,
                                                    targetLang = translationTargetLang
                                                )
                                                if (res.isSuccess) {
                                                    updatedMap[idx] = res.getOrNull() ?: reflowed
                                                    translatedChaptersMap = updatedMap.toMap()
                                                }
                                            }
                                            useTranslatedText = true
                                            isTranslating = false
                                            translationProgressText = "Tradução do livro concluída!"
                                        }
                                    },
                                    enabled = !isTranslating
                                ) {
                                    Text("Traduzir livro todo")
                                }
                            }
                            if (translatedChaptersMap.isNotEmpty()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Exibir versão traduzida", color = currentTheme.text)
                                    Switch(checked = useTranslatedText, onCheckedChange = { useTranslatedText = it })
                                }
                            }
                            if (translationProgressText != null) {
                                Text(translationProgressText!!, fontSize = 12.sp, color = Color(0xFF4F46E5))
                            }
                            Spacer(modifier = Modifier.height(20.dp))
                        }
                    }
                }

                if (showSavedAudioSheet) {
                    ModalBottomSheet(
                        onDismissRequest = { showSavedAudioSheet = false },
                        containerColor = currentTheme.barBg
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = "Áudios Guardados com Sincronização",
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                color = currentTheme.text
                            )
                            Text(
                                text = "Reproduz o áudio guardado enquanto o leitor acompanha e destaca o texto.",
                                fontSize = 12.sp,
                                color = currentTheme.text.copy(alpha = 0.75f)
                            )
                            Button(
                                onClick = {
                                    isExportingAudio = true
                                    audioStatusMessage = "A gerar áudio sincronizado..."
                                    scope.launch {
                                        syncVoiceEngineConfig()
                                        val res = voiceEngine.synthesizeAndSaveChapterAudio(
                                            bookTitle = currentBook.title,
                                            chapterIndex = currentChapterIndex,
                                            chapterTitle = activeChapter.title,
                                            text = textContent
                                        )
                                        isExportingAudio = false
                                        if (res.isSuccess) {
                                            savedAudioTracks = voiceEngine.listSavedAudioTracks()
                                            audioStatusMessage = "Áudio e sincronização guardados com sucesso!"
                                        } else {
                                            audioStatusMessage = res.exceptionOrNull()?.message ?: "Falha ao guardar áudio."
                                        }
                                    }
                                },
                                enabled = !isExportingAudio
                            ) {
                                Icon(Icons.Rounded.AudioFile, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(if (isExportingAudio) "A exportar..." else "Guardar áudio deste capítulo")
                            }

                            if (audioStatusMessage != null) {
                                Text(
                                    text = audioStatusMessage!!,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = Color(0xFF4F46E5)
                                )
                            }

                            if (savedAudioTracks.isEmpty()) {
                                Text(
                                    text = "Ainda não tens áudios guardados.",
                                    fontSize = 13.sp,
                                    color = currentTheme.text.copy(alpha = 0.7f),
                                    modifier = Modifier.padding(vertical = 12.dp)
                                )
                            } else {
                                LazyColumn(
                                    modifier = Modifier.heightIn(max = 320.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    items(savedAudioTracks, key = { it.id }) { track ->
                                        Card(
                                            colors = CardDefaults.cardColors(containerColor = currentTheme.bg),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(12.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(track.chapterTitle, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = currentTheme.text)
                                                    Text(
                                                        text = "${track.bookTitle} • ${track.segments.size} trechos sincronizados",
                                                        fontSize = 11.sp,
                                                        color = currentTheme.text.copy(alpha = 0.7f)
                                                    )
                                                }
                                                IconButton(onClick = {
                                                    if (track.chapterIndex in currentBook.chapters.indices) {
                                                        currentChapterIndex = track.chapterIndex
                                                    }
                                                    showSavedAudioSheet = false
                                                    voiceEngine.playSavedTrackWithSync(
                                                        track = track,
                                                        onSentenceRange = { activeSentenceRange = it },
                                                        onWordRange = { activeWordRange = it },
                                                        onPlaybackState = { isPlaying = it }
                                                    )
                                                }) {
                                                    Icon(Icons.Rounded.PlayArrow, contentDescription = "Ouvir sincronizado", tint = Color(0xFF4F46E5))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(20.dp))
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
                            currentPageIndex = 0
                            scope.launch { scrollState.scrollTo(0) }
                        },
                        onDismiss = { showNotebookSheet = false }
                    )
                }

                if (showNoteDialog) {
                    val paletteList = ReadingEnhancementManager.presetPaletteColors + customColors
                    AlertDialog(
                        onDismissRequest = { showNoteDialog = false },
                        title = { Text("Destacar, Sublinhar ou Anotar") },
                        text = {
                            Column(
                                modifier = Modifier.verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                OutlinedTextField(
                                    value = draftSelection,
                                    onValueChange = { draftSelection = it },
                                    label = { Text("Trecho selecionado") },
                                    maxLines = 3
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Cores de destaque:", fontSize = 13.sp)
                                    TextButton(onClick = { showColorMixerDialog = true }) {
                                        Text("+ Misturar / Criar")
                                    }
                                }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    paletteList.forEach { paletteItem ->
                                        val isSelected = selectedColorKey == paletteItem.id
                                        Box(
                                            modifier = Modifier
                                                .size(34.dp)
                                                .clip(CircleShape)
                                                .background(paletteItem.toComposeColor())
                                                .border(
                                                    width = if (isSelected) 3.dp else 1.dp,
                                                    color = if (isSelected) Color.Black else Color.Gray,
                                                    shape = CircleShape
                                                )
                                                .clickable { selectedColorKey = paletteItem.id }
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
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        TextButton(onClick = { openDictionaryOrTranslate(draftSelection) }) {
                                            Icon(Icons.Rounded.Translate, contentDescription = null)
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Traduzir")
                                        }
                                        TextButton(onClick = {
                                            researchQueryInput = draftSelection
                                            showResearchHubDialog = true
                                        }) {
                                            Icon(Icons.Rounded.Search, contentDescription = null)
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("IA / Google")
                                        }
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
                                        colorName = selectedColorKey,
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
                                                currentPageIndex = 0
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

                val showTopAndBottomBars = barsBehaviorMode == BarsBehaviorMode.ALWAYS_VISIBLE || barsVisible
                val displayedText = if (readingViewMode == ReadingViewMode.PAGED) {
                    pages.getOrElse(currentPageIndex) { "" }
                } else {
                    textContent
                }

                Surface(modifier = Modifier.fillMaxSize(), color = currentTheme.bg) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .clickable {
                                    if (barsBehaviorMode != BarsBehaviorMode.ALWAYS_VISIBLE) {
                                        barsVisible = !barsVisible
                                    }
                                }
                                .padding(horizontal = 22.dp)
                                .then(
                                    if (readingViewMode == ReadingViewMode.SCROLL) {
                                        Modifier.verticalScroll(scrollState)
                                    } else {
                                        Modifier
                                    }
                                )
                                .padding(top = 16.dp, bottom = 150.dp)
                        ) {
                            if (showTopAndBottomBars) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    IconButton(onClick = {
                                        stopAudio()
                                        ReaderLayoutController.applyFullscreenSystemBars(activity, false)
                                        LibraryManager.updateReadingProgress(context, currentMeta.id, currentChapterIndex, scrollState.value)
                                        refreshLibraryAndOrg()
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
                                        val reflowTag = if (activeReflowMode == ReflowMode.AUTO_ADAPT) "Reflow" else "Original"
                                        val subLabel = if (readingViewMode == ReadingViewMode.PAGED) {
                                            "${activeChapter.title} • Pág. ${currentPageIndex + 1}/${pages.size} • $reflowTag"
                                        } else {
                                            "${activeChapter.title} • $reflowTag"
                                        }
                                        Text(
                                            text = subLabel,
                                            fontSize = 12.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            color = currentTheme.text.copy(alpha = 0.65f)
                                        )
                                    }
                                    Row {
                                        IconButton(
                                            onClick = {
                                                activeReflowMode = if (activeReflowMode == ReflowMode.AUTO_ADAPT) {
                                                    ReflowMode.ORIGINAL_LAYOUT
                                                } else {
                                                    ReflowMode.AUTO_ADAPT
                                                }
                                            },
                                            modifier = Modifier.size(34.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.WrapText,
                                                contentDescription = "Reflow / Adaptar ao ecrã",
                                                tint = currentTheme.text
                                            )
                                        }
                                        IconButton(
                                            onClick = { showTranslateSheet = true },
                                            modifier = Modifier.size(34.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Translate,
                                                contentDescription = "Traduzir capítulo/livro",
                                                tint = currentTheme.text
                                            )
                                        }
                                        IconButton(
                                            onClick = {
                                                researchQueryInput = ""
                                                showResearchHubDialog = true
                                            },
                                            modifier = Modifier.size(34.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.TravelExplore,
                                                contentDescription = "IA / Google / Dicionário",
                                                tint = currentTheme.text
                                            )
                                        }
                                        IconButton(
                                            onClick = { showSavedAudioSheet = true },
                                            modifier = Modifier.size(34.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.AudioFile,
                                                contentDescription = "Áudio sincronizado",
                                                tint = currentTheme.text
                                            )
                                        }
                                        IconButton(
                                            onClick = {
                                                readingViewMode = if (readingViewMode == ReadingViewMode.SCROLL) {
                                                    ReadingViewMode.PAGED
                                                } else {
                                                    ReadingViewMode.SCROLL
                                                }
                                            },
                                            modifier = Modifier.size(34.dp)
                                        ) {
                                            Icon(
                                                imageVector = if (readingViewMode == ReadingViewMode.PAGED) Icons.Rounded.ViewStream else Icons.Rounded.AutoStories,
                                                contentDescription = "Rolagem/Páginas",
                                                tint = currentTheme.text
                                            )
                                        }
                                        IconButton(
                                            onClick = { isFullscreenMode = !isFullscreenMode },
                                            modifier = Modifier.size(34.dp)
                                        ) {
                                            Icon(
                                                imageVector = if (isFullscreenMode) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
                                                contentDescription = "Ecrã inteiro",
                                                tint = currentTheme.text
                                            )
                                        }
                                        IconButton(onClick = { showTocSheet = true }, modifier = Modifier.size(34.dp)) {
                                            Icon(Icons.Rounded.FormatListBulleted, contentDescription = "Índice", tint = currentTheme.text)
                                        }
                                        IconButton(onClick = { showNotebookSheet = true }, modifier = Modifier.size(34.dp)) {
                                            Icon(Icons.Rounded.Book, contentDescription = "Notas", tint = currentTheme.text)
                                        }
                                        IconButton(onClick = { showTypeSheet = true }, modifier = Modifier.size(34.dp)) {
                                            Icon(Icons.Rounded.FormatSize, contentDescription = "Fontes", tint = currentTheme.text)
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(16.dp))
                            }

                            val bookAnnotations = annotations.filter {
                                it.bookTitle == currentBook.title &&
                                    it.chapterIndex == currentChapterIndex &&
                                    !it.isBookmark
                            }
                            val annotatedText = buildAnnotatedString {
                                append(displayedText)
                                bookAnnotations.forEach { ann ->
                                    if (ann.selectedText.isNotBlank()) {
                                        val idx = displayedText.indexOf(ann.selectedText)
                                        if (idx >= 0) {
                                            val c = ReadingEnhancementManager.resolveColorByNameOrArgb(context, ann.colorName)
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
                                if (readingViewMode == ReadingViewMode.SCROLL) {
                                    activeSentenceRange?.let { range ->
                                        if (range.first >= 0 && range.last < displayedText.length) {
                                            addStyle(
                                                SpanStyle(
                                                    background = currentTheme.highlightBg.copy(alpha = 0.65f),
                                                    color = currentTheme.highlightText
                                                ),
                                                range.first,
                                                range.last + 1
                                            )
                                        }
                                    }
                                    if (wordHighlightEnabled) {
                                        activeWordRange?.let { wRange ->
                                            if (wRange.first >= 0 && wRange.last < displayedText.length) {
                                                addStyle(
                                                    SpanStyle(
                                                        background = Color(0xFFFFB703),
                                                        color = Color(0xFF000000),
                                                        fontWeight = FontWeight.Bold,
                                                        textDecoration = TextDecoration.Underline
                                                    ),
                                                    wRange.first,
                                                    wRange.last + 1
                                                )
                                            }
                                        }
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

                            if (readingViewMode == ReadingViewMode.PAGED && pages.size > 1) {
                                Spacer(modifier = Modifier.height(20.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            if (currentPageIndex > 0) {
                                                currentPageIndex--
                                                if (barsBehaviorMode == BarsBehaviorMode.HIDE_ON_ADVANCE) {
                                                    barsVisible = false
                                                }
                                            }
                                        },
                                        enabled = currentPageIndex > 0
                                    ) {
                                        Text("Página anterior")
                                    }
                                    Text(
                                        text = "${currentPageIndex + 1} / ${pages.size}",
                                        color = currentTheme.text,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Button(
                                        onClick = {
                                            if (currentPageIndex < pages.lastIndex) {
                                                currentPageIndex++
                                                if (barsBehaviorMode == BarsBehaviorMode.HIDE_ON_ADVANCE) {
                                                    barsVisible = false
                                                }
                                            }
                                        },
                                        enabled = currentPageIndex < pages.lastIndex
                                    ) {
                                        Text("Próxima página")
                                    }
                                }
                            }
                        }

                        if (showStudyBanner) {
                            val quickColors = (ReadingEnhancementManager.presetPaletteColors + customColors).take(4)
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
                                    quickColors.forEach { cItem ->
                                        Box(
                                            modifier = Modifier
                                                .size(24.dp)
                                                .clip(CircleShape)
                                                .background(cItem.toComposeColor())
                                                .clickable {
                                                    val item = StudyAnnotation(
                                                        bookTitle = currentBook.title,
                                                        chapterIndex = currentChapterIndex,
                                                        chapterTitle = activeChapter.title,
                                                        selectedText = draftSelection.trim(),
                                                        colorName = cItem.id
                                                    )
                                                    annotations = listOf(item) + annotations
                                                    ReaderStorage.saveAnnotations(context, annotations)
                                                    showStudyBanner = false
                                                }
                                        )
                                    }
                                    IconButton(onClick = { showNoteDialog = true }, modifier = Modifier.size(32.dp)) {
                                        Icon(Icons.Rounded.EditNote, contentDescription = "Nota/Sublinhar/Cores", tint = Color.White)
                                    }
                                    IconButton(onClick = {
                                        researchQueryInput = draftSelection
                                        showResearchHubDialog = true
                                        showStudyBanner = false
                                    }, modifier = Modifier.size(32.dp)) {
                                        Icon(Icons.Rounded.TravelExplore, contentDescription = "IA / Google / Traduzir", tint = Color.White)
                                    }
                                    IconButton(onClick = { showStudyBanner = false }, modifier = Modifier.size(28.dp)) {
                                        Icon(Icons.Rounded.Close, contentDescription = "Fechar", tint = Color.Gray)
                                    }
                                }
                            }
                        }

                        if (showTopAndBottomBars) {
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
                                                currentPageIndex = 0
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
                                                currentPageIndex = 0
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
                                icon = { Icon(Icons.Rounded.Book, contentDescription = null) },
                                label = { Text("Caderno") }
                            )
                            NavigationBarItem(
                                selected = currentTab == 3,
                                onClick = { currentTab = 3 },
                                icon = { Icon(Icons.Rounded.Settings, contentDescription = null) },
                                label = { Text("Definições") }
                            )
                        }
                    }
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        when (currentTab) {
                            0 -> HomeScreen(
                                theme = currentTheme,
                                recentBook = recentHomeBook,
                                totalBooksCount = savedBooks.size,
                                totalNotesCount = annotations.size,
                                onContinueReading = {
                                    if (recentMeta != null) {
                                        openBookMeta(recentMeta)
                                    } else {
                                        filePicker.launch(arrayOf("application/epub+zip", "application/pdf", "text/plain"))
                                    }
                                },
                                onOpenLibrary = { currentTab = 1 },
                                onOpenNotebook = { currentTab = 2 },
                                onOpenSettings = { currentTab = 3 },
                                onImportBook = {
                                    showWebImportDialog = true
                                }
                            )

                            1 -> {
                                if (isImporting) {
                                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        CircularProgressIndicator(color = Color(0xFF4F46E5))
                                    }
                                } else {
                                    val visibleBooks = LibraryOrganizationManager.filterAndSortBooks(
                                        context = context,
                                        books = savedBooks,
                                        selectedCategory = selectedCategory,
                                        showSecretShelf = showSecretShelf,
                                        searchQuery = librarySearchQuery,
                                        formatFilter = formatFilter,
                                        sortOption = sortOption
                                    )
                                    val duplicateCount = LibraryOrganizationManager.findDuplicateGroups(savedBooks).size

                                    LazyColumn(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(currentTheme.bg)
                                            .padding(16.dp),
                                        verticalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        item {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = if (showSecretShelf) "Estante Secreta" else "Biblioteca",
                                                    fontSize = 24.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = currentTheme.text
                                                )
                                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                    IconButton(onClick = { showDuplicatesSheet = true }) {
                                                        BadgedBox(badge = {
                                                            if (duplicateCount > 0) {
                                                                Badge { Text("$duplicateCount") }
                                                            }
                                                        }) {
                                                            Icon(Icons.Rounded.ContentCopy, contentDescription = "Duplicados", tint = currentTheme.text)
                                                        }
                                                    }
                                                    IconButton(onClick = {
                                                        if (showSecretShelf) {
                                                            showSecretShelf = false
                                                        } else {
                                                            requestSecretShelfAccess(null)
                                                        }
                                                    }) {
                                                        Icon(
                                                            imageVector = if (showSecretShelf) Icons.Rounded.LockOpen else Icons.Rounded.Lock,
                                                            contentDescription = "Estante secreta",
                                                            tint = currentTheme.text
                                                        )
                                                    }
                                                    FilledTonalButton(onClick = { showWebImportDialog = true }) {
                                                        Icon(Icons.Rounded.Language, contentDescription = null)
                                                        Spacer(modifier = Modifier.width(4.dp))
                                                        Text("URL")
                                                    }
                                                    Button(onClick = {
                                                        filePicker.launch(arrayOf("application/epub+zip", "application/pdf", "text/plain"))
                                                    }) {
                                                        Icon(Icons.Rounded.Add, contentDescription = null)
                                                        Spacer(modifier = Modifier.width(4.dp))
                                                        Text("Ficheiro")
                                                    }
                                                }
                                            }
                                        }

                                        item {
                                            OutlinedTextField(
                                                value = librarySearchQuery,
                                                onValueChange = { librarySearchQuery = it },
                                                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                                                label = { Text("Pesquisar título, formato ou categoria") },
                                                singleLine = true,
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        }

                                        if (!showSecretShelf) {
                                            item {
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .horizontalScroll(rememberScrollState()),
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    (listOf("Todos") + categories).forEach { cat ->
                                                        FilterChip(
                                                            selected = selectedCategory == cat,
                                                            onClick = { selectedCategory = cat },
                                                            label = { Text(cat) }
                                                        )
                                                    }
                                                    FilterChip(
                                                        selected = false,
                                                        onClick = {
                                                            newCategoryName = ""
                                                            showAddCategoryDialog = true
                                                        },
                                                        label = { Text("+ Categoria") }
                                                    )
                                                }
                                            }
                                        }

                                        item {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .horizontalScroll(rememberScrollState()),
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                listOf("Todos", "EPUB", "PDF", "TXT", "WEB").forEach { fmt ->
                                                    FilterChip(
                                                        selected = formatFilter == fmt,
                                                        onClick = { formatFilter = fmt },
                                                        label = { Text(fmt) }
                                                    )
                                                }
                                                LibrarySortOption.entries.forEach { opt ->
                                                    FilterChip(
                                                        selected = sortOption == opt,
                                                        onClick = { sortOption = opt },
                                                        label = { Text(opt.label) }
                                                    )
                                                }
                                            }
                                        }

                                        if (visibleBooks.isEmpty()) {
                                            item {
                                                Text(
                                                    text = if (showSecretShelf) {
                                                        "A estante secreta está vazia. Usa o ícone de cadeado num livro para o guardar aqui."
                                                    } else {
                                                        "Nenhum livro encontrado com os filtros atuais."
                                                    },
                                                    color = currentTheme.text.copy(alpha = 0.75f),
                                                    modifier = Modifier.padding(top = 20.dp)
                                                )
                                            }
                                        } else {
                                            items(visibleBooks, key = { it.id }) { book ->
                                                val metaOrg = orgMetaMap[book.id]
                                                val progressPct = (LibraryOrganizationManager.estimateBookProgress(context, book) * 100).toInt()
                                                Card(
                                                    colors = CardDefaults.cardColors(containerColor = currentTheme.barBg),
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clickable { openBookMeta(book) }
                                                ) {
                                                    Row(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .padding(14.dp),
                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Column(modifier = Modifier.weight(1f)) {
                                                            Text(book.title, fontWeight = FontWeight.Bold, color = currentTheme.text)
                                                            Text(
                                                                text = "${book.format} • ${metaOrg?.category ?: "A ler"} • $progressPct%",
                                                                fontSize = 12.sp,
                                                                color = currentTheme.text.copy(alpha = 0.7f)
                                                            )
                                                        }
                                                        IconButton(onClick = { categoryTargetBook = book }) {
                                                            Icon(Icons.Rounded.Label, contentDescription = "Categoria", tint = currentTheme.text)
                                                        }
                                                        IconButton(onClick = { requestSecretShelfAccess(book) }) {
                                                            Icon(
                                                                imageVector = if (metaOrg?.isSecret == true) Icons.Rounded.LockOpen else Icons.Rounded.Lock,
                                                                contentDescription = "Estante secreta",
                                                                tint = currentTheme.text
                                                            )
                                                        }
                                                        IconButton(onClick = {
                                                            LibraryManager.deleteBook(context, book.id)
                                                            refreshLibraryAndOrg()
                                                        }) {
                                                            Icon(Icons.Rounded.Delete, contentDescription = "Remover", tint = currentTheme.text)
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            2 -> {
                                val stats = StudyNotebookManager.computeStats(annotations)
                                LazyColumn(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(currentTheme.bg)
                                        .padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    item {
                                        Text("Caderno", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = currentTheme.text)
                                    }

                                    item {
                                        Card(
                                            colors = CardDefaults.cardColors(containerColor = currentTheme.barBg),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                Text("Resumo de Estudo", fontWeight = FontWeight.Bold, color = currentTheme.text)
                                                Text(
                                                    text = "Total: ${stats.totalEntries} • Notas: ${stats.notesCount} • Destaques: ${stats.highlightsCount}",
                                                    fontSize = 12.sp,
                                                    color = currentTheme.text.copy(alpha = 0.8f)
                                                )
                                                Text(
                                                    text = "Sublinhados: ${stats.underlinesCount} • Marcadores: ${stats.bookmarksCount} • Livros: ${stats.annotatedBooksCount}",
                                                    fontSize = 12.sp,
                                                    color = currentTheme.text.copy(alpha = 0.8f)
                                                )
                                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                    OutlinedButton(onClick = {
                                                        val res = StudyNotebookManager.exportAnnotationsToFile(context, annotations)
                                                        exportStatusMessage = if (res.isSuccess) {
                                                            "Exportado: ${res.getOrNull()?.name}"
                                                        } else {
                                                            "Falha ao exportar."
                                                        }
                                                    }) {
                                                        Text("Exportar .MD")
                                                    }
                                                    OutlinedButton(onClick = {
                                                        val md = StudyNotebookManager.formatAnnotationsAsMarkdown(annotations)
                                                        clipboard.setText(AnnotatedString(md))
                                                        exportStatusMessage = "Caderno copiado para a área de transferência."
                                                    }) {
                                                        Text("Copiar resumo")
                                                    }
                                                }
                                                if (exportStatusMessage != null) {
                                                    Text(exportStatusMessage!!, fontSize = 12.sp, color = Color(0xFF4F46E5))
                                                }
                                            }
                                        }
                                    }

                                    item {
                                        Card(
                                            colors = CardDefaults.cardColors(containerColor = currentTheme.barBg),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Text("Dicionário, Tradutor, IA e Google", fontWeight = FontWeight.Bold, color = currentTheme.text)
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    OutlinedTextField(
                                                        value = lookupQueryInput,
                                                        onValueChange = { lookupQueryInput = it },
                                                        label = { Text("Palavra, conceito ou pergunta") },
                                                        singleLine = true,
                                                        modifier = Modifier.weight(1f)
                                                    )
                                                    Button(onClick = { openDictionaryOrTranslate(lookupQueryInput) }) {
                                                        Text("Consultar")
                                                    }
                                                }
                                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                    OutlinedButton(onClick = {
                                                        openResearchTarget(ResearchToolTarget.GOOGLE_SEARCH, lookupQueryInput)
                                                    }) {
                                                        Text("Google")
                                                    }
                                                    OutlinedButton(onClick = {
                                                        openResearchTarget(ResearchToolTarget.AI_ASSISTANT, lookupQueryInput)
                                                    }) {
                                                        Text("IA na App")
                                                    }
                                                    OutlinedButton(onClick = { showColorMixerDialog = true }) {
                                                        Text("Cores / Mistura")
                                                    }
                                                }
                                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                    listOf("pt", "en", "es", "fr").forEach { langCode ->
                                                        FilterChip(
                                                            selected = translationTargetLang == langCode,
                                                            onClick = { translationTargetLang = langCode },
                                                            label = { Text("→ ${langCode.uppercase()}") }
                                                        )
                                                    }
                                                }
                                                if (lookupHistory.isNotEmpty()) {
                                                    Text("Histórico recente:", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = currentTheme.text)
                                                    lookupHistory.take(5).forEach { hist ->
                                                        Row(
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .clickable {
                                                                    lookupQueryInput = hist.query
                                                                    openDictionaryOrTranslate(hist.query)
                                                                }
                                                                .padding(vertical = 4.dp),
                                                            horizontalArrangement = Arrangement.SpaceBetween
                                                        ) {
                                                            Text(hist.query, fontSize = 13.sp, color = currentTheme.text)
                                                            Text(hist.provider, fontSize = 11.sp, color = currentTheme.text.copy(alpha = 0.65f))
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    if (annotations.isEmpty()) {
                                        item {
                                            Text(
                                                text = "Ainda não tens notas ou destaques guardados.",
                                                color = currentTheme.text.copy(alpha = 0.75f),
                                                modifier = Modifier.padding(top = 12.dp)
                                            )
                                        }
                                    } else {
                                        items(annotations, key = { it.id }) { ann ->
                                            Card(
                                                colors = CardDefaults.cardColors(containerColor = currentTheme.barBg),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(14.dp),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.Top
                                                ) {
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text(ann.bookTitle, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = currentTheme.text)
                                                        if (ann.selectedText.isNotBlank()) {
                                                            Text(ann.selectedText, fontSize = 13.sp, color = currentTheme.text.copy(alpha = 0.85f))
                                                        }
                                                        if (ann.note.isNotBlank()) {
                                                            Text("Nota: ${ann.note}", fontSize = 12.sp, color = Color(0xFF4F46E5))
                                                        }
                                                    }
                                                    IconButton(onClick = {
                                                        annotations = annotations.filterNot { it.id == ann.id }
                                                        ReaderStorage.saveAnnotations(context, annotations)
                                                    }) {
                                                        Icon(Icons.Rounded.Delete, contentDescription = "Apagar nota", tint = currentTheme.text)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            else -> {
                                LazyColumn(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(currentTheme.bg)
                                        .padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(14.dp)
                                ) {
                                    item {
                                        Text("Definições", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = currentTheme.text)
                                    }
                                    item {
                                        Text("Tema da leitura", fontWeight = FontWeight.SemiBold, color = currentTheme.text)
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            ReaderTheme.entries.forEach { themeOption ->
                                                FilterChip(
                                                    selected = currentTheme == themeOption,
                                                    onClick = { currentTheme = themeOption },
                                                    label = { Text(themeOption.label) }
                                                )
                                            }
                                        }
                                    }
                                    item {
                                        Text("Reflow padrão ao abrir livros/PDFs", fontWeight = FontWeight.SemiBold, color = currentTheme.text)
                                        ReflowMode.entries.forEach { mode ->
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                RadioButton(
                                                    selected = defaultReflowMode == mode,
                                                    onClick = {
                                                        defaultReflowMode = mode
                                                        activeReflowMode = mode
                                                        ReadingEnhancementManager.saveDefaultReflowMode(context, mode)
                                                    }
                                                )
                                                Text(mode.label, color = currentTheme.text)
                                            }
                                        }
                                    }
                                    item {
                                        Text("URL da IA na App", fontWeight = FontWeight.SemiBold, color = currentTheme.text)
                                        Spacer(modifier = Modifier.height(4.dp))
                                        OutlinedTextField(
                                            value = customAiUrl,
                                            onValueChange = {
                                                customAiUrl = it
                                                ReadingEnhancementManager.saveCustomAiUrl(context, it)
                                            },
                                            singleLine = true,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                    item {
                                        Text("Modo de leitura", fontWeight = FontWeight.SemiBold, color = currentTheme.text)
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            ReadingViewMode.entries.forEach { modeOption ->
                                                FilterChip(
                                                    selected = readingViewMode == modeOption,
                                                    onClick = { readingViewMode = modeOption },
                                                    label = { Text(modeOption.label) }
                                                )
                                            }
                                        }
                                    }
                                    item {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text("Ecrã inteiro ao ler", fontWeight = FontWeight.SemiBold, color = currentTheme.text)
                                            Switch(
                                                checked = isFullscreenMode,
                                                onCheckedChange = { isFullscreenMode = it }
                                            )
                                        }
                                    }
                                    item {
                                        Text("Comportamento das barras ao avançar", fontWeight = FontWeight.SemiBold, color = currentTheme.text)
                                        BarsBehaviorMode.entries.forEach { barOption ->
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                RadioButton(
                                                    selected = barsBehaviorMode == barOption,
                                                    onClick = { barsBehaviorMode = barOption }
                                                )
                                                Text(barOption.label, color = currentTheme.text)
                                            }
                                        }
                                    }
                                    item {
                                        Text("Estante secreta e segurança", fontWeight = FontWeight.SemiBold, color = currentTheme.text)
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            OutlinedButton(onClick = {
                                                pendingSecretBookToggle = null
                                                secretPinInput = ""
                                                secretPinError = null
                                                showSecretPinDialog = true
                                            }) {
                                                Text(if (LibraryOrganizationManager.hasSecretPin(context)) "Alterar / Verificar PIN" else "Definir PIN")
                                            }
                                            OutlinedButton(onClick = {
                                                LibraryOrganizationManager.lockSecretShelf(context)
                                                showSecretShelf = false
                                            }) {
                                                Text("Bloquear estante")
                                            }
                                        }
                                    }
                                    item {
                                        Text("Motor de Voz (TTS / Neural / Online)", fontWeight = FontWeight.SemiBold, color = currentTheme.text)
                                        VoiceProviderType.entries.forEach { providerType ->
                                            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    RadioButton(
                                                        selected = selectedProvider == providerType,
                                                        onClick = {
                                                            selectedProvider = providerType
                                                            syncVoiceEngineConfig()
                                                        }
                                                    )
                                                    Column {
                                                        Text(providerType.title, color = currentTheme.text, fontWeight = FontWeight.Medium)
                                                        Text(providerType.subtitle, fontSize = 11.sp, color = currentTheme.text.copy(alpha = 0.7f))
                                                    }
                                                }
                                            }
                                        }
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text("Destaque palavra a palavra ao ler", color = currentTheme.text)
                                            Switch(
                                                checked = wordHighlightEnabled,
                                                onCheckedChange = {
                                                    wordHighlightEnabled = it
                                                    syncVoiceEngineConfig()
                                                }
                                            )
                                        }
                                        Text(
                                            text = "Modelos Piper (.onnx) locais encontrados: $piperModelsCount",
                                            fontSize = 12.sp,
                                            color = currentTheme.text.copy(alpha = 0.75f)
                                        )
                                        if (selectedProvider == VoiceProviderType.EDGE_ONLINE || selectedProvider == VoiceProviderType.KOKORO_POCKET_NEURAL) {
                                            Spacer(modifier = Modifier.height(6.dp))
                                            OutlinedTextField(
                                                value = customOnlineEndpoint,
                                                onValueChange = {
                                                    customOnlineEndpoint = it
                                                    syncVoiceEngineConfig()
                                                },
                                                label = { Text("URL do serviço de voz online / Edge") },
                                                singleLine = true,
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                OutlinedButton(onClick = {
                                                    scope.launch {
                                                        val ok = voiceEngine.testOnlineEndpoint(customOnlineEndpoint)
                                                        onlineTestResult = if (ok) "Ligação ao serviço de voz ativa." else "Sem resposta ou URL inválida."
                                                    }
                                                }) {
                                                    Text("Testar ligação")
                                                }
                                                if (onlineTestResult != null) {
                                                    Text(onlineTestResult!!, fontSize = 12.sp, color = currentTheme.text)
                                                }
                                            }
                                        }
                                        if (availableVoices.isNotEmpty()) {
                                            Spacer(modifier = Modifier.height(6.dp))
                                            Text("Vozes disponíveis no sistema (${availableVoices.size}):", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = currentTheme.text)
                                            availableVoices.take(8).forEach { v ->
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    RadioButton(
                                                        selected = selectedVoiceName == v.name,
                                                        onClick = {
                                                            selectedVoiceName = v.name
                                                            selectedLocale = v.locale
                                                            syncVoiceEngineConfig()
                                                        }
                                                    )
                                                    Text(
                                                        text = "${v.locale.displayLanguage} (${v.name.take(24)})",
                                                        fontSize = 12.sp,
                                                        color = currentTheme.text
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    item {
                                        Text("Fornecedor de dicionário/tradutor", fontWeight = FontWeight.SemiBold, color = currentTheme.text)
                                        listOf("Google Tradutor", "Priberam / Dicio", "Linguee / DeepL").forEach { provider ->
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                RadioButton(
                                                    selected = dictionaryProvider == provider,
                                                    onClick = { dictionaryProvider = provider }
                                                )
                                                Text(provider, color = currentTheme.text)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
