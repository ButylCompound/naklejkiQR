plugins {
    id("com.android.application")
}

android {
    namespace = "pl.almara.inwentaryzacja"
    compileSdk = 35

    defaultConfig {
        applicationId = "pl.almara.inwentaryzacja"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        viewBinding = true
    }
    packaging {
        jniLibs {
            // Prekompilowane biblioteki ML Kit / CameraX — pakujemy bez strippowania,
            // dzięki czemu build nie wymaga zainstalowanego NDK
            keepDebugSymbols += listOf(
                "**/libbarhopper_v3.so",
                "**/libimage_processing_util_jni.so",
                "**/libsurface_util_jni.so"
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    // CameraX — minimum 1.4.0 wymagane dla zgodności z 16 KB page size
    val camerax = "1.4.2"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    // ML Kit barcode scanning — "bundled" model, works fully OFFLINE
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
}
