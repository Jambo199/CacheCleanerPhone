import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Every GitHub build gets a higher version number, so the in-app updater can tell it's newer.
val ciBuildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()

android {
    namespace = "io.github.jambo199.cachecleaner"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.jambo199.cachecleaner"
        minSdk = 26
        targetSdk = 36
        versionCode = if (ciBuildNumber != null) 100 + ciBuildNumber else 5
        versionName = if (ciBuildNumber != null) "1.5.$ciBuildNumber" else "1.5-dev"
    }

    signingConfigs {
        // A fixed key, so every build installs over the previous one instead of
        // failing with "App not installed". It only matters for sideloading.
        create("sideload") {
            storeFile = file("signing/cachecleaner.jks")
            storePassword = "cachecleaner"
            keyAlias = "cachecleaner"
            keyPassword = "cachecleaner"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("sideload")
        }
        debug {
            signingConfig = signingConfigs.getByName("sideload")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    testImplementation(libs.junit)
}

// version.json is published next to the APK; the in-app updater reads it to spot a newer build.
tasks.register("writeVersionFile") {
    val versionCode = android.defaultConfig.versionCode
    val versionName = android.defaultConfig.versionName
    val output = layout.buildDirectory.file("version.json")
    outputs.file(output)
    doLast {
        output.get().asFile.writeText("""{"versionCode": $versionCode, "versionName": "$versionName"}""" + "\n")
    }
}
