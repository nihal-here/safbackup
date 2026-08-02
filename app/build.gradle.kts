plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlin-kapt")
}

val envVersionName = System.getenv("APP_VERSION_NAME")
val envVersionCode = System.getenv("APP_VERSION_CODE")?.toIntOrNull()

val isCi = System.getenv("CI") == "true" || System.getenv("GITHUB_ACTIONS") == "true"
val isReleaseTask = gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }

val finalVersionName = when {
    !envVersionName.isNullOrEmpty() -> envVersionName
    isCi && isReleaseTask -> throw GradleException("APP_VERSION_NAME environment variable must be supplied for CI release builds!")
    else -> "0.2.0-dev"
}

val finalVersionCode = when {
    envVersionCode != null && envVersionCode > 0 -> envVersionCode
    isCi && isReleaseTask -> throw GradleException("APP_VERSION_CODE environment variable must be supplied for CI release builds!")
    else -> 2000
}

android {
    namespace = "dev.safbackup"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.safbackup"
        minSdk = 26
        targetSdk = 35
        versionCode = finalVersionCode
        versionName = finalVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("KEYSTORE_FILE")
            val keystorePassword = System.getenv("KEYSTORE_PASSWORD")
            val keyAlias = System.getenv("KEY_ALIAS")
            val keyPassword = System.getenv("KEY_PASSWORD")

            if (!keystorePath.isNullOrEmpty() && file(keystorePath).exists()) {
                storeFile = file(keystorePath)
                storePassword = keystorePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val releaseConfig = signingConfigs.getByName("release")
            if (releaseConfig.storeFile != null && releaseConfig.storeFile!!.exists()) {
                signingConfig = releaseConfig
            }
        }
    }

    buildFeatures {
        viewBinding = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.work:work-runtime-ktx:2.10.1")

    // Room persistence
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")

    // Unit testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
