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
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.TimeZone
import java.util.UUID

enum class RemoteProvider { ZHIPU, OPENAI, GOOGLE }

class VoiceTranscriptionClient {
    fun beginLocalSession(context: String, hotwords: List<String>, targetLanguage: String): Boolean {
        if (!VoiceInputPreferences.preferLocal() || !LocalMnnEngine.isReady()) return false
        LocalMnnEngine.beginSession(context, hotwords, targetLanguage)
        return true
    }

    fun commitLocalTranscript(transcript: String) = LocalMnnEngine.commitTranscript(transcript)

    fun endLocalSession() = LocalMnnEngine.endSession()

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
            return LocalMnnEngine.transcribe(audio, targetLanguage, onPartial)
        }
        val precedingText = context + alreadyInput
        return when (resolveRemoteProvider()) {
            RemoteProvider.ZHIPU -> transcribeWithZhipu(audio, precedingText, hotwords)
            RemoteProvider.OPENAI -> transcribeWithOpenAI(audio, precedingText, hotwords, targetLanguage)
            RemoteProvider.GOOGLE -> transcribeWithGoogle(audio, hotwords, targetLanguage)
        }
    }

    private fun transcribeWithOpenAI(
        audio: File,
        precedingText: String,
        hotwords: List<String>,
        targetLanguage: String
    ): String {
        val key = VoiceInputPreferences.openAIKey()
        require(key.isNotEmpty()) { "OpenAI API key is not configured" }
        val prompt = transcriptionPrompt(precedingText, hotwords, targetLanguage)
        val audioBase64 = Base64.encodeToString(audio.readBytes(), Base64.NO_WRAP)
        val body = buildJsonObject {
            put("model", JsonPrimitive("gpt-audio-1.5"))
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
        Timber.d(
            "OpenAI request: model=gpt-audio-1.5 prompt=%s audioBytes=%d",
            prompt,
            audio.length()
        )
        val response = postJson(VoiceInputPreferences.openAIEndpoint(), key, body)
        Timber.d("OpenAI response: %s", response)
        val root = Json.parseToJsonElement(response).jsonObject
        val content = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
        return content.orEmpty().trim()
    }

    private fun transcribeWithZhipu(
        audio: File,
        precedingText: String,
        hotwords: List<String>
    ): String {
        val key = VoiceInputPreferences.zhipuKey()
        require(key.isNotEmpty()) { "Zhipu API key is not configured" }
        val boundary = "FcitxVoice-${UUID.randomUUID()}"
        val prompt = precedingText.takeLast(MaxContextChars)
        Timber.d(
            "Zhipu request: model=glm-asr-2512 prompt=%s hotwords=%s audioBytes=%d",
            prompt,
            hotwords,
            audio.length()
        )
        val connection = openConnection(ZhipuEndpoint, key).apply {
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        connection.outputStream.use { raw ->
            val writer = BufferedWriter(OutputStreamWriter(raw, Charsets.UTF_8))
            fun field(name: String, value: String) {
                writer.append("--$boundary\r\n")
                writer.append("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
                writer.append(value).append("\r\n")
            }
            field("model", "glm-asr-2512")
            if (prompt.isNotEmpty()) field("prompt", prompt)
            hotwords.forEach { field("hotwords", it) }
            writer.append("--$boundary\r\n")
            writer.append("Content-Disposition: form-data; name=\"file\"; filename=\"voice.wav\"\r\n")
            writer.append("Content-Type: audio/wav\r\n\r\n")
            writer.flush()
            audio.inputStream().use { it.copyTo(raw) }
            raw.write("\r\n--$boundary--\r\n".toByteArray())
        }
        val response = readResponse(connection)
        Timber.d("Zhipu response: %s", response)
        return Json.parseToJsonElement(response).jsonObject["text"]
            ?.jsonPrimitive?.contentOrNull.orEmpty().trim()
    }

    private fun transcribeWithGoogle(
        audio: File,
        hotwords: List<String>,
        targetLanguage: String
    ): String {
        val key = VoiceInputPreferences.googleKey()
        require(key.isNotEmpty()) { "Google API key is not configured" }
        // Google expects BCP-47 (zh-CN); fcitx may report zh_CN
        val language = targetLanguage.replace('_', '-').ifBlank { "zh-CN" }
        val pcm = extractPcm16(audio.readBytes())
        val audioBase64 = Base64.encodeToString(pcm, Base64.NO_WRAP)
        val body = buildJsonObject {
            put("config", buildJsonObject {
                put("encoding", JsonPrimitive("LINEAR16"))
                put("sampleRateHertz", JsonPrimitive(SampleRateHertz))
                put("languageCode", JsonPrimitive(language))
                put("enableAutomaticPunctuation", JsonPrimitive(true))
                if (hotwords.isNotEmpty()) {
                    put("speechContexts", buildJsonArray {
                        add(buildJsonObject {
                            put("phrases", buildJsonArray {
                                hotwords.take(MaxHotwords).forEach { add(JsonPrimitive(it)) }
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
            "Google request: language=%s hotwords=%d audioBytes=%d",
            language,
            hotwords.size,
            pcm.size
        )
        val endpoint = "$GoogleEndpoint?key=${URLEncoder.encode(key, Charsets.UTF_8.name())}"
        val response = postJsonNoAuth(endpoint, body)
        Timber.d("Google response: %s", response)
        val separator =
            if (language.startsWith("zh") || language.startsWith("ja") || language.startsWith("ko")) ""
            else " "
        return Json.parseToJsonElement(response).jsonObject["results"]?.jsonArray
            ?.mapNotNull { result ->
                result.jsonObject["alternatives"]?.jsonArray?.firstOrNull()?.jsonObject
                    ?.get("transcript")?.jsonPrimitive?.contentOrNull
            }
            ?.joinToString(separator)?.trim().orEmpty()
    }

    /**
     * Returns the raw PCM16 payload of a WAV file by locating the "data" chunk.
     * Falls back to skipping the standard 44-byte header.
     */
    internal fun extractPcm16(wav: ByteArray): ByteArray {
        var offset = 12 // skip "RIFF" + size + "WAVE"
        while (offset + 8 <= wav.size) {
            val chunkId = String(wav, offset, 4, Charsets.US_ASCII)
            val chunkSize = (wav[offset + 4].toInt() and 0xFF) or
                ((wav[offset + 5].toInt() and 0xFF) shl 8) or
                ((wav[offset + 6].toInt() and 0xFF) shl 16) or
                ((wav[offset + 7].toInt() and 0xFF) shl 24)
            if (chunkId == "data") {
                return wav.copyOfRange(offset + 8, wav.size)
            }
            offset += 8 + chunkSize
        }
        return wav.copyOfRange(minOf(WavHeaderSize, wav.size), wav.size)
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

    private fun postJsonNoAuth(endpoint: String, body: String): String {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 30_000
            readTimeout = 90_000
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
            setRequestProperty("Authorization", "Bearer $key")
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
        private const val ZhipuEndpoint = "https://open.bigmodel.cn/api/paas/v4/audio/transcriptions"
        private const val GoogleEndpoint = "https://speech.googleapis.com/v1/speech:recognize"
        private const val MaxContextChars = 200
        private const val MaxHotwords = 100
        private const val SampleRateHertz = 16_000
        private const val WavHeaderSize = 44
        private val ChinaTimeZones = setOf(
            "Asia/Shanghai",
            "Asia/Chongqing",
            "Asia/Harbin",
            "Asia/Urumqi",
            "Asia/Kashgar",
            "PRC"
        )

        fun isCurrentTimeZoneChina() = TimeZone.getDefault().id in ChinaTimeZones

        fun resolveRemoteProvider(): RemoteProvider = when (VoiceInputPreferences.remoteProvider()) {
            VoiceInputPreferences.RemoteProviderZhipu -> RemoteProvider.ZHIPU
            VoiceInputPreferences.RemoteProviderOpenAI -> RemoteProvider.OPENAI
            VoiceInputPreferences.RemoteProviderGoogle -> RemoteProvider.GOOGLE
            else -> if (isCurrentTimeZoneChina()) RemoteProvider.ZHIPU else RemoteProvider.OPENAI
        }
    }
}
