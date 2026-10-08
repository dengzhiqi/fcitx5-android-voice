/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.input.voice

import android.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import timber.log.Timber
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder

class VoiceTranscriptionClient {
    fun beginLocalSession(context: String, hotwords: List<String>, targetLanguage: String): Boolean {
        if (!VoiceInputPreferences.preferLocal()) return false
        if (LocalSherpaEngine.isReady()) {
            LocalSherpaEngine.prewarm()
            return true
        }
        if (LocalMnnEngine.isReady()) {
            LocalMnnEngine.beginSession(context, hotwords, targetLanguage)
            return true
        }
        return false
    }

    fun commitLocalTranscript(transcript: String) {
        if (LocalMnnEngine.isReady()) {
            LocalMnnEngine.commitTranscript(transcript)
        }
    }

    fun endLocalSession() {
        if (LocalMnnEngine.isReady()) {
            LocalMnnEngine.endSession()
        }
    }

    suspend fun transcribe(
        audio: File,
        context: String,
        alreadyInput: String,
        hotwords: List<String>,
        targetLanguage: String,
        localSession: Boolean,
        onPartial: (String) -> Unit
    ): String {
        if (localSession) {
            if (LocalSherpaEngine.isReady()) {
                return LocalSherpaEngine.transcribe(audio, targetLanguage, onPartial)
            }
            if (LocalMnnEngine.isReady()) {
                return LocalMnnEngine.transcribe(audio, targetLanguage, onPartial)
            }
        }
        val precedingText = context + alreadyInput
        return if (VoiceInputPreferences.remoteProvider() == VoiceInputPreferences.ProviderGoogle) {
            transcribeWithGoogle(audio, hotwords, targetLanguage)
        } else {
            transcribeWithOpenAI(audio, precedingText, hotwords, targetLanguage)
        }
    }

    private fun transcribeWithGoogle(
        audio: File,
        hotwords: List<String>,
        targetLanguage: String
    ): String {
        val key = VoiceInputPreferences.googleKey()
        require(key.isNotEmpty()) { "Google ASR API key is not configured" }
        val bcp47Language = normalizeBcp47(targetLanguage)
        val audioBytes = audio.readBytes()
        val rawAudio = if (audioBytes.size > 44 &&
            audioBytes[0] == 'R'.code.toByte() &&
            audioBytes[1] == 'I'.code.toByte() &&
            audioBytes[2] == 'F'.code.toByte() &&
            audioBytes[3] == 'F'.code.toByte()
        ) {
            audioBytes.copyOfRange(44, audioBytes.size)
        } else {
            audioBytes
        }
        val audioBase64 = Base64.encodeToString(rawAudio, Base64.NO_WRAP)
        val body = buildJsonObject {
            put("config", buildJsonObject {
                put("encoding", JsonPrimitive("LINEAR16"))
                put("sampleRateHertz", JsonPrimitive(16000))
                put("languageCode", JsonPrimitive(bcp47Language))
                put("enableAutomaticPunctuation", JsonPrimitive(true))
                if (hotwords.isNotEmpty()) {
                    put("speechContexts", buildJsonArray {
                        add(buildJsonObject {
                            put("phrases", buildJsonArray {
                                hotwords.forEach { add(JsonPrimitive(it)) }
                            })
                        })
                    })
                }
            })
            put("audio", buildJsonObject {
                put("content", JsonPrimitive(audioBase64))
            })
        }.toString()

        Timber.d(
            "Google ASR request: language=%s hotwords=%s audioBytes=%d",
            bcp47Language,
            hotwords,
            audio.length()
        )
        val endpoint = "$GoogleSpeechEndpoint?key=$key"
        val response = postJson(endpoint, "", body)
        Timber.d("Google ASR response: %s", response)
        val root = Json.parseToJsonElement(response).jsonObject
        val results = root["results"]?.jsonArray
        val text = results?.joinToString("") { result ->
            result.jsonObject["alternatives"]?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("transcript")?.jsonPrimitive?.contentOrNull.orEmpty()
        }.orEmpty().trim()
        return text
    }

    internal fun normalizeBcp47(lang: String): String {
        val trimmed = lang.trim().replace('_', '-')
        return when {
            trimmed.startsWith("zh", ignoreCase = true) -> "zh-CN"
            trimmed.startsWith("en", ignoreCase = true) -> "en-US"
            trimmed.startsWith("ja", ignoreCase = true) -> "ja-JP"
            trimmed.startsWith("ko", ignoreCase = true) -> "ko-KR"
            trimmed.startsWith("yue", ignoreCase = true) -> "yue-Hant-HK"
            trimmed.isNotEmpty() -> trimmed
            else -> "zh-CN"
        }
    }

    private fun transcribeWithOpenAI(
        audio: File,
        precedingText: String,
        hotwords: List<String>,
        targetLanguage: String
    ): String {
        val key = VoiceInputPreferences.openAIKey()
        val model = VoiceInputPreferences.openAIModel()
        val prompt = transcriptionPrompt(precedingText, hotwords, targetLanguage)
        val endpoint = resolveOpenAIEndpoint(VoiceInputPreferences.openAIEndpoint(), model)

        Timber.d("OpenAI request: model=%s endpoint=%s audioBytes=%d", model, endpoint, audio.length())

        val response = if (isAudioTranscriptionModel(model) || endpoint.endsWith("/audio/transcriptions")) {
            postMultipartAudio(endpoint, key, audio, model, prompt)
        } else {
            val audioBase64 = Base64.encodeToString(audio.readBytes(), Base64.NO_WRAP)
            val body = buildJsonObject {
                put("model", JsonPrimitive(model))
                put("messages", buildJsonArray {
                    add(buildJsonObject {
                        put("role", JsonPrimitive("user"))
                        put("content", buildJsonArray {
                            add(buildJsonObject {
                                put("type", JsonPrimitive("text"))
                                put("text", JsonPrimitive(prompt))
                            })
                            add(buildJsonObject {
                                put("type", JsonPrimitive("input_audio"))
                                put("input_audio", buildJsonObject {
                                    put("data", JsonPrimitive(audioBase64))
                                    put("format", JsonPrimitive("wav"))
                                })
                            })
                        })
                    })
                })
            }.toString()
            postJson(endpoint, key, body)
        }

        Timber.d("OpenAI response: %s", response)
        val root = Json.parseToJsonElement(response).jsonObject
        val text = root["text"]?.jsonPrimitive?.contentOrNull
            ?: root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                ?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
        return text.orEmpty().trim()
    }

    internal fun isAudioTranscriptionModel(model: String): Boolean {
        val lower = model.lowercase().trim()
        if (lower.isEmpty() || lower == "gpt-audio-1.5" || lower.startsWith("gpt-audio") || lower.contains("audio-preview")) {
            return false
        }
        return true
    }

    internal fun resolveOpenAIEndpoint(
        customDomain: String,
        model: String = ""
    ): String {
        val trimmed = customDomain.trim().removeSuffix("/")
        val effectiveModel = model.ifBlank {
            runCatching { VoiceInputPreferences.openAIModel() }.getOrDefault("")
        }
        val isAudio = isAudioTranscriptionModel(effectiveModel)
        val subPath = if (isAudio) "audio/transcriptions" else "chat/completions"

        if (trimmed.isEmpty()) {
            return if (isAudio) "https://api.openai.com/v1/audio/transcriptions" else OpenAIEndpoint
        }
        val base = if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            "https://$trimmed"
        } else {
            trimmed
        }
        if (base.endsWith("/chat/completions") || base.endsWith("/audio/transcriptions")) {
            return base
        }
        return if (base.endsWith("/v1")) "$base/$subPath" else "$base/v1/$subPath"
    }

    private fun postMultipartAudio(
        endpoint: String,
        key: String,
        audioFile: File,
        model: String,
        prompt: String = ""
    ): String {
        val boundary = "Boundary" + System.currentTimeMillis()
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 30_000
            readTimeout = 90_000
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            if (key.isNotEmpty()) {
                setRequestProperty("Authorization", "Bearer $key")
            }
        }

        connection.outputStream.use { out ->
            fun writeField(name: String, value: String) {
                out.write("--$boundary\r\n".toByteArray())
                out.write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray())
                out.write("$value\r\n".toByteArray())
            }

            writeField("model", model)
            if (prompt.isNotEmpty()) {
                writeField("prompt", prompt)
            }
            writeField("response_format", "json")

            out.write("--$boundary\r\n".toByteArray())
            out.write("Content-Disposition: form-data; name=\"file\"; filename=\"${audioFile.name}\"\r\n".toByteArray())
            out.write("Content-Type: audio/wav\r\n\r\n".toByteArray())
            audioFile.inputStream().use { input -> input.copyTo(out) }
            out.write("\r\n".toByteArray())

            out.write("--$boundary--\r\n".toByteArray())
        }

        return readResponse(connection)
    }

    internal fun transcriptionPrompt(
        precedingText: String,
        hotwords: List<String>,
        targetLanguage: String
    ): String = buildString {
        append(contextPrompt(precedingText))
        append("\nHotwords: ")
        append(hotwords.joinToString(", "))
        append("\nOutput language: ")
        append(targetLanguage)
    }

    internal fun contextPrompt(precedingText: String) = "Context: ${precedingText.takeLast(MaxContextChars)}"

    private fun postJson(endpoint: String, key: String, body: String): String {
        val connection = openConnection(endpoint, key).apply {
            setRequestProperty("Content-Type", "application/json")
        }
        connection.outputStream.use { it.write(body.toByteArray()) }
        return readResponse(connection)
    }

    private fun openConnection(endpoint: String, key: String) =
        (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 30_000
            readTimeout = 90_000
            if (key.isNotEmpty()) {
                setRequestProperty("Authorization", "Bearer $key")
            }
        }

    private fun readResponse(connection: HttpURLConnection): String {
        val status = connection.responseCode
        val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (status !in 200..299) {
            throw IllegalStateException("HTTP $status: $body")
        }
        return body
    }

    companion object {
        private const val OpenAIEndpoint = "https://api.openai.com/v1/chat/completions"
        private const val GoogleSpeechEndpoint = "https://speech.googleapis.com/v1/speech:recognize"
        private const val MaxContextChars = 200

        fun testGoogleConnection(apiKey: String = VoiceInputPreferences.googleKey()): String {
            require(apiKey.isNotBlank()) { "Google ASR API key is not configured" }
            val silentPcm = ByteArray(3200)
            val audioBase64 = Base64.encodeToString(silentPcm, Base64.NO_WRAP)
            val body = buildJsonObject {
                put("config", buildJsonObject {
                    put("encoding", JsonPrimitive("LINEAR16"))
                    put("sampleRateHertz", JsonPrimitive(16000))
                    put("languageCode", JsonPrimitive("zh-CN"))
                })
                put("audio", buildJsonObject {
                    put("content", JsonPrimitive(audioBase64))
                })
            }.toString()
            val endpoint = "$GoogleSpeechEndpoint?key=$apiKey"
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 15_000
                readTimeout = 15_000
                setRequestProperty("Content-Type", "application/json")
            }
            connection.outputStream.use { it.write(body.toByteArray()) }
            val status = connection.responseCode
            val responseBody = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                throw IllegalStateException("HTTP $status: $responseBody")
            }
            return responseBody
        }

        fun testOpenAIConnection(
            apiKey: String,
            customDomain: String = "",
            customModel: String = ""
        ): String {
            val domain = customDomain.ifBlank {
                runCatching { VoiceInputPreferences.openAIEndpoint() }.getOrDefault("")
            }
            val modelName = customModel.ifBlank {
                runCatching { VoiceInputPreferences.openAIModel() }.getOrDefault("gpt-4o-audio-preview")
            }

            val isLocal = domain.startsWith("http://") ||
                domain.contains("127.0.0.1") ||
                domain.contains("localhost") ||
                domain.contains("192.168.") ||
                domain.contains("10.")

            if (apiKey.isBlank() && !isLocal) {
                throw IllegalArgumentException("OpenAI API Key is empty")
            }

            val client = VoiceTranscriptionClient()
            val targetEndpoint = client.resolveOpenAIEndpoint(domain, modelName)
            val isAudio = client.isAudioTranscriptionModel(modelName) || targetEndpoint.endsWith("/audio/transcriptions")

            if (isAudio) {
                val tempWav = File.createTempFile("test_silent", ".wav")
                try {
                    createSilentWav(tempWav)
                    val response = client.postMultipartAudio(targetEndpoint, apiKey, tempWav, modelName)
                    return response
                } finally {
                    tempWav.delete()
                }
            } else {
                val body = buildJsonObject {
                    put("model", JsonPrimitive(modelName))
                    put("messages", buildJsonArray {
                        add(buildJsonObject {
                            put("role", JsonPrimitive("user"))
                            put("content", JsonPrimitive("ping"))
                        })
                    })
                    put("max_tokens", JsonPrimitive(1))
                }.toString()

                val connection = (URL(targetEndpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 15_000
                    readTimeout = 15_000
                    setRequestProperty("Content-Type", "application/json")
                    if (apiKey.isNotEmpty()) {
                        setRequestProperty("Authorization", "Bearer $apiKey")
                    }
                }
                connection.outputStream.use { it.write(body.toByteArray()) }
                val status = connection.responseCode
                val responseBody = (if (status in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (status !in 200..299) {
                    if (status == 404 || responseBody.contains("model_not_found", ignoreCase = true) || responseBody.contains("does not exist", ignoreCase = true)) {
                        return "Connected successfully, but model '$modelName' not found on provider ($status)"
                    }
                    throw IllegalStateException("HTTP $status: $responseBody")
                }
                return responseBody
            }
        }

        private fun createSilentWav(targetFile: File, sampleRate: Int = 16000, durationMs: Int = 100) {
            val numSamples = (sampleRate * durationMs) / 1000
            val pcmDataSize = numSamples * 2
            val totalSize = 36 + pcmDataSize
            targetFile.outputStream().use { out ->
                out.write("RIFF".toByteArray())
                out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(totalSize).array())
                out.write("WAVE".toByteArray())
                out.write("fmt ".toByteArray())
                out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(16).array())
                out.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(1).array())
                out.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(1).array())
                out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(sampleRate).array())
                out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(sampleRate * 2).array())
                out.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(2).array())
                out.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(16).array())
                out.write("data".toByteArray())
                out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(pcmDataSize).array())
                out.write(ByteArray(pcmDataSize))
            }
        }

        fun testLocalModel(): String {
            if (LocalSherpaEngine.isReady()) {
                val tempWav = File.createTempFile("test_sherpa", ".wav")
                try {
                    createSilentWav(tempWav, durationMs = 200)
                    LocalSherpaEngine.prewarm()
                    LocalSherpaEngine.transcribe(tempWav)
                    return "本地引擎 [${LocalSherpaEngine.currentModelName()}] 运行正常"
                } finally {
                    tempWav.delete()
                }
            } else if (LocalMnnEngine.isReady()) {
                return "本地 MNN-LLM 引擎已就绪"
            } else {
                val dir = LocalVoiceModel.directory()
                val files = runCatching { dir.listFiles() }.getOrNull()
                val fileSummary = files?.joinToString { "${it.name} (${it.length()} 字节)" }
                    ?: "无法读取目录文件列表（通常为未授予所有文件访问权限）"
                val diagnostics = buildString {
                    appendLine("未检测到就绪的本地离线模型。")
                    appendLine("当前探测路径：${dir.absolutePath}")
                    appendLine("目录是否存在：${dir.exists()}")
                    appendLine("目录是否可读：${dir.canRead()}")
                    appendLine("目录下检测到的文件：$fileSummary")
                    append("请确认目录中包含 model.int8.onnx (或 model.onnx) 及 tokens.txt。")
                }
                throw IllegalStateException(diagnostics)
            }
        }
    }
}
