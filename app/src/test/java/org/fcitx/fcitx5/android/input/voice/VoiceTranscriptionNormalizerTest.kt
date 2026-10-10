/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.input.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceTranscriptionNormalizerTest {
    @Test
    fun removesQwenChineseWrapperAndTerminalToken() {
        val raw = "上传的音频内容是“上滑取消，我已经验证过了能工作”。" +
            "如果还有其他需要处理的音频内容，你可以随时告诉我哦。<eop>"

        assertEquals(
            "上滑取消，我已经验证过了能工作。",
            VoiceTranscriptionNormalizer.normalize(raw)
        )
    }

    @Test
    fun formatsPunctuationAndEnsuresPeriodAtEnd() {
        // SenseVoice 特殊 token 清洗、空格转逗号、句末句号
        val rawSenseVoice = "<|zh|><|NEUTRAL|><|Speech|><|woitn|>今天天气非常好 我们一起去散步吧"
        assertEquals(
            "今天天气非常好，我们一起去散步吧。",
            VoiceTranscriptionNormalizer.normalize(rawSenseVoice)
        )

        // 句末原本是逗号修正为句号
        assertEquals(
            "测试句末逗号自动修正。",
            VoiceTranscriptionNormalizer.normalize("测试句末逗号自动修正，")
        )

        // 句末已有问号或感叹号时保留
        assertEquals(
            "你今天吃饭了吗？",
            VoiceTranscriptionNormalizer.normalize("你今天吃饭了吗？")
        )
        assertEquals(
            "太棒了！",
            VoiceTranscriptionNormalizer.normalize("太棒了！")
        )

        // 英文标点在中文语境下转为全角标点，且保护数字小数点和时间
        assertEquals(
            "当前版本是3.14，会议时间是12:30。",
            VoiceTranscriptionNormalizer.normalize("当前版本是3.14, 会议时间是12:30")
        )
    }

    @Test
    fun segmentsLongChineseSentenceWithConjunctions() {
        val longSentence = "今天我们一起开会讨论项目进展但是大家对方案还有一些不同的意见"
        assertEquals(
            "今天我们一起开会讨论项目进展，但是大家对方案还有一些不同的意见。",
            VoiceTranscriptionNormalizer.normalize(longSentence)
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

    @Test
    fun detectsSherpaSenseVoiceModel() {
        val tempDir = java.nio.file.Files.createTempDirectory("test_sensevoice").toFile()
        try {
            tempDir.resolve("tokens.txt").writeText("dummy")
            tempDir.resolve("model.int8.onnx").writeText("dummy")
            val spec = LocalModelSpec.detect(tempDir)
            assertTrue(spec is LocalModelSpec.SherpaSpec.SenseVoice)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun detectsSherpaWhisperModel() {
        val tempDir = java.nio.file.Files.createTempDirectory("test_whisper").toFile()
        try {
            tempDir.resolve("tokens.txt").writeText("dummy")
            tempDir.resolve("whisper-encoder.onnx").writeText("dummy")
            tempDir.resolve("whisper-decoder.onnx").writeText("dummy")
            val spec = LocalModelSpec.detect(tempDir)
            assertTrue(spec is LocalModelSpec.SherpaSpec.Whisper)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun detectsSherpaSenseVoiceWithModelQuantAndTokensJson() {
        val tempDir = java.nio.file.Files.createTempDirectory("test_sensevoice_json").toFile()
        try {
            tempDir.resolve("tokens.json").writeText("[\"<unk>\", \"<s>\", \"你好\"]")
            tempDir.resolve("model_quant.onnx").writeText("dummy")
            val spec = LocalModelSpec.detect(tempDir)
            assertTrue(spec is LocalModelSpec.SherpaSpec.SenseVoice)
            val tokensFile = (spec as LocalModelSpec.SherpaSpec.SenseVoice).tokensFile
            assertTrue(tokensFile.exists())
            val lines = tokensFile.readLines()
            assertEquals("<unk> 0", lines[0])
            assertEquals("<s> 1", lines[1])
            assertEquals("你好 2", lines[2])
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
