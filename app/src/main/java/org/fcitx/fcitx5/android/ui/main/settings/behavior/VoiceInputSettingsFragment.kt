/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import android.os.Bundle
import android.text.InputType
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.voice.LocalVoiceModel
import org.fcitx.fcitx5.android.input.voice.LocalVoiceModelDownloader
import org.fcitx.fcitx5.android.input.voice.ModelKeepAliveService
import org.fcitx.fcitx5.android.input.voice.VoiceInputPreferences
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment

class VoiceInputSettingsFragment : PaddingPreferenceFragment() {
    private var localModelPref: Preference? = null
    private var keepAlivePref: SwitchPreferenceCompat? = null

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext()).also { screen ->
            createPreferences(screen)
        }
    }

    private fun createPreferences(screen: PreferenceScreen) {
        val context = screen.context
        screen.addPreference(SwitchPreferenceCompat(context).apply {
            key = VoiceInputPreferences.PreferLocal
            title = context.getString(R.string.voice_input_prefer_local)
            summary = context.getString(R.string.voice_input_prefer_local_summary)
            setDefaultValue(true)
            isIconSpaceReserved = false
            setOnPreferenceChangeListener { _, _ ->
                updateKeepAliveState()
                true
            }
        })
        val modelPref = Preference(context).apply {
            title = context.getString(R.string.voice_input_local_model)
            summary = localModelSummary()
            isIconSpaceReserved = false
            setOnPreferenceClickListener {
                if (!LocalVoiceModel.isReady() && isEnabled && !LocalVoiceModel.isCustom()) {
                    isEnabled = false
                    lifecycleScope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                LocalVoiceModelDownloader.download { downloaded, total ->
                                    val percent = downloaded * 100 / total
                                    lifecycleScope.launch {
                                        summary = getString(R.string.voice_input_model_downloading, percent)
                                    }
                                }
                            }
                        }.onSuccess {
                            summary = localModelSummary()
                            updateKeepAliveState()
                        }.onFailure {
                            summary = getString(R.string.voice_input_model_download_failed, it.message)
                        }
                        isEnabled = true
                    }
                }
                true
            }
        }
        localModelPref = modelPref
        screen.addPreference(modelPref)

        screen.addPreference(EditTextPreference(context).apply {
            key = VoiceInputPreferences.CustomModelPath
            title = context.getString(R.string.voice_input_custom_model_path)
            isIconSpaceReserved = false
            isSingleLineTitle = false
            summaryProvider = Preference.SummaryProvider<EditTextPreference> { preference ->
                if (preference.text.isNullOrBlank()) {
                    context.getString(R.string.voice_input_custom_model_path_default)
                } else {
                    preference.text
                }
            }
            setOnPreferenceChangeListener { _, _ ->
                view?.post {
                    localModelPref?.summary = localModelSummary()
                    updateKeepAliveState()
                }
                true
            }
        })

        val keepAliveSwitch = SwitchPreferenceCompat(context).apply {
            key = VoiceInputPreferences.KeepModelReady
            title = context.getString(R.string.voice_input_keep_model_ready)
            setDefaultValue(false)
            isIconSpaceReserved = false
            setOnPreferenceChangeListener { _, newValue ->
                if (newValue == true) ModelKeepAliveService.start(context)
                else ModelKeepAliveService.stop(context)
                true
            }
        }
        keepAlivePref = keepAliveSwitch
        screen.addPreference(keepAliveSwitch)
        updateKeepAliveState()

        screen.addPreference(ListPreference(context).apply {
            key = VoiceInputPreferences.RemoteProvider
            title = context.getString(R.string.voice_input_remote_provider)
            entries = arrayOf(
                context.getString(R.string.voice_input_provider_openai),
                context.getString(R.string.voice_input_provider_google)
            )
            entryValues = arrayOf(
                VoiceInputPreferences.ProviderOpenAI,
                VoiceInputPreferences.ProviderGoogle
            )
            setDefaultValue(VoiceInputPreferences.ProviderOpenAI)
            summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
            isIconSpaceReserved = false
        })

        screen.addPreference(secretPreference(R.string.voice_input_openai_key, VoiceInputPreferences.OpenAIKey))
        screen.addPreference(secretPreference(R.string.voice_input_google_key, VoiceInputPreferences.GoogleKey))
        screen.addPreference(EditTextPreference(context).apply {
            key = VoiceInputPreferences.Hotwords
            title = context.getString(R.string.voice_input_hotwords)
            summary = context.getString(R.string.voice_input_hotwords_summary)
            isIconSpaceReserved = false
            isSingleLineTitle = false
            setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                it.isSingleLine = false
            }
        })
    }

    private fun updateKeepAliveState() {
        val keepAliveAvailable = LocalVoiceModel.isReady() && VoiceInputPreferences.preferLocal()
        keepAlivePref?.apply {
            isEnabled = keepAliveAvailable
            summary = if (keepAliveAvailable) {
                context.getString(R.string.voice_input_keep_model_ready_summary)
            } else {
                context.getString(R.string.voice_input_keep_model_ready_unavailable)
            }
        }
    }

    private fun localModelSummary(): String {
        return if (LocalVoiceModel.isReady()) {
            if (LocalVoiceModel.isCustom()) {
                getString(R.string.voice_input_custom_model_ready, LocalVoiceModel.directory().absolutePath)
            } else {
                getString(R.string.voice_input_model_ready)
            }
        } else {
            if (LocalVoiceModel.isCustom()) {
                getString(R.string.voice_input_custom_model_not_found, LocalVoiceModel.directory().absolutePath)
            } else {
                getString(R.string.voice_input_model_download)
            }
        }
    }

    private fun secretPreference(title: Int, keyValue: String) =
        EditTextPreference(requireContext()).apply {
            key = keyValue
            setTitle(title)
            isIconSpaceReserved = false
            isSingleLineTitle = false
            summaryProvider = Preference.SummaryProvider<EditTextPreference> { preference ->
                if (preference.text.isNullOrBlank()) getString(R.string.voice_input_key_missing)
                else getString(R.string.voice_input_key_saved)
            }
            setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                it.isSingleLine = true
            }
        }
}
