package io.mo.glassmic.audio.tts

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import dagger.hilt.android.qualifiers.ApplicationContext
import io.mo.glassmic.log.GlassLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** Fish 账户额度：API 余额（应用调用扣这里）与套餐额度（网站上的已用 / 总额），任一项取不到为 null。 */
data class FishCredits(
    val apiCredit: String? = null,
    val planTotal: Double? = null,
    val planBalance: Double? = null
)

/** 一个 Fish 声音。[mine] 表示来自自己账号（「我的声音」），否则是搜索后选用过的公开声音。 */
data class FishVoice(
    val id: String,
    val title: String,
    val coverUrl: String = "",
    val mine: Boolean = false
)

/**
 * Fish 声音列表与封面缓存，供悬浮窗音色下拉使用。
 *
 * - 列表：「我的声音」从 GET /model?self=true 拉取（打开面板时刷新，10 分钟内不重复请求），
 *   设置页搜索后选用的公开声音也记在这里，保证封面和名字能显示。整体存为 filesDir 下的 JSON。
 * - 封面：按 URL 下载、缩到 [COVER_PX] 后缓存到 cacheDir 和内存。下载失败时 UI 显示首字母头像。
 */
@Singleton
class FishVoiceRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val aiTts: AiTtsSynthesizer
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val listFile = File(context.filesDir, "fish_voices.json")
    private val coverDir = File(context.cacheDir, "fish_covers")
    private val memCovers = LruCache<String, Bitmap>(64)
    private val coverLock = Mutex()

    private val _voices = MutableStateFlow(load())
    val voices: StateFlow<List<FishVoice>> = _voices.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    @Volatile private var lastRefreshMs = 0L

    /** 已尝试按 ID 查询过的声音（成功或失败都记下，避免反复请求）。 */
    private val lookedUp = java.util.Collections.synchronizedSet(HashSet<String>())

    private val _credit = MutableStateFlow<FishCredits?>(null)
    /** Fish 额度（未查询时为 null）。 */
    val credit: StateFlow<FishCredits?> = _credit.asStateFlow()
    @Volatile private var lastCreditMs = 0L

    /** 刷新余额。[force] 为 false 时 30 秒内只查一次（生成完成后会强制刷新）。 */
    fun refreshCredit(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastCreditMs < CREDIT_INTERVAL_MS) return
        lastCreditMs = now
        scope.launch {
            // 两个接口各查各的，一个失败不影响另一个显示
            val api = runCatching { aiTts.fetchFishCredit() }
                .onFailure { GlassLog.b("Tts") { "Fish API 余额查询失败: ${it.message}" } }
                .getOrNull()
            val plan = runCatching { aiTts.fetchFishPackage() }
                .onFailure { GlassLog.b("Tts") { "Fish 套餐额度查询失败: ${it.message}" } }
                .getOrNull()
            if (api != null || plan != null) {
                _credit.value = FishCredits(api, plan?.first, plan?.second)
            }
        }
    }

    /**
     * 确保这些声音 ID 的名称和封面已知：「我的声音」列表只含自己克隆的声音，
     * 手填的 ID、在 Fish 网站收藏的声音不在其中，这里按 ID 逐个查询 GET /model/{id} 后记住。
     */
    fun ensureKnown(ids: Collection<String>) {
        val missing = ids.map { it.trim() }.filter { it.isNotEmpty() && find(it) == null && lookedUp.add(it) }
        if (missing.isEmpty()) return
        scope.launch {
            for (id in missing) {
                runCatching { aiTts.fetchFishVoice(id) }
                    .onSuccess { v -> if (v != null) remember(v) }
                    .onFailure { GlassLog.b("Tts") { "Fish 声音查询失败 $id: ${it.message}" } }
            }
        }
    }

    /** 刷新「我的声音」。[force] 为 false 时 10 分钟内只拉一次。 */
    fun refreshMine(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastRefreshMs < REFRESH_INTERVAL_MS) return
        if (_refreshing.value) return
        lastRefreshMs = now
        _refreshing.value = true
        scope.launch {
            runCatching { aiTts.fetchFishVoices("") }
                .onSuccess { mine ->
                    _voices.update { old ->
                        // 自己的声音以服务器为准；其余（搜索选用的）保留
                        val others = old.filter { !it.mine && mine.none { m -> m.id == it.id } }
                        mine.map { it.copy(mine = true) } + others
                    }
                    save()
                }
                .onFailure { GlassLog.b("Tts") { "Fish 声音列表刷新失败: ${it.message}" } }
            _refreshing.value = false
        }
    }

    /** 记住一个声音（设置页搜索后选用），以便悬浮窗显示它的名字和封面。 */
    fun remember(voice: FishVoice) {
        _voices.update { old ->
            if (old.any { it.id == voice.id }) old.map { if (it.id == voice.id && !it.mine) voice else it }
            else old + voice
        }
        scope.launch { save() }
    }

    fun find(id: String): FishVoice? = _voices.value.firstOrNull { it.id == id }

    /** 加载封面（内存 → 磁盘 → 网络）。失败返回 null。 */
    suspend fun cover(rawUrl: String): Bitmap? {
        val url = coverUrl(rawUrl) ?: return null
        memCovers.get(url)?.let { return it }
        return withContext(Dispatchers.IO) {
            coverLock.withLock {
                memCovers.get(url)?.let { return@withLock it }
                val file = File(coverDir, sha1(url) + ".png")
                val bmp = runCatching {
                    if (file.exists()) BitmapFactory.decodeFile(file.path)
                    else download(url)?.also { b ->
                        coverDir.mkdirs()
                        file.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    }
                }.getOrNull()
                bmp?.also { memCovers.put(url, it) }
            }
        }
    }

    private fun download(url: String): Bitmap? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
        }
        return try {
            if (conn.responseCode !in 200..299) return null
            val bytes = conn.inputStream.use { it.readBytes() }
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            var sample = 1
            while (opts.outWidth / (sample * 2) >= COVER_PX && opts.outHeight / (sample * 2) >= COVER_PX) sample *= 2
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return null
            if (decoded.width <= COVER_PX * 2) decoded
            else Bitmap.createScaledBitmap(decoded, COVER_PX * 2, COVER_PX * 2 * decoded.height / decoded.width, true)
        } finally {
            conn.disconnect()
        }
    }

    /** Fish 返回的 cover_image 可能是完整 URL，也可能是存储桶内的相对路径。 */
    private fun coverUrl(raw: String): String? {
        val s = raw.trim()
        return when {
            s.isEmpty() -> null
            s.startsWith("http://") || s.startsWith("https://") -> s
            else -> COVER_CDN + s.trimStart('/')
        }
    }

    private fun load(): List<FishVoice> = runCatching {
        if (!listFile.exists()) return emptyList()
        val arr = JSONArray(listFile.readText())
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            FishVoice(id, o.optString("title").ifBlank { id }, o.optString("cover"), o.optBoolean("mine"))
        }
    }.getOrDefault(emptyList())

    private fun save() {
        runCatching {
            val arr = JSONArray()
            _voices.value.forEach {
                arr.put(JSONObject().put("id", it.id).put("title", it.title).put("cover", it.coverUrl).put("mine", it.mine))
            }
            listFile.writeText(arr.toString())
        }
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        const val REFRESH_INTERVAL_MS = 10 * 60_000L
        const val CREDIT_INTERVAL_MS = 30_000L
        const val COVER_PX = 64
        const val COVER_CDN = "https://public-platform.r2.fish.audio/"
    }
}
