plugins { id("com.android.application") }
android {
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
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260522")
}
