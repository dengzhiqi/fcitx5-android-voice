/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.input.voice

import androidx.preference.PreferenceManager
import org.fcitx.fcitx5.android.utils.appContext

object VoiceInputPreferences {
    const val PreferLocal = "voice_input_prefer_local"
    const val KeepModelReady = "voice_input_keep_model_ready"
    const val OpenAIKey = "voice_input_openai_key"
    const val ZhipuKey = "voice_input_zhipu_key"
    const val GoogleKey = "voice_input_google_key"
    const val OpenAIEndpointPref = "voice_input_openai_endpoint"
    const val DefaultOpenAIEndpoint = "https://api.openai.com/v1/chat/completions"
    const val Hotwords = "voice_input_hotwords"
    const val RemoteProviderPref = "voice_input_remote_provider"

    const val RemoteProviderAuto = "auto"
    const val RemoteProviderZhipu = "zhipu"
    const val RemoteProviderOpenAI = "openai"
    const val RemoteProviderGoogle = "google"

    private val RemoteProviderValues = setOf(
        RemoteProviderAuto,
        RemoteProviderZhipu,
        RemoteProviderOpenAI,
        RemoteProviderGoogle
    )

    private val preferences
        get() = PreferenceManager.getDefaultSharedPreferences(appContext)

    fun preferLocal() = preferences.getBoolean(PreferLocal, true)

    fun keepModelReady() = preferences.getBoolean(KeepModelReady, false)

    fun setKeepModelReady(value: Boolean) {
        preferences.edit().putBoolean(KeepModelReady, value).apply()
    }

    fun openAIKey() = preferences.getString(OpenAIKey, "").orEmpty().trim()

    fun openAIEndpoint(): String {
        val configured = preferences.getString(OpenAIEndpointPref, "").orEmpty().trim().trimEnd('/')
        return configured.ifEmpty { DefaultOpenAIEndpoint }
    }

    fun zhipuKey() = preferences.getString(ZhipuKey, "").orEmpty().trim()

    fun googleKey() = preferences.getString(GoogleKey, "").orEmpty().trim()

    fun remoteProvider() = preferences.getString(RemoteProviderPref, RemoteProviderAuto).orEmpty()
        .takeIf { it in RemoteProviderValues } ?: RemoteProviderAuto

    fun isAvailable() =
        (preferLocal() && LocalMnnEngine.isReady()) ||
            when (VoiceTranscriptionClient.resolveRemoteProvider()) {
                RemoteProvider.ZHIPU -> zhipuKey().isNotEmpty()
                RemoteProvider.OPENAI -> openAIKey().isNotEmpty()
                RemoteProvider.GOOGLE -> googleKey().isNotEmpty()
            }

    fun hotwords(): List<String> = preferences.getString(Hotwords, "").orEmpty()
        .split(',', '\n')
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
        .take(100)
}
