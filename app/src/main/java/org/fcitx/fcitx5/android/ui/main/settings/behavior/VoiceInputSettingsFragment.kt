/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import android.text.InputType
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.documentfile.provider.DocumentFile
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
import org.fcitx.fcitx5.android.input.voice.LocalModelSpec
import org.fcitx.fcitx5.android.input.voice.LocalSherpaEngine
import org.fcitx.fcitx5.android.input.voice.LocalVoiceModel
import org.fcitx.fcitx5.android.input.voice.LocalVoiceModelDownloader
import org.fcitx.fcitx5.android.input.voice.ModelKeepAliveService
import org.fcitx.fcitx5.android.input.voice.VoiceInputPreferences
import org.fcitx.fcitx5.android.input.voice.VoiceTranscriptionClient
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import java.io.File

class VoiceInputSettingsFragment : PaddingPreferenceFragment() {
    private var localModelPref: Preference? = null
    private var modelDirectoryPref: Preference? = null
    private var keepAlivePref: SwitchPreferenceCompat? = null
    private var downloadAfterFolderSelected = false

    private fun modelDirectorySummary(): String {
        val customPath = VoiceInputPreferences.customModelPath()
        return if (customPath.isNotBlank()) {
            customPath
        } else {
            LocalVoiceModel.defaultDirectory.absolutePath
        }
    }

    private val chooseDirectoryLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) {
                downloadAfterFolderSelected = false
                return@registerForActivityResult
            }
            val path = uriToAbsolutePath(uri)
            if (!path.isNullOrBlank()) {
                VoiceInputPreferences.setCustomModelPath(path)
                modelDirectoryPref?.summary = path
                localModelPref?.summary = localModelSummary()
                updateKeepAliveState()
                val targetDir = File(path)
                if (downloadAfterFolderSelected) {
                    downloadAfterFolderSelected = false
                    startDownloadModel(targetDir)
                } else {
                    if (LocalVoiceModel.isReady()) {
                        val modelName = LocalSherpaEngine.currentModelName()
                        Toast.makeText(
                            requireContext(),
                            "已成功加载本地模型 [$modelName]",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        handleModelNotReady(uri, targetDir)
                    }
                }
            } else {
                downloadAfterFolderSelected = false
                handleModelNotReady(uri, null)
            }
        }

    private fun handleModelNotReady(uri: Uri, targetDir: File?) {
        val context = requireContext()
        val hasAllFilesAccess = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }

        val builder = AlertDialog.Builder(context)
            .setTitle("本地模型尚未就绪")

        if (!hasAllFilesAccess) {
            builder.setMessage(
                "未能直接读取所选目录中的模型文件。这通常是由于 Android 系统限制了对外部文件夹的访问权限。\n\n" +
                "请选择以下处理方式：\n" +
                "1. 点击【授权所有文件权限】允许读取手机存储文件夹；\n" +
                "2. 或点击【导入至应用内部】将模型直接复制到输入法内部（零权限离线使用）。"
            )
            .setPositiveButton("授权所有文件权限") { _, _ ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                    startActivity(intent)
                }
            }
            .setNeutralButton("导入至应用内部") { _, _ ->
                importModelFromSafUri(uri)
            }
            .setNegativeButton(android.R.string.cancel, null)
        } else {
            val filesFound = targetDir?.listFiles()?.joinToString { it.name } ?: "无法列出文件"
            builder.setMessage(
                "已获得存储访问权限，但所选目录未检测到完整的离线语音模型。\n\n" +
                "当前目录内容：$filesFound\n\n" +
                "请确认目录中包含：\n" +
                "• model.int8.onnx (或 model.onnx)\n" +
                "• tokens.txt (或 tokens.json)"
            )
            .setPositiveButton("尝试从该目录导入") { _, _ ->
                importModelFromSafUri(uri)
            }
            .setNegativeButton(android.R.string.cancel, null)
        }
        builder.show()
    }

    private fun importModelFromSafUri(uri: Uri) {
        val context = requireContext()
        val docFolder = DocumentFile.fromTreeUri(context, uri) ?: run {
            Toast.makeText(context, "无法访问所选文件夹", Toast.LENGTH_SHORT).show()
            return
        }

        val targetDir = context.filesDir.resolve("models/custom_asr")
        targetDir.mkdirs()

        val progressDialog = AlertDialog.Builder(context)
            .setTitle("正在导入模型到输入法内部…")
            .setMessage("请稍候，复制完成后即可零权限离线使用")
            .setCancelable(false)
            .create()
        progressDialog.show()

        lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) {
                runCatching {
                    for (file in docFolder.listFiles()) {
                        val name = file.name ?: continue
                        if (name.endsWith(".onnx", ignoreCase = true) ||
                            name.startsWith("tokens", ignoreCase = true) ||
                            name.endsWith(".json", ignoreCase = true) ||
                            name.endsWith(".txt", ignoreCase = true) ||
                            name.endsWith(".mnn", ignoreCase = true)
                        ) {
                            val dest = targetDir.resolve(name)
                            context.contentResolver.openInputStream(file.uri)?.use { input ->
                                dest.outputStream().use { output ->
                                    input.copyTo(output)
                                }
                            }
                        }
                    }
                    LocalModelSpec.detect(targetDir) != null
                }.getOrDefault(false)
            }
            progressDialog.dismiss()
            if (success) {
                VoiceInputPreferences.setCustomModelPath(targetDir.absolutePath)
                modelDirectoryPref?.summary = modelDirectorySummary()
                localModelPref?.summary = localModelSummary()
                updateKeepAliveState()
                AlertDialog.Builder(context)
                    .setTitle("导入成功")
                    .setMessage("模型已成功导入至应用私有目录，现在可以正常离线使用了！")
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            } else {
                AlertDialog.Builder(context)
                    .setTitle("导入未完成")
                    .setMessage("所选文件夹中未找到有效的模型文件（需要 model.int8.onnx 和 tokens.txt）。")
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
    }

    private fun uriToAbsolutePath(uri: Uri): String? {
        val docId = runCatching {
            if (DocumentsContract.isTreeUri(uri)) {
                DocumentsContract.getTreeDocumentId(uri)
            } else {
                DocumentsContract.getDocumentId(uri)
            }
        }.getOrNull() ?: uri.path ?: return null

        val split = docId.split(":")
        if (split.isNotEmpty()) {
            val type = split[0]
            val relativePath = if (split.size > 1) split.subList(1, split.size).joinToString(":") else ""
            if ("primary".equals(type, ignoreCase = true)) {
                val root = Environment.getExternalStorageDirectory().absolutePath
                return if (relativePath.isNotEmpty()) "$root/$relativePath" else root
            } else if (type.isNotEmpty()) {
                val candidate1 = "/storage/$type" + if (relativePath.isNotEmpty()) "/$relativePath" else ""
                if (File(candidate1).exists()) return candidate1
                val candidate2 = "/storage/emulated/0" + if (relativePath.isNotEmpty()) "/$relativePath" else ""
                if (File(candidate2).exists()) return candidate2
                return candidate1
            }
        }
        return uri.path
    }

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
                promptDownloadModel()
                true
            }
        }
        localModelPref = modelPref
        screen.addPreference(modelPref)

        val chooseDirPref = Preference(context).apply {
            key = VoiceInputPreferences.CustomModelPath
            title = context.getString(R.string.voice_input_choose_model_directory)
            summary = modelDirectorySummary()
            isIconSpaceReserved = false
            setOnPreferenceClickListener {
                downloadAfterFolderSelected = false
                chooseDirectoryLauncher.launch(null)
                true
            }
        }
        modelDirectoryPref = chooseDirPref
        screen.addPreference(chooseDirPref)

        screen.addPreference(Preference(context).apply {
            title = context.getString(R.string.voice_input_test_local_model)
            summary = context.getString(R.string.voice_input_test_local_model_summary)
            isIconSpaceReserved = false
            setOnPreferenceClickListener {
                isEnabled = false
                summary = getString(R.string.voice_input_test_local_model_testing)
                lifecycleScope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            VoiceTranscriptionClient.testLocalModel()
                        }
                    }
                    isEnabled = true
                    result.onSuccess { msg ->
                        summary = getString(R.string.voice_input_test_local_model_success, msg)
                        Toast.makeText(context, getString(R.string.voice_input_test_local_model_success, msg), Toast.LENGTH_SHORT).show()
                    }.onFailure { error ->
                        val msg = error.message ?: error.toString()
                        summary = getString(R.string.voice_input_test_local_model_failed, "请查看详细诊断弹窗")
                        showTestFailureDialog(msg)
                    }
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

        val openAIEndpointPref = EditTextPreference(context).apply {
            key = VoiceInputPreferences.OpenAIEndpoint
            title = context.getString(R.string.voice_input_openai_endpoint)
            summaryProvider = Preference.SummaryProvider<EditTextPreference> { preference ->
                if (preference.text.isNullOrBlank()) {
                    context.getString(R.string.voice_input_openai_endpoint_default)
                } else {
                    preference.text
                }
            }
            isIconSpaceReserved = false
            isSingleLineTitle = false
            setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                it.isSingleLine = true
            }
        }

        val openAIModelPref = EditTextPreference(context).apply {
            key = VoiceInputPreferences.OpenAIModel
            title = context.getString(R.string.voice_input_openai_model)
            summaryProvider = Preference.SummaryProvider<EditTextPreference> { preference ->
                if (preference.text.isNullOrBlank()) {
                    context.getString(R.string.voice_input_openai_model_default)
                } else {
                    preference.text
                }
            }
            isIconSpaceReserved = false
            isSingleLineTitle = false
            setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_TEXT
                it.isSingleLine = true
            }
        }

        val openAIKeyPref = secretPreference(R.string.voice_input_openai_key, VoiceInputPreferences.OpenAIKey)

        val openAITestPref = Preference(context).apply {
            title = context.getString(R.string.voice_input_test_openai_connection)
            summary = context.getString(R.string.voice_input_test_openai_connection_summary)
            isIconSpaceReserved = false
            setOnPreferenceClickListener {
                val key = VoiceInputPreferences.openAIKey()
                if (key.isBlank()) {
                    Toast.makeText(context, R.string.voice_input_test_openai_key_empty, Toast.LENGTH_SHORT).show()
                    return@setOnPreferenceClickListener true
                }
                val domain = VoiceInputPreferences.openAIEndpoint()
                val model = VoiceInputPreferences.openAIModel()
                isEnabled = false
                summary = getString(R.string.voice_input_test_openai_testing)
                lifecycleScope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            VoiceTranscriptionClient.testOpenAIConnection(key, domain, model)
                        }
                    }
                    isEnabled = true
                    result.onSuccess {
                        summary = getString(R.string.voice_input_test_openai_success)
                        Toast.makeText(context, R.string.voice_input_test_openai_success, Toast.LENGTH_SHORT).show()
                    }.onFailure { error ->
                        val msg = error.message ?: error.toString()
                        summary = getString(R.string.voice_input_test_openai_failed, msg)
                        Toast.makeText(
                            context,
                            getString(R.string.voice_input_test_openai_failed, msg),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
                true
            }
        }

        val googleKeyPref = secretPreference(R.string.voice_input_google_key, VoiceInputPreferences.GoogleKey)

        val googleTestPref = Preference(context).apply {
            title = context.getString(R.string.voice_input_test_google_connection)
            summary = context.getString(R.string.voice_input_test_google_connection_summary)
            isIconSpaceReserved = false
            setOnPreferenceClickListener {
                val key = VoiceInputPreferences.googleKey()
                if (key.isBlank()) {
                    Toast.makeText(context, R.string.voice_input_test_google_key_empty, Toast.LENGTH_SHORT).show()
                    return@setOnPreferenceClickListener true
                }
                isEnabled = false
                summary = getString(R.string.voice_input_test_google_testing)
                lifecycleScope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            VoiceTranscriptionClient.testGoogleConnection(key)
                        }
                    }
                    isEnabled = true
                    result.onSuccess {
                        summary = getString(R.string.voice_input_test_google_success)
                        Toast.makeText(context, R.string.voice_input_test_google_success, Toast.LENGTH_SHORT).show()
                    }.onFailure { error ->
                        val msg = error.message ?: error.toString()
                        summary = getString(R.string.voice_input_test_google_failed, msg)
                        Toast.makeText(
                            context,
                            getString(R.string.voice_input_test_google_failed, msg),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
                true
            }
        }

        fun updateRemoteProviderDependencies(provider: String) {
            val isOpenAI = provider == VoiceInputPreferences.ProviderOpenAI
            val isGoogle = provider == VoiceInputPreferences.ProviderGoogle

            openAIEndpointPref.isEnabled = isOpenAI
            openAIModelPref.isEnabled = isOpenAI
            openAIKeyPref.isEnabled = isOpenAI
            openAITestPref.isEnabled = isOpenAI

            googleKeyPref.isEnabled = isGoogle
            googleTestPref.isEnabled = isGoogle
        }

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
            setOnPreferenceChangeListener { _, newValue ->
                updateRemoteProviderDependencies(newValue as String)
                true
            }
        })

        screen.addPreference(openAIEndpointPref)
        screen.addPreference(openAIModelPref)
        screen.addPreference(openAIKeyPref)
        screen.addPreference(openAITestPref)

        screen.addPreference(googleKeyPref)
        screen.addPreference(googleTestPref)

        updateRemoteProviderDependencies(VoiceInputPreferences.remoteProvider())
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

    private fun promptDownloadModel() {
        if (LocalVoiceModel.isReady()) {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.voice_input_local_model)
                .setMessage(getString(R.string.voice_input_model_already_ready_confirm, LocalVoiceModel.directory().absolutePath))
                .setPositiveButton(R.string.voice_input_redownload) { _, _ ->
                    showDownloadLocationDialog()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } else {
            showDownloadLocationDialog()
        }
    }

    private fun showDownloadLocationDialog() {
        val context = requireContext()
        val customPath = VoiceInputPreferences.customModelPath()
        val defaultDir = LocalVoiceModel.defaultDirectory

        val options: Array<String>
        val actions: List<() -> Unit>

        if (customPath.isNotEmpty()) {
            val customDir = File(customPath)
            options = arrayOf(
                getString(R.string.voice_input_download_to_custom, customPath),
                getString(R.string.voice_input_download_to_default, defaultDir.absolutePath)
            )
            actions = listOf(
                { startDownloadModel(customDir) },
                {
                    VoiceInputPreferences.setCustomModelPath("")
                    modelDirectoryPref?.summary = modelDirectorySummary()
                    localModelPref?.summary = localModelSummary()
                    updateKeepAliveState()
                    startDownloadModel(defaultDir)
                }
            )
        } else {
            options = arrayOf(
                getString(R.string.voice_input_download_to_default, defaultDir.absolutePath),
                getString(R.string.voice_input_download_choose_folder)
            )
            actions = listOf(
                { startDownloadModel(defaultDir) },
                {
                    downloadAfterFolderSelected = true
                    chooseDirectoryLauncher.launch(null)
                }
            )
        }

        AlertDialog.Builder(context)
            .setTitle(R.string.voice_input_download_target_title)
            .setItems(options) { _, which ->
                actions.getOrNull(which)?.invoke()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun startDownloadModel(targetDir: File) {
        val modelPref = localModelPref ?: return
        if (!modelPref.isEnabled) return
        modelPref.isEnabled = false
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    LocalVoiceModelDownloader.download(targetDir) { downloaded, total ->
                        val percent = downloaded * 100 / total
                        lifecycleScope.launch {
                            modelPref.summary = getString(R.string.voice_input_model_downloading, percent)
                        }
                    }
                }
            }.onSuccess {
                modelPref.summary = localModelSummary()
                updateKeepAliveState()
                Toast.makeText(requireContext(), R.string.voice_input_model_ready, Toast.LENGTH_SHORT).show()
            }.onFailure {
                modelPref.summary = getString(R.string.voice_input_model_download_failed, it.message)
                Toast.makeText(
                    requireContext(),
                    getString(R.string.voice_input_model_download_failed, it.message),
                    Toast.LENGTH_LONG
                ).show()
            }
            modelPref.isEnabled = true
        }
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
            val modelName = LocalSherpaEngine.currentModelName()
            if (LocalVoiceModel.isCustom()) {
                "[$modelName] ${getString(R.string.voice_input_custom_model_ready, LocalVoiceModel.directory().absolutePath)}"
            } else {
                "[$modelName] ${getString(R.string.voice_input_model_ready)}"
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

    private fun showTestFailureDialog(msg: String) {
        val context = requireContext()
        val hasAllFilesAccess = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
        val builder = AlertDialog.Builder(context)
            .setTitle("本地模型测试诊断")
            .setMessage(msg)
            .setPositiveButton(android.R.string.ok, null)

        if (!hasAllFilesAccess && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setNeutralButton("授权所有文件权限") { _, _ ->
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
                startActivity(intent)
            }
        }
        builder.show()
    }
}
