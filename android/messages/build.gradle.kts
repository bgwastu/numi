plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}
val releaseCode = rootProject.extra["numi.versionCode"] as Int
val releaseName = rootProject.extra["numi.versionName"] as String
android {
    namespace = "org.fossify.messages"
    compileSdk = 36
    defaultConfig {
        minSdk = 26
        buildConfigField("String", "APPLICATION_ID", "\"${providers.gradleProperty("numi.applicationId").orElse("net.wastu.numi").get()}\"")
        buildConfigField("String", "VERSION_NAME", "\"$releaseName\"")
        buildConfigField("int", "VERSION_CODE", "$releaseCode")
    }
    buildFeatures { viewBinding = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
dependencies {
    implementation(project(":contracts"))
    implementation("org.fossify:commons:7.1.3")
    implementation("org.fossify:IndicatorFastScroll:c7873f7168")
    implementation("org.fossify:mmslib:1.0.1")
    implementation("org.greenrobot:eventbus:3.3.1")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.2.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.2")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("androidx.lifecycle:lifecycle-process:2.10.0")
    implementation("com.googlecode.ez-vcard:ez-vcard:0.12.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")
}
