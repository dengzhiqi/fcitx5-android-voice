/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.input.voice

object VoiceTranscriptionNormalizer {
    private val terminalTokens = Regex("<eop>|<\\|im_end\\|>|<\\|endoftext\\|>")
    private val thinking = Regex("<think>.*?</think>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val openThinking = Regex("<think>.*$", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    // 过滤 SenseVoice、Whisper 等模型产生的特殊控制 token，如 <|zh|>, <|NEUTRAL|>, <|Speech|>, <|woitn|>, <|withitn|> 等
    private val specialTokens = Regex("<\\|[a-zA-Z0-9_.-]+\\|>")

    private val chineseQuotedWrapper = Regex(
        "^(?:上传的)?音频(?:内容)?(?:是|为|中说的是)[：:]?\\s*[“\"](.*)[”\"]" +
            "(?:[。.]?\\s*(?:如果|如有).*)?$",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )
    private val englishQuotedWrapper = Regex(
        "^(?:the )?(?:uploaded )?audio(?: content)?(?: says| is| transcription is)?[：:]?\\s*[“\"](.*)[”\"]" +
            "(?:[。.]?\\s*(?:if|let me know).*)?$",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )
    private val resultPrefix = Regex("^(?:转写|识别)(?:结果|文本)?[：:]\\s*")
    private val wrapperStart = Regex(
        "^(?:(?:上传的)?音频(?:内容)?(?:是|为|中说的是)|" +
            "(?:the )?(?:uploaded )?audio(?: content)?(?: says| is| transcription is)?)[：:]?\\s*[“\"]",
        RegexOption.IGNORE_CASE
    )
    private val introductions = buildList {
        for (upload in listOf("", "上传的")) {
            for (content in listOf("", "内容")) {
                for (verb in listOf("是", "为", "中说的是")) add("${upload}音频$content$verb")
            }
        }
        for (article in listOf("", "the ")) {
            for (upload in listOf("", "uploaded ")) {
                for (content in listOf("", " content")) {
                    for (verb in listOf("", " says", " is", " transcription is")) add("${article}${upload}audio$content$verb")
                }
            }
        }
        for (verb in listOf("转写", "识别")) {
            for (suffix in listOf("", "结果", "文本")) add("$verb$suffix")
        }
    }

    // 中文常见用于分句停顿的连词和关联词（长文断句关键词）
    private val clauseConjunctions = listOf(
        "但是", "然而", "可是", "不过", "而且", "并且", "所以", "因此",
        "因为", "虽然", "如果", "要是", "假如", "同时", "另外", "此外",
        "其实", "总之", "然后", "接着", "随后", "也就是说"
    )

    fun preview(raw: String): String {
        var text = raw.replace(thinking, "").replace(openThinking, "").replace(terminalTokens, "")
        val tagStart = text.lastIndexOf('<')
        if (tagStart >= 0 && listOf("<think>", "<eop>", "<|im_end|>", "<|endoftext|>")
                .any { it.startsWith(text.substring(tagStart), ignoreCase = true) }) {
            text = text.substring(0, tagStart)
        }
        text = text.trim()
        val wrapper = wrapperStart.find(text)
        if (wrapper != null) {
            text = text.substring(wrapper.range.last + 1).substringBefore('”').substringBefore('"')
        } else if (introductions.any { it.startsWith(text.trimEnd('：', ':', ' '), ignoreCase = true) }) {
            return ""
        }
        return formatPunctuation(cleanRaw(text), isFinal = false)
    }

    fun normalize(raw: String): String {
        val cleaned = cleanRaw(raw)
        return formatPunctuation(cleaned, isFinal = true)
    }

    private fun cleanRaw(raw: String): String {
        var text = raw.replace(thinking, "").replace(terminalTokens, "").replace(specialTokens, "").trim()
        text = chineseQuotedWrapper.matchEntire(text)?.groupValues?.get(1)
            ?: englishQuotedWrapper.matchEntire(text)?.groupValues?.get(1)
            ?: text.replaceFirst(resultPrefix, "")
        return text.trim()
    }

    /**
     * 规范化标点符号与长句断句：
     * 1. 句中用逗号，汉字之间的短暂停顿空格映射为逗号
     * 2. 句末为句号（若非问号、感叹号等），句末为逗号时自动修正为句号
     * 3. 超过一定长度的长句在转折连词前智能断句插入逗号
     */
    fun formatPunctuation(input: String, isFinal: Boolean): String {
        if (input.isBlank()) return ""
        var text = input

        val hasChinese = text.any { it in '\u4e00'..'\u9fa5' }
        if (!hasChinese) {
            return text.trim()
        }

        // 1. 去除文本开头的无意义标点（如开头的 ，。、？！等）
        text = text.replace(Regex("^[，。、？！,.;:!\\s]+"), "")
        if (text.isEmpty()) return ""

        // 2. 汉字之间的空格映射为逗号（ASR 短暂停顿通常输出空格）
        text = text.replace(Regex("(?<=[\u4e00-\u9fa5])\\s+(?=[\u4e00-\u9fa5])"), "，")

        // 3. 中文语境下标点全角化（保护数字小数和时间）
        text = text.replace(Regex("(?<!\\d),(?!\\d)|(?<=\\d),(?!\\d)|(?<!\\d),(?=\\d)"), "，")
        text = text.replace(";", "，")
        text = text.replace("；", "，")
        text = text.replace("?", "？")
        text = text.replace("!", "！")

        // 英文句号转中文句号（保护浮点数如 3.14）
        text = text.replace(Regex("(?<!\\d)\\.(?!\\d)|(?<=\\d)\\.(?!\\d)|(?<!\\d)\\.(?=\\d)"), "。")

        // 英文冒号转中文冒号（保护时间 12:30）
        text = text.replace(Regex("(?<!\\d):(?!\\d)"), "：")

        // 4. 清理中文标点前后的多余空格
        text = text.replace(Regex("(?<=[\u4e00-\u9fa5])\\s+(?=[，。、？！：])"), "")
        text = text.replace(Regex("(?<=[，。、？！：])\\s+(?=[\u4e00-\u9fa5])"), "")

        // 5. 消除连续重复标点
        text = text.replace(Regex("[，]{2,}"), "，")
        text = text.replace(Regex("[。]{2,}"), "。")
        text = text.replace(Regex("，。|。，"), "。")

        // 6. 长文准确断句（句中用逗号）
        text = segmentLongChineseSentence(text)

        // 7. 句末标点处理（句末者是句号）
        if (isFinal) {
            text = text.trim()
            if (text.endsWith("，") || text.endsWith(",")) {
                text = text.dropLast(1) + "。"
            } else {
                val lastChar = text.last()
                val terminalPunctuation = setOf('。', '？', '！', '…', '”', '’', ')', '）', ']', '】', '>', '》')
                if (lastChar !in terminalPunctuation) {
                    text += "。"
                }
            }
        }

        return text
    }

    private fun segmentLongChineseSentence(text: String): String {
        val punctuationSet = setOf('，', '。', '？', '！', '、', '；', '：', '\n', '\r')
        val sb = StringBuilder()
        var charCountSinceLastPunct = 0

        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c in punctuationSet) {
                charCountSinceLastPunct = 0
                sb.append(c)
                i++
                continue
            }

            var matchedConj: String? = null
            if (charCountSinceLastPunct >= 10) {
                for (conj in clauseConjunctions) {
                    if (text.startsWith(conj, i)) {
                        matchedConj = conj
                        break
                    }
                }
            }

            if (matchedConj != null) {
                if (sb.isNotEmpty() && sb.last() !in punctuationSet) {
                    sb.append("，")
                }
                sb.append(matchedConj)
                i += matchedConj.length
                charCountSinceLastPunct = 0
                continue
            }

            if (c in '\u4e00'..'\u9fa5') {
                charCountSinceLastPunct++
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }
}
