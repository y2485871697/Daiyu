plugins { id("com.android.application"); kotlin("android") }
android {
    namespace = "io.github.y2485871697.daiyu"
    compileSdk = 36
    defaultConfig {
        applicationId = "io.github.y2485871697.daiyu"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes { release { isMinifyEnabled = false } }
}
kotlin { jvmToolchain(17) }
dependencies { implementation(project(":core-domain")) }
