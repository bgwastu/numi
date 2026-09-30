import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
}

val releaseVersionCode = providers.environmentVariable("GITHUB_RUN_NUMBER")
    .orNull?.toIntOrNull()?.coerceAtLeast(1) ?: 1
val releaseStoreFile = providers.environmentVariable("ANDROID_KEYSTORE_FILE").orNull
val releaseStorePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("ANDROID_KEYSTORE_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("ANDROID_KEYSTORE_KEY_PASSWORD").orNull
    ?: releaseStorePassword

android {
    namespace = "net.wastu.numi"
    compileSdk = 36

    defaultConfig {
        applicationId = "net.wastu.numi"
        minSdk = 26
        targetSdk = 34
        versionCode = releaseVersionCode
        versionName = "2.0.$releaseVersionCode"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        create("release") {
            if (releaseStoreFile != null && releaseStorePassword != null && releaseKeyAlias != null) {
                storeFile = file(releaseStoreFile)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword ?: releaseStorePassword
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
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

// Fix 16 KB ELF LOAD segment alignment on arm64 native libs
// Rewrites p_align of PT_LOAD segments to 16384 (16 KB) in each .so
tasks.matching { name.startsWith("merge") && name.endsWith("NativeLibs") }.configureEach {
    val variant = name.removePrefix("merge").removeSuffix("NativeLibs").lowercase()
    doLast {
        val nativeDir = project.layout.buildDirectory
            .dir("intermediates/merged_native_libs/$variant/out/lib/arm64-v8a")
            .get().asFile
        if (!nativeDir.exists()) return@doLast
        nativeDir.listFiles { file -> file.name.endsWith(".so") }?.forEach { so ->
            try {
                RandomAccessFile(so, "rw").use { raf ->
                    val bytes = ByteArray(raf.length().toInt())
                    raf.readFully(bytes)
                    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                    if (buffer.getInt(0) != 0x464c457f) return@forEach
                    val programHeaderOffset = buffer.getLong(32).toInt()
                    val entrySize = buffer.getShort(54).toInt() and 0xFFFF
                    val entryCount = buffer.getShort(56).toInt() and 0xFFFF
                    var modified = false
                    for (index in 0 until entryCount) {
                        val headerOffset = programHeaderOffset + index * entrySize
                        if (buffer.getInt(headerOffset) != 1) continue
                        val alignmentOffset = headerOffset + 48
                        if (buffer.getLong(alignmentOffset) < 16384) {
                            buffer.putLong(alignmentOffset, 16384)
                            modified = true
                        }
                    }
                    if (modified) {
                        raf.seek(0)
                        raf.write(buffer.array())
                    }
                }
            } catch (exception: Exception) {
                throw GradleException("Could not align native library ${so.name} to 16 KB pages", exception)
            }
        }
    }
}

tasks.register("validateReleaseSigning") {
    doLast {
        val missing = buildList {
            if (releaseStoreFile.isNullOrBlank()) add("ANDROID_KEYSTORE_FILE")
            if (releaseStorePassword.isNullOrBlank()) add("ANDROID_KEYSTORE_PASSWORD")
            if (releaseKeyAlias.isNullOrBlank()) add("ANDROID_KEYSTORE_ALIAS")
        }
        require(missing.isEmpty()) { "Release signing is missing: ${missing.joinToString()}" }
        require(file(releaseStoreFile!!).isFile) { "ANDROID_KEYSTORE_FILE does not exist" }
    }
}
tasks.matching { name == "validateSigningRelease" }.configureEach {
    dependsOn("validateReleaseSigning")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.litertlm.android)

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Ktor Client
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
