// SPDX-License-Identifier: Apache-2.0
// Adapted from k2-fsa/sherpa-ncnn v2.1.15; explicit close added by the maintainer.
package com.k2fsa.sherpa.ncnn

import android.content.res.AssetManager

data class FeatureExtractorConfig(
    var sampleRate: Float,
    var featureDim: Int,
)

data class ModelConfig(
    var encoderParam: String,
    var encoderBin: String,
    var decoderParam: String,
    var decoderBin: String,
    var joinerParam: String,
    var joinerBin: String,
    var tokens: String,
    var numThreads: Int = 1,
    var useGPU: Boolean = true,
)

data class DecoderConfig(
    var method: String = "modified_beam_search",
    var numActivePaths: Int = 4,
)

data class RecognizerConfig(
    var featConfig: FeatureExtractorConfig,
    var modelConfig: ModelConfig,
    var decoderConfig: DecoderConfig,
    var enableEndpoint: Boolean = true,
    var rule1MinTrailingSilence: Float = 2.4f,
    var rule2MinTrailingSilence: Float = 1.0f,
    var rule3MinUtteranceLength: Float = 30.0f,
    var hotwordsFile: String = "",
    var hotwordsScore: Float = 1.5f,
)

class SherpaNcnn(
    var config: RecognizerConfig,
    assetManager: AssetManager? = null,
) : AutoCloseable {
    private var ptr: Long

    init {
        ptr = if (assetManager != null) newFromAsset(assetManager, config) else newFromFile(config)
        check(ptr != 0L) { "Unable to initialize offline recognizer" }
    }

    override fun close() {
        if (ptr != 0L) {
            delete(ptr)
            ptr = 0L
        }
    }

    fun acceptSamples(samples: FloatArray) =
        acceptWaveform(ptr, samples = samples, sampleRate = config.featConfig.sampleRate)
    fun isReady() = isReady(ptr)
    fun decode() = decode(ptr)
    fun inputFinished() = inputFinished(ptr)
    fun isEndpoint(): Boolean = isEndpoint(ptr)
    fun reset(recreate: Boolean = false) = reset(ptr, recreate = recreate)
    val text: String get() = getText(ptr)

    private external fun newFromAsset(assetManager: AssetManager, config: RecognizerConfig): Long
    private external fun newFromFile(config: RecognizerConfig): Long
    private external fun delete(ptr: Long)
    private external fun acceptWaveform(ptr: Long, samples: FloatArray, sampleRate: Float)
    private external fun inputFinished(ptr: Long)
    private external fun isReady(ptr: Long): Boolean
    private external fun decode(ptr: Long)
    private external fun isEndpoint(ptr: Long): Boolean
    private external fun reset(ptr: Long, recreate: Boolean)
    private external fun getText(ptr: Long): String

    companion object {
        init {
            // Prevent OpenMP affinity failures when Android offlines a CPU in powersave.
            android.system.Os.setenv("KMP_AFFINITY", "disabled", true)
            android.system.Os.setenv("KMP_DUPLICATE_LIB_OK", "TRUE", true)
            android.system.Os.setenv("OMP_NUM_THREADS", "1", true)
            android.system.Os.setenv("OMP_WAIT_POLICY", "PASSIVE", true)
            System.loadLibrary("ncnn")
            System.loadLibrary("sherpa-ncnn-jni")
        }
    }
}
