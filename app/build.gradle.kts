plugins {
    alias(libs.plugins.android.application)
    // (AGP 9 compiles Kotlin itself: no kotlin-android plugin)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.neodesktop.dmodem"
    compileSdk = 37
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "com.neodesktop.dmodem"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        }
        externalNativeBuild {
            cmake {
                // only the JNI library: not dmodemd or D-Modem-RE's other programs
                targets += "dmodem_jni"
                arguments += listOf("-DANDROID_STL=c++_static")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
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

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            // (sshj's dependencies each carry these: one is enough, the licences are in the app's)
            excludes += setOf("META-INF/LICENSE.md", "META-INF/NOTICE.md", "META-INF/LICENSE-notice.md", "META-INF/versions/9/OSGI-INF/MANIFEST.MF")
            pickFirsts += setOf("META-INF/INDEX.LIST")
        }
        jniLibs {
            // 16 KB page alignment, uncompressed in the APK
            useLegacyPackaging = false
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)
    // SSH: sshj on BouncyCastle (Android's own "BC" is cut down: the full one is registered at start)
    implementation(libs.sshj)
    implementation(libs.bcprov)
    implementation(libs.bcpkix)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    // an SSH server for the SSH client's tests
    testImplementation(libs.mina.sshd.core)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}

// Robolectric fetches its Android framework jars itself: -ProbolectricRepoUrl=<a Maven mirror> to fetch them elsewhere
tasks.withType<Test>().configureEach {
    providers.gradleProperty("robolectricRepoUrl").orNull?.let {
        systemProperty("robolectric.dependency.repo.url", it)
    }
}
