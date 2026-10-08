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
        val endpoint = resolveOpenAIEndpoint(VoiceInputPreferences.openAIEndpoint())
        val response = postJson(endpoint, key, body)
        Timber.d("OpenAI response: %s", response)
        val root = Json.parseToJsonElement(response).jsonObject
        val content = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
        return content.orEmpty().trim()
    }

    internal fun resolveOpenAIEndpoint(customDomain: String): String {
        val trimmed = customDomain.trim().removeSuffix("/")
        if (trimmed.isEmpty()) return OpenAIEndpoint
        val base = if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            "https://$trimmed"
        } else {
            trimmed
        }
        return when {
            base.endsWith("/chat/completions") -> base
            base.endsWith("/v1") -> "$base/chat/completions"
            else -> "$base/v1/chat/completions"
        }
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
            customDomain: String = ""
        ): String {
            require(apiKey.isNotBlank()) { "OpenAI API key is not configured" }
            val client = VoiceTranscriptionClient()
            val domain = customDomain.ifBlank { VoiceInputPreferences.openAIEndpoint() }
            val targetEndpoint = client.resolveOpenAIEndpoint(domain)
            val body = buildJsonObject {
                put("model", JsonPrimitive("gpt-audio-1.5"))
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
                    return "Connected successfully, but model 'gpt-audio-1.5' not found on provider ($status)"
                }
                throw IllegalStateException("HTTP $status: $responseBody")
            }
            return responseBody
        }
    }
}
