import java.net.URL

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "by.tablescan"
    compileSdk = 34

    defaultConfig {
        applicationId = "by.tablescan"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Подпись debug-ключом, чтобы release-APK можно было сразу установить
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    // Tesseract OCR (поддерживает кириллицу, работает офлайн)
    implementation("cz.adaptech.tesseract4android:tesseract4android:4.7.0")
}

// Автоматически скачивает языковую модель Tesseract в assets при первой сборке
val tessDir = file("src/main/assets/tessdata")
val downloadTessdata by tasks.registering {
    outputs.dir(tessDir)
    doLast {
        tessDir.mkdirs()
        listOf("rus").forEach { lang ->
            val f = File(tessDir, "$lang.traineddata")
            if (!f.exists() || f.length() < 100_000) {
                println("Downloading $lang.traineddata ...")
                URL("https://github.com/tesseract-ocr/tessdata_best/raw/main/$lang.traineddata")
                    .openStream().use { input -> f.outputStream().use { input.copyTo(it) } }
            }
        }
    }
}
tasks.named("preBuild") { dependsOn(downloadTessdata) }
