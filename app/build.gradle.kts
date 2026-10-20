plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.naeem.assistant"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.naeem.assistant"
        minSdk = 26
        targetSdk = 35
        versionCode = 60
        versionName = "0.6.0-preview"
        testInstrumentationRunner = "android.test.InstrumentationTestRunner"
    }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = true }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            val runtimeHome = File(gradle.gradleUserHomeDir, "assistant-test-runtime").apply { mkdirs() }
            it.systemProperty("user.home", runtimeHome.absolutePath)
            it.systemProperty("java.io.tmpdir", File(runtimeHome,"tmp").apply { mkdirs() }.absolutePath)
            it.systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
        }
    }
}
dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
