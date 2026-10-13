plugins { id("com.android.application") }
android {
    namespace = "io.github.y2485871697.daiyu"
    compileSdk { version = release(37) { minorApiLevel = 2 } }
    buildToolsVersion = "37.0.0"
    defaultConfig {
        applicationId = "io.github.y2485871697.daiyu"
        minSdk = 26
        targetSdk = 37
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
