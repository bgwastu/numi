plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}
val releaseCode = rootProject.extra["numi.versionCode"] as Int
val releaseName = rootProject.extra["numi.versionName"] as String
android {
    namespace = "org.fossify.phone"
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
dependencies {
    implementation(project(":contracts"))
    implementation("org.fossify:commons:7.1.3")
    implementation("org.fossify:IndicatorFastScroll:c7873f7168")
    implementation("org.greenrobot:eventbus:3.3.1")
    implementation("me.grantland:autofittextview:0.2.1") {
        exclude(group = "com.android.support")
    }
    implementation("com.googlecode.libphonenumber:libphonenumber:9.0.40")
    implementation("com.googlecode.libphonenumber:geocoder:3.40")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
}
