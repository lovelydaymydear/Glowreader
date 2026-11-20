package com.glowreader.app

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

private val ReaderTealAccent = Color(0xFF55A89F)
private val ReaderDarkCanvas = Color(0xFF0A1410)
private val ReaderDarkSheet = Color(0xFF14211B)
private val ReaderDarkPill = Color(0xFF1C2D25)

enum class ReaderTheme(
    val label: String,
    val pageTag: String,
    val bg: Color,
    val text: Color,
    val highlightBg: Color,
    val highlightText: Color,
    val barBg: Color,
    val accent: Color
) {
    FOREST("Theme Verde", "TEMA", ReaderDarkCanvas, Color(0xFFE3ECE7), Color(0xFF23473C), Color(0xFFFFFFFF), ReaderDarkSheet, ReaderTealAccent),
    LIGHT("Claro", "TINTA", Color(0xFFF6F7F6), Color(0xFF18201C), Color(0xFFCDE8DF), Color(0xFF0A1410), Color(0xFFFFFFFF), Color(0xFF2E7D6B)),
    PAPYRUS("Papyrus", "PAPYRUS", Color(0xFFEADDBB), Color(0xFF2A2217), Color(0xFFD5B77A), Color(0xFF1A140C), Color(0xFFDFCFA9), Color(0xFF3E7A6C)),
    DARK("Escuro", "CREPÚSCULO", Color(0xFF121514), Color(0xFFDCE2DF), Color(0xFF28423A), Color(0xFFFFFFFF), Color(0xFF1B201E), ReaderTealAccent),
    SEPIA("Sépia", "SÉPIA", Color(0xFFF4ECD8), Color(0xFF3E3224), Color(0xFFE6C27A), Color(0xFF1F1810), Color(0xFFEBE0C5), Color(0xFF3B7A6B))
}

enum class LibraryLayoutMode(val label: String) {
    GRID("Grelha"),
    LIST("Lista"),
    COMPACT("Compacto")
}

private fun formatCleanBookTitle(rawTitle: String): String {
    return rawTitle
        .replace('_', ' ')
        .replace(Regex("\\s+"), " ")
        .trim()
        .ifBlank { "Livro" }
}

private fun buildInAppToolUrl(
    toolType: String,
    query: String,
    targetLang: String,
    customAiUrl: String
): Pair<String, String> {
    val cleanQuery = query.trim()
    val encoded = URLEncoder.encode(cleanQuery, StandardCharsets.UTF_8.toString())
    val lang = targetLang.lowercase(Locale.ROOT).ifBlank { "pt" }

    return when (toolType) {
        "TRANSLATE" -> {
            val url = if (cleanQuery.isEmpty()) {
                "https://translate.google.com/?sl=auto&tl=$lang&op=translate"
            } else {
                "https://translate.google.com/?sl=auto&tl=$lang&text=$encoded&op=translate"
            }
            "Tradutor na App" to url
        }
        "DICTIONARY" -> {
            val url = if (cleanQuery.isEmpty()) {
                "https://dicio.com.br/"
            } else {
                "https://dicio.com.br/$encoded/"
            }
            "Dicionário na App" to url
        }
        "AI" -> {
            val baseAi = customAiUrl.trim().ifBlank { "https://gemini.google.com/app" }
            val url = if (cleanQuery.isEmpty()) {
                baseAi
            } else {
                "https://www.google.com/search?q=$encoded"
            }
            "IA e Pesquisa na App" to url
        }
        else -> {
            val url = if (cleanQuery.isEmpty()) {
                "https://www.google.com/"
            } else {
                "https://www.google.com/search?q=$encoded"
            }
            "Pesquisa na App" to url
        }
    }
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

            var currentTab by remember { mutableIntStateOf(0) }
            var currentTheme by remember { mutableStateOf(ReaderTheme.FOREST) }
            var fontSize by remember { mutableFloatStateOf(20f) }
            var currentFont by remember { mutableStateOf(ReaderFont.SERIF) }
            var justifyText by remember { mutableStateOf(true) }
            var lineHeightMultiplier by remember { mutableFloatStateOf(1.62f) }
            var horizontalPaddingDp by remember { mutableIntStateOf(20) }
            var paragraphSpaced by remember { mutableStateOf(true) }
            var warmthPercent by remember { mutableIntStateOf(0) }
            var keepScreenOn by remember { mutableStateOf(false) }

            var libraryLayoutMode by remember { mutableStateOf(LibraryLayoutMode.GRID) }
            var libraryVersion by remember { mutableIntStateOf(0) }

            var readingViewMode by remember { mutableStateOf(ReadingViewMode.SCROLL) }
            var barsBehaviorMode by remember { mutableStateOf(BarsBehaviorMode.ALWAYS_VISIBLE) }
            var isFullscreenMode by remember { mutableStateOf(false) }

            val initialReflow = ReadingEnhancementManager.loadDefaultReflowMode(context)
            var defaultReflowMode by remember { mutableStateOf(initialReflow) }
            var activeReflowMode by remember { mutableStateOf(initialReflow) }
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

            var showTranslateSheet by remember { mutableStateOf(false) }
            var translatedChaptersMap by remember { mutableStateOf(emptyMap<Int, String>()) }
            var useTranslatedText by remember { mutableStateOf(false) }
            var isTranslating by remember { mutableStateOf(false) }
            var translationProgressText by remember { mutableStateOf<String?>(null) }
            var customAiUrl by remember { mutableStateOf(ReadingEnhancementManager.loadCustomAiUrl(context)) }
            var showResearchHubDialog by remember { mutableStateOf(false) }
            var researchQueryInput by remember { mutableStateOf("") }

            var savedBooks by remember { mutableStateOf(LibraryManager.loadLibrary(context)) }
            var orgMetaMap by remember {
                mutableStateOf(LibraryOrganizationManager.loadAllMeta(context))
            }
            var categories by remember { mutableStateOf(LibraryManager.loadAllCategories(context)) }
            var selectedCategory by remember { mutableStateOf("Todos") }
            var showSecretShelf by remember { mutableStateOf(false) }
            var librarySearchQuery by remember { mutableStateOf("") }
            var formatFilter by remember { mutableStateOf("Todos") }
            var sortOption by remember { mutableStateOf(LibrarySortOption.RECENT) }

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

            var showWebImportDialog by remember { mutableStateOf(false) }
            var webUrlInput by remember { mutableStateOf("") }
            var webImportError by remember { mutableStateOf<String?>(null) }
            var webImportStatus by remember { mutableStateOf<String?>(null) }

            var annotations by remember { mutableStateOf(ReaderStorage.loadAnnotations(context)) }
            var dictionaryProvider by remember { mutableStateOf("Dicionário / Tradutor") }
            var translationTargetLang by remember { mutableStateOf("pt") }
            var lookupQueryInput by remember { mutableStateOf("") }
            var lookupHistory by remember { mutableStateOf(StudyNotebookManager.loadLookupHistory(context)) }
            var exportStatusMessage by remember { mutableStateOf<String?>(null) }

            var internalLookupTitle by remember { mutableStateOf<String?>(null) }
            var internalLookupUrl by remember { mutableStateOf<String?>(null) }

            var selectedProvider by remember { mutableStateOf(VoiceProviderType.ANDROID_SYSTEM) }
            var selectedLocale by remember { mutableStateOf(Locale("pt", "PT")) }
            var voiceLangFilter by remember { mutableStateOf("pt") }
            var availableVoices by remember { mutableStateOf<List<Voice>>(emptyList()) }
            var selectedVoiceName by remember { mutableStateOf("") }
            var selectedEdgeVoice by remember { mutableStateOf("pt-PT-RaquelNeural") }
            var edgeVoiceLangFilter by remember { mutableStateOf("all") }
            var selectedOfflineModelId by remember { mutableStateOf("") }
            var wordHighlightEnabled by remember { mutableStateOf(true) }
            var onlineTestResult by remember { mutableStateOf<String?>(null) }
            var catalogRefreshTick by remember { mutableIntStateOf(0) }
            var downloadingItemId by remember { mutableStateOf<String?>(null) }
            var downloadingPercent by remember { mutableIntStateOf(0) }
            var voiceZipSourceInput by remember { mutableStateOf("") }
            var voiceDownloadStatus by remember { mutableStateOf<String?>(null) }

            var isPlaying by remember { mutableStateOf(false) }
            var speechRate by remember { mutableFloatStateOf(1.0f) }
            var forThisBookOnly by remember { mutableStateOf(false) }
            var sleepTimerMinutes by remember { mutableIntStateOf(0) }
            var activeSentenceRange by remember { mutableStateOf<IntRange?>(null) }
            var activeWordRange by remember { mutableStateOf<IntRange?>(null) }

            var savedAudioTracks by remember { mutableStateOf(voiceEngine.listSavedAudioTracks()) }
            var audioStatusMessage by remember { mutableStateOf<String?>(null) }
            var isExportingAudio by remember { mutableStateOf(false) }
            var showSavedAudioSheet by remember { mutableStateOf(false) }

            fun refreshLibraryAndOrg() {
                LibraryManager.ensureBooksRegistered(context)
                val loaded = LibraryManager.loadLibrary(context)
                savedBooks = loaded
                orgMetaMap = LibraryOrganizationManager.loadAllMeta(context)
                categories = LibraryManager.loadAllCategories(context)
                libraryVersion++
            }

            fun providerForCatalogItem(item: DownloadableVoiceCatalogItem): VoiceProviderType {
                return when (item.engineLabel.lowercase(Locale.ROOT)) {
                    "kokoro tts" -> VoiceProviderType.KOKORO_LOCAL
                    "pocket tts" -> VoiceProviderType.POCKET_TTS_LOCAL
                    else -> VoiceProviderType.PIPER_LOCAL
                }
            }

            LaunchedEffect(Unit) {
                refreshLibraryAndOrg()
                catalogRefreshTick++
                voiceEngine.initializeAndroidTts { voices ->
                    availableVoices = voices
                }
            }

            DisposableEffect(voiceEngine) {
                onDispose {
                    ReaderLayoutController.applyFullscreenSystemBars(activity, false)
                    activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    voiceEngine.release()
                }
            }

            fun syncVoiceEngineConfig() {
                voiceEngine.currentProvider = selectedProvider
                voiceEngine.selectedLocale = selectedLocale
                voiceEngine.selectedVoiceName = selectedVoiceName
                voiceEngine.selectedEdgeVoice = selectedEdgeVoice
                voiceEngine.selectedOfflineModelId = selectedOfflineModelId
                voiceEngine.speechRate = speechRate
                voiceEngine.wordHighlightEnabled = wordHighlightEnabled
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

            LaunchedEffect(isPlaying, sleepTimerMinutes) {
                if (isPlaying && sleepTimerMinutes > 0) {
                    delay(sleepTimerMinutes * 60_000L)
                    stopAudio()
                }
            }

            fun openToolInsideApp(toolType: String, query: String) {
                val clean = query.trim()
                if (clean.isNotEmpty()) {
                    lookupHistory = StudyNotebookManager.recordLookup(context, clean, toolType)
                }
                val (title, url) = buildInAppToolUrl(
                    toolType = toolType,
                    query = clean,
                    targetLang = translationTargetLang,
                    customAiUrl = customAiUrl
                )
                internalLookupTitle = title
                internalLookupUrl = url
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
                    activeBookContent = loaded.copy(title = formatCleanBookTitle(loaded.title))
                    currentChapterIndex = meta.lastChapterIndex.coerceIn(0, (loaded.chapters.size - 1).coerceAtLeast(0))
                }
            }

            fun openBookMeta(meta: SavedBookMeta) {
                pendingOpenBookMeta = meta
            }

            fun requestSecretShelfAccess(bookToToggle: SavedBookMeta? = null) {
                if (!LibraryManager.hasSecretPin(context)) {
                    pendingSecretBookToggle = bookToToggle
                    secretPinInput = ""
                    secretPinError = null
                    showSecretPinDialog = true
                    return
                }
                if (LibraryManager.isSecretUnlocked(context)) {
                    if (bookToToggle != null) {
                        LibraryManager.toggleBookSecret(context, bookToToggle.id)
                        refreshLibraryAndOrg()
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
                    val result = withContext(Dispatchers.IO) {
                        WebImporter.importFromUrl(rawUrl)
                    }
                    if (result.success && result.book != null && result.book.chapters.isNotEmpty()) {
                        val cleanedBook = result.book.copy(title = formatCleanBookTitle(result.book.title))
                        withContext(Dispatchers.IO) {
                            LibraryManager.saveBookToLibrary(
                                context = context,
                                parsed = cleanedBook,
                                format = "WEB",
                                coverBitmap = cleanedBook.coverBitmap
                            )
                        }
                        refreshLibraryAndOrg()
                        webImportStatus = null
                        webImportError = null
                        webUrlInput = ""
                        showWebImportDialog = false
                        isImporting = false
                        currentTab = 1
                    } else {
                        webImportStatus = null
                        webImportError = result.errorMessage ?: "Não foi possível importar a página."
                        isImporting = false
                    }
                }
            }

            val hasOverlayOpen = internalLookupUrl != null ||
                pendingOpenBookMeta != null ||
                showColorMixerDialog ||
                showResearchHubDialog ||
                showAddCategoryDialog ||
                categoryTargetBook != null ||
                showSecretPinDialog ||
                showDuplicatesSheet ||
                showWebImportDialog

            BackHandler(enabled = activeBookContent == null && (hasOverlayOpen || showSecretShelf || currentTab != 0)) {
                when {
                    internalLookupUrl != null -> {
                        internalLookupUrl = null
                        internalLookupTitle = null
                    }
                    pendingOpenBookMeta != null -> pendingOpenBookMeta = null
                    showColorMixerDialog -> showColorMixerDialog = false
                    showResearchHubDialog -> showResearchHubDialog = false
                    showAddCategoryDialog -> showAddCategoryDialog = false
                    categoryTargetBook != null -> categoryTargetBook = null
                    showSecretPinDialog -> {
                        showSecretPinDialog = false
                        pendingSecretBookToggle = null
                    }
                    showDuplicatesSheet -> showDuplicatesSheet = false
                    showWebImportDialog && !isImporting -> {
                        showWebImportDialog = false
                        webImportError = null
                        webImportStatus = null
                    }
                    showSecretShelf -> showSecretShelf = false
                    currentTab != 0 -> currentTab = 0
                }
            }

            val zipPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
                uri?.let { pickedUri ->
                    voiceDownloadStatus = "A copiar e extrair .zip..."
                    scope.launch {
                        val copiedZipFile = withContext(Dispatchers.IO) {
                            runCatching {
                                val cacheDir = File(context.cacheDir, "zip_imports").apply { if (!exists()) mkdirs() }
                                val dest = File(cacheDir, "picked_${System.currentTimeMillis()}.zip")
                                context.contentResolver.openInputStream(pickedUri)?.use { input ->
                                    FileOutputStream(dest).use { output -> input.copyTo(output) }
                                }
                                if (dest.exists() && dest.length() > 16L) dest else null
                            }.getOrNull()
                        }
                        if (copiedZipFile == null) {
                            voiceDownloadStatus = "Não foi possível ler o ficheiro .zip selecionado."
                            return@launch
                        }
                        voiceZipSourceInput = copiedZipFile.absolutePath
                        val res = voiceEngine.downloadAndExtractVoiceZip(
                            zipUrlOrLocalPath = copiedZipFile.absolutePath,
                            voiceName = "clone_${System.currentTimeMillis()}"
                        )
                        catalogRefreshTick++
                        voiceDownloadStatus = if (res.isSuccess) {
                            selectedProvider = VoiceProviderType.POCKET_TTS_LOCAL
                            syncVoiceEngineConfig()
                            "Pacote .zip extraído (${voiceEngine.activeClonedVoiceName})"
                        } else {
                            "Erro .zip: ${res.exceptionOrNull()?.message}"
                        }
                    }
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
                            val cleanedBook = parsedBook.copy(title = formatCleanBookTitle(parsedBook.title))
                            withContext(Dispatchers.IO) {
                                LibraryManager.saveBookToLibrary(
                                    context = context,
                                    parsed = cleanedBook,
                                    format = format,
                                    coverBitmap = cleanedBook.coverBitmap
                                )
                            }
                            refreshLibraryAndOrg()
                            isImporting = false
                            currentTab = 1
                        } else {
                            isImporting = false
                        }
                    }
                }
            }

            val bookToPromptReflow = pendingOpenBookMeta
            if (bookToPromptReflow != null) {
                val cleanPromptTitle = formatCleanBookTitle(bookToPromptReflow.title)
                val isPdf = bookToPromptReflow.format.equals("PDF", ignoreCase = true)
                AlertDialog(
                    onDismissRequest = { pendingOpenBookMeta = null },
                    containerColor = ReaderDarkSheet,
                    title = {
                        Text(
                            text = "Abrir: $cleanPromptTitle",
                            color = Color(0xFFE3ECE7),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                text = if (isPdf) {
                                    "Este livro é um PDF. Queres adaptar o texto ao ecrã (Reflow) para leitura fluida, voz e marcação?"
                                } else {
                                    "Escolhe como queres apresentar este livro ao abrir:"
                                },
                                color = Color(0xFFE3ECE7).copy(alpha = 0.85f),
                                fontSize = 13.sp
                            )
                            ReflowMode.entries.forEach { mode ->
                                val isRecommended = isPdf && mode == ReflowMode.AUTO_ADAPT
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = ReaderDarkCanvas),
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
                                            color = ReaderTealAccent,
                                            fontSize = 14.sp
                                        )
                                        Text(mode.description, color = Color(0xFFE3ECE7).copy(alpha = 0.8f), fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = {
                        Button(
                            colors = ButtonDefaults.buttonColors(containerColor = ReaderTealAccent, contentColor = Color(0xFF07120E)),
                            onClick = {
                                val target = bookToPromptReflow
                                pendingOpenBookMeta = null
                                launchBookWithReflow(target, defaultReflowMode)
                            }
                        ) {
                            Text("Abrir padrão", fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { pendingOpenBookMeta = null }) {
                            Text("Cancelar", color = Color(0xFFE3ECE7), maxLines = 1, softWrap = false)
                        }
                    }
                )
            }

            if (showColorMixerDialog) {
                val mixedPreview = ReadingEnhancementManager.mixColors(mixColorA, mixColorB, mixRatio)
                val rgbPreview = ReadingEnhancementManager.buildColorFromRgb(rgbRed.toInt(), rgbGreen.toInt(), rgbBlue.toInt())

                AlertDialog(
                    onDismissRequest = { showColorMixerDialog = false },
                    containerColor = currentTheme.barBg,
                    title = { Text("Criar ou Misturar Cor de Destaque", color = currentTheme.text) },
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

                            Text("Mistura de duas cores:", color = currentTheme.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
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
                                            .border(2.dp, if (mixColorA == c) currentTheme.accent else Color.Transparent, CircleShape)
                                            .clickable { mixColorA = c }
                                    )
                                }
                                Text("+", color = currentTheme.text, fontWeight = FontWeight.Bold)
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
                                            .border(2.dp, if (mixColorB == c) currentTheme.accent else Color.Transparent, CircleShape)
                                            .clickable { mixColorB = c }
                                    )
                                }
                            }
                            Text("Proporção da mistura: ${(mixRatio * 100).toInt()}%", color = currentTheme.text, fontSize = 11.sp)
                            Slider(value = mixRatio, onValueChange = { mixRatio = it })

                            Text("Ajuste fino RGB:", color = currentTheme.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Text("Vermelho: ${rgbRed.toInt()}", color = currentTheme.text, fontSize = 11.sp)
                            Slider(value = rgbRed, onValueChange = { rgbRed = it }, valueRange = 0f..255f)
                            Text("Verde: ${rgbGreen.toInt()}", color = currentTheme.text, fontSize = 11.sp)
                            Slider(value = rgbGreen, onValueChange = { rgbGreen = it }, valueRange = 0f..255f)
                            Text("Azul: ${rgbBlue.toInt()}", color = currentTheme.text, fontSize = 11.sp)
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
                                Text("Mistura", color = currentTheme.text, fontSize = 12.sp, maxLines = 1, softWrap = false)
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(rgbPreview)
                                        .border(1.dp, Color.Gray, RoundedCornerShape(8.dp))
                                )
                                Text("RGB", color = currentTheme.text, fontSize = 12.sp, maxLines = 1, softWrap = false)
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
                                Text("Guardar RGB", color = currentTheme.accent, maxLines = 1, softWrap = false)
                            }
                            Button(
                                colors = ButtonDefaults.buttonColors(containerColor = currentTheme.accent, contentColor = Color.White),
                                onClick = {
                                    val savedList = ReadingEnhancementManager.saveCustomColor(
                                        context,
                                        newCustomColorName.ifBlank { "Mistura Guardada" },
                                        mixedPreview
                                    )
                                    customColors = savedList
                                    savedList.firstOrNull()?.let { selectedColorKey = it.id }
                                    newCustomColorName = ""
                                    showColorMixerDialog = false
                                }
                            ) {
                                Text("Guardar Mistura", maxLines = 1, softWrap = false)
                            }
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showColorMixerDialog = false }) {
                            Text("Cancelar", color = currentTheme.text, maxLines = 1, softWrap = false)
                        }
                    }
                )
            }

            if (showResearchHubDialog) {
                AlertDialog(
                    onDismissRequest = { showResearchHubDialog = false },
                    containerColor = currentTheme.barBg,
                    title = { Text("Pesquisa, IA, Dicionário e Tradutor", color = currentTheme.text) },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                text = "Abre qualquer consulta diretamente dentro do GlowReader sem sair da leitura:",
                                color = currentTheme.text.copy(alpha = 0.8f),
                                fontSize = 12.sp
                            )
                            OutlinedTextField(
                                value = researchQueryInput,
                                onValueChange = { researchQueryInput = it },
                                label = { Text("Termo, conceito ou pergunta") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedButton(
                                onClick = {
                                    showResearchHubDialog = false
                                    openToolInsideApp("SEARCH", researchQueryInput)
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Pesquisar na App", color = currentTheme.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            OutlinedButton(
                                onClick = {
                                    showResearchHubDialog = false
                                    openToolInsideApp("DICTIONARY", researchQueryInput)
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Dicionário na App", color = currentTheme.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            OutlinedButton(
                                onClick = {
                                    showResearchHubDialog = false
                                    openToolInsideApp("TRANSLATE", researchQueryInput)
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Tradutor na App", color = currentTheme.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            OutlinedButton(
                                onClick = {
                                    showResearchHubDialog = false
                                    openResearchTarget(ResearchToolTarget.AI_ASSISTANT, researchQueryInput)
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Assistente / IA na App", color = currentTheme.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showResearchHubDialog = false }) {
                            Text("Fechar", color = currentTheme.accent, maxLines = 1, softWrap = false)
                        }
                    }
                )
            }

            if (showAddCategoryDialog) {
                AlertDialog(
                    onDismissRequest = { showAddCategoryDialog = false },
                    containerColor = currentTheme.barBg,
                    title = { Text("Nova categoria", color = currentTheme.text) },
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
                        Button(
                            colors = ButtonDefaults.buttonColors(containerColor = currentTheme.accent, contentColor = Color.White),
                            onClick = {
                                if (newCategoryName.isNotBlank()) {
                                    LibraryManager.addCustomCategory(context, newCategoryName)
                                    categories = LibraryManager.loadAllCategories(context)
                                    selectedCategory = newCategoryName.trim()
                                    newCategoryName = ""
                                }
                                showAddCategoryDialog = false
                            }
                        ) {
                            Text("Criar", maxLines = 1, softWrap = false)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showAddCategoryDialog = false }) {
                            Text("Cancelar", color = currentTheme.text, maxLines = 1, softWrap = false)
                        }
                    }
                )
            }

            val bookForCat = categoryTargetBook
            if (bookForCat != null) {
                val currentBookCat = bookForCat.folderName.ifBlank { "Geral" }
                AlertDialog(
                    onDismissRequest = { categoryTargetBook = null },
                    containerColor = currentTheme.barBg,
                    title = {
                        Text(
                            text = "Categoria — ${formatCleanBookTitle(bookForCat.title)}",
                            color = currentTheme.text,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    text = {
                        Column {
                            categories.forEach { cat ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            LibraryManager.setBookCategory(context, bookForCat.id, cat)
                                            refreshLibraryAndOrg()
                                            categoryTargetBook = null
                                        }
                                        .padding(vertical = 4.dp)
                                ) {
                                    RadioButton(
                                        selected = currentBookCat.equals(cat, ignoreCase = true),
                                        onClick = {
                                            LibraryManager.setBookCategory(context, bookForCat.id, cat)
                                            refreshLibraryAndOrg()
                                            categoryTargetBook = null
                                        }
                                    )
                                    Text(cat, color = currentTheme.text)
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { categoryTargetBook = null }) {
                            Text("Fechar", color = currentTheme.accent, maxLines = 1, softWrap = false)
                        }
                    }
                )
            }

            if (showSecretPinDialog) {
                val pinAlreadySet = LibraryManager.hasSecretPin(context)
                AlertDialog(
                    onDismissRequest = {
                        showSecretPinDialog = false
                        pendingSecretBookToggle = null
                    },
                    containerColor = currentTheme.barBg,
                    title = {
                        Text(
                            text = if (pinAlreadySet) "Desbloquear estante secreta" else "Definir PIN da estante secreta",
                            color = currentTheme.text
                        )
                    },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = if (pinAlreadySet) {
                                    "Introduz o teu PIN para aceder ou mover itens da estante secreta."
                                } else {
                                    "Cria um PIN de pelo menos 4 dígitos para proteger os teus livros privados."
                                },
                                color = currentTheme.text.copy(alpha = 0.85f),
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
                        Button(
                            colors = ButtonDefaults.buttonColors(containerColor = currentTheme.accent, contentColor = Color.White),
                            onClick = {
                                if (pinAlreadySet) {
                                    if (LibraryManager.verifySecretPin(context, secretPinInput)) {
                                        val toggleTarget = pendingSecretBookToggle
                                        if (toggleTarget != null) {
                                            LibraryManager.toggleBookSecret(context, toggleTarget.id)
                                            refreshLibraryAndOrg()
                                        } else {
                                            showSecretShelf = true
                                        }
                                        pendingSecretBookToggle = null
                                        showSecretPinDialog = false
                                    } else {
                                        secretPinError = "PIN incorreto."
                                    }
                                } else {
                                    if (LibraryManager.setSecretPin(context, secretPinInput)) {
                                        val toggleTarget = pendingSecretBookToggle
                                        if (toggleTarget != null) {
                                            LibraryManager.toggleBookSecret(context, toggleTarget.id)
                                            refreshLibraryAndOrg()
                                        } else {
                                            showSecretShelf = true
                                        }
                                        pendingSecretBookToggle = null
                                        showSecretPinDialog = false
                                    } else {
                                        secretPinError = "Usa pelo menos 4 caracteres."
                                    }
                                }
                            }
                        ) {
                            Text("Confirmar", maxLines = 1, softWrap = false)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            showSecretPinDialog = false
                            pendingSecretBookToggle = null
                        }) {
                            Text("Cancelar", color = currentTheme.text, maxLines = 1, softWrap = false)
                        }
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
                                                text = formatCleanBookTitle(group.books.first().title),
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
                                                        Text("Remover cópia", fontSize = 12.sp, color = MaterialTheme.colorScheme.error, maxLines = 1, softWrap = false)
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
                    containerColor = currentTheme.barBg,
                    title = { Text("Importar página da web", color = currentTheme.text) },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                text = "Cola o link de um artigo, capítulo ou página web em português para extrair o texto e guardar na biblioteca.",
                                color = currentTheme.text.copy(alpha = 0.85f),
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
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = currentTheme.accent)
                                    Text(webImportStatus!!, color = currentTheme.text, fontSize = 12.sp)
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
                            colors = ButtonDefaults.buttonColors(containerColor = currentTheme.accent, contentColor = Color.White),
                            onClick = { triggerWebImport(webUrlInput) },
                            enabled = !isImporting && webUrlInput.isNotBlank()
                        ) {
                            Text("Importar link", maxLines = 1, softWrap = false)
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
                            Text("Cancelar", color = currentTheme.text, maxLines = 1, softWrap = false)
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

            LaunchedEffect(currentBook, keepScreenOn) {
                if (currentBook != null && keepScreenOn) {
                    activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }

            if (currentBook != null && currentMeta != null) {
                val scrollState = rememberScrollState(initial = currentMeta.lastScrollPosition)
                val activeChapter = currentBook.chapters.getOrElse(currentChapterIndex) { BookChapter("Vazio", "") }
                val baseChapterText = remember(activeChapter.content, activeReflowMode, paragraphSpaced) {
                    val reflowed = ReadingEnhancementManager.applyReflow(activeChapter.content, activeReflowMode)
                    if (paragraphSpaced) {
                        reflowed
                    } else {
                        reflowed.replace(Regex("\n\\s*\n"), "\n")
                    }
                }
                val textContent = if (useTranslatedText && translatedChaptersMap.containsKey(currentChapterIndex)) {
                    translatedChaptersMap[currentChapterIndex] ?: baseChapterText
                } else {
                    baseChapterText
                }

                val pages = remember(textContent, fontSize) {
                    val dynamicChars = (2200f * (20f / fontSize.coerceIn(13f, 32f))).toInt()
                    ReaderLayoutController.paginateChapter(textContent, dynamicChars)
                }
                var currentPageIndex by remember(currentChapterIndex, pages.size) { mutableIntStateOf(0) }

                var barsVisible by remember(currentChapterIndex, barsBehaviorMode) {
                    mutableStateOf(barsBehaviorMode != BarsBehaviorMode.TAP_TO_TOGGLE)
                }

                var autoScrollEnabled by remember { mutableStateOf(false) }
                var autoScrollLinesPerMinute by remember { mutableIntStateOf(24) }

                LaunchedEffect(autoScrollEnabled, autoScrollLinesPerMinute, readingViewMode) {
                    while (autoScrollEnabled && readingViewMode == ReadingViewMode.SCROLL && scrollState.maxValue > 0) {
                        val stepDelayMs = (60_000L / autoScrollLinesPerMinute.coerceIn(6, 80)).coerceAtLeast(120L)
                        delay(stepDelayMs)
                        val nextPos = (scrollState.value + 16).coerceAtMost(scrollState.maxValue)
                        scrollState.scrollTo(nextPos)
                        if (nextPos >= scrollState.maxValue) {
                            autoScrollEnabled = false
                        }
                    }
                }

                var showTocSheet by remember { mutableStateOf(false) }
                var showListenSheet by remember { mutableStateOf(false) }
                var listenSheetTab by remember { mutableIntStateOf(0) }
                var showTypeSheet by remember { mutableStateOf(false) }
                var showNotebookSheet by remember { mutableStateOf(false) }
                var showNoteDialog by remember { mutableStateOf(false) }
                var draftSelection by remember { mutableStateOf("") }
                var draftNote by remember { mutableStateOf("") }
                var draftUnderline by remember { mutableStateOf(false) }
                var showStudyBanner by remember { mutableStateOf(false) }

                LaunchedEffect(scrollState, scrollState.isScrollInProgress, barsBehaviorMode) {
                    if (scrollState.isScrollInProgress && barsBehaviorMode == BarsBehaviorMode.HIDE_ON_ADVANCE) {
                        barsVisible = false
                        showStudyBanner = false
                    }
                }

                fun closeReaderBackToLibrary() {
                    autoScrollEnabled = false
                    stopAudio()
                    ReaderLayoutController.applyFullscreenSystemBars(activity, false)
                    activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    LibraryManager.updateReadingProgress(context, currentMeta.id, currentChapterIndex, scrollState.value)
                    refreshLibraryAndOrg()
                    activeBookContent = null
                    activeBookMeta = null
                    currentTab = 1
                }

                BackHandler(enabled = true) {
                    when {
                        showColorMixerDialog -> showColorMixerDialog = false
                        showNoteDialog -> showNoteDialog = false
                        showTranslateSheet -> if (!isTranslating) showTranslateSheet = false
                        showSavedAudioSheet -> showSavedAudioSheet = false
                        showListenSheet -> showListenSheet = false
                        showTypeSheet -> showTypeSheet = false
                        showNotebookSheet -> showNotebookSheet = false
                        showTocSheet -> showTocSheet = false
                        showResearchHubDialog -> showResearchHubDialog = false
                        internalLookupUrl != null -> {
                            internalLookupUrl = null
                            internalLookupTitle = null
                        }
                        showStudyBanner -> showStudyBanner = false
                        else -> closeReaderBackToLibrary()
                    }
                }

                DisposableEffect(currentChapterIndex) {
                    onDispose {
                        LibraryManager.updateReadingProgress(context, currentMeta.id, currentChapterIndex, scrollState.value)
                        refreshLibraryAndOrg()
                    }
                }

                val highlightAnchor = activeWordRange?.first ?: activeSentenceRange?.first
                LaunchedEffect(highlightAnchor) {
                    val pos = highlightAnchor
                    if (pos != null && textContent.isNotEmpty() && isPlaying) {
                        if (readingViewMode == ReadingViewMode.SCROLL && scrollState.maxValue > 0) {
                            val ratio = (pos.toFloat() / textContent.length.toFloat()).coerceIn(0f, 1f)
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

                @Composable
                fun SheetHandle() {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp, bottom = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(width = 36.dp, height = 4.dp)
                                .clip(CircleShape)
                                .background(currentTheme.text.copy(alpha = 0.25f))
                        )
                    }
                }

                @Composable
                fun SpeedSectionContent() {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = "Velocidade",
                            fontFamily = FontFamily.Serif,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = currentTheme.text
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = ReaderDarkPill,
                                modifier = Modifier
                                    .size(44.dp)
                                    .clickable {
                                        speechRate = (speechRate - 0.25f).coerceIn(0.5f, 2.5f)
                                        if (isPlaying) {
                                            stopAudio()
                                            startAudio(textContent)
                                        }
                                    }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text("−", fontSize = 20.sp, color = currentTheme.text)
                                }
                            }
                            Text(
                                text = "${speechRate}×",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = currentTheme.text
                            )
                            Surface(
                                shape = CircleShape,
                                color = ReaderDarkPill,
                                modifier = Modifier
                                    .size(44.dp)
                                    .clickable {
                                        speechRate = (speechRate + 0.25f).coerceIn(0.5f, 2.5f)
                                        if (isPlaying) {
                                            stopAudio()
                                            startAudio(textContent)
                                        }
                                    }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text("+", fontSize = 20.sp, color = currentTheme.text)
                                }
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(24.dp),
                            color = ReaderDarkPill,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { rateOpt ->
                                    val selected = kotlin.math.abs(speechRate - rateOpt) < 0.01f
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(20.dp))
                                            .background(if (selected) ReaderTealAccent else Color.Transparent)
                                            .clickable {
                                                speechRate = rateOpt
                                                if (isPlaying) {
                                                    stopAudio()
                                                    startAudio(textContent)
                                                }
                                            }
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "${rateOpt}×",
                                            fontSize = 13.sp,
                                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                            color = if (selected) Color(0xFF07120E) else currentTheme.text,
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                    }
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Apenas neste livro", fontSize = 15.sp, color = currentTheme.text)
                            Switch(
                                checked = forThisBookOnly,
                                onCheckedChange = { forThisBookOnly = it },
                                colors = SwitchDefaults.colors(checkedTrackColor = ReaderTealAccent)
                            )
                        }
                    }
                }

                if (showListenSheet) {
                    ModalBottomSheet(
                        onDismissRequest = { showListenSheet = false },
                        containerColor = ReaderDarkSheet,
                        dragHandle = null
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 20.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            SheetHandle()

                            Surface(
                                shape = RoundedCornerShape(26.dp),
                                color = ReaderDarkPill,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(modifier = Modifier.fillMaxWidth().padding(4.dp)) {
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(22.dp))
                                            .background(if (listenSheetTab == 0) ReaderTealAccent else Color.Transparent)
                                            .clickable { listenSheetTab = 0 }
                                            .padding(vertical = 10.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "Ouvir",
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (listenSheetTab == 0) Color(0xFF07120E) else Color(0xFFE3ECE7)
                                        )
                                    }
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(22.dp))
                                            .background(if (listenSheetTab == 1) ReaderTealAccent else Color.Transparent)
                                            .clickable { listenSheetTab = 1 }
                                            .padding(vertical = 10.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "Ler",
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (listenSheetTab == 1) Color(0xFF07120E) else Color(0xFFE3ECE7)
                                        )
                                    }
                                }
                            }

                            if (listenSheetTab == 0) {
                                SpeedSectionContent()

                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text(
                                        text = "Temporizador de sono",
                                        fontFamily = FontFamily.Serif,
                                        fontSize = 20.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFFE3ECE7)
                                    )
                                    Surface(
                                        shape = RoundedCornerShape(24.dp),
                                        color = ReaderDarkPill,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(4.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            listOf(0 to "Desligado", 15 to "15 min", 30 to "30 min", 60 to "1 hora").forEach { (mins, lbl) ->
                                                val selected = sleepTimerMinutes == mins
                                                Box(
                                                    modifier = Modifier
                                                        .clip(RoundedCornerShape(20.dp))
                                                        .background(if (selected) ReaderTealAccent else Color.Transparent)
                                                        .clickable { sleepTimerMinutes = mins }
                                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Text(
                                                        text = lbl,
                                                        fontSize = 12.sp,
                                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                                        color = if (selected) Color(0xFF07120E) else Color(0xFFE3ECE7),
                                                        maxLines = 1,
                                                        softWrap = false
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Voz",
                                            fontFamily = FontFamily.Serif,
                                            fontSize = 20.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFFE3ECE7)
                                        )
                                        Text(
                                            text = "LIVRO: PORTUGUÊS",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = Color(0xFFE3ECE7).copy(alpha = 0.6f)
                                        )
                                    }

                                    Surface(
                                        shape = RoundedCornerShape(18.dp),
                                        color = ReaderDarkPill,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(Icons.Rounded.RecordVoiceOver, contentDescription = null, tint = Color(0xFFE3ECE7))
                                            Spacer(modifier = Modifier.width(12.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = selectedVoiceName.ifBlank { "Português (${selectedLocale.displayLanguage})" },
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 14.sp,
                                                    color = Color(0xFFE3ECE7),
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = if (selectedProvider == VoiceProviderType.ANDROID_SYSTEM) "Voz do sistema" else selectedProvider.title,
                                                    fontSize = 12.sp,
                                                    color = Color(0xFFE3ECE7).copy(alpha = 0.65f)
                                                )
                                            }
                                            Surface(
                                                shape = RoundedCornerShape(20.dp),
                                                color = Color(0xFF283D34),
                                                modifier = Modifier.clickable {
                                                    showListenSheet = false
                                                    activeBookContent = null
                                                    activeBookMeta = null
                                                    currentTab = 3
                                                }
                                            ) {
                                                Text(
                                                    text = "Alterar",
                                                    color = ReaderTealAccent,
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                                                    maxLines = 1,
                                                    softWrap = false
                                                )
                                            }
                                        }
                                    }
                                }
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text(
                                        text = "Leitura e navegação",
                                        fontFamily = FontFamily.Serif,
                                        fontSize = 20.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFFE3ECE7)
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        FilterChip(
                                            selected = readingViewMode == ReadingViewMode.SCROLL,
                                            onClick = { readingViewMode = ReadingViewMode.SCROLL },
                                            label = { Text("Rolagem", maxLines = 1, softWrap = false) }
                                        )
                                        FilterChip(
                                            selected = readingViewMode == ReadingViewMode.PAGED,
                                            onClick = { readingViewMode = ReadingViewMode.PAGED },
                                            label = { Text("Páginas", maxLines = 1, softWrap = false) }
                                        )
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("Rolagem automática", fontSize = 15.sp, color = Color(0xFFE3ECE7))
                                        Switch(
                                            checked = autoScrollEnabled,
                                            onCheckedChange = {
                                                autoScrollEnabled = it
                                                if (it) stopAudio()
                                            },
                                            colors = SwitchDefaults.colors(checkedTrackColor = ReaderTealAccent)
                                        )
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("Justificar texto", fontSize = 15.sp, color = Color(0xFFE3ECE7))
                                        Switch(
                                            checked = justifyText,
                                            onCheckedChange = { justifyText = it },
                                            colors = SwitchDefaults.colors(checkedTrackColor = ReaderTealAccent)
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(18.dp))
                        }
                    }
                }

                if (showTranslateSheet) {
                    ModalBottomSheet(
                        onDismissRequest = { if (!isTranslating) showTranslateSheet = false },
                        containerColor = ReaderDarkSheet
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
                                color = Color(0xFFE3ECE7)
                            )
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                listOf("pt", "en", "es", "fr").forEach { lang ->
                                    FilterChip(
                                        selected = translationTargetLang == lang,
                                        onClick = { translationTargetLang = lang },
                                        label = { Text("→ ${lang.uppercase()}", maxLines = 1, softWrap = false) }
                                    )
                                }
                            }
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    colors = ButtonDefaults.buttonColors(containerColor = ReaderTealAccent, contentColor = Color(0xFF07120E)),
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
                                    Text("Traduzir capítulo", maxLines = 1, softWrap = false)
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
                                    Text("Traduzir livro todo", color = Color(0xFFE3ECE7), maxLines = 1, softWrap = false)
                                }
                            }
                            if (translatedChaptersMap.isNotEmpty()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Exibir versão traduzida", color = Color(0xFFE3ECE7))
                                    Switch(checked = useTranslatedText, onCheckedChange = { useTranslatedText = it })
                                }
                            }
                            if (translationProgressText != null) {
                                Text(translationProgressText!!, fontSize = 12.sp, color = ReaderTealAccent)
                            }
                            Spacer(modifier = Modifier.height(20.dp))
                        }
                    }
                }

                if (showSavedAudioSheet) {
                    ModalBottomSheet(
                        onDismissRequest = { showSavedAudioSheet = false },
                        containerColor = ReaderDarkSheet
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
                                color = Color(0xFFE3ECE7)
                            )
                            Button(
                                colors = ButtonDefaults.buttonColors(containerColor = ReaderTealAccent, contentColor = Color(0xFF07120E)),
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
                                Text(
                                    text = if (isExportingAudio) "A exportar..." else "Guardar áudio deste capítulo",
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            if (audioStatusMessage != null) {
                                Text(audioStatusMessage!!, fontSize = 12.sp, color = ReaderTealAccent)
                            }

                            if (savedAudioTracks.isEmpty()) {
                                Text(
                                    text = "Ainda não tens áudios guardados.",
                                    fontSize = 13.sp,
                                    color = Color(0xFFE3ECE7).copy(alpha = 0.7f),
                                    modifier = Modifier.padding(vertical = 12.dp)
                                )
                            } else {
                                LazyColumn(
                                    modifier = Modifier.heightIn(max = 320.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    items(savedAudioTracks, key = { it.id }) { track ->
                                        Card(
                                            colors = CardDefaults.cardColors(containerColor = ReaderDarkCanvas),
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
                                                    Text(track.chapterTitle, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color(0xFFE3ECE7))
                                                    Text(
                                                        text = "${track.bookTitle} • ${track.segments.size} trechos sincronizados",
                                                        fontSize = 11.sp,
                                                        color = Color(0xFFE3ECE7).copy(alpha = 0.7f)
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
                                                    Icon(Icons.Rounded.PlayArrow, contentDescription = "Ouvir sincronizado", tint = ReaderTealAccent)
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
                    ModalBottomSheet(
                        onDismissRequest = { showTypeSheet = false },
                        containerColor = ReaderDarkSheet,
                        dragHandle = null
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 20.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            SheetHandle()

                            Text(
                                text = "Luz",
                                fontFamily = FontFamily.Serif,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFE3ECE7)
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Calor", fontSize = 15.sp, color = Color(0xFFE3ECE7))
                                    Text(
                                        text = if (warmthPercent == 0) "Desligado" else "$warmthPercent%",
                                        fontSize = 15.sp,
                                        color = ReaderTealAccent
                                    )
                                }
                                Slider(
                                    value = warmthPercent.toFloat(),
                                    onValueChange = { warmthPercent = it.toInt() },
                                    valueRange = 0f..100f
                                )
                                Text(
                                    text = "Tom âmbar sobre a página para leitura noturna.",
                                    fontSize = 13.sp,
                                    color = Color(0xFFE3ECE7).copy(alpha = 0.65f)
                                )
                            }

                            Text(
                                text = "Página",
                                fontFamily = FontFamily.Serif,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFE3ECE7)
                            )
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                listOf(ReaderTheme.FOREST, ReaderTheme.LIGHT, ReaderTheme.PAPYRUS, ReaderTheme.DARK).forEach { themeCard ->
                                    val isSelected = currentTheme == themeCard
                                    Surface(
                                        shape = RoundedCornerShape(14.dp),
                                        color = themeCard.bg,
                                        modifier = Modifier
                                            .size(width = 82.dp, height = 64.dp)
                                            .border(
                                                width = if (isSelected) 2.dp else 1.dp,
                                                color = if (isSelected) ReaderTealAccent else Color.White.copy(alpha = 0.2f),
                                                shape = RoundedCornerShape(14.dp)
                                            )
                                            .clickable { currentTheme = themeCard }
                                    ) {
                                        Column(
                                            modifier = Modifier.fillMaxSize().padding(8.dp),
                                            verticalArrangement = Arrangement.SpaceBetween,
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            Text(
                                                text = "Aa",
                                                fontFamily = FontFamily.Serif,
                                                fontSize = 18.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = themeCard.text
                                            )
                                            Text(
                                                text = themeCard.pageTag,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = themeCard.text.copy(alpha = 0.8f),
                                                maxLines = 1,
                                                softWrap = false
                                            )
                                        }
                                    }
                                }
                            }

                            Text(
                                text = "Tamanho do texto",
                                fontFamily = FontFamily.Serif,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFE3ECE7)
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = ReaderDarkPill,
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clickable { fontSize = (fontSize - 1f).coerceIn(13f, 32f) }
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text("−", fontSize = 20.sp, color = Color(0xFFE3ECE7))
                                    }
                                }
                                Text(
                                    text = "Aa",
                                    fontFamily = FontFamily.Serif,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFFE3ECE7)
                                )
                                Surface(
                                    shape = CircleShape,
                                    color = ReaderDarkPill,
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clickable { fontSize = (fontSize + 1f).coerceIn(13f, 32f) }
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text("+", fontSize = 20.sp, color = Color(0xFFE3ECE7))
                                    }
                                }
                            }

                            Text(
                                text = "Fonte",
                                fontFamily = FontFamily.Serif,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFE3ECE7)
                            )
                            Surface(
                                shape = RoundedCornerShape(24.dp),
                                color = ReaderDarkPill,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    ReaderFont.entries.forEach { fontOpt ->
                                        val selected = currentFont == fontOpt
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(20.dp))
                                                .background(if (selected) ReaderTealAccent else Color.Transparent)
                                                .clickable { currentFont = fontOpt }
                                                .padding(horizontal = 14.dp, vertical = 9.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = fontOpt.label,
                                                fontSize = 13.sp,
                                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                                color = if (selected) Color(0xFF07120E) else Color(0xFFE3ECE7),
                                                maxLines = 1,
                                                softWrap = false
                                            )
                                        }
                                    }
                                }
                            }
                            Text(
                                text = "A rápida raposa castanha salta sobre o cão preguiçoso.",
                                fontFamily = currentFont.family,
                                fontSize = 16.sp,
                                color = Color(0xFFE3ECE7).copy(alpha = 0.85f)
                            )

                            Text(
                                text = "Espaçamento entre linhas",
                                fontFamily = FontFamily.Serif,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFE3ECE7)
                            )
                            Surface(
                                shape = RoundedCornerShape(24.dp),
                                color = ReaderDarkPill,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    listOf(1.38f to "Compacto", 1.62f to "Normal", 1.88f to "Amplo").forEach { (mult, lbl) ->
                                        val selected = kotlin.math.abs(lineHeightMultiplier - mult) < 0.05f
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(20.dp))
                                                .background(if (selected) ReaderTealAccent else Color.Transparent)
                                                .clickable { lineHeightMultiplier = mult }
                                                .padding(horizontal = 16.dp, vertical = 9.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = lbl,
                                                fontSize = 13.sp,
                                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                                color = if (selected) Color(0xFF07120E) else Color(0xFFE3ECE7),
                                                maxLines = 1,
                                                softWrap = false
                                            )
                                        }
                                    }
                                }
                            }

                            Text(
                                text = "Margens",
                                fontFamily = FontFamily.Serif,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFE3ECE7)
                            )
                            Surface(
                                shape = RoundedCornerShape(24.dp),
                                color = ReaderDarkPill,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    listOf(14 to "Estreitas", 20 to "Normais", 30 to "Largas").forEach { (pad, lbl) ->
                                        val selected = horizontalPaddingDp == pad
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(20.dp))
                                                .background(if (selected) ReaderTealAccent else Color.Transparent)
                                                .clickable { horizontalPaddingDp = pad }
                                                .padding(horizontal = 16.dp, vertical = 9.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = lbl,
                                                fontSize = 13.sp,
                                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                                color = if (selected) Color(0xFF07120E) else Color(0xFFE3ECE7),
                                                maxLines = 1,
                                                softWrap = false
                                            )
                                        }
                                    }
                                }
                            }

                            Text(
                                text = "Parágrafos",
                                fontFamily = FontFamily.Serif,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFE3ECE7)
                            )
                            Surface(
                                shape = RoundedCornerShape(24.dp),
                                color = ReaderDarkPill,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(20.dp))
                                            .background(if (paragraphSpaced) ReaderTealAccent else Color.Transparent)
                                            .clickable { paragraphSpaced = true }
                                            .padding(vertical = 9.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "Espaçados",
                                            fontSize = 13.sp,
                                            fontWeight = if (paragraphSpaced) FontWeight.Bold else FontWeight.Medium,
                                            color = if (paragraphSpaced) Color(0xFF07120E) else Color(0xFFE3ECE7),
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                    }
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(20.dp))
                                            .background(if (!paragraphSpaced) ReaderTealAccent else Color.Transparent)
                                            .clickable { paragraphSpaced = false }
                                            .padding(vertical = 9.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "Contínuos",
                                            fontSize = 13.sp,
                                            fontWeight = if (!paragraphSpaced) FontWeight.Bold else FontWeight.Medium,
                                            color = if (!paragraphSpaced) Color(0xFF07120E) else Color(0xFFE3ECE7),
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                    }
                                }
                            }

                            Text(
                                text = "Layout",
                                fontFamily = FontFamily.Serif,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFE3ECE7)
                            )
                            Surface(
                                shape = RoundedCornerShape(24.dp),
                                color = ReaderDarkPill,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    val isScroll = readingViewMode == ReadingViewMode.SCROLL
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(20.dp))
                                            .background(if (isScroll) ReaderTealAccent else Color.Transparent)
                                            .clickable { readingViewMode = ReadingViewMode.SCROLL }
                                            .padding(vertical = 9.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "Rolagem",
                                            fontSize = 13.sp,
                                            fontWeight = if (isScroll) FontWeight.Bold else FontWeight.Medium,
                                            color = if (isScroll) Color(0xFF07120E) else Color(0xFFE3ECE7),
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                    }
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(20.dp))
                                            .background(if (!isScroll) ReaderTealAccent else Color.Transparent)
                                            .clickable { readingViewMode = ReadingViewMode.PAGED }
                                            .padding(vertical = 9.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "Páginas",
                                            fontSize = 13.sp,
                                            fontWeight = if (!isScroll) FontWeight.Bold else FontWeight.Medium,
                                            color = if (!isScroll) Color(0xFF07120E) else Color(0xFFE3ECE7),
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                    }
                                }
                            }

                            Text(
                                text = "Rolagem automática",
                                fontFamily = FontFamily.Serif,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFE3ECE7)
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = ReaderDarkPill,
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clickable { autoScrollLinesPerMinute = (autoScrollLinesPerMinute - 4).coerceIn(8, 72) }
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text("−", fontSize = 20.sp, color = Color(0xFFE3ECE7))
                                    }
                                }
                                Text(
                                    text = "$autoScrollLinesPerMinute linhas por minuto",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFFE3ECE7)
                                )
                                Surface(
                                    shape = CircleShape,
                                    color = ReaderDarkPill,
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clickable { autoScrollLinesPerMinute = (autoScrollLinesPerMinute + 4).coerceIn(8, 72) }
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text("+", fontSize = 20.sp, color = Color(0xFFE3ECE7))
                                    }
                                }
                            }
                            Text(
                                text = "A página avança sozinha até ao fim do capítulo; um toque pausa momentaneamente.",
                                fontSize = 13.sp,
                                color = Color(0xFFE3ECE7).copy(alpha = 0.65f)
                            )
                            Button(
                                colors = ButtonDefaults.buttonColors(containerColor = ReaderTealAccent, contentColor = Color(0xFF07120E)),
                                onClick = {
                                    readingViewMode = ReadingViewMode.SCROLL
                                    autoScrollEnabled = !autoScrollEnabled
                                    if (autoScrollEnabled) stopAudio()
                                    showTypeSheet = false
                                },
                                shape = RoundedCornerShape(24.dp),
                                modifier = Modifier.height(46.dp)
                            ) {
                                Icon(
                                    imageVector = if (autoScrollEnabled) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                    contentDescription = null
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (autoScrollEnabled) "Pausar rolagem automática" else "Iniciar rolagem automática",
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Justificar texto", fontSize = 16.sp, color = Color(0xFFE3ECE7))
                                    Text("Margem direita alinhada como livro impresso", fontSize = 12.sp, color = Color(0xFFE3ECE7).copy(alpha = 0.65f))
                                }
                                Switch(
                                    checked = justifyText,
                                    onCheckedChange = { justifyText = it },
                                    colors = SwitchDefaults.colors(checkedTrackColor = ReaderTealAccent)
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Ecrã inteiro", fontSize = 16.sp, color = Color(0xFFE3ECE7))
                                    Text("Oculta barras do sistema durante a leitura", fontSize = 12.sp, color = Color(0xFFE3ECE7).copy(alpha = 0.65f))
                                }
                                Switch(
                                    checked = isFullscreenMode,
                                    onCheckedChange = { isFullscreenMode = it },
                                    colors = SwitchDefaults.colors(checkedTrackColor = ReaderTealAccent)
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Manter ecrã ligado", fontSize = 16.sp, color = Color(0xFFE3ECE7))
                                    Text("Enquanto um livro estiver aberto", fontSize = 12.sp, color = Color(0xFFE3ECE7).copy(alpha = 0.65f))
                                }
                                Switch(
                                    checked = keepScreenOn,
                                    onCheckedChange = { keepScreenOn = it },
                                    colors = SwitchDefaults.colors(checkedTrackColor = ReaderTealAccent)
                                )
                            }

                            Spacer(modifier = Modifier.height(22.dp))
                        }
                    }
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
                        containerColor = ReaderDarkSheet,
                        title = { Text("Destacar, Sublinhar ou Anotar", color = Color(0xFFE3ECE7)) },
                        text = {
                            Column(
                                modifier = Modifier.verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                OutlinedTextField(
                                    value = draftSelection,
                                    onValueChange = { draftSelection = it },
                                    label = { Text("Trecho ou palavra") },
                                    maxLines = 3,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Paleta de cores:", color = Color(0xFFE3ECE7), fontSize = 13.sp, maxLines = 1, softWrap = false)
                                    TextButton(onClick = { showColorMixerDialog = true }) {
                                        Text("+ Misturar / Criar", color = ReaderTealAccent, maxLines = 1, softWrap = false)
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
                                                    color = if (isSelected) ReaderTealAccent else Color.Gray,
                                                    shape = CircleShape
                                                )
                                                .clickable { selectedColorKey = paletteItem.id }
                                        )
                                    }
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(checked = draftUnderline, onCheckedChange = { draftUnderline = it })
                                    Text("Sublinhar texto no livro", color = Color(0xFFE3ECE7))
                                }
                                OutlinedTextField(
                                    value = draftNote,
                                    onValueChange = { draftNote = it },
                                    label = { Text("Nota pessoal (opcional)") },
                                    maxLines = 3,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Row(
                                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    if (draftSelection.isNotBlank()) {
                                        OutlinedButton(onClick = {
                                            showNoteDialog = false
                                            showStudyBanner = false
                                            startAudio(draftSelection)
                                        }) {
                                            Icon(Icons.Rounded.VolumeUp, contentDescription = null, tint = ReaderTealAccent)
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Ouvir", color = Color(0xFFE3ECE7), maxLines = 1, softWrap = false)
                                        }
                                    }
                                    OutlinedButton(onClick = { openToolInsideApp("DICTIONARY", draftSelection) }) {
                                        Icon(Icons.Rounded.MenuBook, contentDescription = null, tint = ReaderTealAccent)
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Dicionário na App", color = Color(0xFFE3ECE7), maxLines = 1, softWrap = false)
                                    }
                                    OutlinedButton(onClick = { openToolInsideApp("TRANSLATE", draftSelection) }) {
                                        Icon(Icons.Rounded.Translate, contentDescription = null, tint = ReaderTealAccent)
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Tradutor na App", color = Color(0xFFE3ECE7), maxLines = 1, softWrap = false)
                                    }
                                    OutlinedButton(onClick = { openToolInsideApp("SEARCH", draftSelection) }) {
                                        Icon(Icons.Rounded.Search, contentDescription = null, tint = ReaderTealAccent)
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Pesquisar na App", color = Color(0xFFE3ECE7), maxLines = 1, softWrap = false)
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            Button(
                                colors = ButtonDefaults.buttonColors(containerColor = ReaderTealAccent, contentColor = Color(0xFF07120E)),
                                onClick = {
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
                                }
                            ) { Text("Guardar", fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false) }
                        },
                        dismissButton = {
                            TextButton(onClick = { showNoteDialog = false }) {
                                Text("Cancelar", color = Color(0xFFE3ECE7), maxLines = 1, softWrap = false)
                            }
                        }
                    )
                }

                if (showTocSheet) {
                    ModalBottomSheet(
                        onDismissRequest = { showTocSheet = false },
                        containerColor = ReaderDarkSheet
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                            Text(
                                text = "Índice — ${currentBook.title}",
                                fontFamily = FontFamily.Serif,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                color = Color(0xFFE3ECE7)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                                itemsIndexed(currentBook.chapters) { index, chapter ->
                                    val isSelected = index == currentChapterIndex
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(if (isSelected) ReaderTealAccent.copy(alpha = 0.22f) else Color.Transparent)
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
                                            color = if (isSelected) ReaderTealAccent else Color(0xFFE3ECE7),
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

                val effectiveAlign = if (justifyText && displayedText.length >= 180) {
                    TextAlign.Justify
                } else {
                    TextAlign.Start
                }

                val customTextToolbar = remember(
                    currentBook.title,
                    currentChapterIndex,
                    activeChapter.title,
                    selectedColorKey
                ) {
                    object : TextToolbar {
                        override var status: TextToolbarStatus = TextToolbarStatus.Hidden

                        override fun showMenu(
                            rect: Rect,
                            onCopyRequested: (() -> Unit)?,
                            onPasteRequested: (() -> Unit)?,
                            onCutRequested: (() -> Unit)?,
                            onSelectAllRequested: (() -> Unit)?
                        ) {
                            status = TextToolbarStatus.Shown
                            onCopyRequested?.invoke()
                            val clipNow = clipboard.getText()?.text ?: ""
                            if (clipNow.isNotBlank()) {
                                draftSelection = clipNow
                                showStudyBanner = true
                            }
                        }

                        override fun hide() {
                            status = TextToolbarStatus.Hidden
                        }
                    }
                }

                Surface(modifier = Modifier.fillMaxSize(), color = currentTheme.bg) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = horizontalPaddingDp.dp)
                                .then(
                                    if (readingViewMode == ReadingViewMode.SCROLL) {
                                        Modifier.verticalScroll(scrollState)
                                    } else {
                                        Modifier
                                    }
                                )
                                .padding(top = 14.dp, bottom = 148.dp)
                        ) {
                            if (showTopAndBottomBars) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(
                                        shape = CircleShape,
                                        color = ReaderDarkPill,
                                        modifier = Modifier
                                            .size(40.dp)
                                            .clickable { closeReaderBackToLibrary() }
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = Icons.Rounded.ArrowBack,
                                                contentDescription = "Voltar",
                                                tint = currentTheme.text,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = currentBook.title,
                                            fontFamily = FontFamily.Serif,
                                            fontSize = 18.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            color = currentTheme.text
                                        )
                                        val chapterPosText = "${currentChapterIndex + 1}/${currentBook.chapters.size.coerceAtLeast(1)}"
                                        Text(
                                            text = "${activeChapter.title.uppercase()} • $chapterPosText",
                                            fontSize = 11.sp,
                                            letterSpacing = 0.5.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            color = currentTheme.text.copy(alpha = 0.65f)
                                        )
                                    }

                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Surface(
                                            shape = CircleShape,
                                            color = ReaderDarkPill,
                                            modifier = Modifier
                                                .size(38.dp)
                                                .clickable {
                                                    researchQueryInput = draftSelection
                                                    showResearchHubDialog = true
                                                }
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    imageVector = Icons.Rounded.Search,
                                                    contentDescription = "Pesquisar",
                                                    tint = currentTheme.text,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }
                                        Surface(
                                            shape = CircleShape,
                                            color = ReaderDarkPill,
                                            modifier = Modifier
                                                .size(38.dp)
                                                .clickable { showTocSheet = true }
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    imageVector = Icons.Rounded.BookmarkBorder,
                                                    contentDescription = "Índice e marcadores",
                                                    tint = currentTheme.text,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }
                                        Surface(
                                            shape = CircleShape,
                                            color = ReaderDarkPill,
                                            modifier = Modifier
                                                .size(38.dp)
                                                .clickable { showTypeSheet = true }
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Text(
                                                    text = "Tt",
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = currentTheme.text
                                                )
                                            }
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(14.dp))
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
                                                    background = currentTheme.highlightBg.copy(alpha = 0.72f),
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
                                                        background = currentTheme.accent,
                                                        color = Color(0xFF07120E),
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

                            CompositionLocalProvider(LocalTextToolbar provides customTextToolbar) {
                                SelectionContainer(
                                    modifier = Modifier.clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) {
                                        if (barsBehaviorMode == BarsBehaviorMode.TAP_TO_TOGGLE) {
                                            barsVisible = !barsVisible
                                        } else if (barsBehaviorMode == BarsBehaviorMode.HIDE_ON_ADVANCE && barsVisible) {
                                            barsVisible = false
                                        }
                                    }
                                ) {
                                    Text(
                                        text = annotatedText,
                                        fontSize = fontSize.sp,
                                        lineHeight = (fontSize * lineHeightMultiplier).sp,
                                        fontFamily = currentFont.family,
                                        textAlign = effectiveAlign,
                                        color = currentTheme.text
                                    )
                                }
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
                                        Text("Anterior", color = currentTheme.text, maxLines = 1, softWrap = false)
                                    }
                                    Text(
                                        text = "${currentPageIndex + 1} / ${pages.size}",
                                        color = currentTheme.text,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                    Button(
                                        colors = ButtonDefaults.buttonColors(containerColor = currentTheme.accent, contentColor = Color(0xFF07120E)),
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
                                        Text("Seguinte", fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                                    }
                                }
                            }
                        }

                        if (warmthPercent > 0) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color(0xFFFFB347).copy(alpha = (warmthPercent / 100f) * 0.24f))
                            )
                        }

                        if (showStudyBanner) {
                            val quickColors = (ReadingEnhancementManager.presetPaletteColors + customColors).take(5)
                            Surface(
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .padding(top = 68.dp, start = 12.dp, end = 12.dp),
                                shape = RoundedCornerShape(24.dp),
                                color = ReaderDarkSheet,
                                shadowElevation = 10.dp
                            ) {
                                Row(
                                    modifier = Modifier
                                        .horizontalScroll(rememberScrollState())
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
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
                                                    selectedColorKey = cItem.id
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
                                    TextButton(
                                        onClick = {
                                            val item = StudyAnnotation(
                                                bookTitle = currentBook.title,
                                                chapterIndex = currentChapterIndex,
                                                chapterTitle = activeChapter.title,
                                                selectedText = draftSelection.trim(),
                                                colorName = selectedColorKey,
                                                isUnderline = true
                                            )
                                            annotations = listOf(item) + annotations
                                            ReaderStorage.saveAnnotations(context, annotations)
                                            showStudyBanner = false
                                        },
                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text("Sublinhar", color = Color(0xFFE3ECE7), fontSize = 12.sp, maxLines = 1, softWrap = false)
                                    }
                                    IconButton(
                                        onClick = {
                                            showStudyBanner = false
                                            startAudio(draftSelection)
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(Icons.Rounded.VolumeUp, contentDescription = "Ouvir trecho", tint = ReaderTealAccent)
                                    }
                                    IconButton(onClick = { showNoteDialog = true }, modifier = Modifier.size(32.dp)) {
                                        Icon(Icons.Rounded.EditNote, contentDescription = "Nota/Sublinhar/Cores", tint = Color(0xFFE3ECE7))
                                    }
                                    IconButton(
                                        onClick = {
                                            showStudyBanner = false
                                            openToolInsideApp("DICTIONARY", draftSelection)
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(Icons.Rounded.MenuBook, contentDescription = "Dicionário na App", tint = Color(0xFFE3ECE7))
                                    }
                                    IconButton(
                                        onClick = {
                                            showStudyBanner = false
                                            openToolInsideApp("TRANSLATE", draftSelection)
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(Icons.Rounded.Translate, contentDescription = "Tradutor na App", tint = Color(0xFFE3ECE7))
                                    }
                                    IconButton(
                                        onClick = {
                                            showStudyBanner = false
                                            openToolInsideApp("SEARCH", draftSelection)
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(Icons.Rounded.TravelExplore, contentDescription = "Pesquisar na App", tint = Color(0xFFE3ECE7))
                                    }
                                    IconButton(onClick = { showStudyBanner = false }, modifier = Modifier.size(28.dp)) {
                                        Icon(Icons.Rounded.Close, contentDescription = "Fechar", tint = Color(0xFFE3ECE7).copy(alpha = 0.6f))
                                    }
                                }
                            }
                        }

                        if (showTopAndBottomBars) {
                            Surface(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(horizontal = 14.dp, vertical = 12.dp)
                                    .fillMaxWidth()
                                    .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(30.dp)),
                                shape = RoundedCornerShape(30.dp),
                                color = ReaderDarkSheet,
                                shadowElevation = 10.dp
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    TextButton(
                                        onClick = {
                                            listenSheetTab = 0
                                            showListenSheet = true
                                        },
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                    ) {
                                        Text(
                                            text = "${speechRate}×",
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFFE3ECE7),
                                            maxLines = 1,
                                            softWrap = false
                                        )
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
                                        Icon(Icons.Rounded.SkipPrevious, contentDescription = "Capítulo anterior", tint = Color(0xFFE3ECE7))
                                    }

                                    Surface(
                                        shape = CircleShape,
                                        color = ReaderTealAccent,
                                        modifier = Modifier
                                            .size(52.dp)
                                            .clickable {
                                                if (isPlaying) stopAudio() else startAudio(textContent)
                                            }
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                                contentDescription = if (isPlaying) "Pausar" else "Reproduzir",
                                                tint = Color(0xFF07120E),
                                                modifier = Modifier.size(28.dp)
                                            )
                                        }
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
                                        Icon(Icons.Rounded.SkipNext, contentDescription = "Capítulo seguinte", tint = Color(0xFFE3ECE7))
                                    }

                                    IconButton(
                                        onClick = {
                                            listenSheetTab = 0
                                            showListenSheet = true
                                        }
                                    ) {
                                        Icon(Icons.Rounded.MoreHoriz, contentDescription = "Mais opções de voz e leitura", tint = Color(0xFFE3ECE7))
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                val recentMeta = remember(savedBooks, libraryVersion) { savedBooks.firstOrNull() }
                val recentHomeBook = remember(recentMeta, libraryVersion) {
                    recentMeta?.let { meta ->
                        val cleanTitle = formatCleanBookTitle(meta.title)
                        val content = LibraryManager.loadBookContent(context, meta.id)
                        val chTitle = content?.chapters?.getOrNull(meta.lastChapterIndex)?.title
                            ?: "Capítulo ${meta.lastChapterIndex + 1}"
                        val totalCh = (content?.chapters?.size ?: meta.totalChapters.coerceAtLeast(1)).coerceAtLeast(1)
                        val prog = ((meta.lastChapterIndex + 1).toFloat() / totalCh.toFloat()).coerceIn(0f, 1f)
                        HomeRecentBook(
                            id = meta.id,
                            title = cleanTitle,
                            author = "${meta.format} • $chTitle",
                            chapterTitle = chTitle,
                            progressPercent = prog,
                            format = meta.format
                        )
                    }
                }

                Scaffold(
                    containerColor = currentTheme.bg,
                    bottomBar = {
                        NavigationBar(containerColor = currentTheme.barBg) {
                            NavigationBarItem(
                                selected = currentTab == 0,
                                onClick = {
                                    refreshLibraryAndOrg()
                                    currentTab = 0
                                },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = currentTheme.accent,
                                    selectedTextColor = currentTheme.accent,
                                    indicatorColor = currentTheme.accent.copy(alpha = 0.16f),
                                    unselectedIconColor = currentTheme.text.copy(alpha = 0.65f),
                                    unselectedTextColor = currentTheme.text.copy(alpha = 0.65f)
                                ),
                                icon = { Icon(Icons.Rounded.Home, contentDescription = null) },
                                label = { Text("Início", fontSize = 11.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) }
                            )
                            NavigationBarItem(
                                selected = currentTab == 1,
                                onClick = {
                                    refreshLibraryAndOrg()
                                    currentTab = 1
                                },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = currentTheme.accent,
                                    selectedTextColor = currentTheme.accent,
                                    indicatorColor = currentTheme.accent.copy(alpha = 0.16f),
                                    unselectedIconColor = currentTheme.text.copy(alpha = 0.65f),
                                    unselectedTextColor = currentTheme.text.copy(alpha = 0.65f)
                                ),
                                icon = { Icon(Icons.Rounded.LocalLibrary, contentDescription = null) },
                                label = { Text("Biblioteca", fontSize = 11.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) }
                            )
                            NavigationBarItem(
                                selected = currentTab == 2,
                                onClick = { currentTab = 2 },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = currentTheme.accent,
                                    selectedTextColor = currentTheme.accent,
                                    indicatorColor = currentTheme.accent.copy(alpha = 0.16f),
                                    unselectedIconColor = currentTheme.text.copy(alpha = 0.65f),
                                    unselectedTextColor = currentTheme.text.copy(alpha = 0.65f)
                                ),
                                icon = { Icon(Icons.Rounded.Book, contentDescription = null) },
                                label = { Text("Caderno", fontSize = 11.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) }
                            )
                            NavigationBarItem(
                                selected = currentTab == 3,
                                onClick = { currentTab = 3 },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = currentTheme.accent,
                                    selectedTextColor = currentTheme.accent,
                                    indicatorColor = currentTheme.accent.copy(alpha = 0.16f),
                                    unselectedIconColor = currentTheme.text.copy(alpha = 0.65f),
                                    unselectedTextColor = currentTheme.text.copy(alpha = 0.65f)
                                ),
                                icon = { Icon(Icons.Rounded.Settings, contentDescription = null) },
                                label = { Text("Ajustes", fontSize = 11.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) }
                            )
                        }
                    }
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(currentTheme.bg)
                            .padding(innerPadding)
                    ) {
                        when (currentTab) {
                            0 -> {
                                LazyColumn(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = 16.dp, vertical = 14.dp),
                                    verticalArrangement = Arrangement.spacedBy(14.dp)
                                ) {
                                    item {
                                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Text(
                                                text = "GlowReader",
                                                fontFamily = FontFamily.Serif,
                                                fontSize = 28.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = currentTheme.text
                                            )
                                            Text(
                                                text = "Continua a tua leitura, organiza o estudo e consulta tudo sem sair da app.",
                                                fontSize = 13.sp,
                                                color = currentTheme.text.copy(alpha = 0.72f)
                                            )
                                        }
                                    }

                                    item {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                            Card(
                                                colors = CardDefaults.cardColors(containerColor = currentTheme.barBg),
                                                shape = RoundedCornerShape(16.dp),
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .clickable {
                                                        refreshLibraryAndOrg()
                                                        currentTab = 1
                                                    }
                                            ) {
                                                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                    Text(
                                                        text = "${savedBooks.size}",
                                                        fontSize = 22.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = currentTheme.accent
                                                    )
                                                    Text(
                                                        text = "Na biblioteca",
                                                        fontSize = 12.sp,
                                                        color = currentTheme.text.copy(alpha = 0.8f),
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                            }

                                            Card(
                                                colors = CardDefaults.cardColors(containerColor = currentTheme.barBg),
                                                shape = RoundedCornerShape(16.dp),
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .clickable { currentTab = 2 }
                                            ) {
                                                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                    Text(
                                                        text = "${annotations.size}",
                                                        fontSize = 22.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = currentTheme.accent
                                                    )
                                                    Text(
                                                        text = "Notas e marcas",
                                                        fontSize = 12.sp,
                                                        color = currentTheme.text.copy(alpha = 0.8f),
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    item {
                                        Card(
                                            colors = CardDefaults.cardColors(containerColor = currentTheme.barBg),
                                            shape = RoundedCornerShape(20.dp),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .border(1.dp, currentTheme.accent.copy(alpha = 0.25f), RoundedCornerShape(20.dp))
                                        ) {
                                            Column(
                                                modifier = Modifier.padding(16.dp),
                                                verticalArrangement = Arrangement.spacedBy(10.dp)
                                            ) {
                                                Text(
                                                    text = "Continuar leitura",
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = currentTheme.accent
                                                )
                                                if (recentHomeBook != null && recentMeta != null) {
                                                    Text(
                                                        text = recentHomeBook.title,
                                                        fontFamily = FontFamily.Serif,
                                                        fontSize = 18.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = currentTheme.text,
                                                        maxLines = 2,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    Text(
                                                        text = recentHomeBook.author,
                                                        fontSize = 12.sp,
                                                        color = currentTheme.text.copy(alpha = 0.72f),
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    LinearProgressIndicator(
                                                        progress = { recentHomeBook.progressPercent },
                                                        color = currentTheme.accent,
                                                        trackColor = currentTheme.text.copy(alpha = 0.12f),
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .height(6.dp)
                                                            .clip(CircleShape)
                                                    )
                                                    Button(
                                                        colors = ButtonDefaults.buttonColors(containerColor = currentTheme.accent, contentColor = Color(0xFF07120E)),
                                                        onClick = { openBookMeta(recentMeta) },
                                                        modifier = Modifier.fillMaxWidth()
                                                    ) {
                                                        Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                                                        Spacer(modifier = Modifier.width(6.dp))
                                                        Text("Continuar a ler", fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                                                    }
                                                } else {
                                                    Text(
                                                        text = "Ainda não tens nenhum livro aberto. Importa um EPUB, PDF ou TXT para começar.",
                                                        fontSize = 13.sp,
                                                        color = currentTheme.text.copy(alpha = 0.8f)
                                                    )
                                                    Button(
                                                        colors = ButtonDefaults.buttonColors(containerColor = currentTheme.accent, contentColor = Color(0xFF07120E)),
                                                        onClick = {
                                                            filePicker.launch(arrayOf("application/epub+zip", "application/pdf", "text/plain"))
                                                        }
                                                    ) {
                                                        Icon(Icons.Rounded.Add, contentDescription = null)
                                                        Spacer(modifier = Modifier.width(6.dp))
                                                        Text("Importar primeiro livro", fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    item {
                                        Text(
                                            text = "Atalhos rápidos",
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = currentTheme.text
                                        )
                                    }

                                    item {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                            Card(
                                                colors = CardDefaults.cardColors(containerColor = currentTheme.barBg),
                                                shape = RoundedCornerShape(16.dp),
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .clickable {
                                                        refreshLibraryAndOrg()
                                                        currentTab = 1
                                                    }
                                            ) {
                                                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                    Icon(Icons.Rounded.LocalLibrary, contentDescription = null, tint = currentTheme.accent)
                                                    Text("Biblioteca", fontWeight = FontWeight.Bold, color = currentTheme.text, maxLines = 1, softWrap = false)
                                                    Text("Livros e artigos", fontSize = 11.sp, color = currentTheme.text.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                }
                                            }
                                            Card(
                                                colors = CardDefaults.cardColors(containerColor = currentTheme.barBg),
                                                shape = RoundedCornerShape(16.dp),
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .clickable {
                                                        filePicker.launch(arrayOf("application/epub+zip", "application/pdf", "text/plain"))
                                                    }
                                            ) {
                                                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                    Icon(Icons.Rounded.UploadFile, contentDescription = null, tint = currentTheme.accent)
                                                    Text("Importar livro", fontWeight = FontWeight.Bold, color = currentTheme.text, maxLines = 1, softWrap = false)
                                                    Text("EPUB, PDF ou TXT", fontSize = 11.sp, color = currentTheme.text.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                }
                                            }
                                        }
                                    }

                                    item {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                            Card(
                                                colors = CardDefaults.cardColors(containerColor = currentTheme.barBg),
                                                shape = RoundedCornerShape(16.dp),
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .clickable { currentTab = 2 }
                                            ) {
                                                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                    Icon(Icons.Rounded.Book, contentDescription = null, tint = currentTheme.accent)
                                                    Text("Caderno", fontWeight = FontWeight.Bold, color = currentTheme.text, maxLines = 1, softWrap = false)
                                                    Text("Destaques e notas", fontSize = 11.sp, color = currentTheme.text.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                }
                                            }
                                            Card(
                                                colors = CardDefaults.cardColors(containerColor = currentTheme.barBg),
                                                shape = RoundedCornerShape(16.dp),
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .clickable { currentTab = 3 }
                                            ) {
                                                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                    Icon(Icons.Rounded.Settings, contentDescription = null, tint = currentTheme.accent)
                                                    Text("Ajustes", fontWeight = FontWeight.Bold, color = currentTheme.text, maxLines = 1, softWrap = false)
                                                    Text("Leitura, voz e app", fontSize = 11.sp, color = currentTheme.text.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                }
                                            }
                                        }
                                    }

                                    item {
                                        Card(
                                            colors = CardDefaults.cardColors(containerColor = currentTheme.barBg),
                                            shape = RoundedCornerShape(18.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Column(
                                                modifier = Modifier.padding(14.dp),
                                                verticalArrangement = Arrangement.spacedBy(10.dp)
                                            ) {
                                                Text(
                                                    text = "Consulta rápida na app",
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 15.sp,
                                                    color = currentTheme.text
                                                )
                                                OutlinedTextField(
                                                    value = lookupQueryInput,
                                                    onValueChange = { lookupQueryInput = it },
                                                    label = { Text("Palavra, expressão ou tema") },
                                                    singleLine = true,
                                                    modifier = Modifier.fillMaxWidth()
                                                )
                                                Row(
                                                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                ) {
                                                    Button(
                                                        colors = ButtonDefaults.buttonColors(containerColor = currentTheme.accent, contentColor = Color(0xFF07120E)),
                                                        onClick = { openToolInsideApp("DICTIONARY", lookupQueryInput) }
                                                    ) {
                                                        Text("Dicionário", fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                                                    }
                                                    OutlinedButton(onClick = {
                                                        openToolInsideApp("TRANSLATE", lookupQueryInput)
                                                    }) {
                                                        Text("Traduzir", color = currentTheme.text, maxLines = 1, softWrap = false)
                                                    }
                                                    OutlinedButton(onClick = {
                                                        openToolInsideApp("SEARCH", lookupQueryInput)
                                                    }) {
                                                        Text("Pesquisar", color = currentTheme.text, maxLines = 1, softWrap = false)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            1 -> {
                                if (isImporting) {
                                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        CircularProgressIndicator(color = currentTheme.accent)
                                    }
                                } else {
                                    val secretBookIds = remember(savedBooks, libraryVersion) {
                                        LibraryManager.getSecretBookIds(context)
                                    }
                                    val shelfFilteredBooks = remember(savedBooks, showSecretShelf, secretBookIds) {
                                        if (showSecretShelf) {
                                            savedBooks.filter { it.id in secretBookIds }
                                        } else {
                                            savedBooks.filterNot { it.id in secretBookIds }
                                        }
                                    }
                                    val visibleBooks = remember(
                                        shelfFilteredBooks,
                                        orgMetaMap,
                                        selectedCategory,
                                        librarySearchQuery,
                                        formatFilter,
                                        sortOption,
                                        libraryVersion
                                    ) {
                                        LibraryOrganizationManager.filterAndSortBooks(
                                            books = shelfFilteredBooks,
                                            metaMap = orgMetaMap,
                                            query = librarySearchQuery,
                                            folderFilter = if (selectedCategory == "Todos") "Todos" else selectedCategory,
                                            formatFilter = if (formatFilter == "Todos") "TODOS" else formatFilter,
                                            sortOption = sortOption
                                        )
                                    }
                                    val duplicateCount = remember(savedBooks, libraryVersion) {
                                        LibraryOrganizationManager.findDuplicateGroups(savedBooks).size
                                    }

                                    @Composable
                                    fun BookCardContent(book: SavedBookMeta, compact: Boolean) {
                                        val cleanTitle = remember(book.title) { formatCleanBookTitle(book.title) }
                                        val isSecretBook = book.id in secretBookIds
                                        val progressPct = (LibraryManager.estimateBookProgress(book) * 100).toInt()
                                        val coverImageBitmap = remember(book.coverPath, book.id) {
                                            if (book.coverPath.isNotBlank()) {
                                                val file = File(book.coverPath)
                                                if (file.exists()) {
                                                    BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
                                                } else {
                                                    null
                                                }
                                            } else {
                                                null
                                            }
                                        }
                                        val coverGradient = when (book.format.uppercase(Locale.ROOT)) {
                                            "EPUB" -> Brush.verticalGradient(listOf(Color(0xFF1F4E43), Color(0xFF10241F)))
                                            "PDF" -> Brush.verticalGradient(listOf(Color(0xFF6A3B28), Color(0xFF261711)))
                                            "WEB" -> Brush.verticalGradient(listOf(Color(0xFF28466A), Color(0xFF111D2B)))
                                            else -> Brush.verticalGradient(listOf(Color(0xFF4D3B27), Color(0xFF1E1710)))
                                        }

                                        Box(
                                            modifier = Modifier
                                                .then(
                                                    if (libraryLayoutMode == LibraryLayoutMode.GRID) {
                                                        Modifier
                                                            .fillMaxWidth()
                                                            .height(232.dp)
                                                    } else if (compact) {
                                                        Modifier
                                                            .fillMaxWidth()
                                                            .height(74.dp)
                                                    } else {
                                                        Modifier
                                                            .fillMaxWidth()
                                                            .height(106.dp)
                                                    }
                                                )
                                                .clip(RoundedCornerShape(16.dp))
                                                .background(coverGradient)
                                                .clickable { openBookMeta(book) }
                                        ) {
                                            if (coverImageBitmap != null) {
                                                Image(
                                                    bitmap = coverImageBitmap,
                                                    contentDescription = cleanTitle,
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier.fillMaxSize()
                                                )
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxSize()
                                                        .background(
                                                            Brush.verticalGradient(
                                                                colors = listOf(
                                                                    Color.Black.copy(alpha = 0.08f),
                                                                    Color.Black.copy(alpha = 0.22f),
                                                                    Color.Black.copy(alpha = 0.84f)
                                                                )
                                                            )
                                                        )
                                                )
                                            }

                                            if (libraryLayoutMode == LibraryLayoutMode.GRID) {
                                                Column(
                                                    modifier = Modifier
                                                        .fillMaxSize()
                                                        .padding(11.dp),
                                                    verticalArrangement = Arrangement.SpaceBetween
                                                ) {
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Surface(
                                                            shape = RoundedCornerShape(8.dp),
                                                            color = Color.Black.copy(alpha = 0.65f)
                                                        ) {
                                                            Text(
                                                                text = if (progressPct >= 100) "CONCLUÍDO" else "$progressPct%",
                                                                color = Color.White,
                                                                fontSize = 11.sp,
                                                                fontWeight = FontWeight.Bold,
                                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                                            )
                                                        }
                                                        Row(
                                                            modifier = Modifier
                                                                .clip(RoundedCornerShape(10.dp))
                                                                .background(Color.Black.copy(alpha = 0.55f))
                                                                .padding(horizontal = 4.dp, vertical = 2.dp),
                                                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                                                        ) {
                                                            IconButton(
                                                                onClick = { categoryTargetBook = book },
                                                                modifier = Modifier.size(26.dp)
                                                            ) {
                                                                Icon(Icons.Rounded.Label, contentDescription = "Categoria", tint = Color.White, modifier = Modifier.size(16.dp))
                                                            }
                                                            IconButton(
                                                                onClick = { requestSecretShelfAccess(book) },
                                                                modifier = Modifier.size(26.dp)
                                                            ) {
                                                                Icon(
                                                                    imageVector = if (isSecretBook) Icons.Rounded.LockOpen else Icons.Rounded.Lock,
                                                                    contentDescription = "Estante secreta",
                                                                    tint = Color.White,
                                                                    modifier = Modifier.size(16.dp)
                                                                )
                                                            }
                                                        }
                                                    }

                                                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                                        Text(
                                                            text = cleanTitle,
                                                            fontWeight = FontWeight.Bold,
                                                            fontSize = 14.sp,
                                                            color = Color.White,
                                                            maxLines = 2,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                            Surface(
                                                                shape = RoundedCornerShape(6.dp),
                                                                color = Color.Black.copy(alpha = 0.62f)
                                                            ) {
                                                                Text(
                                                                    text = book.format,
                                                                    color = Color.White,
                                                                    fontSize = 10.sp,
                                                                    fontWeight = FontWeight.SemiBold,
                                                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                                                                )
                                                            }
                                                            Surface(
                                                                shape = RoundedCornerShape(6.dp),
                                                                color = Color.Black.copy(alpha = 0.62f)
                                                            ) {
                                                                Text(
                                                                    text = book.folderName.ifBlank { "Geral" },
                                                                    color = Color.White,
                                                                    fontSize = 10.sp,
                                                                    maxLines = 1,
                                                                    overflow = TextOverflow.Ellipsis,
                                                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                                                                )
                                                            }
                                                        }
                                                        Row(
                                                            modifier = Modifier.fillMaxWidth(),
                                                            horizontalArrangement = Arrangement.SpaceBetween,
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            LinearProgressIndicator(
                                                                progress = { (progressPct / 100f).coerceIn(0f, 1f) },
                                                                color = currentTheme.accent,
                                                                modifier = Modifier
                                                                    .weight(1f)
                                                                    .height(4.dp)
                                                                    .clip(CircleShape)
                                                            )
                                                            IconButton(
                                                                onClick = {
                                                                    LibraryManager.deleteBook(context, book.id)
                                                                    refreshLibraryAndOrg()
                                                                },
                                                                modifier = Modifier.size(28.dp)
                                                            ) {
                                                                Icon(Icons.Rounded.Delete, contentDescription = "Remover", tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(18.dp))
                                                            }
                                                        }
                                                    }
                                                }
                                            } else {
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxSize()
                                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Box(
                                                        modifier = Modifier
                                                            .size(if (compact) 44.dp else 64.dp)
                                                            .clip(RoundedCornerShape(10.dp))
                                                            .background(Color.Black.copy(alpha = 0.35f)),
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        if (coverImageBitmap != null) {
                                                            Image(
                                                                bitmap = coverImageBitmap,
                                                                contentDescription = cleanTitle,
                                                                contentScale = ContentScale.Crop,
                                                                modifier = Modifier.fillMaxSize()
                                                            )
                                                        } else {
                                                            Text(
                                                                text = book.format,
                                                                color = Color.White,
                                                                fontSize = 10.sp,
                                                                fontWeight = FontWeight.Bold
                                                            )
                                                        }
                                                    }
                                                    Spacer(modifier = Modifier.width(10.dp))
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text(
                                                            text = cleanTitle,
                                                            fontWeight = FontWeight.Bold,
                                                            fontSize = if (compact) 14.sp else 15.sp,
                                                            color = Color.White,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                        if (!compact) {
                                                            Text(
                                                                text = "${book.format} • ${book.folderName.ifBlank { "Geral" }} • $progressPct%",
                                                                fontSize = 12.sp,
                                                                color = Color.White.copy(alpha = 0.8f),
                                                                maxLines = 1,
                                                                overflow = TextOverflow.Ellipsis
                                                            )
                                                        }
                                                    }
                                                    if (!compact) {
                                                        IconButton(onClick = { categoryTargetBook = book }) {
                                                            Icon(Icons.Rounded.Label, contentDescription = "Categoria", tint = Color.White)
                                                        }
                                                        IconButton(onClick = { requestSecretShelfAccess(book) }) {
                                                            Icon(
                                                                imageVector = if (isSecretBook) Icons.Rounded.LockOpen else Icons.Rounded.Lock,
                                                                contentDescription = "Estante secreta",
                                                                tint = Color.White
                                                            )
                                                        }
                                                    }
                                                    IconButton(onClick = {
                                                        LibraryManager.deleteBook(context, book.id)
                                                        refreshLibraryAndOrg()
                                                    }) {
                                                        Icon(Icons.Rounded.Delete, contentDescription = "Remover", tint = Color.White)
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    @Composable
                                    fun LibraryHeaderControls() {
                                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = if (showSecretShelf) "Estante Secreta" else "Biblioteca",
                                                    fontFamily = FontFamily.Serif,
                                                    fontSize = 24.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = currentTheme.text,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.weight(1f)
                                                )
                                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                    IconButton(onClick = {
                                                        libraryLayoutMode = when (libraryLayoutMode) {
                                                            LibraryLayoutMode.GRID -> LibraryLayoutMode.LIST
                                                            LibraryLayoutMode.LIST -> LibraryLayoutMode.COMPACT
                                                            LibraryLayoutMode.COMPACT -> LibraryLayoutMode.GRID
                                                        }
                                                    }) {
                                                        Icon(
                                                            imageVector = when (libraryLayoutMode) {
                                                                LibraryLayoutMode.GRID -> Icons.Rounded.GridView
                                                                LibraryLayoutMode.LIST -> Icons.Rounded.ViewAgenda
                                                                LibraryLayoutMode.COMPACT -> Icons.Rounded.ViewList
                                                            },
                                                            contentDescription = "Vista: ${libraryLayoutMode.label}",
                                                            tint = currentTheme.text
                                                        )
                                                    }
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
                                                    FilledTonalButton(
                                                        onClick = { showWebImportDialog = true },
                                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                                    ) {
                                                        Icon(Icons.Rounded.Language, contentDescription = null, modifier = Modifier.size(16.dp))
                                                        Spacer(modifier = Modifier.width(4.dp))
                                                        Text("URL", fontSize = 12.sp, maxLines = 1, softWrap = false)
                                                    }
                                                    Button(
                                                        colors = ButtonDefaults.buttonColors(containerColor = currentTheme.accent, contentColor = Color(0xFF07120E)),
                                                        onClick = {
                                                            filePicker.launch(arrayOf("application/epub+zip", "application/pdf", "text/plain"))
                                                        },
                                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                                    ) {
                                                        Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                                        Spacer(modifier = Modifier.width(4.dp))
                                                        Text("Ficheiro", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                                                    }
                                                }
                                            }

                                            OutlinedTextField(
                                                value = librarySearchQuery,
                                                onValueChange = { librarySearchQuery = it },
                                                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                                                label = { Text("Pesquisar título, formato ou categoria") },
                                                singleLine = true,
                                                modifier = Modifier.fillMaxWidth()
                                            )

                                            if (!showSecretShelf) {
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
                                                            label = { Text(cat, maxLines = 1, softWrap = false) }
                                                        )
                                                    }
                                                    FilterChip(
                                                        selected = false,
                                                        onClick = {
                                                            newCategoryName = ""
                                                            showAddCategoryDialog = true
                                                        },
                                                        label = { Text("+ Categoria", maxLines = 1, softWrap = false) }
                                                    )
                                                }
                                            }

                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .horizontalScroll(rememberScrollState()),
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                LibraryLayoutMode.entries.forEach { modeOpt ->
                                                    FilterChip(
                                                        selected = libraryLayoutMode == modeOpt,
                                                        onClick = { libraryLayoutMode = modeOpt },
                                                        label = { Text(modeOpt.label, maxLines = 1, softWrap = false) }
                                                    )
                                                }
                                                listOf("Todos", "EPUB", "PDF", "TXT", "WEB").forEach { fmt ->
                                                    FilterChip(
                                                        selected = formatFilter == fmt,
                                                        onClick = { formatFilter = fmt },
                                                        label = { Text(fmt, maxLines = 1, softWrap = false) }
                                                    )
                                                }
                                                LibrarySortOption.entries.forEach { opt ->
                                                    FilterChip(
                                                        selected = sortOption == opt,
                                                        onClick = { sortOption = opt },
                                                        label = { Text(opt.label, maxLines = 1, softWrap = false) }
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    if (libraryLayoutMode == LibraryLayoutMode.GRID) {
                                        LazyVerticalGrid(
                                            columns = GridCells.Fixed(2),
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .background(currentTheme.bg)
                                                .padding(16.dp),
                                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                                            verticalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                            item(span = { GridItemSpan(maxLineSpan) }) {
                                                LibraryHeaderControls()
                                            }
                                            if (visibleBooks.isEmpty()) {
                                                item(span = { GridItemSpan(maxLineSpan) }) {
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
                                                    BookCardContent(book = book, compact = false)
                                                }
                                            }
                                        }
                                    } else {
                                        LazyColumn(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .background(currentTheme.bg)
                                                .padding(16.dp),
                                            verticalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            item {
                                                LibraryHeaderControls()
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
                                                    BookCardContent(
                                                        book = book,
                                                        compact = libraryLayoutMode == LibraryLayoutMode.COMPACT
                                                    )
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
                                        Text(
                                            text = "Caderno",
                                            fontFamily = FontFamily.Serif,
                                            fontSize = 24.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = currentTheme.text
                                        )
                                    }

                                    item {
                                        Card(
                                            colors = CardDefaults.cardColors(containerColor = currentTheme.barBg),
                                            shape = RoundedCornerShape(18.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                                                Row(
                                                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                ) {
                                                    OutlinedButton(onClick = {
                                                        val res = StudyNotebookManager.exportAnnotationsToFile(context, annotations)
                                                        exportStatusMessage = if (res.isSuccess) {
                                                            "Exportado: ${res.getOrNull()?.name}"
                                                        } else {
                                                            "Falha ao exportar."
                                                        }
                                                    }) {
                                                        Text("Exportar .MD", color = currentTheme.text, maxLines = 1, softWrap = false)
                                                    }
                                                    OutlinedButton(onClick = {
                                                        val md = StudyNotebookManager.formatAnnotationsAsMarkdown(annotations)
                                                        clipboard.setText(AnnotatedString(md))
                                                        exportStatusMessage = "Caderno copiado para a área de transferência."
                                                    }) {
                                                        Text("Copiar resumo", color = currentTheme.text, maxLines = 1, softWrap = false)
                                                    }
                                                }
                                                if (exportStatusMessage != null) {
                                                    Text(exportStatusMessage!!, fontSize = 12.sp, color = currentTheme.accent)
                                                }
                                            }
                                        }
                                    }

                                    item {
                                        Card(
                                            colors = CardDefaults.cardColors(containerColor = currentTheme.barBg),
                                            shape = RoundedCornerShape(18.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Text("Dicionário, Tradutor, IA e Pesquisa na App", fontWeight = FontWeight.Bold, color = currentTheme.text)
                                                OutlinedTextField(
                                                    value = lookupQueryInput,
                                                    onValueChange = { lookupQueryInput = it },
                                                    label = { Text("Palavra, conceito ou pergunta") },
                                                    singleLine = true,
                                                    modifier = Modifier.fillMaxWidth()
                                                )
                                                Row(
                                                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Button(
                                                        colors = ButtonDefaults.buttonColors(containerColor = currentTheme.accent, contentColor = Color(0xFF07120E)),
                                                        onClick = { openToolInsideApp("DICTIONARY", lookupQueryInput) }
                                                    ) {
                                                        Text("Dicionário", fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                                                    }
                                                    OutlinedButton(onClick = {
                                                        openToolInsideApp("TRANSLATE", lookupQueryInput)
                                                    }) {
                                                        Text("Tradutor", color = currentTheme.text, maxLines = 1, softWrap = false)
                                                    }
                                                    OutlinedButton(onClick = {
                                                        openToolInsideApp("SEARCH", lookupQueryInput)
                                                    }) {
                                                        Text("Pesquisar", color = currentTheme.text, maxLines = 1, softWrap = false)
                                                    }
                                                    OutlinedButton(onClick = {
                                                        openResearchTarget(ResearchToolTarget.AI_ASSISTANT, lookupQueryInput)
                                                    }) {
                                                        Text("IA na App", color = currentTheme.text, maxLines = 1, softWrap = false)
                                                    }
                                                    OutlinedButton(onClick = { showColorMixerDialog = true }) {
                                                        Text("Paleta", color = currentTheme.text, maxLines = 1, softWrap = false)
                                                    }
                                                }
                                                Row(
                                                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                ) {
                                                    listOf("pt", "en", "es", "fr").forEach { langCode ->
                                                        FilterChip(
                                                            selected = translationTargetLang == langCode,
                                                            onClick = { translationTargetLang = langCode },
                                                            label = { Text("→ ${langCode.uppercase()}", maxLines = 1, softWrap = false) }
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
                                                shape = RoundedCornerShape(16.dp),
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
                                                        Text(formatCleanBookTitle(ann.bookTitle), fontWeight = FontWeight.Bold, fontSize = 14.sp, color = currentTheme.text)
                                                        if (ann.selectedText.isNotBlank()) {
                                                            Text(ann.selectedText, fontSize = 13.sp, color = currentTheme.text.copy(alpha = 0.85f))
                                                        }
                                                        if (ann.note.isNotBlank()) {
                                                            Text("Nota: ${ann.note}", fontSize = 12.sp, color = currentTheme.accent)
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
                                val filteredVoices = remember(availableVoices, voiceLangFilter) {
                                    if (voiceLangFilter == "all") {
                                        availableVoices
                                    } else {
                                        availableVoices.filter { it.locale.language.equals(voiceLangFilter, ignoreCase = true) }
                                    }
                                }
                                val filteredEdgeVoices = remember(edgeVoiceLangFilter) {
                                    when (edgeVoiceLangFilter) {
                                        "pt" -> VoiceEngineManager.EDGE_VOICES_CATALOG.filter { it.languageFilter == "pt" }
                                        "en" -> VoiceEngineManager.EDGE_VOICES_CATALOG.filter { it.languageFilter == "en" }
                                        "multi" -> VoiceEngineManager.EDGE_VOICES_CATALOG.filter { it.isMultilingual }
                                        else -> VoiceEngineManager.EDGE_VOICES_CATALOG
                                    }
                                }
                                val downloadedCatalog = remember(catalogRefreshTick) {
                                    VoiceEngineManager.OFFLINE_VOICE_CATALOG.filter { voiceEngine.isCatalogItemDownloaded(it) }
                                }
                                val availableToDownloadCatalog = remember(catalogRefreshTick) {
                                    VoiceEngineManager.OFFLINE_VOICE_CATALOG.filterNot { voiceEngine.isCatalogItemDownloaded(it) }
                                }

                                LazyColumn(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(currentTheme.bg)
                                        .padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(14.dp)
                                ) {
                                    item {
                                        Text(
                                            text = "Ajustes",
                                            fontFamily = FontFamily.Serif,
                                            fontSize = 24.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = currentTheme.text
                                        )
                                    }
                                    item {
                                        Text("Tema da leitura", fontWeight = FontWeight.SemiBold, color = currentTheme.text)
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Row(
                                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            ReaderTheme.entries.forEach { themeOption ->
                                                FilterChip(
                                                    selected = currentTheme == themeOption,
                                                    onClick = { currentTheme = themeOption },
                                                    label = { Text(themeOption.label, maxLines = 1, softWrap = false) }
                                                )
                                            }
                                        }
                                    }
                                    item {
                                        Text("Layout da biblioteca", fontWeight = FontWeight.SemiBold, color = currentTheme.text)
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Row(
                                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            LibraryLayoutMode.entries.forEach { modeOpt ->
                                                FilterChip(
                                                    selected = libraryLayoutMode == modeOpt,
                                                    onClick = { libraryLayoutMode = modeOpt },
                                                    label = { Text(modeOpt.label, maxLines = 1, softWrap = false) }
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
                                        Row(
                                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            ReadingViewMode.entries.forEach { modeOption ->
                                                FilterChip(
                                                    selected = readingViewMode == modeOption,
                                                    onClick = { readingViewMode = modeOption },
                                                    label = { Text(modeOption.label, maxLines = 1, softWrap = false) }
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
                                        Row(
                                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            OutlinedButton(onClick = {
                                                pendingSecretBookToggle = null
                                                secretPinInput = ""
                                                secretPinError = null
                                                showSecretPinDialog = true
                                            }) {
                                                Text(
                                                    text = if (LibraryManager.hasSecretPin(context)) "Alterar PIN" else "Definir PIN",
                                                    color = currentTheme.text,
                                                    maxLines = 1,
                                                    softWrap = false
                                                )
                                            }
                                            OutlinedButton(onClick = {
                                                LibraryManager.lockSecretShelf()
                                                showSecretShelf = false
                                            }) {
                                                Text("Bloquear estante", color = currentTheme.text, maxLines = 1, softWrap = false)
                                            }
                                        }
                                    }
                                    item {
                                        Text("Motor de Voz e Downloads", fontWeight = FontWeight.SemiBold, color = currentTheme.text)
                                        Text(
                                            text = voiceEngine.getAvailableStorageGb(),
                                            fontSize = 12.sp,
                                            color = currentTheme.text.copy(alpha = 0.7f)
                                        )
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Row(
                                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            OutlinedButton(onClick = {
                                                val intent = Intent("com.android.settings.TTS_SETTINGS").apply {
                                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                }
                                                runCatching { context.startActivity(intent) }
                                            }) {
                                                Text("Motor TTS Sistema", color = currentTheme.text, maxLines = 1, softWrap = false)
                                            }
                                            OutlinedButton(onClick = {
                                                val intent = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).apply {
                                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                }
                                                runCatching { context.startActivity(intent) }
                                            }) {
                                                Text("Vozes do Sistema", color = currentTheme.text, maxLines = 1, softWrap = false)
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(6.dp))
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

                                        Spacer(modifier = Modifier.height(8.dp))
                                        Card(
                                            colors = CardDefaults.cardColors(containerColor = currentTheme.barBg),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Column(
                                                modifier = Modifier.padding(12.dp),
                                                verticalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Text(
                                                    text = "Vozes Edge TTS (${VoiceEngineManager.EDGE_VOICES_CATALOG.size} vozes + Multilingual)",
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 14.sp,
                                                    color = currentTheme.text
                                                )
                                                Row(
                                                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                ) {
                                                    listOf(
                                                        "all" to "Todas",
                                                        "multi" to "Multi",
                                                        "pt" to "PT",
                                                        "en" to "EN"
                                                    ).forEach { (key, label) ->
                                                        FilterChip(
                                                            selected = edgeVoiceLangFilter == key,
                                                            onClick = { edgeVoiceLangFilter = key },
                                                            label = { Text(label, maxLines = 1, softWrap = false) }
                                                        )
                                                    }
                                                }
                                                filteredEdgeVoices.forEach { edgeVoice ->
                                                    Row(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .clickable {
                                                                selectedProvider = VoiceProviderType.EDGE_ONLINE
                                                                selectedEdgeVoice = edgeVoice.shortName
                                                                syncVoiceEngineConfig()
                                                            }
                                                            .padding(vertical = 2.dp),
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        RadioButton(
                                                            selected = selectedEdgeVoice == edgeVoice.shortName,
                                                            onClick = {
                                                                selectedProvider = VoiceProviderType.EDGE_ONLINE
                                                                selectedEdgeVoice = edgeVoice.shortName
                                                                syncVoiceEngineConfig()
                                                            }
                                                        )
                                                        Column(modifier = Modifier.weight(1f)) {
                                                            Text(edgeVoice.displayName, fontSize = 13.sp, color = currentTheme.text)
                                                            Text(
                                                                text = "${edgeVoice.shortName} • ${edgeVoice.gender}",
                                                                fontSize = 11.sp,
                                                                color = currentTheme.text.copy(alpha = 0.65f)
                                                            )
                                                        }
                                                    }
                                                }
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                ) {
                                                    OutlinedButton(onClick = {
                                                        scope.launch {
                                                            val ok = voiceEngine.testOnlineEndpoint()
                                                            onlineTestResult = if (ok) "Ligação Edge/Online ativa." else "Sem resposta de rede."
                                                        }
                                                    }) {
                                                        Text("Testar Edge", color = currentTheme.text, maxLines = 1, softWrap = false)
                                                    }
                                                    if (onlineTestResult != null) {
                                                        Text(onlineTestResult!!, fontSize = 12.sp, color = currentTheme.text)
                                                    }
                                                }
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(10.dp))
                                        Card(
                                            colors = CardDefaults.cardColors(containerColor = currentTheme.barBg),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Column(
                                                modifier = Modifier.padding(12.dp),
                                                verticalArrangement = Arrangement.spacedBy(10.dp)
                                            ) {
                                                Text(
                                                    text = "Vozes Offline (No aparelho / Para baixar)",
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 15.sp,
                                                    color = currentTheme.text
                                                )

                                                Text(
                                                    text = "No aparelho (${downloadedCatalog.size})",
                                                    fontWeight = FontWeight.SemiBold,
                                                    fontSize = 13.sp,
                                                    color = currentTheme.accent
                                                )
                                                if (downloadedCatalog.isEmpty()) {
                                                    Text(
                                                        text = "Nenhuma voz descarregada ainda. Toca em \"Baixar\" abaixo.",
                                                        fontSize = 12.sp,
                                                        color = currentTheme.text.copy(alpha = 0.7f)
                                                    )
                                                } else {
                                                    downloadedCatalog.forEach { item ->
                                                        Row(
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .clickable {
                                                                    selectedOfflineModelId = item.id
                                                                    selectedProvider = providerForCatalogItem(item)
                                                                    syncVoiceEngineConfig()
                                                                }
                                                                .padding(vertical = 4.dp),
                                                            horizontalArrangement = Arrangement.SpaceBetween,
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            Row(
                                                                verticalAlignment = Alignment.CenterVertically,
                                                                modifier = Modifier.weight(1f)
                                                            ) {
                                                                RadioButton(
                                                                    selected = selectedOfflineModelId == item.id,
                                                                    onClick = {
                                                                        selectedOfflineModelId = item.id
                                                                        selectedProvider = providerForCatalogItem(item)
                                                                        syncVoiceEngineConfig()
                                                                    }
                                                                )
                                                                Column {
                                                                    Text(item.title, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = currentTheme.text)
                                                                    Text(
                                                                        text = "${item.subtitle} • ${voiceEngine.getCatalogItemSizeLabel(item)}",
                                                                        fontSize = 11.sp,
                                                                        color = currentTheme.text.copy(alpha = 0.7f)
                                                                    )
                                                                }
                                                            }
                                                            IconButton(onClick = {
                                                                voiceEngine.deleteCatalogItem(item)
                                                                catalogRefreshTick++
                                                            }) {
                                                                Icon(Icons.Rounded.DeleteOutline, contentDescription = "Apagar voz", tint = currentTheme.text)
                                                            }
                                                        }
                                                    }
                                                }

                                                HorizontalDivider()

                                                Text(
                                                    text = "Para baixar (${availableToDownloadCatalog.size})",
                                                    fontWeight = FontWeight.SemiBold,
                                                    fontSize = 13.sp,
                                                    color = currentTheme.accent
                                                )
                                                availableToDownloadCatalog.forEach { item ->
                                                    val isThisDownloading = downloadingItemId == item.id
                                                    Row(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .padding(vertical = 4.dp),
                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Column(modifier = Modifier.weight(1f)) {
                                                            Text(item.title, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = currentTheme.text)
                                                            Text(
                                                                text = if (isThisDownloading) {
                                                                    "A baixar... $downloadingPercent%"
                                                                } else {
                                                                    "${item.subtitle}\nNão baixado • ${item.approxSizeLabel}"
                                                                },
                                                                fontSize = 11.sp,
                                                                color = currentTheme.text.copy(alpha = 0.72f)
                                                            )
                                                        }
                                                        Button(
                                                            colors = ButtonDefaults.buttonColors(containerColor = currentTheme.accent, contentColor = Color(0xFF07120E)),
                                                            onClick = {
                                                                downloadingItemId = item.id
                                                                downloadingPercent = 1
                                                                voiceDownloadStatus = null
                                                                scope.launch {
                                                                    val mainRes = voiceEngine.downloadFileFromUrl(
                                                                        urlStr = item.downloadUrl,
                                                                        targetSubdir = item.targetSubdir,
                                                                        outputFileName = item.fileName,
                                                                        onProgressPercent = { pct -> downloadingPercent = pct }
                                                                    )
                                                                    if (mainRes.isSuccess && item.companionUrl != null && item.companionFileName != null) {
                                                                        voiceEngine.downloadFileFromUrl(
                                                                            urlStr = item.companionUrl,
                                                                            targetSubdir = item.targetSubdir,
                                                                            outputFileName = item.companionFileName
                                                                        )
                                                                    }
                                                                    downloadingItemId = null
                                                                    catalogRefreshTick++
                                                                    if (mainRes.isSuccess) {
                                                                        selectedOfflineModelId = item.id
                                                                        selectedProvider = providerForCatalogItem(item)
                                                                        syncVoiceEngineConfig()
                                                                        voiceDownloadStatus = "Voz ${item.title} instalada no aparelho!"
                                                                    } else {
                                                                        voiceDownloadStatus = "Falha ao baixar ${item.title}: ${mainRes.exceptionOrNull()?.message}"
                                                                    }
                                                                }
                                                            },
                                                            enabled = downloadingItemId == null,
                                                            shape = RoundedCornerShape(20.dp)
                                                        ) {
                                                            Text(
                                                                text = if (isThisDownloading) "$downloadingPercent%" else "Baixar",
                                                                fontWeight = FontWeight.SemiBold,
                                                                maxLines = 1,
                                                                softWrap = false
                                                            )
                                                        }
                                                    }
                                                }

                                                HorizontalDivider()
                                                Text(
                                                    text = "Clonador de Voz (.zip)",
                                                    fontWeight = FontWeight.SemiBold,
                                                    fontSize = 13.sp,
                                                    color = currentTheme.text
                                                )
                                                OutlinedTextField(
                                                    value = voiceZipSourceInput,
                                                    onValueChange = { voiceZipSourceInput = it },
                                                    label = { Text("URL ou caminho local do .zip") },
                                                    singleLine = true,
                                                    modifier = Modifier.fillMaxWidth()
                                                )
                                                Row(
                                                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                ) {
                                                    OutlinedButton(onClick = {
                                                        zipPicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
                                                    }) {
                                                        Text("Escolher .ZIP", color = currentTheme.text, maxLines = 1, softWrap = false)
                                                    }
                                                    OutlinedButton(onClick = {
                                                        voiceDownloadStatus = "A baixar e extrair .zip..."
                                                        scope.launch {
                                                            val res = voiceEngine.downloadAndExtractVoiceZip(
                                                                zipUrlOrLocalPath = voiceZipSourceInput,
                                                                voiceName = "clone_${System.currentTimeMillis()}"
                                                            )
                                                            catalogRefreshTick++
                                                            voiceDownloadStatus = if (res.isSuccess) {
                                                                selectedProvider = VoiceProviderType.POCKET_TTS_LOCAL
                                                                syncVoiceEngineConfig()
                                                                "Pacote .zip extraído (${voiceEngine.activeClonedVoiceName})"
                                                            } else {
                                                                "Erro .zip: ${res.exceptionOrNull()?.message}"
                                                            }
                                                        }
                                                    }) {
                                                        Text("Extrair .ZIP", color = currentTheme.text, maxLines = 1, softWrap = false)
                                                    }
                                                }
                                                if (voiceDownloadStatus != null) {
                                                    Text(voiceDownloadStatus!!, fontSize = 12.sp, color = currentTheme.accent)
                                                }
                                            }
                                        }

                                        if (availableVoices.isNotEmpty()) {
                                            Spacer(modifier = Modifier.height(8.dp))
                                            Text(
                                                text = "Vozes do Sistema Android (${filteredVoices.size} de ${availableVoices.size}):",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = currentTheme.text
                                            )
                                            Row(
                                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                listOf("pt" to "PT", "en" to "EN", "es" to "ES", "fr" to "FR", "all" to "Todas").forEach { (code, lbl) ->
                                                    FilterChip(
                                                        selected = voiceLangFilter == code,
                                                        onClick = { voiceLangFilter = code },
                                                        label = { Text(lbl, maxLines = 1, softWrap = false) }
                                                    )
                                                }
                                            }
                                            filteredVoices.take(10).forEach { v ->
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
                                        listOf("Dicionário / Tradutor", "Priberam / Dicio", "Google Tradutor").forEach { provider ->
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
