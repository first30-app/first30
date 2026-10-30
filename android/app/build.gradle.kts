import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// 릴리스 서명: android/keystore.properties (저장소에 올리지 않는다 — .gitignore)
//   storeFile=keystore/first30-release.jks  storePassword=…  keyAlias=…  keyPassword=…
// 파일이 없으면(새로 받은 저장소) release 는 debug 키로 서명된다 — 제출용으로 쓰지 말 것.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.isFile) keystorePropsFile.inputStream().use { load(it) }
}
val hasReleaseKey = keystorePropsFile.isFile &&
    listOf("storeFile", "storePassword", "keyAlias", "keyPassword").all { !keystoreProps.getProperty(it).isNullOrBlank() } &&
    rootProject.file(keystoreProps.getProperty("storeFile")).isFile
if (!hasReleaseKey) {
    logger.warn("WARNING: android/keystore.properties (or its keystore) not found — release APK will be signed with the DEBUG key. Not for submission.")
}

android {
    namespace = "com.first30.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.first30.app"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
                // Pleos 문서: minSdk 28 = 서명 키 교체·계보(APK Signature Scheme v3)
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            signingConfig = if (hasReleaseKey) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
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

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.all { it.useJUnitPlatform() }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // signals / incident layer
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json) // JsonElement only (seal files), no compiler plugin
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.kotlinx.coroutines.test)
}

// 제출용 APK 복사: <저장소>/apk/<팀명>_퍼스트30_release.apk  (팀명 = gradle.properties 의 submission.teamName)
val exportReleaseApk by tasks.registering(Copy::class) {
    group = "distribution"
    description = "Copies the signed release APK to <repo>/apk/<teamName>_퍼스트30_release.apk"
    dependsOn("assembleRelease")
    // gradle.properties 는 ISO-8859-1 로 읽힌다 — UTF-8 로 적은 한글 팀명을 되살린다(-P 나 \u 이스케이프 값은 그대로).
    val team = (findProperty("submission.teamName") as String?)
        ?.let { if (it.all { c -> c.code <= 0xFF }) String(it.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8) else it }
        ?.trim()?.takeIf { it.isNotEmpty() } ?: "팀명"
    inputs.property("teamName", team)
    from(layout.buildDirectory.dir("outputs/apk/release")) { include("*-release.apk") }
    into(rootProject.layout.projectDirectory.dir("../apk"))
    rename { "${team}_퍼스트30_release.apk" }
    doFirst {
        // 제출 파일명으로 debug 키 서명 APK 가 복사되지 않게 막는다(assembleRelease 자체는 debug 키로 계속 된다).
        if (!hasReleaseKey) throw GradleException("exportReleaseApk: android/keystore.properties (or its keystore) not found — refusing to export a DEBUG-signed APK as the submission file.")
    }
}
