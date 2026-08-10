import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.unora"
    compileSdk = 36

    defaultConfig {
        val configuredEdgeUrl = providers.gradleProperty("UNORA_EDGE_URL")
            .getOrElse("https://unora-edge.unora-tarnished.workers.dev")
        val configuredTurn = providers.gradleProperty("ENABLE_TURN")
            .getOrElse("false")
            .toBooleanStrictOrNull() ?: false

        applicationId = "app.unora"
        minSdk = 29
        targetSdk = 36
        versionCode = 11
        versionName = "0.2.6"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "UNORA_EDGE_URL", "\"${configuredEdgeUrl.replace("\"", "\\\"")}\"")
        buildConfigField("boolean", "ENABLE_TURN", configuredTurn.toString())
        manifestPlaceholders["unoraHost"] = "unora.app"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.compose.material:material-icons-extended")
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.webrtc)

    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
}
