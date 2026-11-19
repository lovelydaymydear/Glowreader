package com.glowreader.app

import android.content.Context
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

enum class ReflowMode(val label: String, val description: String) {
    AUTO_ADAPT(
        label = "Adaptar ao ecrã (Reflow)",
        description = "Une quebras duras de PDF/linhas curtas e organiza parágrafos para leitura fluida."
    ),
    ORIGINAL_LAYOUT(
        label = "Texto original",
        description = "Mantém a estrutura de linhas e quebras tal como foi extraída do ficheiro."
    )
}

enum class ResearchToolTarget(val label: String, val subtitle: String) {
    GOOGLE_SEARCH("Google Pesquisa", "Pesquisa profunda na web dentro do GlowReader."),
    AI_ASSISTANT("IA na App (Gemini Web)", "Abre assistente de IA para explicar conceitos, resumir ou debater."),
    GOOGLE_TRANSLATE("Google Tradutor", "Tradução rápida de palavras, frases ou trechos."),
    DEEPL_TRANSLATE("DeepL Tradutor", "Tradução contextual em múltiplos idiomas."),
    DICTIONARY_PT("Dicionário (Dicio)", "Definições, sinónimos e análise de palavras.")
}

data class CustomHighlightColor(
    val id: String,
    val name: String,
    val argb: Long
) {
    fun toComposeColor(): Color = Color(argb.toInt())
}

object ReadingEnhancementManager {

    private const val PREFS_NAME = "glowreader_enhancement_prefs"
    private const val KEY_CUSTOM_COLORS = "custom_highlight_colors_json"
    private const val KEY_REFLOW_MODE = "default_reflow_mode"
    private const val KEY_AI_URL = "custom_ai_url"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadDefaultReflowMode(context: Context): ReflowMode {
        val raw = prefs(context).getString(KEY_REFLOW_MODE, ReflowMode.AUTO_ADAPT.name)
        return try {
            ReflowMode.valueOf(raw ?: ReflowMode.AUTO_ADAPT.name)
        } catch (_: Exception) {
            ReflowMode.AUTO_ADAPT
        }
    }

    fun saveDefaultReflowMode(context: Context, mode: ReflowMode) {
        prefs(context).edit().putString(KEY_REFLOW_MODE, mode.name).apply()
    }

    fun loadCustomAiUrl(context: Context): String {
        return prefs(context).getString(KEY_AI_URL, "https://gemini.google.com/app")
            ?: "https://gemini.google.com/app"
    }

    fun saveCustomAiUrl(context: Context, url: String) {
        val clean = url.trim().ifBlank { "https://gemini.google.com/app" }
        prefs(context).edit().putString(KEY_AI_URL, clean).apply()
    }

    /**
     * Adapta o texto (especialmente PDFs com quebras de linha artificiais) para leitura fluida no ecrã.
     */
    fun applyReflow(rawText: String, mode: ReflowMode): String {
        if (rawText.isBlank() || mode == ReflowMode.ORIGINAL_LAYOUT) return rawText

        val normalizedNewlines = rawText.replace("\r\n", "\n").replace("\r", "\n")
        val rawParagraphs = normalizedNewlines.split(Regex("\\n\\s*\\n+"))

        return rawParagraphs.mapNotNull { block ->
            val lines = block.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) return@mapNotNull null

            val merged = StringBuilder()
            lines.forEachIndexed { idx, line ->
                if (idx == 0) {
                    merged.append(line)
                } else {
                    val prev = merged.toString()
                    // Se a linha anterior termina com hífen de quebra de palavra (ex.: "psicolo-\ngia")
                    if (prev.endsWith("-") && !prev.endsWith("--") && line.firstOrNull()?.isLowerCase() == true) {
                        merged.setLength(merged.length - 1)
                        merged.append(line)
                    } else {
                        merged.append(" ").append(line)
                    }
                }
            }
            merged.toString().replace(Regex("[ \\t]{2,}"), " ").trim()
        }.joinToString("\n\n")
    }

    val presetPaletteColors: List<CustomHighlightColor> = listOf(
        CustomHighlightColor("preset_yellow", "Amarelo Clássico", 0xFFFFEB3B),
        CustomHighlightColor("preset_green", "Verde Menta", 0xFFA5D6A7),
        CustomHighlightColor("preset_cyan", "Azul Céu", 0xFF81D4FA),
        CustomHighlightColor("preset_pink", "Rosa Suave", 0xFFF48FB1),
        CustomHighlightColor("preset_lavender", "Lilás Estudo", 0xFFCE93D8),
        CustomHighlightColor("preset_orange", "Laranja Quente", 0xFFFFCC80),
        CustomHighlightColor("preset_coral", "Coral", 0xFFFF8A80),
        CustomHighlightColor("preset_mint", "Turquesa", 0xFF80CBC4)
    )

    fun mixColors(colorA: Color, colorB: Color, ratio: Float = 0.5f): Color {
        val t = ratio.coerceIn(0f, 1f)
        val inv = 1f - t
        return Color(
            red = (colorA.red * inv + colorB.red * t).coerceIn(0f, 1f),
            green = (colorA.green * inv + colorB.green * t).coerceIn(0f, 1f),
            blue = (colorA.blue * inv + colorB.blue * t).coerceIn(0f, 1f),
            alpha = 1f
        )
    }

    fun buildColorFromRgb(red: Int, green: Int, blue: Int): Color {
        val r = red.coerceIn(0, 255)
        val g = green.coerceIn(0, 255)
        val b = blue.coerceIn(0, 255)
        val argb = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        return Color(argb)
    }

    fun colorToArgbLong(color: Color): Long {
        val r = (color.red * 255f).toInt().coerceIn(0, 255)
        val g = (color.green * 255f).toInt().coerceIn(0, 255)
        val b = (color.blue * 255f).toInt().coerceIn(0, 255)
        return ((0xFFL shl 24) or (r.toLong() shl 16) or (g.toLong() shl 8) or b.toLong())
    }

    fun loadCustomColors(context: Context): List<CustomHighlightColor> {
        val raw = prefs(context).getString(KEY_CUSTOM_COLORS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<CustomHighlightColor>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val id = obj.optString("id")
                val name = obj.optString("name", "Cor Personalizada")
                val argb = obj.optLong("argb", 0xFFFFE082)
                if (id.isNotBlank()) {
                    list.add(CustomHighlightColor(id = id, name = name, argb = argb))
                }
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveCustomColor(context: Context, name: String, color: Color): List<CustomHighlightColor> {
        val current = loadCustomColors(context).toMutableList()
        val cleanName = name.trim().ifBlank { "Mistura ${current.size + 1}" }
        val item = CustomHighlightColor(
            id = "custom_${System.currentTimeMillis()}",
            name = cleanName,
            argb = colorToArgbLong(color)
        )
        current.add(0, item)
        val capped = current.take(24)

        val arr = JSONArray()
        capped.forEach { c ->
            arr.put(
                JSONObject().apply {
                    put("id", c.id)
                    put("name", c.name)
                    put("argb", c.argb)
                }
            )
        }
        prefs(context).edit().putString(KEY_CUSTOM_COLORS, arr.toString()).apply()
        return capped
    }

    fun resolveColorByNameOrArgb(
        context: Context,
        colorKey: String
    ): Color {
        try {
            return HighlightColor.valueOf(colorKey).toColor()
        } catch (_: Exception) {
        }
        presetPaletteColors.firstOrNull { it.id == colorKey || it.name.equals(colorKey, ignoreCase = true) }?.let {
            return it.toComposeColor()
        }
        loadCustomColors(context).firstOrNull { it.id == colorKey || it.name.equals(colorKey, ignoreCase = true) }?.let {
            return it.toComposeColor()
        }
        colorKey.toLongOrNull()?.let { parsed ->
            return Color(parsed.toInt())
        }
        return HighlightColor.YELLOW.toColor()
    }

    fun buildResearchUrl(
        context: Context,
        target: ResearchToolTarget,
        query: String,
        targetLang: String = "pt"
    ): Pair<String, String> {
        val clean = query.trim()
        val encoded = URLEncoder.encode(clean, "UTF-8")
        val lang = targetLang.trim().lowercase().ifBlank { "pt" }

        return when (target) {
            ResearchToolTarget.GOOGLE_SEARCH -> {
                val title = if (clean.isBlank()) "Google Pesquisa" else "Google: $clean"
                val url = if (clean.isBlank()) {
                    "https://www.google.com/"
                } else {
                    "https://www.google.com/search?q=$encoded"
                }
                title to url
            }

            ResearchToolTarget.AI_ASSISTANT -> {
                val baseAiUrl = loadCustomAiUrl(context)
                val title = if (clean.isBlank()) "Assistente de IA" else "IA: $clean"
                title to baseAiUrl
            }

            ResearchToolTarget.GOOGLE_TRANSLATE -> {
                val title = if (clean.isBlank()) "Google Tradutor" else "Traduzir ($lang): $clean"
                val url = "https://translate.google.com/?sl=auto&tl=$lang&text=$encoded"
                title to url
            }

            ResearchToolTarget.DEEPL_TRANSLATE -> {
                val title = if (clean.isBlank()) "DeepL Tradutor" else "DeepL ($lang): $clean"
                val url = "https://www.deepl.com/translator#auto/$lang/$encoded"
                title to url
            }

            ResearchToolTarget.DICTIONARY_PT -> {
                val title = if (clean.isBlank()) "Dicionário" else "Dicionário: $clean"
                val url = if (clean.isBlank()) {
                    "https://www.dicio.com.br/"
                } else {
                    "https://www.dicio.com.br/$encoded/"
                }
                title to url
            }
        }
    }

    /**
     * Traduz um bloco de texto via endpoint público leve do Google Translate quando há ligação à internet.
     * Caso o texto seja longo, divide em partes seguras para não truncar.
     */
    suspend fun translateTextBlock(
        text: String,
        targetLang: String = "pt"
    ): Result<String> = withContext(Dispatchers.IO) {
        val clean = text.trim()
        if (clean.isEmpty()) return@withContext Result.success("")

        val safeTarget = targetLang.trim().lowercase().ifBlank { "pt" }
        val chunks = splitIntoChunks(clean, maxChars = 1100)
        val out = StringBuilder()

        for (chunk in chunks) {
            var conn: HttpURLConnection? = null
            try {
                val encodedText = URLEncoder.encode(chunk, "UTF-8")
                val urlStr =
                    "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=$safeTarget&dt=t&q=$encodedText"
                conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 10000
                    readTimeout = 12000
                    setRequestProperty("User-Agent", "Mozilla/5.0")
                }
                if (conn.responseCode !in 200..299) {
                    return@withContext Result.failure(
                        IllegalStateException("Serviço de tradução retornou código ${conn.responseCode}.")
                    )
                }
                val rawJson = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val translatedChunk = parseGoogleTranslateResponse(rawJson)
                if (translatedChunk.isBlank()) {
                    return@withContext Result.failure(
                        IllegalStateException("Não foi possível extrair a tradução do trecho.")
                    )
                }
                if (out.isNotEmpty()) out.append("\n\n")
                out.append(translatedChunk.trim())
            } catch (e: Exception) {
                return@withContext Result.failure(
                    IllegalStateException("Sem ligação ou erro ao traduzir: ${e.message ?: "erro de rede"}")
                )
            } finally {
                conn?.disconnect()
            }
        }

        Result.success(out.toString())
    }

    private fun splitIntoChunks(text: String, maxChars: Int): List<String> {
        if (text.length <= maxChars) return listOf(text)
        val paragraphs = text.split("\n\n")
        val result = mutableListOf<String>()
        val current = StringBuilder()

        for (p in paragraphs) {
            if (p.length > maxChars) {
                if (current.isNotEmpty()) {
                    result.add(current.toString().trim())
                    current.clear()
                }
                var idx = 0
                while (idx < p.length) {
                    val end = (idx + maxChars).coerceAtMost(p.length)
                    result.add(p.substring(idx, end).trim())
                    idx = end
                }
            } else {
                if (current.length + p.length + 2 > maxChars) {
                    result.add(current.toString().trim())
                    current.clear()
                }
                if (current.isNotEmpty()) current.append("\n\n")
                current.append(p)
            }
        }
        if (current.isNotEmpty()) {
            result.add(current.toString().trim())
        }
        return result.filter { it.isNotEmpty() }
    }

    private fun parseGoogleTranslateResponse(rawJson: String): String {
        return try {
            val rootArray = JSONArray(rawJson)
            val firstLevel = rootArray.optJSONArray(0) ?: return ""
            val sb = StringBuilder()
            for (i in 0 until firstLevel.length()) {
                val item = firstLevel.optJSONArray(i)
                if (item != null && item.length() > 0) {
                    val seg = item.optString(0, "")
                    if (seg != "null") sb.append(seg)
                }
            }
            sb.toString()
        } catch (_: Exception) {
            ""
        }
    }
}
