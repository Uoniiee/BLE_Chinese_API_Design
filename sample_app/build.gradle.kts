plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.uoniiee.blechineseapi.sample"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.uoniiee.blechineseapi.sample"
        minSdk = 26
        targetSdk = 35
        versionCode = 63
        versionName = "0.6.3"
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
    implementation(project(":ble_chinese_api"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
