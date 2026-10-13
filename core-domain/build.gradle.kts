plugins { kotlin("jvm") }
kotlin {
    jvmToolchain(27)
    compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 }
}
tasks.withType<JavaCompile>().configureEach { options.release = 17 }
dependencies { testImplementation("junit:junit:4.13.2") }
tasks.test { useJUnit() }
