plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// --- Upload-keystore guard ---
// Fail fast if release/internal builds are requested without RELEASE_* signing
// configured. Mirrors loxation-android/app/build.gradle:81-90.
val hasUploadSigning: Boolean = listOf(
    "RELEASE_STORE_FILE", "RELEASE_STORE_PASSWORD",
    "RELEASE_KEY_ALIAS",  "RELEASE_KEY_PASSWORD"
).all { name ->
    val v = (project.findProperty(name) as String?) ?: System.getenv(name)
    !v.isNullOrBlank()
}
val requestedTasks: List<String> = gradle.startParameter.taskNames.map { it.lowercase() }
val isProtectedBuild: Boolean = requestedTasks.any { it.contains("release") || it.contains("internal") }
if (isProtectedBuild && !hasUploadSigning) {
    throw GradleException(
        "Upload keystore is not configured. Set RELEASE_STORE_FILE, " +
        "RELEASE_STORE_PASSWORD, RELEASE_KEY_ALIAS, RELEASE_KEY_PASSWORD as " +
        "Gradle properties (e.g. in ~/.gradle/gradle.properties) or env vars " +
        "before building release/internal variants. See DEPLOY.md."
    )
}

android {
    namespace = "com.blemesh.router"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.blemesh.router"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        create("release") {
            val storeFileProp     = (project.findProperty("RELEASE_STORE_FILE") as String?)
                ?: System.getenv("RELEASE_STORE_FILE")
            val storePasswordProp = (project.findProperty("RELEASE_STORE_PASSWORD") as String?)
                ?: System.getenv("RELEASE_STORE_PASSWORD")
            val keyAliasProp      = (project.findProperty("RELEASE_KEY_ALIAS") as String?)
                ?: System.getenv("RELEASE_KEY_ALIAS")
            val keyPasswordProp   = (project.findProperty("RELEASE_KEY_PASSWORD") as String?)
                ?: System.getenv("RELEASE_KEY_PASSWORD")
            if (!storeFileProp.isNullOrBlank() && !storePasswordProp.isNullOrBlank()
                && !keyAliasProp.isNullOrBlank() && !keyPasswordProp.isNullOrBlank()) {
                storeFile     = file(storeFileProp)
                storePassword = storePasswordProp
                keyAlias      = keyAliasProp
                keyPassword   = keyPasswordProp
            } else {
                // Dev-only fallback. Never upload builds signed with the debug keystore.
                storeFile     = file(System.getProperty("user.home") + "/.android/debug.keystore")
                storePassword = "android"
                keyAlias      = "androiddebugkey"
                keyPassword   = "android"
            }
            enableV1Signing = true
            enableV2Signing = true
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
            isDebuggable = false
        }
        // Variant intended for side-loaded QA builds and Play Internal Testing.
        // Same applicationId as release (no suffix), signed with the upload key,
        // but debuggable=true and minify=off so logcat + breakpoints work.
        create("internal") {
            initWith(getByName("release"))
            isMinifyEnabled = false
            isShrinkResources = false
            matchingFallbacks += listOf("release")
            signingConfig = signingConfigs.getByName("release")
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
