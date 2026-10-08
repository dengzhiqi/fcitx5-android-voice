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
        return transcribeWithOpenAI(audio, precedingText, hotwords, targetLanguage)
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
        val response = postJson(OpenAIEndpoint, key, body)
        Timber.d("OpenAI response: %s", response)
        val root = Json.parseToJsonElement(response).jsonObject
        val content = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
        return content.orEmpty().trim()
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
        private const val OpenAIEndpoint = "https://api.openai.com/v1/chat/completions"
        private const val MaxContextChars = 200
    }
}
