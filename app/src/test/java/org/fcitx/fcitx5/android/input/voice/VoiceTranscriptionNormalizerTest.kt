/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.input.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceTranscriptionNormalizerTest {
    @Test
    fun removesQwenChineseWrapperAndTerminalToken() {
        val raw = "上传的音频内容是“上滑取消，我已经验证过了能工作”。" +
            "如果还有其他需要处理的音频内容，你可以随时告诉我哦。<eop>"

        assertEquals(
            "上滑取消，我已经验证过了能工作",
            VoiceTranscriptionNormalizer.normalize(raw)
        )
    }

    @Test
    fun preservesPlainTranscription() {
        assertEquals(
            "明天用小企鹅语音输入法测试 Fcitx5。",
            VoiceTranscriptionNormalizer.normalize("明天用小企鹅语音输入法测试 Fcitx5。<eop>")
        )
    }

    @Test
    fun streamsPlainTextWithoutWaitingForTheSentence() {
        assertEquals("今", VoiceTranscriptionNormalizer.preview("今"))
        assertEquals("今天天气", VoiceTranscriptionNormalizer.preview("今天天气"))
    }

    @Test
    fun hidesIncompleteControlTokensAndThinking() {
        for (raw in listOf("<", "<thi", "<think>internal", "<think>internal</thi")) {
            assertEquals("", VoiceTranscriptionNormalizer.preview(raw))
        }
        assertEquals("你好", VoiceTranscriptionNormalizer.preview("<think>internal</think>你好<|im_"))
        assertEquals("你好", VoiceTranscriptionNormalizer.preview("你好<eop>"))
    }

    @Test
    fun streamsWrapperContentsWithoutExplanations() {
        val intro = "上传的音频内容是“"
        for (end in 1..intro.length) {
            assertEquals("", VoiceTranscriptionNormalizer.preview(intro.take(end)))
        }
        assertEquals("你好", VoiceTranscriptionNormalizer.preview(intro + "你好"))
        assertEquals("你好", VoiceTranscriptionNormalizer.preview(intro + "你好”。如果还需要"))
        assertEquals("hello", VoiceTranscriptionNormalizer.preview("the audio says \"hello\". If you"))
        assertEquals("hello", VoiceTranscriptionNormalizer.preview("the uploaded audio is \"hello"))
        assertEquals("你好", VoiceTranscriptionNormalizer.preview("识别结果：你好"))
    }

    @Test
    fun normalizesLanguageToBcp47() {
        val client = VoiceTranscriptionClient()
        assertEquals("zh-CN", client.normalizeBcp47("zh_CN"))
        assertEquals("zh-CN", client.normalizeBcp47("zh"))
        assertEquals("en-US", client.normalizeBcp47("en_US"))
        assertEquals("en-US", client.normalizeBcp47("en"))
        assertEquals("ja-JP", client.normalizeBcp47("ja"))
        assertEquals("ko-KR", client.normalizeBcp47("ko"))
        assertEquals("yue-Hant-HK", client.normalizeBcp47("yue"))
        assertEquals("fr-FR", client.normalizeBcp47("fr_FR"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun throwsWhenTestingGoogleConnectionWithEmptyKey() {
        VoiceTranscriptionClient.testGoogleConnection("")
    }

    @Test
    fun resolvesCustomOpenAIEndpoints() {
        val client = VoiceTranscriptionClient()
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            client.resolveOpenAIEndpoint("")
        )
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            client.resolveOpenAIEndpoint("https://api.openai.com")
        )
        assertEquals(
            "https://api.deepseek.com/v1/chat/completions",
            client.resolveOpenAIEndpoint("api.deepseek.com")
        )
        assertEquals(
            "https://api.moonshot.cn/v1/chat/completions",
            client.resolveOpenAIEndpoint("https://api.moonshot.cn/v1")
        )
        assertEquals(
            "https://custom-proxy.internal/v1/chat/completions",
            client.resolveOpenAIEndpoint("https://custom-proxy.internal/v1/chat/completions")
        )
        assertEquals(
            "http://192.168.124.1:8003/v1/audio/transcriptions",
            client.resolveOpenAIEndpoint("http://192.168.124.1:8003/v1", "SenseVoiceSmall")
        )
        assertEquals(
            "http://192.168.124.1:8003/v1/audio/transcriptions",
            client.resolveOpenAIEndpoint("http://192.168.124.1:8003", "SenseVoiceSmall")
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun throwsWhenTestingOpenAIConnectionWithEmptyKey() {
        VoiceTranscriptionClient.testOpenAIConnection(apiKey = "", customDomain = "https://api.openai.com")
    }
}
