plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

abstract class StageBrowserCore:Sync() {
    @get:OutputDirectory abstract val outputDirectory:DirectoryProperty
}
val browserRoot=rootProject.layout.projectDirectory.dir("components/openminis-browser")
androidComponents.onVariants { variant ->
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

android {
    buildFeatures { compose = true }
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
        testInstrumentationRunner = "android.test.InstrumentationTestRunner"
        versionCode = 1
        versionName = "0.1.0"
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
        debug { if (fixedSigning) signingConfig = signingConfigs.getByName("release") }
        release {
            isMinifyEnabled = false
            if (fixedSigning) signingConfig = signingConfigs.getByName("release")
        }
    }
}
kotlin { jvmToolchain(27) }
dependencies {
    implementation(project(":core-domain"))
    implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.4-rc01")
    implementation("top.yukonga.miuix.kmp:miuix-blur-android:0.9.4-rc01")
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.navigationevent:navigationevent-compose:1.1.2")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    testImplementation("junit:junit:4.13.2")
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
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    if(System.getProperty("os.name").startsWith("Linux",ignoreCase=true)) {
        dependsOn(verifyHostPty)
        systemProperty("daiyu.pty.helper",hostPty.get().asFile.absolutePath)
    } else {
        exclude("**/PtyRunSessionHostTest.class")
        filter {excludeTestsMatching("app.daiyu.android.terminal.PtySessionHostTest.testRealPtyPersistsShellAndRejectsStartupReplay")}
    }
}
