plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.botcontrol.admin"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.botcontrol.admin"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        minSdk = 26
        targetSdk = 34
        versionCode = 31
        versionName = "1.6.4"
    }

    // Постоянный debug-ключ в репозитории: APK из CI (GitHub Actions) всегда
    // подписан одним сертификатом → обновление ставится поверх, без удаления
    // приложения и потери ботов. Стандартные debug-пароли «android».
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
            storeType = "pkcs12"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.navigation.compose)

    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    implementation(libs.datastore)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.security.crypto)
    implementation(libs.work.runtime)
    implementation(libs.webkit)

    // файловый сервер (страница загрузки/скачивания в локальной сети)
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    // On-device LLM inference (MediaPipe GenAI — runs the model locally, no cloud API)
    implementation(libs.mediapipe.genai)
    // LiteRT-LM: modern .litertlm models (Qwen3, Gemma-3n)
    implementation(libs.litertlm.android)
    // Локальные скрипты логики бота (JavaScript-песочница)
    implementation(libs.rhino)
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}
