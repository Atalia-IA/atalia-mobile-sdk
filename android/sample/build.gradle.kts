plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ai.atalia.chat.sample"
    compileSdk = 35

    defaultConfig {
        applicationId = "ai.atalia.chat.sample"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        // ./gradlew :sample:installDebug -Patalia.channelToken=<token-do-canal> [-Patalia.baseUrl=<outra-origem>]
        val token = (project.findProperty("atalia.channelToken") as String?) ?: ""
        val baseUrl = (project.findProperty("atalia.baseUrl") as String?) ?: "https://chat.atalia.ai"
        buildConfigField("String", "ATALIA_CHANNEL_TOKEN", "\"$token\"")
        buildConfigField("String", "ATALIA_BASE_URL", "\"$baseUrl\"")
    }

    buildFeatures {
        buildConfig = true
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
    implementation(project(":chat"))
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
}
