plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}
val betaStore = providers.environmentVariable("BILI_BETA_KEYSTORE").orNull
val betaPassword = providers.environmentVariable("BILI_BETA_STORE_PASSWORD").orNull
val betaAlias = providers.environmentVariable("BILI_BETA_KEY_ALIAS").orNull
val betaRequested = gradle.startParameter.taskNames.any { it.contains("beta", ignoreCase = true) } ||
    providers.gradleProperty("deviceTestBuildType").orNull?.startsWith("beta") == true
if (betaRequested) {
    require(!betaStore.isNullOrBlank() && file(betaStore).isFile &&
        !betaPassword.isNullOrBlank() && !betaAlias.isNullOrBlank()) {
        "Dedicated beta signing is missing. Use scripts/build.ps1 -Mode Beta; never fall back to the debug key."
    }
}
android {
    namespace = "app.bililisten"
    compileSdk = 36
    compileSdkMinor = 1
    buildToolsVersion = "36.1.0"
    ndkVersion = "28.2.13676358"
    defaultConfig {
        applicationId = "app.bililisten.prototype"
        minSdk = 26
        targetSdk = 36
        versionCode = providers.gradleProperty("betaVersionCode").orNull?.toInt() ?: 65
        versionName = providers.gradleProperty("betaVersionName").orNull ?: "0.10.13-beta.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true; buildConfig = true }
    sourceSets["main"].jniLibs.srcDir(layout.buildDirectory.dir("tempo/lib"))
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    testBuildType = providers.gradleProperty("deviceTestBuildType").orNull ?: "debug"
    signingConfigs {
        create("beta") {
            if (!betaStore.isNullOrBlank()) storeFile = file(betaStore)
            storePassword = betaPassword
            keyPassword = betaPassword
            keyAlias = betaAlias
            storeType = "PKCS12"
        }
    }
    buildTypes {
        release { isMinifyEnabled = true; isShrinkResources = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro") }
        create("internalQa") {
            initWith(getByName("release"))
            // Local device upgrade testing only: same certificate as the existing test app.
            // Production release remains unsigned; never distribute this as a public release.
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            isDebuggable = false
        }
        create("beta") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("beta")
            matchingFallbacks += listOf("release")
            isDebuggable = false
        }
        create("betaProbe") {
            initWith(getByName("debug"))
            signingConfig = signingConfigs.getByName("beta")
            matchingFallbacks += listOf("debug")
        }
    }
}
// Relative ndk-build paths avoid AGP's ndk-build JSON parser failure on Windows CJK paths.
// Inputs/outputs are tracked, and clean removes only generated libraries under app/build.
val buildTempoNative by tasks.registering(Exec::class) {
    inputs.files(fileTree("src/main/cpp"), fileTree("../third_party/soundtouch/include"), fileTree("../third_party/soundtouch/source/SoundTouch"))
    inputs.property("ndkVersion", android.ndkVersion ?: "")
    outputs.dir(layout.buildDirectory.dir("tempo"))
    workingDir(projectDir)
    val ndk = androidComponents.sdkComponents.sdkDirectory.get().asFile.resolve("ndk/${android.ndkVersion}")
    commandLine(ndk.resolve(if (System.getProperty("os.name").startsWith("Windows")) "ndk-build.cmd" else "ndk-build"),
        "NDK_PROJECT_PATH=null", "APP_BUILD_SCRIPT=src/main/cpp/Android.mk", "NDK_APPLICATION_MK=src/main/cpp/Application.mk",
        "NDK_OUT=build/tempo/obj", "NDK_LIBS_OUT=build/tempo/lib", "NDK_DEBUG=0", "-j2")
}
tasks.named("preBuild").configure { dependsOn(buildTempoNative) }
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
dependencies {
    implementation(project(":shared"))
    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-exoplayer-hls:1.8.0")
    implementation("androidx.media3:media3-session:1.8.0")
    implementation("androidx.media3:media3-datasource-okhttp:1.8.0")
    implementation("io.ktor:ktor-client-okhttp:3.3.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.room:room-runtime:2.8.3")
    implementation("androidx.room:room-ktx:2.8.3")
    implementation("androidx.datastore:datastore:1.2.1")
    ksp("androidx.room:room-compiler:2.8.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
