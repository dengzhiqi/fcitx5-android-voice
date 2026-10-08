/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.input.voice

import androidx.preference.PreferenceManager
import org.fcitx.fcitx5.android.utils.appContext

object VoiceInputPreferences {
    const val PreferLocal = "voice_input_prefer_local"
    const val KeepModelReady = "voice_input_keep_model_ready"
    const val CustomModelPath = "voice_input_custom_model_path"
    const val RemoteProvider = "voice_input_remote_provider"
    const val ProviderOpenAI = "openai"
    const val ProviderGoogle = "google"
    const val DefaultOpenAIEndpoint = "https://api.openai.com"
    const val OpenAIEndpoint = "voice_input_openai_endpoint"
    const val DefaultOpenAIModel = "gpt-audio-1.5"
    const val OpenAIModel = "voice_input_openai_model"
    const val OpenAIKey = "voice_input_openai_key"
    const val GoogleKey = "voice_input_google_key"
    const val Hotwords = "voice_input_hotwords"

    private val preferences
        get() = PreferenceManager.getDefaultSharedPreferences(appContext)

    fun preferLocal() = preferences.getBoolean(PreferLocal, true)

    fun keepModelReady() = preferences.getBoolean(KeepModelReady, false)

    fun setKeepModelReady(value: Boolean) {
        preferences.edit().putBoolean(KeepModelReady, value).apply()
    }

    fun customModelPath() = preferences.getString(CustomModelPath, "").orEmpty().trim()

    fun setCustomModelPath(path: String) {
        preferences.edit().putString(CustomModelPath, path.trim()).apply()
    }

    fun remoteProvider() = preferences.getString(RemoteProvider, ProviderOpenAI).orEmpty().ifEmpty { ProviderOpenAI }

    fun openAIEndpoint() = preferences.getString(OpenAIEndpoint, "").orEmpty().trim().ifEmpty { DefaultOpenAIEndpoint }

    fun setOpenAIEndpoint(endpoint: String) {
        preferences.edit().putString(OpenAIEndpoint, endpoint.trim()).apply()
    }

    fun openAIModel() = preferences.getString(OpenAIModel, "").orEmpty().trim().ifEmpty { DefaultOpenAIModel }

    fun setOpenAIModel(model: String) {
        preferences.edit().putString(OpenAIModel, model.trim()).apply()
    }

    fun openAIKey() = preferences.getString(OpenAIKey, "").orEmpty().trim()

    fun googleKey() = preferences.getString(GoogleKey, "").orEmpty().trim()

    fun remoteKeyConfigured() = if (remoteProvider() == ProviderGoogle) googleKey().isNotEmpty() else openAIKey().isNotEmpty()

    fun isAvailable() =
        (preferLocal() && LocalMnnEngine.isReady()) || remoteKeyConfigured()

    fun hotwords(): List<String> = preferences.getString(Hotwords, "").orEmpty()
        .split(',', '\n')
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
        .take(100)
}
