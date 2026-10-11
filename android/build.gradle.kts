buildscript {
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.ksp) apply false
}

// Derive local and CI versions from the same folder-scoped commit history.
// CI supplies the version assigned to the exact commit being released.
val numiVersionCode = providers.environmentVariable("NUMI_VERSION_CODE").orElse(
    providers.exec {
        workingDir = rootDir.parentFile
        commandLine("python3", "tools/release.py", "--version-code")
    }.standardOutput.asText.map { it.trim() }
).get().toInt()
require(numiVersionCode in 20..2_100_000_000) { "NUMI_VERSION_CODE must be a valid upgrade version" }
val numiVersionName = providers.environmentVariable("NUMI_VERSION_NAME").orElse("3.0.$numiVersionCode").get()
allprojects {
    extra["numi.versionCode"] = numiVersionCode
    extra["numi.versionName"] = numiVersionName
}
