package app.daiyu.android.voice.offline

/** Immutable upstream model revision, file lengths and LFS SHA-256 values. */
internal object SpeechModelManifest {
    const val REVISION = "05945efc40afe4b572542f01104ca5c413a9f6e1"
    const val BASE_URL = "https://huggingface.co/csukuangfj/sherpa-ncnn-streaming-zipformer-bilingual-zh-en-2023-02-13/resolve/$REVISION/"
    data class Asset(val name: String, val bytes: Long, val sha256: String)
    val assets = listOf(
        Asset("decoder_jit_trace-pnnx.ncnn.bin", 6412296L, "dc4df2d8e1ddee1b90ac72a2de982eb1d320ee6c9a70e1dee4d23d9acfc8b978"),
        Asset("decoder_jit_trace-pnnx.ncnn.param", 439L, "cb88f5894978fd3e85369d2f8ea55621809fceb2b5158243fb0cd025eb4f1aaf"),
        Asset("encoder_jit_trace-pnnx.ncnn.bin", 127364056L, "4ed65f05b78c0106d3d176018ab01e26a15c200604490d3d49b08cc75a122dd0"),
        Asset("encoder_jit_trace-pnnx.ncnn.param", 161888L, "97ad0954fb2cb4730f87a7eb66401b024f756752ece246e4b2063f870ebf3e18"),
        Asset("joiner_jit_trace-pnnx.ncnn.bin", 7350724L, "0e6c4370017394de5d74128756233d2e4451209e63ac2abd525da3b089e8bee1"),
        Asset("joiner_jit_trace-pnnx.ncnn.param", 490L, "46c339f3869136c2f6d9d9d6983a6cbc2bfbcd0e3dab0f76ae25e9477f00a360"),
        Asset("tokens.txt", 56317L, "a8e0e4ec53810e433789b54a5c0134a7eaa2ffca595a6334d54c00da858841d3"),
    )
    val totalBytes: Long = assets.sumOf { it.bytes }
}
