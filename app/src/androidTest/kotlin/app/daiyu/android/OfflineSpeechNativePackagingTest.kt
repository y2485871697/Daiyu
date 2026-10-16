package app.daiyu.android

import android.test.InstrumentationTestCase
import java.io.File
import java.util.zip.ZipFile

/** Packaging/linker proof only. Does not claim microphone/model recognition or download a pack. */
@Suppress("DEPRECATION")
class OfflineSpeechNativePackagingTest : InstrumentationTestCase() {
    fun testFinalApkContainsAllPinnedRuntimeAbisAndJniCanLoadOnThisDevice() {
        val context = instrumentation.targetContext
        ZipFile(context.applicationInfo.sourceDir).use { apk ->
            for (abi in listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")) {
                for (library in listOf("libncnn.so", "libsherpa-ncnn-jni.so")) {
                    assertNotNull("missing $abi/$library in final APK", apk.getEntry("lib/$abi/$library"))
                }
            }
        }
        val installed = File(context.applicationInfo.nativeLibraryDir)
        assertTrue(File(installed, "libncnn.so").isFile)
        assertTrue(File(installed, "libsherpa-ncnn-jni.so").isFile)
        // Runs the actual binding initializer and dynamic loader, not a fake backend.
        val binding = Class.forName("com.k2fsa.sherpa.ncnn.SherpaNcnn", true, context.classLoader)
        assertNotNull(binding.getDeclaredField("ptr"))
        assertNotNull(binding.declaredMethods.singleOrNull { it.name == "newFromFile" })
        val config = Class.forName("com.k2fsa.sherpa.ncnn.ModelConfig", false, context.classLoader)
        for (name in listOf("encoderParam", "encoderBin", "decoderParam", "decoderBin", "joinerParam", "joinerBin", "tokens", "numThreads", "useGPU")) {
            assertNotNull(config.getDeclaredField(name))
        }
    }
    fun testApkDeliversCompleteEngineKissFftAndModelNotices() {
        val assets = instrumentation.targetContext.assets
        val root = "licenses/sherpa-ncnn/"
        for (name in listOf("LICENSE", "LICENSE-ncnn", "LICENSE-kaldi-native-fbank", "NOTICE.md", "MODEL-CARD.md")) {
            assertTrue("missing complete $name", assets.open(root + name).use { it.readBytes() }.size > 100)
        }
        val exact = mapOf(
            "KISSFFT-COPYING" to "a2840585f8411be8e6826a31ef15ae65c950bd74a2437a73b013398a934ad0c6",
            "KISSFFT-LICENSE" to "4d6bcab46f27d6d0c878e8305d4bdcfe040d52bf6656b9771f04c9fb9902c0ed")
        for ((name, expected) in exact) {
            val bytes = assets.open(root + name).use { it.readBytes() }
            val actual = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals(name, expected, actual)
        }
        assertTrue(assets.open(root + "MODEL-CARD.md").bufferedReader().use { it.readText() }
            .contains("license: apache-2.0"))
    }

}
