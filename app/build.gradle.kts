import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

// Load local.properties for API keys
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// Load keystore.properties for signing
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.remnant.dreams"
    // Google Play requires new apps to target API 36 from 31 Aug 2026.
    compileSdk = 36

    defaultConfig {
        applicationId = "com.remnant.dreams"
        minSdk = 29
        targetSdk = 36
        versionCode = 3
        versionName = "1.2.0"

        buildConfigField("String", "GOOGLE_CLOUD_TTS_KEY", "\"${localProps.getProperty("GOOGLE_CLOUD_TTS_KEY", "")}\"")
    }

    signingConfigs {
        create("release") {
            val ksFile = keystoreProps.getProperty("storeFile")
            if (ksFile != null) {
                storeFile = file(ksFile)
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
            // Real entitlement only -- Pro comes from Google Play Billing.
            buildConfigField("boolean", "SIMULATE_PRO", "false")
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            // Simulate the Pro entitlement in debug builds so search/export are fully
            // testable without a Play account. Flip to "false" locally to test the
            // free-tier gating and upgrade dialogs.
            buildConfigField("boolean", "SIMULATE_PRO", "true")
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
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    // AndroidX
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.cardview:cardview:1.0.0")

    // Material Design
    implementation("com.google.android.material:material:1.12.0")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // WorkManager
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")

    // Google Play Billing (one-time Remnant Pro unlock)
    // Note: 9.x is built with Kotlin 2.3 metadata which our Kotlin 2.1.20 toolchain
    // cannot read. 8.3.0 is the newest version compatible with this project and is
    // well inside Google Play's minimum-billing-library policy.
    implementation("com.android.billingclient:billing-ktx:8.3.0")

    // Unit tests
    testImplementation("junit:junit:4.13.2")
}
