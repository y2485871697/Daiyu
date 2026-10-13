plugins { id("com.android.application") }
android {
    useLibrary("android.test.runner")
    useLibrary("android.test.base")
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
    buildTypes { release { isMinifyEnabled = false } }
}
kotlin { jvmToolchain(27) }
dependencies { implementation(project(":core-domain")) }
