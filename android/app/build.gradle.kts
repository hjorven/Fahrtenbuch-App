plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

android {
    namespace = "de.hjorven.fahrtenbuch"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.hjorven.fahrtenbuch"
        minSdk = 26
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // NUMMER = Laufnummer + 100 aus der CI (siehe .github/workflows/android.yml)
        versionCode = (System.getenv("NUMMER") ?: "1").toInt()
        versionName = "1.0.${System.getenv("NUMMER") ?: "0"}"
    }

    // Signierschlüssel kommt aus GitHub-Secrets (siehe android/README.md).
    // Ohne Secret wird mit dem Debug-Schlüssel signiert: installierbar, aber Updates
    // über eine alte Version gehen dann nur, wenn der Schlüssel gleich bleibt.
    val keystore = System.getenv("FB_KEYSTORE")?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (keystore != null) {
            create("familie") {
                storeFile = keystore
                storePassword = System.getenv("FB_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("FB_KEY_ALIAS")
                keyPassword = System.getenv("FB_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (keystore != null) signingConfigs.getByName("familie") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.05.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.9")

    implementation("androidx.room:room-runtime:2.7.0")
    implementation("androidx.room:room-ktx:2.7.0")
    ksp("androidx.room:room-compiler:2.7.0")

    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("androidx.glance:glance-material3:1.1.1")

    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.osmdroid:osmdroid-android:6.1.20")
    // Texterkennung für Tankbelege und Tacho, Modell steckt in der App (offline, keine Cloud).
    implementation("com.google.mlkit:text-recognition:16.0.1")
    // QR-Codes zum Verbinden und Einladen (Roadmap Nr. 1/2): lesen mit ML Kit, erzeugen mit ZXing
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    implementation("com.google.zxing:core:3.5.3")

    testImplementation("junit:junit:4.13.2")

    // Oberflächentests im Emulator (Roadmap Nr. 20, .github/workflows/android.yml Job "oberflaeche")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
