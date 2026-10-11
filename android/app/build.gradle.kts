import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.InstrumentationParameters
import com.android.build.api.instrumentation.InstrumentationScope
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

// Commons 7.1.3 assumes every derivative uses an org.fossify package. Numi keeps
// its original Android identity for data-preserving upgrades. Remove only the
// incorrect package-name warning, allow appearance settings for this fork, and
// route calls to this APK. Retain the upstream activities and attribution.
abstract class NumiForkCompatibility : AsmClassVisitorFactory<InstrumentationParameters.None> {
    override fun isInstrumentable(classData: ClassData) =
        classData.className in setOf(
            "org.fossify.commons.activities.BaseSimpleActivity",
            "org.fossify.commons.extensions.ActivityKt",
            "org.fossify.commons.compose.extensions.ActivityExtensionsKt"
        )

    override fun createClassVisitor(classContext: ClassContext, nextClassVisitor: ClassVisitor): ClassVisitor =
        object : ClassVisitor(Opcodes.ASM9, nextClassVisitor) {
            override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?): MethodVisitor {
                val next = super.visitMethod(access, name, descriptor, signature, exceptions)
                if (name == "fakeVersionCheck" && descriptor == "(Landroid/content/Context;Lkotlin/jvm/functions/Function0;)V") {
                    return object : MethodVisitor(Opcodes.ASM9, next) {
                        private var adapted = 0
                        override fun visitLdcInsn(value: Any?) {
                            if (value == "org.fossify.") {
                                super.visitVarInsn(Opcodes.ALOAD, 0)
                                super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "android/content/Context", "getPackageName", "()Ljava/lang/String;", false)
                                adapted++
                            } else super.visitLdcInsn(value)
                        }
                        override fun visitEnd() {
                            check(adapted == 1) { "Commons Compose package guard changed; review the Numi compatibility patch" }
                            super.visitEnd()
                        }
                    }
                }
                if (name.startsWith("launchCallIntent\$lambda") && descriptor ==
                    "(Ljava/lang/String;Landroid/telecom/PhoneAccountHandle;Lorg/fossify/commons/activities/BaseSimpleActivity;Z)Lkotlin/Unit;") {
                    return object : MethodVisitor(Opcodes.ASM9, next) {
                        private var routed = 0
                        override fun visitLdcInsn(value: Any?) {
                            if (value == "org.fossify.phone" || value == "org.fossify.phone.debug") {
                                super.visitVarInsn(Opcodes.ALOAD, 2)
                                super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "android/content/Context", "getPackageName", "()Ljava/lang/String;", false)
                                routed++
                            } else super.visitLdcInsn(value)
                        }
                        override fun visitEnd() {
                            check(routed == 2) { "Commons call routing changed; review the Numi fork compatibility patch" }
                            super.visitEnd()
                        }
                    }
                }
                if (name == "startCustomizationActivity" && descriptor == "()V") {
                    return object : MethodVisitor(Opcodes.ASM9, next) {
                        private var adapted = 0
                        override fun visitLdcInsn(value: Any?) {
                            if (value == "yfissof") {
                                // The following upstream code reverses this string before
                                // checking it against the package. Supply our own identity.
                                super.visitVarInsn(Opcodes.ALOAD, 0)
                                super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "android/content/Context", "getPackageName", "()Ljava/lang/String;", false)
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, "kotlin/text/StringsKt", "reversed", "(Ljava/lang/CharSequence;)Ljava/lang/CharSequence;", false)
                                super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "toString", "()Ljava/lang/String;", false)
                                adapted++
                            } else super.visitLdcInsn(value)
                        }
                        override fun visitEnd() {
                            check(adapted == 1) { "Commons appearance guard changed; review the Numi compatibility patch" }
                            super.visitEnd()
                        }
                    }
                }
                if (name != "onCreate" || descriptor != "(Landroid/os/Bundle;)V") return next
                return object : MethodVisitor(Opcodes.ASM9, next) {
                    private var removed = 0
                    private var adapted = 0
                    override fun visitLdcInsn(value: Any?) {
                        if (value == "org.fossify.") {
                            super.visitVarInsn(Opcodes.ALOAD, 0)
                            super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "android/content/Context", "getPackageName", "()Ljava/lang/String;", false)
                            adapted++
                        } else super.visitLdcInsn(value)
                    }
                    override fun visitMethodInsn(opcode: Int, owner: String, name: String, descriptor: String, isInterface: Boolean) {
                        if (opcode == Opcodes.INVOKESTATIC && owner == "org/fossify/commons/extensions/ActivityKt" &&
                            name == "showModdedAppWarning" && descriptor == "(Lorg/fossify/commons/activities/BaseSimpleActivity;)V") {
                            super.visitInsn(Opcodes.POP)
                            removed++
                        } else super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
                    }
                    override fun visitEnd() {
                        check(removed == 1) { "Commons package warning changed; review the Numi fork compatibility patch" }
                        check(adapted == 1) { "Commons package guard changed; review the Numi fork compatibility patch" }
                        super.visitEnd()
                    }
                }
            }
        }
}

androidComponents {
    onVariants(selector().all()) { variant ->
        variant.instrumentation.transformClassesWith(NumiForkCompatibility::class.java, InstrumentationScope.ALL) {}
    }
}

val releaseVersionCode = rootProject.extra["numi.versionCode"] as Int
val releaseVersionName = rootProject.extra["numi.versionName"] as String
val releaseStoreFile = providers.environmentVariable("ANDROID_KEYSTORE_FILE").orNull
val releaseStorePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("ANDROID_KEYSTORE_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("ANDROID_KEYSTORE_KEY_PASSWORD").orNull
    ?.takeIf { it.isNotBlank() }
    ?: releaseStorePassword

android {
    namespace = "net.wastu.numi"
    compileSdk = 36

    defaultConfig {
        applicationId = providers.gradleProperty("numi.applicationId").orElse("net.wastu.numi").get()
        minSdk = 26
        targetSdk = 34
        versionCode = releaseVersionCode
        versionName = releaseVersionName

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
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    sourceSets.getByName("test").resources.srcDir("src/main/assets")
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
    implementation(project(":contracts"))
    implementation(project(":messages"))
    implementation(project(":phone"))
    implementation("org.fossify:commons:7.1.3")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    implementation(libs.okhttp)
    implementation(libs.litertlm)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
