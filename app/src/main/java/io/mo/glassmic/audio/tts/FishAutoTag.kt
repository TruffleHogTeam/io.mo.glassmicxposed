package io.mo.glassmic.audio.tts

/**
 * Fish Audio 标签清洗：合成前让 LLM 插入的 [方向] / <成对标签> 经这里规整后再送去合成。
 *
 * S2 / S2.1 的 [xxx] 是自由文本方向（不限固定列表），Drama 3 额外支持 7 个成对标签
 * （<whisper>…</whisper> 等，只作用于包住的词）。这里只负责清洗，提示词见 [FishPrompts]，
 * 网络请求由 [AiTtsSynthesizer] 发起。
 */
internal object FishAutoTag {

    /** 情绪语气标签示例（Fish 标签面板 Emotional Tone）。S2.1 起不限于这些，提示词里作为参考。 */
    val TONE_TAGS = listOf(
        "angry", "sad", "embarrassed", "emphasis", "whispering", "soft", "breathy", "excited"
    )

    /** 音效标签示例（Fish 标签面板 Audio Effects）。群体笑声类不适合一对一语音，未列入。 */
    val EFFECT_TAGS = listOf(
        "laughing", "chuckling", "moaning", "clear throat", "sobbing", "crying loudly",
        "sighing", "panting", "groaning", "pause", "long pause"
    )

    /** Drama 3 的成对标签：<name>词</name>，只改变被包住的词。 */
    val PAIRED_TAGS = listOf("whisper", "emphasis", "soft", "fast", "slow", "stress", "forceful")

    const val DRAMA_MODEL = "drama-3"

    fun supportsPairedTags(model: String): Boolean = model.trim().lowercase().startsWith("drama")

    private val bracketRegex = Regex("\\[([^\\[\\]\\n]{1,60})]")
    private val pairedRegex = Regex(
        "<(${PAIRED_TAGS.joinToString("|")})>([^<>\\n]{1,200})</\\1>",
        RegexOption.IGNORE_CASE
    )
    private val angleRegex = Regex("</?[A-Za-z][A-Za-z ]{0,20}>")
    private val emojiRegex = Regex("[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{FE0F}\\x{200D}]+")
    private val actionRegex = Regex("\\*[^*\\n]{1,80}\\*")
    private val spacesRegex = Regex("[ \\t]{2,}")

    // 清洗时用的占位符，保证合法标签不被后面的「去落单括号」误伤
    private const val OPEN_SQ = '\u0000'
    private const val CLOSE_SQ = '\u0001'
    private const val OPEN_ANG = '\u0002'
    private const val CLOSE_ANG = '\u0003'

    /**
     * 去掉 Fish 会照读的内容：emoji、多余空白，[actions] 为 true 时连同 *动作* 一起去掉。
     * 未开自动标签时传 false：用户原文里的 *…* 可能是算式（2*3*4）等正文。
     */
    fun stripUnspeakable(text: String, actions: Boolean = true): String =
        (if (actions) text.replace(actionRegex, "") else text)
            .replace(emojiRegex, "").replace(spacesRegex, " ").trim()

    /**
     * 规整标签：保留 1–60 字的 [方向]；[model] 为 Drama 时保留成对且名字合法的 <tag>…</tag>，
     * 其余尖括号标签删掉（内容保留）；落单的 [ ] < > 一律去掉——LLM 输出被截断时会留下半个括号，
     * Fish 会照读甚至返回空音频。
     */
    fun keepFishTags(text: String, model: String = ""): String {
        var s = text
        if (supportsPairedTags(model)) {
            s = pairedRegex.replace(s) { m ->
                val name = m.groupValues[1].lowercase()
                "${OPEN_ANG}$name${CLOSE_ANG}${m.groupValues[2]}${OPEN_ANG}/$name${CLOSE_ANG}"
            }
        }
        s = angleRegex.replace(s, "")
        s = bracketRegex.replace(s) { m ->
            val body = m.groupValues[1].trim()
            if (body.isEmpty()) "" else "$OPEN_SQ$body$CLOSE_SQ"
        }
        return s
            .replace("[", "").replace("]", "").replace("<", "").replace(">", "")
            .replace(OPEN_SQ, '[').replace(CLOSE_SQ, ']')
            .replace(OPEN_ANG, '<').replace(CLOSE_ANG, '>')
            .replace(spacesRegex, " ").trim()
    }

    private val trailingTagRegex = Regex("\\s*\\[[^\\[\\]\\n]{1,60}]\\s*$")

    /**
     * 去掉结尾处的 [方向]：标签只作用于后面的内容，放在末尾没有可作用的文字，
     * Fish 可能把它读出来或生成怪声（Fish 官方提示词同样要求「不要以标签结尾」）。
     */
    fun dropTrailingTags(text: String): String {
        var s = text.trimEnd()
        while (true) {
            val next = trailingTagRegex.replace(s, "")
            if (next == s) return s
            s = next.trimEnd()
        }
    }

    /** 文本里是否已经带有标签（例如已优化过的文本被再次生成）。 */
    fun hasFishTags(text: String): Boolean =
        bracketRegex.containsMatchIn(text) || pairedRegex.containsMatchIn(text)

    /** 去掉全部 [方向] 与 <标签>，只留要读出来的文字（复制到剪贴板用）。 */
    fun plainText(text: String): String =
        angleRegex.replace(bracketRegex.replace(text, " "), "")
            .replace(Regex("\\s+"), " ").trim()

    /** LLM 输出可能带换行 / 多余空白，Fish 会读坏，压成单行。 */
    fun singleLine(text: String): String = text.replace(Regex("\\s+"), " ").trim()

    /** 去掉全部标签后是否还有可读内容（用来判断 LLM 输出是否可用）。 */
    fun hasSpeakableText(text: String): Boolean = plainText(text).isNotBlank()
}
