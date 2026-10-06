plugins {
    id("com.android.application") version "8.13.2" apply false
    id("com.android.library") version "8.13.2" apply false
    kotlin("android") version "2.2.21" apply false
    kotlin("multiplatform") version "2.2.21" apply false
    kotlin("plugin.serialization") version "2.2.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21" apply false
    id("com.google.devtools.ksp") version "2.2.21-2.0.4" apply false
}

subprojects {
    if (name == "app" || name == "shared") {
        dependencyLocking {
            lockAllConfigurations()
            // KMP's metadata-only compatibility module is not resolved in ordinary Android builds.
            // Actual kotlin-stdlib runtime remains locked; the Kotlin plugin version is fixed above.
            ignoredDependencies.add("org.jetbrains.kotlin:kotlin-stdlib-common")
        }
    }
}

// Keep public business logic portable and the dependency direction one-way as M1 grows.
tasks.register("checkArchitecture") {
    group = "verification"
    description = "Reject Android/player/UI types in shared business code and cyclic module dependencies"
    val common = fileTree("shared/src/commonMain") { include("**/*.kt") }
    inputs.files(common, file("shared/build.gradle.kts"), file("app/build.gradle.kts"))
    doLast {
        val forbidden = Regex("(?m)^import\\s+(android\\.|androidx\\.|app\\.bililisten\\.(platform|playback)\\.)")
        val violations = common.files.filter { forbidden.containsMatchIn(it.readText()) }
        check(violations.isEmpty()) { "Platform types in shared: ${violations.joinToString { it.relativeTo(projectDir).path }}" }
        check(!file("shared/build.gradle.kts").readText().contains("project(\":app\")")) { "shared must not depend on app" }
        check(!file("app/build.gradle.kts").readText().contains("project(\":ui-preview\")")) { "Production must not depend on the UI demo" }
    }
}
