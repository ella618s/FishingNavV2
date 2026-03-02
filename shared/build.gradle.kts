import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.jetbrainsCompose) // <--- 檢查有沒有這一行！
    alias(libs.plugins.compose.compiler) // <--- 還有這一行！
    kotlin("plugin.serialization") version "1.9.20" // 確保版本與 Kotlin 一致
}

kotlin {
    androidTarget {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_1_8)
                }
            }
        }
    }
    
    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach {
        it.binaries.framework {
            baseName = "shared"
            isStatic = true
        }
    }

    sourceSets {
        // 1. 定義共用邏輯
        val commonMain by getting {
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.ui)
                implementation(compose.components.resources)

                implementation("com.squareup.retrofit2:retrofit:2.9.0")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
                implementation("com.squareup.retrofit2:converter-scalars:2.9.0")
                implementation("com.squareup.okhttp3:okhttp:4.11.0")
            }
        }
        // 2. 定義 Android 專屬 (地圖就在這)
        val androidMain by getting {
            dependencies {
                implementation(libs.osmdroid)
                implementation(libs.androidx.lifecycle.viewmodel.compose)
                implementation(libs.androidx.lifecycle.runtime.compose)
                // 加入 osmdroid 的額外功能包
                implementation("org.osmdroid:osmdroid-android:6.1.20")
                implementation(libs.osmdroid) // 確保這行也在
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
    }
}

android {
    namespace = "com.example.fishingnavv2"
    compileSdk = 34
    defaultConfig {
        minSdk = 24
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}
