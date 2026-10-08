/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.input.voice

import java.io.File

sealed interface LocalModelSpec {
    sealed interface SherpaSpec : LocalModelSpec {
        val tokensFile: File

        data class SenseVoice(
            val modelFile: File,
            override val tokensFile: File
        ) : SherpaSpec

        data class Whisper(
            val encoderFile: File,
            val decoderFile: File,
            override val tokensFile: File
        ) : SherpaSpec

        data class Paraformer(
            val modelFile: File,
            override val tokensFile: File
        ) : SherpaSpec

        data class Zipformer(
            val encoderFile: File,
            val decoderFile: File,
            val joinerFile: File,
            override val tokensFile: File
        ) : SherpaSpec
    }

    data class QwenOmniMnn(val configFile: File) : LocalModelSpec

    companion object {
        fun detect(dir: File): LocalModelSpec? {
            if (!dir.exists() || !dir.isDirectory) return null

            val allFiles = dir.listFiles()?.filter { it.isFile } ?: return null
            val onnxFiles = allFiles.filter { it.name.endsWith(".onnx", ignoreCase = true) }
            val tokensFile = allFiles.firstOrNull {
                it.name.equals("tokens.txt", ignoreCase = true) ||
                    it.name.startsWith("tokens", ignoreCase = true) && it.name.endsWith(".txt", ignoreCase = true)
            }

            if (tokensFile != null && onnxFiles.isNotEmpty()) {
                // 1. 检查 Zipformer: 同时拥有 encoder, decoder, joiner
                val joinerFile = onnxFiles.firstOrNull { it.name.contains("joiner", ignoreCase = true) }
                val encoderFile = onnxFiles.firstOrNull { it.name.contains("encoder", ignoreCase = true) }
                val decoderFile = onnxFiles.firstOrNull { it.name.contains("decoder", ignoreCase = true) }

                if (joinerFile != null && encoderFile != null && decoderFile != null) {
                    return SherpaSpec.Zipformer(encoderFile, decoderFile, joinerFile, tokensFile)
                }

                // 2. 检查 Whisper: 拥有 encoder 和 decoder (且没有 joiner)
                if (encoderFile != null && decoderFile != null) {
                    return SherpaSpec.Whisper(encoderFile, decoderFile, tokensFile)
                }

                // 3. 检查 Paraformer
                val paraformerFile = onnxFiles.firstOrNull { it.name.contains("paraformer", ignoreCase = true) }
                if (paraformerFile != null) {
                    return SherpaSpec.Paraformer(paraformerFile, tokensFile)
                }

                // 4. 检查 SenseVoice: 名字包含 sense-voice，或者 model.int8.onnx / model.onnx
                val senseVoiceFile = onnxFiles.firstOrNull {
                    it.name.contains("sense-voice", ignoreCase = true) ||
                        it.name.contains("sensevoice", ignoreCase = true)
                } ?: onnxFiles.firstOrNull {
                    it.name.equals("model.int8.onnx", ignoreCase = true) ||
                        it.name.equals("model.onnx", ignoreCase = true)
                } ?: onnxFiles.firstOrNull() // 只有一个 onnx 且配有 tokens.txt 时默认尝试以 SenseVoice 驱动

                if (senseVoiceFile != null) {
                    return SherpaSpec.SenseVoice(senseVoiceFile, tokensFile)
                }
            }

            // 5. 检查是否为 MNN-LLM (如 Qwen2.5-Omni)
            val configFile = dir.resolve("config.json")
            val llmFile = dir.resolve("llm.mnn")
            if (configFile.isFile && configFile.length() > 0 && (llmFile.isFile || allFiles.any { it.name.endsWith(".mnn", ignoreCase = true) })) {
                return QwenOmniMnn(configFile)
            }

            return null
        }
    }
}
