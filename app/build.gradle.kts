plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

abstract class StageBrowserCore:Sync() {
    @get:OutputDirectory abstract val outputDirectory:DirectoryProperty
}
// The only new build input is the pinned sherpa-ncnn JNI runtime (no model weights).
abstract class PrepareOfflineSpeechRuntime:Exec() {
    @get:OutputDirectory abstract val outputDirectory:DirectoryProperty
}
val prepareOfflineSpeechRuntime=tasks.register<PrepareOfflineSpeechRuntime>("prepareOfflineSpeechRuntime") {
    val script=rootProject.layout.projectDirectory.file("scripts/prepare-speech-runtime.py")
    val archive=providers.environmentVariable("DAIYU_SPEECH_RUNTIME_ARCHIVE").orNull
    outputDirectory.set(layout.buildDirectory.dir("generated/offlineSpeech/jniLibs"))
    inputs.file(script)
    if(archive != null) inputs.file(file(archive))
    val python=if(System.getProperty("os.name").startsWith("Windows",ignoreCase=true)) "python" else "python3"
    val args=mutableListOf(python,script.asFile.absolutePath,outputDirectory.get().asFile.absolutePath)
    if(archive != null) args.addAll(listOf("--archive",file(archive).absolutePath))
    commandLine(args)
}
val browserRoot=rootProject.layout.projectDirectory.dir("components/openminis-browser")
androidComponents.onVariants { variant ->
    variant.sources.jniLibs?.addGeneratedSourceDirectory(prepareOfflineSpeechRuntime,PrepareOfflineSpeechRuntime::outputDirectory)
    val stageBrowserCore=tasks.register<StageBrowserCore>("stage${variant.name.replaceFirstChar {it.uppercaseChar()}}BrowserCore") {
        from(browserRoot.dir("upstream/src/main/kotlin")) {
            include(listOf("BrowserAction.kt","BrowserActionInput.kt","BrowserActionResult.kt",
                "BrowserUseJS.kt","InterceptedDialogQueue.kt","BrowserUseManager.kt").map {
                "com/openminis/app/browser/$it"
            })
        }
        into(outputDirectory)
    }
    variant.sources.kotlin?.addGeneratedSourceDirectory(stageBrowserCore,StageBrowserCore::outputDirectory)
}

val daiyuSyntheticApplication = providers.gradleProperty("daiyuSyntheticApplication").map {
    it.toBooleanStrictOrNull() ?: error("daiyuSyntheticApplication 必须是 true 或 false")
}.getOrElse(false)

android {
    buildFeatures { compose = true; aidl = true }
    useLibrary("android.test.runner")
    useLibrary("android.test.base")
    useLibrary("android.test.mock")
    namespace = "app.daiyu.android"
    compileSdk { version = release(37) { minorApiLevel = 2 } }
    buildToolsVersion = "37.0.0"
    defaultConfig {
        applicationId = "app.daiyu.android"
        minSdk = 26
        targetSdk = 37
        proguardFiles("offline-speech-rules.pro")
        testInstrumentationRunner = "android.test.InstrumentationTestRunner"
        versionCode = 1
        versionName = "0.1.0"
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        }
        externalNativeBuild {
            cmake {
                // Keep the helper usable on the app's minimum Android version.
                arguments += "-DANDROID_PLATFORM=android-26"
                // Executables must be selected explicitly for AGP packaging.
                targets += "daiyu_pty"
            }
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/kotlin/app/daiyu/android/terminal/native/CMakeLists.txt")
        }
    }
    packaging {
        jniLibs {
            // A ProcessBuilder executable needs an installed file, not an APK mmap entry.
            // AGP merges extractNativeLibs=true; the package installer owns extraction.
            useLegacyPackaging = true
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets {
        getByName("main") {
            kotlin.directories.add(browserRoot.dir("adapter/src/main/kotlin").asFile.absolutePath)
        }
        getByName("test").kotlin.directories.add(browserRoot.dir("adapter/src/test/kotlin").asFile.absolutePath)
    }
    val signingValues = listOf(
        "DAIYU_RELEASE_STORE_FILE", "DAIYU_RELEASE_STORE_PASSWORD",
        "DAIYU_RELEASE_KEY_ALIAS", "DAIYU_RELEASE_KEY_PASSWORD"
    ).map { System.getenv(it)?.takeIf(String::isNotBlank) }
    val fixedSigning = signingValues.all { it != null }
    check(signingValues.all { it == null } || fixedSigning) { "固定签名配置不完整" }
    check(System.getenv("DAIYU_REQUIRE_SIGNING") != "true" || fixedSigning) { "交付构建必须配置固定签名" }
    if (fixedSigning) {
        signingConfigs.create("release") {
            storeFile = file(signingValues[0]!!)
            storePassword = signingValues[1]
            keyAlias = signingValues[2]
            keyPassword = signingValues[3]
            storeType = "PKCS12"
            check(storeFile!!.isFile) { "固定签名文件不存在" }
        }
    }
    buildTypes {
        debug {
            if (daiyuSyntheticApplication) applicationIdSuffix = ".synthetic"
            if (fixedSigning) signingConfig = signingConfigs.getByName("release")
        }
        release {
            isMinifyEnabled = false
            if (fixedSigning) signingConfig = signingConfigs.getByName("release")
        }
    }
}
kotlin { jvmToolchain(27) }
dependencies {
    implementation(project(":core-domain"))
    // P32: bounded RFC6455 decoding + one-shot HTTPS; licenses/API26 evidence in docs/P32-voice-network.md.
    implementation("org.java-websocket:Java-WebSocket:1.6.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Java-WebSocket TRACE includes wire data. Never install a logging backend for this stack.
    runtimeOnly("org.slf4j:slf4j-nop:2.0.13")
    // Streaming rootfs extraction; XZ decoder memory is separately capped at 128 MiB.
    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation("org.tukaani:xz:1.12")
    // Keep the tested runtime aligned with Espresso 3.7.0.
    implementation("androidx.concurrent:concurrent-futures:1.2.0")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.4-rc01")
    implementation("top.yukonga.miuix.kmp:miuix-blur-android:0.9.4-rc01")
    implementation("top.yukonga.miuix.kmp:miuix-preference-android:0.9.4-rc01")
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.navigationevent:navigationevent-compose:1.1.2")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    // Match the Compose 1.9 runtime used by Material3 1.4; keep instrumentation APIs pinned.
    androidTestImplementation("androidx.compose.ui:ui-test-junit4:1.9.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.9.0")
    testImplementation("junit:junit:4.13.2")
    // The migrated owner tests use Android Intent/Binder at SDK 36; host tests only.
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("org.json:json:20260522")
}

val hostPty=layout.buildDirectory.file("host-pty/daiyu_pty")
val compileHostPty=tasks.register<Exec>("compileHostPty") {
    val source=layout.projectDirectory.file("src/main/kotlin/app/daiyu/android/terminal/native/daiyu_pty.c")
    inputs.file(source)
    outputs.file(hostPty)
    doFirst {hostPty.get().asFile.parentFile.mkdirs()}
    commandLine("cc","-std=c11","-Wall","-Wextra","-Werror","-O2",source.asFile.absolutePath,
        "-o",hostPty.get().asFile.absolutePath)
}
val verifyHostPty=tasks.register<Exec>("verifyHostPty") {
    val verifier=layout.projectDirectory.file("src/main/kotlin/app/daiyu/android/terminal/native/verify_host.py")
    val report=layout.buildDirectory.dir("reports/host-pty")
    dependsOn(compileHostPty)
    inputs.files(verifier,hostPty)
    outputs.dir(report)
    commandLine("python3",verifier.asFile.absolutePath,hostPty.get().asFile.absolutePath,
        report.get().asFile.absolutePath)
}
// Keep the build on JDK 27; Robolectric cannot instrument its runtime classes.
val hostTestJavaLauncher = extensions.getByType<org.gradle.jvm.toolchain.JavaToolchainService>()
    .launcherFor { languageVersion.set(org.gradle.jvm.toolchain.JavaLanguageVersion.of(21)) }
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    javaLauncher.set(hostTestJavaLauncher)
    doFirst {
        val launcher = javaLauncher.get()
        check(launcher.metadata.languageVersion.asInt() == 21)
        logger.lifecycle("Test JVM: task={} version={}", path, launcher.metadata.languageVersion)
    }
    testLogging { events("started", "failed", "skipped") }
    if(System.getProperty("os.name").startsWith("Linux",ignoreCase=true)) {
        dependsOn(verifyHostPty)
        systemProperty("daiyu.pty.helper",hostPty.get().asFile.absolutePath)
    } else {
        // These contracts exercise Unix host paths / POSIX 0600, not Windows equivalents.
        // They remain mandatory in the Linux lane; never weaken the production checks.
        exclude("**/PtyRunSessionHostTest.class", "**/LinuxEnvironmentStoreTest.class")
        filter {
            listOf(
                "executeOnlyPostsAndOffloadHasExactSecretBytesButSummaryHasNoValues",
                "keywordsAreStrictAndMatchOnlyAuthorizedNames",
                "tenantsAndWholeGenerationsAreFencedAndCancellationDoesNotTouchOtherOwner",
                "cancelBeforeStartAndAfterWriteFencesLatePublishAndLeavesNoFiles",
                "cookieNameAuthorizationReceivesOriginBeforeName",
            ).forEach { excludeTestsMatching("app.daiyu.android.browser.BrowserCookieBackendTest.$it") }
        }
        filter {excludeTestsMatching("app.daiyu.android.terminal.PtySessionHostTest.testRealPtyPersistsShellAndRejectsStartupReplay")}
    }
}
