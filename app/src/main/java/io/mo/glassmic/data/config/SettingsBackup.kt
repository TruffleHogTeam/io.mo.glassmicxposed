package io.mo.glassmic.data.config

import android.content.Context
import android.net.Uri
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import io.mo.glassmic.proto.AppConfig
import io.mo.glassmic.proto.FishTextMode
import io.mo.glassmic.proto.TtsAiConfig
import io.mo.glassmic.proto.TtsProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 设置导出 / 导入（JSON）。
 *
 * 文件结构：
 * ```
 * { "app": "GlassMic", "format": 1, "exported_at": <ms>,
 *   "tts_ai": { 可读的 AI TTS 设置，含 API Key、音色、人设 },
 *   "config": "<AppConfig protobuf 的 base64>" }
 * ```
 * `config` 保存全部设置（protobuf 能容忍新旧版本字段差异）；`tts_ai` 方便查看和手改，导入时覆盖在 `config` 之上。
 * 音频文件、图标、背景图、参考音频不在其中，导入后清掉指向这些文件的路径与当前选中的音频。
 * 注意：API Key 以明文写入文件。
 */
@Singleton
class SettingsBackup @Inject constructor(
    @ApplicationContext private val context: Context,
    private val configStore: ConfigStore
) {

    suspend fun export(uri: Uri) = withContext(Dispatchers.IO) {
        val cfg = configStore.current()
        val json = JSONObject()
            .put("app", APP_TAG)
            .put("format", FORMAT)
            .put("exported_at", System.currentTimeMillis())
            .put("tts_ai", aiToJson(cfg.tts.ai))
            .put("config", Base64.encodeToString(cfg.toByteArray(), Base64.NO_WRAP))
        val out = context.contentResolver.openOutputStream(uri, "wt") ?: error("cannot open output")
        out.use { it.write(json.toString(2).toByteArray(Charsets.UTF_8)) }
    }

    /** 读取并应用备份。文件不是 GlassMic 备份时抛 [IllegalArgumentException]。 */
    suspend fun import(uri: Uri) = withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: error("cannot open input")
        val json = runCatching { JSONObject(text) }.getOrNull()
        require(json != null && json.optString("app") == APP_TAG) { "not a GlassMic backup" }

        val base = json.optString("config").takeIf { it.isNotBlank() }?.let {
            AppConfig.parseFrom(Base64.decode(it, Base64.DEFAULT))
        }
        val aiJson = json.optJSONObject("tts_ai")
        require(base != null || aiJson != null) { "backup is empty" }

        configStore.update { b ->
            if (base != null) b.clear().mergeFrom(base)
            if (aiJson != null) {
                val ai = jsonToAi(aiJson, b.tts.ai.toBuilder())
                b.setTts(b.tts.toBuilder().setAi(ai))
            }
            // 这些都指向本机文件 / 数据库条目，换机后无效
            b.currentAudioId = ""
            b.currentGroupId = ""
            b.setFloatingWindow(b.floatingWindow.toBuilder().setCustomIconPath(""))
            b.setAppearance(b.appearance.toBuilder().setBackgroundImagePath(""))
            b.setTts(b.tts.toBuilder().setAi(b.tts.ai.toBuilder().setCloneSamplePath("")))
        }
    }

    private fun aiToJson(ai: TtsAiConfig) = JSONObject()
        .put("enabled", ai.enabled)
        .put("provider", ai.provider.name)
        .put("endpoint", ai.endpoint)
        .put("api_key", ai.apiKey)
        .put("model", ai.model)
        .put("voice", ai.voice)
        .put("format", ai.format)
        .put("sample_rate", ai.sampleRate)
        .put("style_prompt", ai.stylePrompt)
        .put("fish_auto_tag", ai.fishAutoTag)
        .put("fish_humanize", ai.fishHumanize)
        .put("fish_voices", ai.fishVoices)
        .put("fish_text_mode", ai.fishTextMode.name)
        .put("persona_prompt", ai.personaPrompt)
        .put("llm_endpoint", ai.llmEndpoint)
        .put("llm_api_key", ai.llmApiKey)
        .put("llm_model", ai.llmModel)
        .apply { if (ai.hasLlmTemperature()) put("llm_temperature", ai.llmTemperature.toDouble()) }

    /** 只覆盖 JSON 里出现的字段，缺的保持 [into] 原值。 */
    private fun jsonToAi(o: JSONObject, into: TtsAiConfig.Builder): TtsAiConfig.Builder = into.apply {
        if (o.has("enabled")) enabled = o.optBoolean("enabled")
        o.optString("provider").takeIf { o.has("provider") }?.let { name ->
            runCatching { TtsProvider.valueOf(name) }.getOrNull()?.takeIf { it != TtsProvider.UNRECOGNIZED }?.let { provider = it }
        }
        if (o.has("endpoint")) endpoint = o.optString("endpoint")
        if (o.has("api_key")) apiKey = o.optString("api_key")
        if (o.has("model")) model = o.optString("model")
        if (o.has("voice")) voice = o.optString("voice")
        if (o.has("format")) format = o.optString("format")
        if (o.has("sample_rate")) sampleRate = o.optInt("sample_rate")
        if (o.has("style_prompt")) stylePrompt = o.optString("style_prompt")
        if (o.has("fish_auto_tag")) fishAutoTag = o.optBoolean("fish_auto_tag")
        if (o.has("fish_humanize")) fishHumanize = o.optBoolean("fish_humanize")
        if (o.has("fish_voices")) fishVoices = o.optString("fish_voices")
        o.optString("fish_text_mode").takeIf { o.has("fish_text_mode") }?.let { name ->
            runCatching { FishTextMode.valueOf(name) }.getOrNull()?.takeIf { it != FishTextMode.UNRECOGNIZED }?.let { fishTextMode = it }
        }
        if (o.has("persona_prompt")) personaPrompt = o.optString("persona_prompt")
        if (o.has("llm_endpoint")) llmEndpoint = o.optString("llm_endpoint")
        if (o.has("llm_api_key")) llmApiKey = o.optString("llm_api_key")
        if (o.has("llm_model")) llmModel = o.optString("llm_model")
        if (o.has("llm_temperature")) llmTemperature = o.optDouble("llm_temperature").toFloat()
    }

    private companion object {
        const val APP_TAG = "GlassMic"
        const val FORMAT = 1
    }
}
