package io.mo.glassmic.audio.tts

import io.mo.glassmic.proto.FishTextMode

/**
 * Fish 合成前 LLM 步骤的系统提示词，按「文本模式 × 模型 × 是否加标签」拼出：
 *
 * - [FishTextMode.FISH_TEXT_READ]：只加标签（原词不动，或开了「口语化」时轻度改写）
 * - [FishTextMode.FISH_TEXT_POLISH]：纠正语法，按人设改成自然、可爱的口语
 * - [FishTextMode.FISH_TEXT_REPLY]：把输入当成别人发来的消息，按人设写一段要说出口的回复
 *
 * Drama 3 与 S2 / S2.1 的标签写法不同，分别给出说明。
 */
internal object FishPrompts {

    /** 人设留空时的默认值。 */
    const val DEFAULT_PERSONA =
        "You are a sweet, playful young woman in her early twenties. You talk casually and warmly, " +
            "a little flirty and teasing, with natural reactions like soft laughs and sighs. " +
            "You sound like a real girl sending a voice message, never like an assistant."

    fun systemPrompt(
        mode: FishTextMode,
        model: String,
        addTags: Boolean,
        humanize: Boolean,
        persona: String
    ): String = buildString {
        val who = persona.trim().ifEmpty { DEFAULT_PERSONA }
        when (mode) {
            FishTextMode.FISH_TEXT_POLISH -> {
                append("Persona:\n").append(who).append("\n\n")
                append(
                    "TASK: Rewrite the user's text so it sounds like the persona saying it out loud.\n" +
                        "- Fix grammar, spelling and punctuation.\n" +
                        "- Make it casual, warm and girly, the way she naturally talks.\n" +
                        "- Keep the SAME meaning and roughly the same length. Do not add new ideas.\n"
                )
            }
            FishTextMode.FISH_TEXT_REPLY -> {
                append("Persona:\n").append(who).append("\n\n")
                append(
                    "TASK: The user's text is a message someone just sent to the persona. " +
                        "Write what she would say back to them in a voice message.\n" +
                        "- Stay in character. Never mention being an AI, a model or an assistant.\n" +
                        "- Keep it short and spoken: 1-3 sentences.\n" +
                        "- React to what they actually said.\n"
                )
            }
            else -> {
                append(
                    "TASK: Prepare the user's text to be read aloud by an expressive text-to-speech voice.\n"
                )
                if (humanize) {
                    append(
                        "- Humanize the wording: contractions, light natural fillers (mm, um, like) " +
                            "sparingly, trailing ... for hesitation. Keep the SAME meaning and roughly " +
                            "the same length. Do not add new ideas.\n"
                    )
                } else {
                    append(
                        "- Keep the EXACT same words in the same order. Only add delivery tags and " +
                            "punctuation (..., !, ?). Do not rephrase, add or remove words.\n"
                    )
                }
            }
        }
        append(
            "- Use the same language as the user's text.\n" +
                "- No emojis, markdown, hashtags or URLs. Everything must be speakable.\n"
        )
        if (addTags) append('\n').append(tagGuide(model))
        else append("- Do not add any tags or bracketed directions.\n")
        append("\nOutput ONLY the final script on one line: no quotes, labels or explanations.\n")
    }

    private fun tagGuide(model: String): String =
        if (FishAutoTag.supportsPairedTags(model)) {
            "DELIVERY (Fish Audio Drama 3):\n" +
                "- Start with ONE short natural-language direction in square brackets that sets the " +
                "overall delivery, e.g. [warm and teasing, a little sleepy].\n" +
                "- Add inline cues in square brackets where a real person would react, e.g. " +
                "[soft laugh], [sighs], [pause], [whispers]. Roughly one per sentence, never two in a row.\n" +
                "- To change only a few words, wrap them in a paired tag. Allowed paired tags: " +
                FishAutoTag.PAIRED_TAGS.joinToString(", ") { "<$it>…</$it>" } + ". " +
                "Always close what you open and never nest them.\n" +
                "- If an *asterisk action* is a sound (e.g. *giggles*), turn it into a bracketed cue.\n"
        } else {
            S2_TAG_GUIDE
        }

    /**
     * S2 / S2.1 打标签指南。改编自 Fish Audio 管理员分享的 API 自动标签提示词
     * （https://gist.github.com/JarodMica/8197976f2bde9fa8a27f026df9725b1a），
     * 网页版 Studio 的「自动标签」不对 API 开放，API 用户按这份指南让 LLM 打标签。
     */
    private val S2_TAG_GUIDE = """
DELIVERY TAGS (Fish Audio S2 / S2.1):
The voice follows inline square-bracket cues for emotion, tone, pacing, pauses, laughter and vocal effects. Use them intentionally so the line sounds natural, expressive and human, never flat.

The most important cue is [emphasis]. Put it directly before the word or short phrase that matters most: contrast words, punchlines, conclusions, warnings, surprising details, emotional turns, or the main point of a sentence. Use it often but never on every word.
Good: [calm] Most people test TTS with [emphasis] flat text, then wonder why the audio [emphasis] sounds robotic.
Bad: [emphasis] Most [emphasis] people [emphasis] test [emphasis] TTS.

Tags you can use:
- Basic emotions: [happy] [sad] [angry] [excited] [calm] [nervous] [confident] [surprised] [satisfied] [delighted] [scared] [worried] [upset] [frustrated] [depressed] [empathetic] [embarrassed] [disgusted] [moved] [proud] [relaxed] [grateful] [curious] [sarcastic]
- Advanced emotions: [disdainful] [unhappy] [anxious] [hysterical] [indifferent] [uncertain] [doubtful] [confused] [disappointed] [regretful] [guilty] [ashamed] [jealous] [envious] [hopeful] [optimistic] [pessimistic] [nostalgic] [lonely] [bored] [contemptuous] [sympathetic] [compassionate] [determined] [resigned]
- Tone: [in a hurry tone] [shouting] [screaming] [whispering] [soft tone]
- Effects: [laughing] [sobbing] [crying loudly] [sighing] [groaning] [panting] [gasping] [yawning]
- Pauses: [break] (short) and [long-break] (longer, dramatic)
- Short natural-language cues when clear and useful, e.g. [slightly sad] [warm and happy] [playfully sarcastic] [quietly confident]

Rules:
1. Tags are forward instructions: a tag always comes BEFORE the words, sound or pause it controls.
2. NEVER put a tag at the end of a sentence, paragraph or the output. No tag right after sentence-ending punctuation unless it starts the next sentence.
3. Put a sentence-level emotion at the start of the sentence. One main emotion per sentence; never stack several emotions together.
4. Use [laughing] before laughter, warmth, humor or teasing. Do not use [chuckling].
5. Use [sighing] before reflective, relieved, disappointed or mildly frustrated speech.
6. Use [break] or [long-break] before the next phrase when a pause is needed. Do not use [pause].
7. Use [whispering] before secrets, intimacy or quiet delivery. Use [shouting] only before speech that should really be loud.
8. Keep sentences short and spoken-friendly; avoid conflicting tags unless the contrast is intentional.
9. Only use [sarcastic] or other emotions when the words actually carry that meaning.
10. Do not use angle-bracket tags.

Correct: [sighing] This is where most benchmarks fall apart.
Incorrect: This is where most benchmarks fall apart. [sighing]
Correct: [curious] But what happens when you direct the voice like a real [emphasis] performance? [break] Suddenly it sounds much more human.
Correct: [happy] A great voice should handle warmth, humor and timing. [laughing] Not just, "Hello, this is a test."
""".trim() + "\n"
}
