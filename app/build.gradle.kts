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

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Room's MigrationTestHelper reads the exported schemas from the test APK's assets, so the
    // directory ksp writes them to has to be on the androidTest asset path. Without this the
    // migration tests fail with "Cannot find the schema file" rather than anything informative.
    sourceSets {
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
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
            // Unused drawables and strings pulled in by Compose, CameraX and ML Kit are a few
            // megabytes that nothing on any screen ever draws.
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )

            // ML Kit's OCR pipeline ships as a native library, and the APK carries one copy per
            // ABI: 11.1 MB for x86_64, 11.1 for x86, 10.6 for arm64-v8a and 6.5 for armeabi-v7a.
            // Every phone uses exactly one of them, and the two x86 builds exist for emulators
            // and a handful of Chromebooks - 22 MB of a 56 MB download that no real user's phone
            // will ever load. Debug keeps all four so the emulator still works.
            //
            // Play splits an App Bundle per ABI anyway, so this mainly buys a smaller direct
            // APK; it also keeps the bundle itself from carrying weight nobody can use.
            ndk {
                abiFilters += listOf("arm64-v8a", "armeabi-v7a")
            }

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

    // Read EXIF orientation off a captured page. PageTextReader decodes the photo itself, with a
    // pixel budget, rather than handing the Uri to ML Kit - and a hand-rolled decode does not get
    // the automatic EXIF correction that InputImage.fromFilePath performs, so a page photographed
    // in landscape would reach the recogniser lying on its side. ~70 KB.
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    // Bundled (not the "-unbundled" / Play-Services-backed) builds: every recognition model
    // ships inside the APK, so OCR never needs a model download and works with no network at
    // all. That is the whole privacy position, and it is what these cost.
    //
    // APK SIZE, measured rather than assumed. All five scripts' models together are 5.2 MB of
    // assets - Latin 0.34, Devanagari 0.46, Chinese 0.96, Japanese 0.92, Korean 0.83 - because
    // they share one native pipeline library instead of each bringing its own. So dropping a
    // script buys well under a megabyte and costs a language: not the lever it looks like.
    //
    // The library is the weight, at 6.5-11.1 MB per ABI, and the release build's abiFilters is
    // what cuts it. Reach for Play Feature Delivery only if the asset total itself grows.
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

    // Instrumented tests, for the things a JVM test genuinely cannot reach: Room's migrations and
    // its transactions. A migration that throws on upgrade destroys a collection, and the exported
    // schemas under app/schemas are only worth having if something checks the migrations against
    // them - which is what room-testing's MigrationTestHelper does.
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.room:room-testing:2.6.1")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
