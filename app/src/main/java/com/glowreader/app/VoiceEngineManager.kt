package com.glowreader.app

import android.content.Context
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipInputStream
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

enum class VoiceProviderType(
    val id: String,
    val title: String,
    val subtitle: String,
    val requiresNetworkOrModel: Boolean
) {
    ANDROID_SYSTEM(
        id = "android_tts",
        title = "Voz do Sistema Android",
        subtitle = "Motor TTS nativo do aparelho com vozes instaladas.",
        requiresNetworkOrModel = false
    ),
    EDGE_ONLINE(
        id = "edge_tts_online",
        title = "Edge TTS (Todas as Vozes + Multilingual)",
        subtitle = "Catálogo completo de vozes neurais Edge (PT, EN, ES, FR e Multilingual).",
        requiresNetworkOrModel = true
    ),
    PIPER_LOCAL(
        id = "piper_tts",
        title = "Piper / Supertonic (Offline no Aparelho)",
        subtitle = "Modelos locais leves e rápidos guardados no armazenamento da app.",
        requiresNetworkOrModel = true
    ),
    KOKORO_LOCAL(
        id = "kokoro_tts",
        title = "Kokoro TTS (Modelo Neural Compacto)",
        subtitle = "Modelos dedicados Kokoro guardados na pasta kokoro_models.",
        requiresNetworkOrModel = true
    ),
    POCKET_TTS_LOCAL(
        id = "pocket_tts",
        title = "Pocket TTS & Clonador (.zip)",
        subtitle = "Motor Pocket TTS independente e pacotes de clonagem de voz (.zip).",
        requiresNetworkOrModel = true
    )
}

data class DownloadableVoiceCatalogItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val languageTag: String,
    val approxSizeLabel: String,
    val engineLabel: String,
    val downloadUrl: String,
    val targetSubdir: String,
    val fileName: String,
    val companionUrl: String? = null,
    val companionFileName: String? = null
)

data class EdgeVoiceItem(
    val shortName: String,
    val displayName: String,
    val localeCode: String,
    val languageFilter: String,
    val gender: String,
    val isMultilingual: Boolean
)

data class SentenceSegment(
    val index: Int,
    val range: IntRange,
    val text: String,
    val estimatedStartMs: Long,
    val estimatedEndMs: Long
)

data class SavedAudioTrack(
    val id: String,
    val bookTitle: String,
    val chapterIndex: Int,
    val chapterTitle: String,
    val audioFilePath: String,
    val syncFilePath: String,
    val createdAt: Long,
    val segments: List<SentenceSegment>
)

private enum class BufferSlotState {
    PENDING,
    LOADING,
    READY,
    FAILED
}

private data class BufferedSegmentEntry(
    val segmentIndex: Int,
    val state: BufferSlotState,
    val audioFile: File? = null
)

class VoiceEngineManager(private val context: Context) {

    companion object {
        const val DEFAULT_ONLINE_TTS_ENDPOINT = "https://api.streamelements.com/kappa/v2/speech"
        const val BUFFER_LOOKAHEAD_SEGMENTS = 3

        val EDGE_VOICES_CATALOG = listOf(
            EdgeVoiceItem("pt-PT-RaquelNeural", "Raquel · pt-PT (Feminina)", "pt-PT", "pt", "Feminina", false),
            EdgeVoiceItem("pt-PT-DuarteNeural", "Duarte · pt-PT (Masculina)", "pt-PT", "pt", "Masculina", false),
            EdgeVoiceItem("pt-BR-FranciscaNeural", "Francisca · pt-BR (Feminina)", "pt-BR", "pt", "Feminina", false),
            EdgeVoiceItem("pt-BR-AntonioNeural", "Antônio · pt-BR (Masculina)", "pt-BR", "pt", "Masculina", false),
            EdgeVoiceItem("pt-BR-ThalitaMultilingualNeural", "Thalita Multilingual · pt-BR", "pt-BR", "multi", "Feminina", true),
            EdgeVoiceItem("en-US-AvaMultilingualNeural", "Ava Multilingual · en-US", "en-US", "multi", "Feminina", true),
            EdgeVoiceItem("en-US-AndrewMultilingualNeural", "Andrew Multilingual · en-US", "en-US", "multi", "Masculina", true),
            EdgeVoiceItem("en-US-EmmaMultilingualNeural", "Emma Multilingual · en-US", "en-US", "multi", "Feminina", true),
            EdgeVoiceItem("en-US-BrianMultilingualNeural", "Brian Multilingual · en-US", "en-US", "multi", "Masculina", true),
            EdgeVoiceItem("fr-FR-VivienneMultilingualNeural", "Vivienne Multilingual · fr-FR", "fr-FR", "multi", "Feminina", true),
            EdgeVoiceItem("fr-FR-RemyMultilingualNeural", "Rémy Multilingual · fr-FR", "fr-FR", "multi", "Masculina", true),
            EdgeVoiceItem("de-DE-SeraphinaMultilingualNeural", "Seraphina Multilingual · de-DE", "de-DE", "multi", "Feminina", true),
            EdgeVoiceItem("de-DE-FlorianMultilingualNeural", "Florian Multilingual · de-DE", "de-DE", "multi", "Masculina", true),
            EdgeVoiceItem("es-ES-ArabellaMultilingualNeural", "Arabella Multilingual · es-ES", "es-ES", "multi", "Feminina", true),
            EdgeVoiceItem("it-IT-GiuseppeMultilingualNeural", "Giuseppe Multilingual · it-IT", "it-IT", "multi", "Masculina", true),
            EdgeVoiceItem("ko-KR-HyunsuMultilingualNeural", "Hyunsu Multilingual · ko-KR", "ko-KR", "multi", "Masculina", true),
            EdgeVoiceItem("en-US-JennyNeural", "Jenny · en-US", "en-US", "en", "Feminina", false),
            EdgeVoiceItem("en-US-GuyNeural", "Guy · en-US", "en-US", "en", "Masculina", false),
            EdgeVoiceItem("en-US-AriaNeural", "Aria · en-US", "en-US", "en", "Feminina", false),
            EdgeVoiceItem("en-GB-SoniaNeural", "Sonia · en-GB", "en-GB", "en", "Feminina", false),
            EdgeVoiceItem("en-GB-RyanNeural", "Ryan · en-GB", "en-GB", "en", "Masculina", false),
            EdgeVoiceItem("es-ES-ElviraNeural", "Elvira · es-ES", "es-ES", "other", "Feminina", false),
            EdgeVoiceItem("es-MX-DaliaNeural", "Dalia · es-MX", "es-MX", "other", "Feminina", false),
            EdgeVoiceItem("fr-FR-DeniseNeural", "Denise · fr-FR", "fr-FR", "other", "Feminina", false)
        )

        val OFFLINE_VOICE_CATALOG = listOf(
            DownloadableVoiceCatalogItem(
                id = "supertonic_pt",
                title = "Supertonic",
                subtitle = "Supertonic · vozes naturais",
                languageTag = "pt",
                approxSizeLabel = "~65 MB",
                engineLabel = "Supertonic",
                downloadUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/pt/pt_BR/faber/medium/pt_BR-faber-medium.onnx",
                targetSubdir = "supertonic_models",
                fileName = "supertonic_pt_BR.onnx",
                companionUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/pt/pt_BR/faber/medium/pt_BR-faber-medium.onnx.json",
                companionFileName = "supertonic_pt_BR.onnx.json"
            ),
            DownloadableVoiceCatalogItem(
                id = "paradee_pt",
                title = "Português do Brasil",
                subtitle = "Paradee · voz neural rápida",
                languageTag = "pt",
                approxSizeLabel = "~18 MB",
                engineLabel = "Paradee",
                downloadUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/pt/pt_BR/edresson/low/pt_BR-edresson-low.onnx",
                targetSubdir = "piper_models",
                fileName = "paradee_pt_BR.onnx",
                companionUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/pt/pt_BR/edresson/low/pt_BR-edresson-low.onnx.json",
                companionFileName = "paradee_pt_BR.onnx.json"
            ),
            DownloadableVoiceCatalogItem(
                id = "kokoro_82m_quant",
                title = "Kokoro-82M (Motor Kokoro)",
                subtitle = "Kokoro TTS · síntese neural compacta",
                languageTag = "multi",
                approxSizeLabel = "~88 MB",
                engineLabel = "Kokoro TTS",
                downloadUrl = "https://huggingface.co/onnx-community/Kokoro-82M-v1.0-ONNX/resolve/main/onnx/model_q8f16.onnx",
                targetSubdir = "kokoro_models",
                fileName = "kokoro_82m_q8f16.onnx",
                companionUrl = "https://huggingface.co/onnx-community/Kokoro-82M-v1.0-ONNX/resolve/main/config.json",
                companionFileName = "kokoro_config.json"
            ),
            DownloadableVoiceCatalogItem(
                id = "pocket_tts_profile",
                title = "Pocket TTS · Perfil Local",
                subtitle = "Pocket TTS · motor próprio (usa .zip para clonar)",
                languageTag = "pt",
                approxSizeLabel = "Config",
                engineLabel = "Pocket TTS",
                downloadUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/pt/pt_BR/jeff/medium/pt_BR-jeff-medium.onnx.json",
                targetSubdir = "pocket_tts_models",
                fileName = "pocket_tts_profile.json"
            ),
            DownloadableVoiceCatalogItem(
                id = "cadu_pt_br",
                title = "Cadu · pt-BR",
                subtitle = "Piper · leve e rápida",
                languageTag = "pt",
                approxSizeLabel = "~63 MB",
                engineLabel = "Piper",
                downloadUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/pt/pt_BR/cadu/medium/pt_BR-cadu-medium.onnx",
                targetSubdir = "piper_models",
                fileName = "pt_BR-cadu-medium.onnx",
                companionUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/pt/pt_BR/cadu/medium/pt_BR-cadu-medium.onnx.json",
                companionFileName = "pt_BR-cadu-medium.onnx.json"
            ),
            DownloadableVoiceCatalogItem(
                id = "jeff_pt_br",
                title = "Jeff · pt-BR",
                subtitle = "Piper · leve e rápida",
                languageTag = "pt",
                approxSizeLabel = "~63 MB",
                engineLabel = "Piper",
                downloadUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/pt/pt_BR/jeff/medium/pt_BR-jeff-medium.onnx",
                targetSubdir = "piper_models",
                fileName = "pt_BR-jeff-medium.onnx",
                companionUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/pt/pt_BR/jeff/medium/pt_BR-jeff-medium.onnx.json",
                companionFileName = "pt_BR-jeff-medium.onnx.json"
            ),
            DownloadableVoiceCatalogItem(
                id = "tugao_pt_pt",
                title = "Tugão · pt-PT",
                subtitle = "Piper · Português Europeu",
                languageTag = "pt",
                approxSizeLabel = "~63 MB",
                engineLabel = "Piper",
                downloadUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/pt/pt_PT/tug%C3%A3o/medium/pt_PT-tug%C3%A3o-medium.onnx",
                targetSubdir = "piper_models",
                fileName = "pt_PT-tugao-medium.onnx",
                companionUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/pt/pt_PT/tug%C3%A3o/medium/pt_PT-tug%C3%A3o-medium.onnx.json",
                companionFileName = "pt_PT-tugao-medium.onnx.json"
            ),
            DownloadableVoiceCatalogItem(
                id = "amy_en_us",
                title = "Amy · en-US",
                subtitle = "Piper · English US natural",
                languageTag = "en",
                approxSizeLabel = "~63 MB",
                engineLabel = "Piper",
                downloadUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/amy/medium/en_US-amy-medium.onnx",
                targetSubdir = "piper_models",
                fileName = "en_US-amy-medium.onnx",
                companionUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/amy/medium/en_US-amy-medium.onnx.json",
                companionFileName = "en_US-amy-medium.onnx.json"
            ),
            DownloadableVoiceCatalogItem(
                id = "lessac_en_us",
                title = "Lessac · en-US",
                subtitle = "Piper · English US claro",
                languageTag = "en",
                approxSizeLabel = "~63 MB",
                engineLabel = "Piper",
                downloadUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/lessac/medium/en_US-lessac-medium.onnx",
                targetSubdir = "piper_models",
                fileName = "en_US-lessac-medium.onnx",
                companionUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/en/en_US/lessac/medium/en_US-lessac-medium.onnx.json",
                companionFileName = "en_US-lessac-medium.onnx.json"
            )
        )
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var prefetchJob: Job? = null
    private val segmentBuffer = ConcurrentHashMap<Int, BufferedSegmentEntry>()

    private var androidTts: TextToSpeech? = null
    private var mediaPlayer: MediaPlayer? = null
    private var isTtsInitialized = false

    var currentProvider: VoiceProviderType = VoiceProviderType.ANDROID_SYSTEM
    var selectedLocale: Locale = Locale("pt", "PT")
    var selectedVoiceName: String = ""
    var selectedEdgeVoice: String = "pt-PT-RaquelNeural"
    var selectedOfflineModelId: String = ""
    var speechRate: Float = 1.0f
    var pitch: Float = 1.0f
    var wordHighlightEnabled: Boolean = true
    var customOnlineEndpointUrl: String = DEFAULT_ONLINE_TTS_ENDPOINT
    var activeClonedVoiceName: String = ""

    private var onSentenceRangeCallback: ((IntRange?) -> Unit)? = null
    private var onWordRangeCallback: ((IntRange?) -> Unit)? = null
    private var onPlaybackStateCallback: ((Boolean) -> Unit)? = null
    private var onChapterFinishedCallback: (() -> Unit)? = null

    private var activeSegments: List<SentenceSegment> = emptyList()
    private var currentSegmentIndex: Int = 0
    private var segmentStartElapsedMs: Long = 0L
    private var isPlayingInternal: Boolean = false
    private var receivedNativeRangeEvents: Boolean = false

    private val wordProgressRunnable = object : Runnable {
        override fun run() {
            if (!isPlayingInternal) return
            val seg = activeSegments.getOrNull(currentSegmentIndex)
            if (seg != null) {
                onSentenceRangeCallback?.invoke(seg.range)
                if (wordHighlightEnabled && !receivedNativeRangeEvents) {
                    val expectedDurationMs = estimateSegmentDurationMs(seg.text, speechRate)
                    val elapsedMs = (SystemClock.elapsedRealtime() - segmentStartElapsedMs).coerceAtLeast(0L)
                    val frac = (elapsedMs.toFloat() / expectedDurationMs.toFloat()).coerceIn(0f, 0.995f)
                    onWordRangeCallback?.invoke(estimateWordRangeInSegment(seg, frac))
                }
            }
            mainHandler.postDelayed(this, 85L)
        }
    }

    private val playbackUtteranceListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            mainHandler.post {
                if (!isPlayingInternal) return@post
                segmentStartElapsedMs = SystemClock.elapsedRealtime()
                onPlaybackStateCallback?.invoke(true)
            }
        }

        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
            if (!wordHighlightEnabled) return
            mainHandler.post {
                if (!isPlayingInternal) return@post
                val seg = activeSegments.getOrNull(currentSegmentIndex) ?: return@post
                if (end > start && end <= seg.text.length + 4) {
                    receivedNativeRangeEvents = true
                    val absStart = (seg.range.first + start).coerceIn(seg.range.first, seg.range.last)
                    val absEnd = (seg.range.first + end).coerceIn(absStart, seg.range.last + 1)
                    if (absStart < absEnd) {
                        onWordRangeCallback?.invoke(absStart until absEnd)
                    }
                }
            }
        }

        override fun onDone(utteranceId: String?) {
            mainHandler.post {
                if (isPlayingInternal) {
                    playSentenceAt(currentSegmentIndex + 1)
                }
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            mainHandler.post {
                if (isPlayingInternal) {
                    playSentenceAt(currentSegmentIndex + 1)
                }
            }
        }
    }

    fun getAvailableStorageGb(): String {
        return try {
            val stat = StatFs(context.filesDir.absolutePath)
            val freeBytes = stat.availableBlocksLong * stat.blockSizeLong
            val gb = freeBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
            String.format(Locale.US, "%.0f GB livres", gb)
        } catch (_: Exception) {
            "Espaço disponível"
        }
    }

    fun isCatalogItemDownloaded(item: DownloadableVoiceCatalogItem): Boolean {
        val file = File(File(context.filesDir, item.targetSubdir), item.fileName)
        return file.exists() && file.length() > 16L
    }

    fun getCatalogItemSizeLabel(item: DownloadableVoiceCatalogItem): String {
        val file = File(File(context.filesDir, item.targetSubdir), item.fileName)
        if (!file.exists() || file.length() == 0L) return item.approxSizeLabel
        val mb = file.length().toDouble() / (1024.0 * 1024.0)
        return if (mb >= 1.0) {
            String.format(Locale.US, "%.0f MB", mb)
        } else {
            val kb = (file.length().toDouble() / 1024.0).coerceAtLeast(1.0)
            String.format(Locale.US, "%.0f KB", kb)
        }
    }

    fun deleteCatalogItem(item: DownloadableVoiceCatalogItem) {
        val dir = File(context.filesDir, item.targetSubdir)
        val file = File(dir, item.fileName)
        if (file.exists()) file.delete()
        item.companionFileName?.let { comp ->
            val cf = File(dir, comp)
            if (cf.exists()) cf.delete()
        }
        if (selectedOfflineModelId == item.id) {
            selectedOfflineModelId = ""
        }
    }

    private fun estimateSegmentDurationMs(sentence: String, rate: Float): Long {
        val effectiveRate = rate.coerceIn(0.5f, 2.5f)
        val words = sentence.split(Regex("\\s+")).filter { it.isNotBlank() }
        val baseMs = words.sumOf { w ->
            val cleanLen = w.replace(Regex("[^\\p{L}\\p{N}]"), "").length.coerceAtLeast(2)
            val pauseBonus = if (w.endsWith(",") || w.endsWith(";") || w.endsWith(":")) 135L else 0L
            (cleanLen * 50L) + pauseBonus
        }.coerceAtLeast(500L)
        return (baseMs / effectiveRate).toLong().coerceAtLeast(380L)
    }

    fun initializeAndroidTts(
        onReady: (voices: List<Voice>) -> Unit = {}
    ) {
        if (androidTts != null && isTtsInitialized) {
            androidTts?.setOnUtteranceProgressListener(playbackUtteranceListener)
            onReady(getAvailableVoices())
            return
        }
        androidTts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                isTtsInitialized = true
                androidTts?.language = selectedLocale
                androidTts?.setOnUtteranceProgressListener(playbackUtteranceListener)
                applyVoiceAndParams()
                onReady(getAvailableVoices())
            }
        }
    }

    fun getAvailableVoices(): List<Voice> {
        return androidTts?.voices?.toList()?.sortedWith(
            compareBy<Voice> { it.locale.displayLanguage }.thenBy { it.name }
        ) ?: emptyList()
    }

    fun applyVoiceAndParams() {
        val engine = androidTts ?: return
        val targetLocale = when {
            currentProvider == VoiceProviderType.EDGE_ONLINE && selectedEdgeVoice.startsWith("pt-BR") -> Locale("pt", "BR")
            currentProvider == VoiceProviderType.EDGE_ONLINE && selectedEdgeVoice.startsWith("pt-PT") -> Locale("pt", "PT")
            currentProvider == VoiceProviderType.EDGE_ONLINE && selectedEdgeVoice.startsWith("en-") -> Locale.US
            currentProvider == VoiceProviderType.EDGE_ONLINE && selectedEdgeVoice.startsWith("es-") -> Locale("es", "ES")
            currentProvider == VoiceProviderType.EDGE_ONLINE && selectedEdgeVoice.startsWith("fr-") -> Locale.FRANCE
            else -> selectedLocale
        }
        engine.language = targetLocale
        engine.setSpeechRate(speechRate.coerceIn(0.5f, 2.5f))
        engine.setPitch(pitch.coerceIn(0.5f, 2.0f))
        if (selectedVoiceName.isNotBlank()) {
            val matchedVoice = engine.voices?.firstOrNull { it.name == selectedVoiceName }
            if (matchedVoice != null) {
                engine.voice = matchedVoice
            }
        }
    }

    fun buildSentenceSegments(text: String): List<SentenceSegment> {
        if (text.isBlank()) return emptyList()
        val sentenceRegex = Regex("[^.!?\\n]+[.!?\\n]*")
        val rawMatches = sentenceRegex.findAll(text)
            .map { it.range to it.value.trim() }
            .filter { it.second.isNotEmpty() }
            .toList()

        if (rawMatches.isEmpty()) return emptyList()

        val result = mutableListOf<SentenceSegment>()
        var cursorMs = 0L

        rawMatches.forEachIndexed { idx, (range, sentence) ->
            val durationMs = estimateSegmentDurationMs(sentence, speechRate)
            val startMs = cursorMs
            val endMs = cursorMs + durationMs
            result.add(
                SentenceSegment(
                    index = idx,
                    range = range,
                    text = sentence,
                    estimatedStartMs = startMs,
                    estimatedEndMs = endMs
                )
            )
            cursorMs = endMs
        }
        return result
    }

    fun estimateWordRangeInSegment(segment: SentenceSegment, progressFraction: Float): IntRange? {
        if (!wordHighlightEnabled) return null
        val words = Regex("\\S+").findAll(segment.text).toList()
        if (words.isEmpty()) return null
        val weights = words.map { m ->
            val len = m.value.replace(Regex("[^\\p{L}\\p{N}]"), "").length.coerceAtLeast(2)
            val extra = if (m.value.endsWith(",") || m.value.endsWith(";")) 3 else 0
            (len + extra).toFloat()
        }
        val totalWeight = weights.sum().coerceAtLeast(1f)
        val targetWeight = progressFraction.coerceIn(0f, 0.999f) * totalWeight

        var acc = 0f
        var chosenIndex = words.lastIndex
        for (i in words.indices) {
            acc += weights[i]
            if (targetWeight <= acc) {
                chosenIndex = i
                break
            }
        }

        val match = words[chosenIndex]
        val start = segment.range.first + match.range.first
        val end = segment.range.first + match.range.last + 1
        return if (start < end) start until end else null
    }

    private fun clearSegmentBuffer() {
        segmentBuffer.values.forEach { entry ->
            entry.audioFile?.let { file ->
                if (file.exists() && file.name.startsWith("tts_buf_")) {
                    runCatching { file.delete() }
                }
            }
        }
        segmentBuffer.clear()
    }

    private fun scheduleBufferPrefetch(fromIndex: Int) {
        if (currentProvider != VoiceProviderType.EDGE_ONLINE) return
        val segmentsSnapshot = activeSegments
        if (segmentsSnapshot.isEmpty()) return

        prefetchJob?.cancel()
        prefetchJob = engineScope.launch {
            val endExclusive = (fromIndex + 1 + BUFFER_LOOKAHEAD_SEGMENTS).coerceAtMost(segmentsSnapshot.size)
            for (idx in fromIndex until endExclusive) {
                if (!isPlayingInternal) break
                val existing = segmentBuffer[idx]
                if (existing != null && (existing.state == BufferSlotState.READY || existing.state == BufferSlotState.LOADING)) {
                    continue
                }
                val seg = segmentsSnapshot.getOrNull(idx) ?: continue
                segmentBuffer[idx] = BufferedSegmentEntry(idx, BufferSlotState.LOADING)
                val file = synthesizeOnlineSegmentToCache(idx, seg.text)
                if (file != null && file.exists() && file.length() > 512L) {
                    segmentBuffer[idx] = BufferedSegmentEntry(idx, BufferSlotState.READY, file)
                } else {
                    segmentBuffer[idx] = BufferedSegmentEntry(idx, BufferSlotState.FAILED)
                }
            }
        }
    }

    private suspend fun synthesizeOnlineSegmentToCache(segmentIndex: Int, text: String): File? = withContext(Dispatchers.IO) {
        val cleanText = text.trim().take(400)
        if (cleanText.isEmpty()) return@withContext null
        val cacheDir = File(context.cacheDir, "tts_buffer").apply { if (!exists()) mkdirs() }
        val outFile = File(cacheDir, "tts_buf_${segmentIndex}_${selectedEdgeVoice.hashCode()}.mp3")
        if (outFile.exists() && outFile.length() > 512L) return@withContext outFile

        val voiceParam = URLEncoder.encode(selectedEdgeVoice, "UTF-8")
        val textParam = URLEncoder.encode(cleanText, "UTF-8")
        val urls = listOf(
            "https://api.streamelements.com/kappa/v2/speech?voice=$voiceParam&text=$textParam",
            "https://translate.google.com/translate_tts?ie=UTF-8&tl=${selectedLocale.language.ifBlank { "pt" }}&client=tw-ob&q=$textParam"
        )

        for (candidate in urls) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(candidate).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 5000
                    readTimeout = 8000
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Android; GlowReader)")
                }
                if (conn.responseCode in 200..299) {
                    val tempFile = File(cacheDir, "${outFile.name}.part")
                    conn.inputStream.use { input ->
                        FileOutputStream(tempFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    if (tempFile.exists() && tempFile.length() > 512L) {
                        if (outFile.exists()) outFile.delete()
                        tempFile.renameTo(outFile)
                        return@withContext outFile
                    }
                    if (tempFile.exists()) tempFile.delete()
                }
            } catch (_: Exception) {
            } finally {
                conn?.disconnect()
            }
        }
        null
    }

    private fun playBufferedAudioFile(index: Int, seg: SentenceSegment, file: File) {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (_: Exception) {
        }
        mediaPlayer = null

        val mp = MediaPlayer().apply {
            setDataSource(file.absolutePath)
            prepare()
            start()
        }
        mediaPlayer = mp
        segmentStartElapsedMs = SystemClock.elapsedRealtime()
        onPlaybackStateCallback?.invoke(true)
        onSentenceRangeCallback?.invoke(seg.range)
        onWordRangeCallback?.invoke(estimateWordRangeInSegment(seg, 0f))
        scheduleBufferPrefetch(index + 1)

        mp.setOnCompletionListener {
            try {
                mp.release()
            } catch (_: Exception) {
            }
            if (mediaPlayer === mp) {
                mediaPlayer = null
            }
            if (isPlayingInternal) {
                playSentenceAt(index + 1)
            }
        }
    }

    fun startReadingText(
        text: String,
        onSentenceRange: (IntRange?) -> Unit,
        onWordRange: (IntRange?) -> Unit,
        onPlaybackState: (Boolean) -> Unit,
        onChapterFinished: () -> Unit = {}
    ) {
        stopPlayback()
        onSentenceRangeCallback = onSentenceRange
        onWordRangeCallback = onWordRange
        onPlaybackStateCallback = onPlaybackState
        onChapterFinishedCallback = onChapterFinished

        val segments = buildSentenceSegments(text)
        if (segments.isEmpty()) {
            onPlaybackState(false)
            return
        }

        activeSegments = segments
        currentSegmentIndex = 0
        receivedNativeRangeEvents = false
        isPlayingInternal = true
        onPlaybackState(true)
        mainHandler.post(wordProgressRunnable)

        if (!isTtsInitialized) {
            initializeAndroidTts {
                if (isPlayingInternal) {
                    playSentenceAt(0)
                }
            }
        } else {
            androidTts?.setOnUtteranceProgressListener(playbackUtteranceListener)
            playSentenceAt(0)
        }
    }

    private fun playSentenceAt(index: Int) {
        if (!isPlayingInternal) return
        if (index >= activeSegments.size) {
            isPlayingInternal = false
            prefetchJob?.cancel()
            mainHandler.removeCallbacks(wordProgressRunnable)
            clearSegmentBuffer()
            onSentenceRangeCallback?.invoke(null)
            onWordRangeCallback?.invoke(null)
            onPlaybackStateCallback?.invoke(false)
            onChapterFinishedCallback?.invoke()
            return
        }
        currentSegmentIndex = index
        segmentStartElapsedMs = SystemClock.elapsedRealtime()
        val seg = activeSegments[index]
        onSentenceRangeCallback?.invoke(seg.range)
        onWordRangeCallback?.invoke(estimateWordRangeInSegment(seg, 0f))

        if (currentProvider == VoiceProviderType.EDGE_ONLINE) {
            scheduleBufferPrefetch(index)
            val cached = segmentBuffer[index]
            if (cached?.state == BufferSlotState.READY && cached.audioFile?.exists() == true) {
                playBufferedAudioFile(index, seg, cached.audioFile)
                return
            }
            engineScope.launch {
                val preparedFile = withTimeoutOrNull(2200L) {
                    val existing = segmentBuffer[index]
                    if (existing?.state == BufferSlotState.READY && existing.audioFile?.exists() == true) {
                        existing.audioFile
                    } else {
                        synthesizeOnlineSegmentToCache(index, seg.text)
                    }
                }
                withContext(Dispatchers.Main) {
                    if (!isPlayingInternal || currentSegmentIndex != index) return@withContext
                    if (preparedFile != null && preparedFile.exists() && preparedFile.length() > 512L) {
                        segmentBuffer[index] = BufferedSegmentEntry(index, BufferSlotState.READY, preparedFile)
                        playBufferedAudioFile(index, seg, preparedFile)
                    } else {
                        speakWithSystemTtsFallback(index, seg)
                    }
                }
            }
            return
        }

        speakWithSystemTtsFallback(index, seg)
    }

    private fun speakWithSystemTtsFallback(index: Int, seg: SentenceSegment) {
        scheduleBufferPrefetch(index + 1)
        applyVoiceAndParams()
        val utteranceId = "glow_seg_$index"
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
        }
        androidTts?.speak(seg.text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
    }

    fun stopPlayback() {
        isPlayingInternal = false
        prefetchJob?.cancel()
        mainHandler.removeCallbacks(wordProgressRunnable)
        clearSegmentBuffer()
        try {
            androidTts?.stop()
        } catch (_: Exception) {
        }
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (_: Exception) {
        }
        mediaPlayer = null
        onSentenceRangeCallback?.invoke(null)
        onWordRangeCallback?.invoke(null)
        onPlaybackStateCallback?.invoke(false)
    }

    fun release() {
        stopPlayback()
        engineScope.cancel()
        try {
            androidTts?.shutdown()
        } catch (_: Exception) {
        }
        androidTts = null
        isTtsInitialized = false
    }

    fun getAudioStorageDir(): File {
        val dir = File(context.filesDir, "glowreader_audio_tracks")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    suspend fun synthesizeAndSaveChapterAudio(
        bookTitle: String,
        chapterIndex: Int,
        chapterTitle: String,
        text: String
    ): Result<SavedAudioTrack> = withContext(Dispatchers.IO) {
        if (text.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("O capítulo não tem texto para gerar áudio."))
        }
        if (!isTtsInitialized) {
            withContext(Dispatchers.Main) {
                initializeAndroidTts()
            }
            var waited = 0
            while (!isTtsInitialized && waited < 5000) {
                delay(100)
                waited += 100
            }
        }
        val engine = androidTts
            ?: return@withContext Result.failure(IllegalStateException("Motor TTS indisponível neste aparelho."))

        val safeId = "${bookTitle.hashCode()}_ch_${chapterIndex}_${System.currentTimeMillis()}"
        val dir = getAudioStorageDir()
        val wavFile = File(dir, "$safeId.wav")
        val fallbackWavFile = File(context.cacheDir, "$safeId.wav")
        val syncFile = File(dir, "$safeId.sync.json")
        val segments = buildSentenceSegments(text)
        val sampleText = text.take(3500)

        suspend fun trySynthesizeToTarget(targetFile: File): Boolean {
            runCatching {
                if (targetFile.exists()) targetFile.delete()
                targetFile.parentFile?.mkdirs()
            }
            return suspendCoroutine { cont ->
                mainHandler.post {
                    applyVoiceAndParams()
                    val utteranceId = "synth_${targetFile.name}_${System.nanoTime()}"
                    engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(id: String?) {}
                        override fun onDone(id: String?) {
                            if (id == utteranceId) {
                                mainHandler.post {
                                    engine.setOnUtteranceProgressListener(playbackUtteranceListener)
                                }
                                cont.resume(true)
                            }
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(id: String?) {
                            if (id == utteranceId) {
                                mainHandler.post {
                                    engine.setOnUtteranceProgressListener(playbackUtteranceListener)
                                }
                                cont.resume(false)
                            }
                        }
                    })
                    val bundle = Bundle()
                    val res = engine.synthesizeToFile(sampleText, bundle, targetFile, utteranceId)
                    if (res != TextToSpeech.SUCCESS) {
                        engine.setOnUtteranceProgressListener(playbackUtteranceListener)
                        cont.resume(false)
                    }
                }
            }
        }

        val primaryOk = trySynthesizeToTarget(wavFile)
        val finalAudioFile = when {
            primaryOk && wavFile.exists() && wavFile.length() > 0L -> wavFile
            trySynthesizeToTarget(fallbackWavFile) && fallbackWavFile.exists() && fallbackWavFile.length() > 0L -> {
                fallbackWavFile.copyTo(wavFile, overwrite = true)
                fallbackWavFile.delete()
                wavFile
            }
            else -> null
        }

        if (finalAudioFile == null || !finalAudioFile.exists() || finalAudioFile.length() == 0L) {
            return@withContext Result.failure(
                IllegalStateException("O motor TTS do aparelho não permitiu exportar o ficheiro de áudio.")
            )
        }

        val track = SavedAudioTrack(
            id = safeId,
            bookTitle = bookTitle,
            chapterIndex = chapterIndex,
            chapterTitle = chapterTitle,
            audioFilePath = finalAudioFile.absolutePath,
            syncFilePath = syncFile.absolutePath,
            createdAt = System.currentTimeMillis(),
            segments = segments
        )
        writeSyncManifest(track)
        Result.success(track)
    }

    private fun writeSyncManifest(track: SavedAudioTrack) {
        val root = JSONObject().apply {
            put("id", track.id)
            put("bookTitle", track.bookTitle)
            put("chapterIndex", track.chapterIndex)
            put("chapterTitle", track.chapterTitle)
            put("audioFilePath", track.audioFilePath)
            put("createdAt", track.createdAt)
            val arr = JSONArray()
            track.segments.forEach { seg ->
                arr.put(
                    JSONObject().apply {
                        put("index", seg.index)
                        put("startChar", seg.range.first)
                        put("endChar", seg.range.last)
                        put("text", seg.text)
                        put("startMs", seg.estimatedStartMs)
                        put("endMs", seg.estimatedEndMs)
                    }
                )
            }
            put("segments", arr)
        }
        File(track.syncFilePath).writeText(root.toString(2), Charsets.UTF_8)
    }

    fun listSavedAudioTracks(): List<SavedAudioTrack> {
        val dir = getAudioStorageDir()
        val syncFiles = dir.listFiles { f -> f.isFile && f.name.endsWith(".sync.json") } ?: return emptyList()
        return syncFiles.mapNotNull { file ->
            try {
                val obj = JSONObject(file.readText(Charsets.UTF_8))
                val audioPath = obj.optString("audioFilePath")
                if (audioPath.isBlank() || !File(audioPath).exists()) return@mapNotNull null
                val segArray = obj.optJSONArray("segments") ?: JSONArray()
                val segs = mutableListOf<SentenceSegment>()
                for (i in 0 until segArray.length()) {
                    val sObj = segArray.getJSONObject(i)
                    val startChar = sObj.optInt("startChar", 0)
                    val endChar = sObj.optInt("endChar", 0)
                    segs.add(
                        SentenceSegment(
                            index = sObj.optInt("index", i),
                            range = startChar..endChar,
                            text = sObj.optString("text", ""),
                            estimatedStartMs = sObj.optLong("startMs", 0L),
                            estimatedEndMs = sObj.optLong("endMs", 0L)
                        )
                    )
                }
                SavedAudioTrack(
                    id = obj.optString("id", file.nameWithoutExtension),
                    bookTitle = obj.optString("bookTitle", "Livro"),
                    chapterIndex = obj.optInt("chapterIndex", 0),
                    chapterTitle = obj.optString("chapterTitle", "Capítulo"),
                    audioFilePath = audioPath,
                    syncFilePath = file.absolutePath,
                    createdAt = obj.optLong("createdAt", file.lastModified()),
                    segments = segs
                )
            } catch (_: Exception) {
                null
            }
        }.sortedByDescending { it.createdAt }
    }

    fun playSavedTrackWithSync(
        track: SavedAudioTrack,
        onSentenceRange: (IntRange?) -> Unit,
        onWordRange: (IntRange?) -> Unit,
        onPlaybackState: (Boolean) -> Unit,
        onTickPositionMs: (currentMs: Int, durationMs: Int) -> Unit = { _, _ -> }
    ) {
        stopPlayback()
        val file = File(track.audioFilePath)
        if (!file.exists()) {
            onPlaybackState(false)
            return
        }
        val mp = MediaPlayer().apply {
            setDataSource(file.absolutePath)
            prepare()
            start()
        }
        mediaPlayer = mp
        isPlayingInternal = true
        onPlaybackState(true)

        mp.setOnCompletionListener {
            stopPlayback()
            onTickPositionMs(0, mp.duration.coerceAtLeast(0))
        }

        mainHandler.post(object : Runnable {
            override fun run() {
                val player = mediaPlayer
                if (!isPlayingInternal || player == null) return
                try {
                    if (player.isPlaying) {
                        val posMs = player.currentPosition.coerceAtLeast(0)
                        val durMs = player.duration.coerceAtLeast(1)
                        onTickPositionMs(posMs, durMs)

                        val matched = track.segments.firstOrNull { posMs in it.estimatedStartMs..it.estimatedEndMs }
                            ?: track.segments.lastOrNull { posMs >= it.estimatedStartMs }
                        if (matched != null) {
                            onSentenceRange(matched.range)
                            val span = (matched.estimatedEndMs - matched.estimatedStartMs).coerceAtLeast(1L).toFloat()
                            val frac = ((posMs - matched.estimatedStartMs).toFloat() / span).coerceIn(0f, 1f)
                            onWordRange(estimateWordRangeInSegment(matched, frac))
                        }
                        mainHandler.postDelayed(this, 100L)
                    }
                } catch (_: Exception) {
                }
            }
        })
    }

    suspend fun downloadFileFromUrl(
        urlStr: String,
        targetSubdir: String,
        outputFileName: String,
        onProgressPercent: (Int) -> Unit = {}
    ): Result<File> = withContext(Dispatchers.IO) {
        val cleanUrl = urlStr.trim()
        if (!cleanUrl.startsWith("https://") && !cleanUrl.startsWith("http://")) {
            return@withContext Result.failure(IllegalArgumentException("URL inválido."))
        }

        val dir = File(context.filesDir, targetSubdir).apply { if (!exists()) mkdirs() }
        val outFile = File(dir, outputFileName)
        val partFile = File(dir, "$outputFileName.part")
        var lastError: Exception? = null

        for (attempt in 1..2) {
            var conn: HttpURLConnection? = null
            try {
                if (partFile.exists()) partFile.delete()
                conn = (URL(cleanUrl).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    connectTimeout = 45000
                    readTimeout = 90000
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Android; GlowReader)")
                }
                val code = conn.responseCode
                if (code !in 200..299) {
                    lastError = IllegalStateException("HTTP $code ao transferir ficheiro.")
                    continue
                }
                val totalBytes = conn.contentLengthLong
                var downloadedBytes = 0L
                conn.inputStream.use { input ->
                    FileOutputStream(partFile).use { output ->
                        val buf = ByteArray(16384)
                        while (true) {
                            val read = input.read(buf)
                            if (read <= 0) break
                            output.write(buf, 0, read)
                            downloadedBytes += read
                            if (totalBytes > 0L) {
                                val pct = ((downloadedBytes * 100L) / totalBytes).toInt().coerceIn(1, 99)
                                withContext(Dispatchers.Main) {
                                    onProgressPercent(pct)
                                }
                            }
                        }
                        output.flush()
                    }
                }
                if (partFile.exists() && partFile.length() > 16L) {
                    if (outFile.exists()) outFile.delete()
                    partFile.renameTo(outFile)
                    withContext(Dispatchers.Main) { onProgressPercent(100) }
                    return@withContext Result.success(outFile)
                } else {
                    lastError = IllegalStateException("Transferência incompleta (rede muito lenta).")
                }
            } catch (e: Exception) {
                lastError = e
            } finally {
                conn?.disconnect()
            }
        }
        Result.failure(lastError ?: IllegalStateException("Sem ligação estável para baixar o modelo."))
    }

    suspend fun downloadAndExtractVoiceZip(
        zipUrlOrLocalPath: String,
        voiceName: String = "clone_voz"
    ): Result<File> = withContext(Dispatchers.IO) {
        val raw = zipUrlOrLocalPath.trim()
        val clonesRoot = File(context.filesDir, "cloned_voices").apply { if (!exists()) mkdirs() }
        val safeName = voiceName.trim().replace(Regex("[^a-zA-Z0-9_-]"), "_").ifBlank { "clone_voz" }
        val extractDir = File(clonesRoot, safeName).apply { if (!exists()) mkdirs() }
        val tempZip = File(context.cacheDir, "$safeName.zip")

        try {
            if (raw.startsWith("https://") || raw.startsWith("http://")) {
                val dl = downloadFileFromUrl(raw, "cloned_voices", "$safeName.zip")
                if (dl.isFailure) {
                    return@withContext Result.failure(dl.exceptionOrNull() ?: IllegalStateException("Falha ao baixar .zip"))
                }
                dl.getOrThrow().copyTo(tempZip, overwrite = true)
            } else {
                val localZip = File(raw)
                if (!localZip.exists() || !localZip.isFile) {
                    return@withContext Result.failure(IllegalArgumentException("Ficheiro .zip não encontrado."))
                }
                localZip.copyTo(tempZip, overwrite = true)
            }

            var extractedCount = 0
            ZipInputStream(tempZip.inputStream().buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val destFile = File(extractDir, entry.name)
                    if (!destFile.canonicalPath.startsWith(extractDir.canonicalPath)) {
                        entry = zis.nextEntry
                        continue
                    }
                    if (entry.isDirectory) {
                        destFile.mkdirs()
                    } else {
                        destFile.parentFile?.mkdirs()
                        FileOutputStream(destFile).use { fos ->
                            zis.copyTo(fos)
                        }
                        extractedCount++
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            if (tempZip.exists()) tempZip.delete()
            if (extractedCount == 0) {
                return@withContext Result.failure(IllegalStateException("O ficheiro .zip não continha ficheiros."))
            }
            activeClonedVoiceName = safeName
            Result.success(extractDir)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun testOnlineEndpoint(
        endpointUrl: String = DEFAULT_ONLINE_TTS_ENDPOINT,
        sampleText: String = "Olá, este é um teste de voz."
    ): Boolean = withContext(Dispatchers.IO) {
        val candidates = listOf(
            "https://translate.google.com/translate_tts?ie=UTF-8&tl=pt&client=tw-ob",
            "https://api.streamelements.com/kappa/v2/speech?voice=Vitoria"
        )
        for (candidate in candidates) {
            var conn: HttpURLConnection? = null
            try {
                val sep = if (candidate.contains("?")) "&" else "?"
                val full = "$candidate${sep}text=${URLEncoder.encode(sampleText, "UTF-8")}"
                conn = (URL(full).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 10000
                    readTimeout = 10000
                    setRequestProperty("User-Agent", "Mozilla/5.0")
                }
                if (conn.responseCode in 200..299) {
                    customOnlineEndpointUrl = candidate
                    return@withContext true
                }
            } catch (_: Exception) {
            } finally {
                conn?.disconnect()
            }
        }
        false
    }
}
