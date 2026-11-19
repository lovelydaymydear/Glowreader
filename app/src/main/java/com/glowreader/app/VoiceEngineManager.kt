package com.glowreader.app

import android.content.Context
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
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
        subtitle = "Motor TTS nativo do aparelho com vozes instaladas e síntese para ficheiro.",
        requiresNetworkOrModel = false
    ),
    EDGE_ONLINE(
        id = "edge_tts_online",
        title = "TTS Online (Estilo Edge / Cloud)",
        subtitle = "Usa endpoint online pré-configurado no código com fallback automático.",
        requiresNetworkOrModel = true
    ),
    PIPER_LOCAL(
        id = "piper_tts",
        title = "Piper TTS (Motor Neural Offline)",
        subtitle = "Modelos locais Piper (.onnx + .onnx.json) descarregados do Hugging Face.",
        requiresNetworkOrModel = true
    ),
    KOKORO_POCKET_NEURAL(
        id = "kokoro_pocket_tts",
        title = "Kokoro / Pocket TTS & Clonador (.zip)",
        subtitle = "Modelos Kokoro / Pocket TTS do Hugging Face e pacotes de clonagem de voz (.zip).",
        requiresNetworkOrModel = true
    )
}

data class HuggingFaceModelPreset(
    val id: String,
    val category: String,
    val title: String,
    val downloadUrl: String,
    val targetSubdir: String,
    val fileName: String,
    val companionUrl: String? = null,
    val companionFileName: String? = null
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

class VoiceEngineManager(private val context: Context) {

    companion object {
        const val DEFAULT_ONLINE_TTS_ENDPOINT = "https://api.streamelements.com/kappa/v2/speech?voice=Vitoria"
        val FALLBACK_ONLINE_ENDPOINTS = listOf(
            "https://api.streamelements.com/kappa/v2/speech?voice=Vitoria",
            "https://translate.google.com/translate_tts?ie=UTF-8&tl=pt&client=tw-ob"
        )

        val HUGGINGFACE_PRESETS = listOf(
            HuggingFaceModelPreset(
                id = "piper_pt_jeff",
                category = "Piper TTS",
                title = "Piper PT-BR Jeff (Medium .onnx + .json)",
                downloadUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/pt/pt_BR/jeff/medium/pt_BR-jeff-medium.onnx",
                targetSubdir = "piper_models",
                fileName = "pt_BR-jeff-medium.onnx",
                companionUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/pt/pt_BR/jeff/medium/pt_BR-jeff-medium.onnx.json",
                companionFileName = "pt_BR-jeff-medium.onnx.json"
            ),
            HuggingFaceModelPreset(
                id = "piper_pt_edresson",
                category = "Piper TTS",
                title = "Piper PT-BR Edresson (Low .onnx + .json)",
                downloadUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/pt/pt_BR/edresson/low/pt_BR-edresson-low.onnx",
                targetSubdir = "piper_models",
                fileName = "pt_BR-edresson-low.onnx",
                companionUrl = "https://huggingface.co/rhasspy/piper-voices/resolve/main/pt/pt_BR/edresson/low/pt_BR-edresson-low.onnx.json",
                companionFileName = "pt_BR-edresson-low.onnx.json"
            ),
            HuggingFaceModelPreset(
                id = "kokoro_82m_onnx",
                category = "Kokoro TTS",
                title = "Kokoro-82M ONNX (Hugging Face)",
                downloadUrl = "https://huggingface.co/onnx-community/Kokoro-82M-v1.0-ONNX/resolve/main/onnx/model_q8f16.onnx",
                targetSubdir = "kokoro_models",
                fileName = "kokoro-82m-q8f16.onnx"
            ),
            HuggingFaceModelPreset(
                id = "pocket_tts_bundle",
                category = "Pocket TTS",
                title = "Pocket TTS Config / Bundle (Hugging Face)",
                downloadUrl = "https://huggingface.co/onnx-community/Kokoro-82M-v1.0-ONNX/resolve/main/config.json",
                targetSubdir = "pocket_tts_models",
                fileName = "pocket_tts_config.json"
            )
        )
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var androidTts: TextToSpeech? = null
    private var mediaPlayer: MediaPlayer? = null
    private var isTtsInitialized = false

    var currentProvider: VoiceProviderType = VoiceProviderType.ANDROID_SYSTEM
    var selectedLocale: Locale = Locale("pt", "PT")
    var selectedVoiceName: String = ""
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
            mainHandler.postDelayed(this, 90L)
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

    private fun estimateSegmentDurationMs(sentence: String, rate: Float): Long {
        val effectiveRate = rate.coerceIn(0.5f, 2.5f)
        val words = sentence.split(Regex("\\s+")).filter { it.isNotBlank() }
        val baseMs = words.sumOf { w ->
            val cleanLen = w.replace(Regex("[^\\p{L}\\p{N}]"), "").length.coerceAtLeast(2)
            val pauseBonus = if (w.endsWith(",") || w.endsWith(";") || w.endsWith(":")) 140L else 0L
            (cleanLen * 52L) + pauseBonus
        }.coerceAtLeast(550L)
        return (baseMs / effectiveRate).toLong().coerceAtLeast(400L)
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
        engine.language = selectedLocale
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
            mainHandler.removeCallbacks(wordProgressRunnable)
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

        applyVoiceAndParams()
        val utteranceId = "glow_seg_$index"
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
        }
        androidTts?.speak(seg.text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
    }

    fun stopPlayback() {
        isPlayingInternal = false
        mainHandler.removeCallbacks(wordProgressRunnable)
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

    fun checkLocalPiperModels(): List<File> {
        val piperDir = File(context.filesDir, "piper_models")
        if (!piperDir.exists()) return emptyList()
        return piperDir.listFiles { f -> f.isFile && f.extension.equals("onnx", ignoreCase = true) }
            ?.toList()
            ?: emptyList()
    }

    fun listDownloadedNeuralItems(): List<File> {
        val dirs = listOf("piper_models", "kokoro_models", "pocket_tts_models", "cloned_voices")
        return dirs.flatMap { sub ->
            val d = File(context.filesDir, sub)
            if (!d.exists()) emptyList() else d.listFiles()?.filter { it.isFile }?.toList() ?: emptyList()
        }
    }

    suspend fun downloadFileFromUrl(
        urlStr: String,
        targetSubdir: String,
        outputFileName: String
    ): Result<File> = withContext(Dispatchers.IO) {
        val cleanUrl = urlStr.trim()
        if (!cleanUrl.startsWith("https://") && !cleanUrl.startsWith("http://")) {
            return@withContext Result.failure(IllegalArgumentException("URL inválido no Hugging Face."))
        }
        var conn: HttpURLConnection? = null
        return@withContext try {
            val dir = File(context.filesDir, targetSubdir).apply { if (!exists()) mkdirs() }
            val outFile = File(dir, outputFileName)
            conn = (URL(cleanUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 15000
                readTimeout = 30000
                setRequestProperty("User-Agent", "GlowReader/1.0")
            }
            if (conn.responseCode !in 200..299) {
                return@withContext Result.failure(IllegalStateException("HTTP ${conn.responseCode} ao descarregar modelo."))
            }
            conn.inputStream.use { input ->
                FileOutputStream(outFile).use { output ->
                    val buf = ByteArray(8192)
                    while (true) {
                        val read = input.read(buf)
                        if (read <= 0) break
                        output.write(buf, 0, read)
                    }
                    output.flush()
                }
            }
            if (!outFile.exists() || outFile.length() == 0L) {
                return@withContext Result.failure(IllegalStateException("Ficheiro descarregado vazio."))
            }
            Result.success(outFile)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            conn?.disconnect()
        }
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
        val candidates = buildList {
            val chosen = endpointUrl.trim()
            if (chosen.startsWith("http://") || chosen.startsWith("https://")) add(chosen)
            addAll(FALLBACK_ONLINE_ENDPOINTS)
        }.distinct()

        for (candidate in candidates) {
            var conn: HttpURLConnection? = null
            try {
                val sep = if (candidate.contains("?")) "&" else "?"
                val full = "$candidate${sep}text=${URLEncoder.encode(sampleText, "UTF-8")}"
                conn = (URL(full).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 7000
                    readTimeout = 7000
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
