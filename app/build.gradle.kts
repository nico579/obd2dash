import java.util.Properties

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
        versionCode = 13
        versionName = "0.13"
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

        // Clé d'upload Play Store (voir keystore.properties.example à la racine) :
        // jamais commitée, absente tant que la publication n'a pas commencé. Ce bloc ne
        // crée "release" que si le fichier existe, pour ne jamais faire échouer la
        // configuration Gradle en son absence (voir buildTypes.release ci-dessous).
        val keystoreProps = rootProject.file("keystore.properties")
        if (keystoreProps.exists()) {
            val props = Properties().apply { keystoreProps.inputStream().use { load(it) } }
            create("release") {
                storeFile = file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Retombe sur la signature debug si keystore.properties n'existe pas encore :
            // assembleRelease reste utilisable en local pour tester ce type de build avant
            // d'avoir une vraie clé. Google Play refuse de toute façon un envoi signé debug,
            // donc ce repli ne risque pas de finir publié par erreur.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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
    // Dernière branche Core pour compileSdk 34 ; Core >= 1.15 exige SDK 35.
    // Exception ciblée au conseil de version, pas aux contrôles de compatibilité.
    // Voir les métadonnées AAR Google Maven (minCompileSdk de chaque version).
    //noinspection GradleDependency
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // Lifecycle 2.8.7 : correctifs de la branche Kotlin 1.9 ; 2.9 exige Kotlin 2.0.
    //noinspection GradleDependency
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    //noinspection GradleDependency
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    //noinspection GradleDependency
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    // Activity 1.9.3 : correctifs de la branche SDK 34 ; 1.10 exige SDK 35.
    //noinspection GradleDependency
    implementation("androidx.activity:activity-compose:1.9.3")

    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    testImplementation("junit:junit:4.13.2")
}
