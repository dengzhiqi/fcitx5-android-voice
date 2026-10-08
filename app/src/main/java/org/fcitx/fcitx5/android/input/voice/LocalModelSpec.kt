/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.input.voice

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
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

            // 优先查找现有 tokens.txt，若只有 tokens.json 则自动转换为 tokens.txt
            var tokensFile = allFiles.firstOrNull {
                it.name.equals("tokens.txt", ignoreCase = true) ||
                    it.name.startsWith("tokens", ignoreCase = true) && it.name.endsWith(".txt", ignoreCase = true)
            }

            if (tokensFile == null) {
                val tokensJson = allFiles.firstOrNull {
                    it.name.equals("tokens.json", ignoreCase = true) ||
                        it.name.startsWith("tokens", ignoreCase = true) && it.name.endsWith(".json", ignoreCase = true)
                }
                if (tokensJson != null) {
                    tokensFile = convertTokensJsonToTxt(dir, tokensJson)
                }
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

                // 4. 检查 SenseVoice: 名字包含 sense-voice / sensevoice / model_quant / model.int8 / model.onnx
                val senseVoiceFile = onnxFiles.firstOrNull {
                    it.name.contains("sense-voice", ignoreCase = true) ||
                        it.name.contains("sensevoice", ignoreCase = true) ||
                        it.name.contains("model_quant", ignoreCase = true) ||
                        it.name.equals("model.int8.onnx", ignoreCase = true) ||
                        it.name.equals("model.onnx", ignoreCase = true)
                } ?: onnxFiles.firstOrNull() // 只有一个 onnx 且配有 tokens 时默认按 SenseVoice 驱动

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

        internal fun convertTokensJsonToTxt(dir: File, jsonFile: File): File? {
            return runCatching {
                val targetTxt = dir.resolve("tokens.txt")
                if (targetTxt.exists() && targetTxt.length() > 0) {
                    return targetTxt
                }
                val outputFile = runCatching {
                    if (targetTxt.createNewFile() || targetTxt.canWrite()) targetTxt else null
                }.getOrNull() ?: runCatching {
                    org.fcitx.fcitx5.android.utils.appContext.cacheDir.resolve("${dir.name}_tokens.txt")
                }.getOrNull() ?: File.createTempFile("converted_tokens_", ".txt")

                val jsonText = jsonFile.readText()
                val jsonArray = kotlinx.serialization.json.Json.parseToJsonElement(jsonText).jsonArray
                outputFile.bufferedWriter().use { writer ->
                    jsonArray.forEachIndexed { index, element ->
                        val token = element.jsonPrimitive.content
                        writer.write("$token $index\n")
                    }
                }
                outputFile
            }.getOrNull()
        }
    }
}
