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
            "DELIVERY (Fish Audio S2 / S2.1):\n" +
                "- Delivery tags go in square brackets and affect the speech that follows them.\n" +
                "- Start with ONE emotional tone tag that fits the overall mood.\n" +
                "- Tags are free-form, but these work best: tones " +
                FishAutoTag.TONE_TAGS.joinToString(", ") { "[$it]" } + "; effects " +
                FishAutoTag.EFFECT_TAGS.joinToString(", ") { "[$it]" } + ".\n" +
                "- Add tags where a real person would react, roughly one per sentence, never two in a " +
                "row. Use [pause] for natural beats and switch tone when the mood shifts.\n" +
                "- Do not use angle-bracket tags.\n" +
                "- If an *asterisk action* is a sound (e.g. *giggles*), turn it into the closest tag.\n"
        }
}
