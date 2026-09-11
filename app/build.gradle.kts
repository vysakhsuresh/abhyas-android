import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// Release signing credentials live only in a local, git-ignored keystore.properties (see
// keystore.properties.example) - never hardcoded here and never committed.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val hasKeystoreProperties = keystorePropertiesFile.exists()
val keystoreProperties = Properties().apply {
    if (hasKeystoreProperties) load(keystorePropertiesFile.inputStream())
}

android {
    namespace = "com.layerbit.abhyas"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.layerbit.abhyas"
        // Abhyas reads a photo the user just took and writes to its own database - nothing here
        // needs the newer MediaStore APIs Deja is pinned to, so the floor is set by Compose and
        // the bundled ML Kit recogniser instead, which reach much further down.
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        if (hasKeystoreProperties) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasKeystoreProperties) {
                signingConfig = signingConfigs.getByName("release")
            }
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
        // The About screen shows the version it is actually running.
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.navigation:navigation-compose:2.8.2")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.ui:ui-tooling-preview")

    // Drives the daily reminder. WorkManager rather than AlarmManager because the reminder is a
    // nudge, not an alarm: it should survive a reboot, respect Doze, and never claim the
    // exact-alarm permission, which Android 13+ reserves for things like alarm clocks and which
    // a study app has no business asking for.
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // CameraX drives the capture screen. Photographing a page is the entry point to the whole
    // app, so it gets a purpose-built viewfinder rather than an ACTION_IMAGE_CAPTURE hand-off to
    // whatever camera app happens to be installed.
    val cameraX = "1.3.4"
    implementation("androidx.camera:camera-core:$cameraX")
    implementation("androidx.camera:camera-camera2:$cameraX")
    implementation("androidx.camera:camera-lifecycle:$cameraX")
    implementation("androidx.camera:camera-view:$cameraX")

    // Bundled (not the "-unbundled" / Play-Services-backed) builds: every recognition model
    // ships inside the APK, so OCR never needs a model download and works with no network at
    // all. That is the whole privacy position, and it is what these cost.
    //
    // APK SIZE. Each model is several MB and they are additive - all five together add roughly
    // 30-40 MB to the download. That is a real price on a cheap phone and a metered connection.
    // To trim it, delete the recogniser lines you do not need here AND the matching entries in
    // ScriptOption/ScriptProfile; nothing else refers to them. The proper fix when this starts
    // to hurt is Play Feature Delivery, with each non-default script as an on-demand module.
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:text-recognition-devanagari:16.0.1")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1")
    implementation("com.google.mlkit:text-recognition-korean:16.0.1")

    testImplementation("junit:junit:4.13.2")
    // The generator is a suspend function, and its tests drive it with runBlocking. Declared
    // explicitly rather than leaned on as a transitive of Room/Lifecycle, so a dependency bump
    // elsewhere cannot quietly break the test source set.
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    // android.jar's org.json is a stub that throws on every call, so BackupCodec would be
    // untestable without this. The backup file is the only thing standing between a user and
    // losing their whole collection with a phone, so it does not get to go untested.
    testImplementation("org.json:json:20240303")
}
