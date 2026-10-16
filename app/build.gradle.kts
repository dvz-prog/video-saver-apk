plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.videosaver.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.videosaver.app"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        // celulares atuais (arm64) e antigos (arm 32 bits); tira o x86 para o APK ficar menor
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // obrigatório para a biblioteca do yt-dlp (precisa extrair as bibliotecas nativas)
    packaging { jniLibs { useLegacyPackaging = true } }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.documentfile:documentfile:1.0.1")

    // yt-dlp + ffmpeg embutidos no app (se a versão não for encontrada, veja a página
    // github.com/JunkFood02/youtubedl-android e troque 0.17.2 pela mais recente)
    implementation("io.github.junkfood02.youtubedl-android:library:0.17.2")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.17.2")
}
