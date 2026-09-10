plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.nico.obd2dash"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.nico.obd2dash"
        minSdk = 26
        targetSdk = 34
        // À incrémenter avant de pousser un tag vX.Y (voir .github/workflows/release.yml) :
        // rien ne les synchronise automatiquement avec le tag.
        versionCode = 1
        versionName = "0.1"
    }

    signingConfigs {
        // Clé debug committée (app/debug.keystore) au lieu du keystore auto-généré par
        // machine (~/.android/debug.keystore) : sans ça, chaque machine (dont chaque
        // exécution GitHub Actions, éphémère) signe avec une clé différente, et Android
        // refuse d'installer une mise à jour signée par une clé différente de celle déjà
        // installée (finding R10). Mot de passe/alias = les valeurs par défaut standard
        // d'un keystore debug Android (pas un secret : c'est tout le principe du debug).
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")

    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    testImplementation("junit:junit:4.13.2")
}
