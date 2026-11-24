plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.chaquo.python")
}

android {
    namespace = "com.contentstudio.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.contentstudio.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        python {
            version = "3.11"
            pip {
                install("yt-dlp")
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
        }
        debug {
            isDebuggable = true
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
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// Task sinkronisasi otomatis aset & engine sebelum proses build
val syncContentStudioAssets = tasks.register("syncContentStudioAssets") {
    doLast {
        val projectRoot = rootProject.projectDir.parentFile ?: return@doLast
        val engineDir = File(projectRoot, "engine")
        val targetPyEngineDir = file("src/main/python/engine")

        if (engineDir.exists()) {
            targetPyEngineDir.mkdirs()
            engineDir.listFiles()?.forEach { file ->
                if (file.isFile && file.extension == "py") {
                    file.copyTo(File(targetPyEngineDir, file.name), overwrite = true)
                }
            }
        }

        val targetBundleDir = file("src/main/assets/bundle")
        targetBundleDir.mkdirs()

        val copyDirs = listOf("workspaces", ".agents", "assets")
        copyDirs.forEach { dirName ->
            val srcDir = File(projectRoot, dirName)
            if (srcDir.exists()) {
                val destDir = File(targetBundleDir, dirName)
                srcDir.copyRecursively(destDir, overwrite = true)
            }
        }
    }
}

tasks.named("preBuild").configure {
    dependsOn(syncContentStudioAssets)
}

dependencies {
    // Core Android & Lifecycle
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")

    // Jetpack Compose & Material 3
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Navigation Compose
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
