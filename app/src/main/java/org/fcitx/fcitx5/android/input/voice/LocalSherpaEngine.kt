/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.input.voice

import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.WaveReader
import timber.log.Timber
import java.io.File

object LocalSherpaEngine {

    @Volatile
    private var recognizer: OfflineRecognizer? = null
    private var loadedModelSpec: LocalModelSpec.SherpaSpec? = null
    private val lock = Any()

    fun isReady(): Boolean {
        return LocalVoiceModel.currentSpec() is LocalModelSpec.SherpaSpec
    }

    fun currentModelName(): String {
        return when (val spec = LocalVoiceModel.currentSpec()) {
            is LocalModelSpec.SherpaSpec.SenseVoice -> "SenseVoiceSmall (Sherpa-ONNX)"
            is LocalModelSpec.SherpaSpec.Whisper -> "Whisper (Sherpa-ONNX)"
            is LocalModelSpec.SherpaSpec.Paraformer -> "Paraformer (Sherpa-ONNX)"
            is LocalModelSpec.SherpaSpec.Zipformer -> "Zipformer (Sherpa-ONNX)"
            is LocalModelSpec.QwenOmniMnn -> "Qwen2.5-Omni (MNN)"
            null -> "未安装"
        }
    }

    private fun getOrCreateRecognizer(): OfflineRecognizer {
        val spec = LocalVoiceModel.currentSpec() as? LocalModelSpec.SherpaSpec
            ?: error("当前目录未检测到兼容的 Sherpa-ONNX 离线语音模型")

        synchronized(lock) {
            if (recognizer != null && loadedModelSpec == spec) {
                return recognizer!!
            }
            unload()

            Timber.i("正在初始化 Sherpa-ONNX 离线模型: %s", spec)
            val modelConfig = OfflineModelConfig().apply {
                tokens = spec.tokensFile.absolutePath
                numThreads = 2
                debug = false
                provider = "cpu"

                when (spec) {
                    is LocalModelSpec.SherpaSpec.SenseVoice -> {
                        senseVoice = OfflineSenseVoiceModelConfig(
                            model = spec.modelFile.absolutePath,
                            language = "auto",
                            useInverseTextNormalization = true
                        )
                    }
                    is LocalModelSpec.SherpaSpec.Whisper -> {
                        whisper = OfflineWhisperModelConfig(
                            encoder = spec.encoderFile.absolutePath,
                            decoder = spec.decoderFile.absolutePath,
                            language = "zh"
                        )
                    }
                    is LocalModelSpec.SherpaSpec.Paraformer -> {
                        paraformer = OfflineParaformerModelConfig(
                            model = spec.modelFile.absolutePath
                        )
                    }
                    is LocalModelSpec.SherpaSpec.Zipformer -> {
                        transducer = OfflineTransducerModelConfig(
                            encoder = spec.encoderFile.absolutePath,
                            decoder = spec.decoderFile.absolutePath,
                            joiner = spec.joinerFile.absolutePath
                        )
                    }
                }
            }

            val recognizerConfig = OfflineRecognizerConfig().apply {
                this.modelConfig = modelConfig
            }

            val newRecognizer = OfflineRecognizer(null, recognizerConfig)
            recognizer = newRecognizer
            loadedModelSpec = spec
            Timber.i("Sherpa-ONNX 离线模型加载完成")
            return newRecognizer
        }
    }

    fun prewarm() {
        if (!isReady()) return
        getOrCreateRecognizer()
    }

    fun transcribe(
        audio: File,
        targetLanguage: String = "zh",
        onPartial: ((String) -> Unit)? = null
    ): String {
        val activeRecognizer = getOrCreateRecognizer()
        Timber.d("Sherpa-ONNX 开始识别音频: %s 大小=%d", audio.absolutePath, audio.length())

        val waveData = WaveReader.readWave(audio.absolutePath)
        val stream = activeRecognizer.createStream()
        try {
            stream.acceptWaveform(waveData.samples, waveData.sampleRate)
            activeRecognizer.decode(stream)
            val result = activeRecognizer.getResult(stream)
            val text = result.text.trim()
            Timber.d("Sherpa-ONNX 识别结果: %s", text)
            onPartial?.invoke(text)
            return text
        } finally {
            stream.release()
        }
    }

    fun unload() {
        synchronized(lock) {
            recognizer?.release()
            recognizer = null
            loadedModelSpec = null
        }
    }
}
