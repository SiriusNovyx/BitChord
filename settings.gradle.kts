pluginManagement {
    resolutionStrategy {
        eachPlugin {
            if (providers.gradleProperty("bitchord.modernBuild").orNull == "true") {
                when {
                    requested.id.id.startsWith("com.android.") -> useVersion("9.3.3")
                    requested.id.id.startsWith("org.jetbrains.kotlin.") -> useVersion("2.4.21")
                }
            }
        }
    }
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // NewPipeExtractor is published via JitPack.
        maven("https://jitpack.io")
    }
}

rootProject.name = "BitChord"
if (providers.gradleProperty("bitchord.modernBuild").orNull == "true") {
    require(org.gradle.util.GradleVersion.current() >= org.gradle.util.GradleVersion.version("9.5")) {
        "The AGP 9.3 candidate requires Gradle 9.5 or newer; use gradlew-modern."
    }
    logger.lifecycle("Modern build candidate: AGP 9.3.3 / Kotlin 2.4.21; application JVM targets are unchanged.")
}
include(":shared")
include(":sharedUi")
include(":desktopApp")

// The Android module only when there is an SDK to build it against. A machine
// set up for the desktop alone has none, and including it there fails at
// configuration time — before the desktop module it has nothing to do with can
// build at all.
// Read as a Properties file, not line by line: Android Studio writes the path
// escaped (`C\:\Users\...` on Windows), and only Properties undoes that.
val androidSdk: String? = file("local.properties")
    .takeIf { it.isFile }
    ?.let { f -> java.util.Properties().apply { f.inputStream().use(::load) } }
    ?.getProperty("sdk.dir")
    ?.trim()
    ?: System.getenv("ANDROID_HOME")
    ?: System.getenv("ANDROID_SDK_ROOT")

if (androidSdk != null && file(androidSdk).isDirectory) {
    include(":app")
} else {
    logger.lifecycle("No Android SDK found — building the desktop targets only.")
}
