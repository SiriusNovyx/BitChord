import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import com.music.bitchord.build.DesktopTarget
import com.music.bitchord.build.WindowsBinary

/** What the packages, the launcher and the settings footer all say. */
val appVersion: String = providers.gradleProperty("bitchord.version").orNull
    ?.removePrefix("v")
    ?.takeIf { it.isNotBlank() }
    ?: "1.8-beta1"

/** Which platform this build is *for*, which is the host unless told otherwise. */
val hostIsWindows = System.getProperty("os.name").contains("Windows", ignoreCase = true)
val hostIsLinux = System.getProperty("os.name").contains("Linux", ignoreCase = true)
val hostIsMac = System.getProperty("os.name").contains("Mac", ignoreCase = true)
val targetOs: String = (providers.gradleProperty("bitchord.target").orNull ?: when {
    hostIsWindows -> "windows"
    hostIsLinux -> "linux"
    hostIsMac -> "macos"
    else -> error("BitChord desktop supports Linux, Windows, and macOS only")
}).lowercase()

val hostArch = System.getProperty("os.arch").lowercase()
val targetArch = DesktopTarget.architecture(
    System.getProperty("os.name"), hostArch,
    if (hostIsWindows) DesktopTarget.windowsNativeArchitecture() else null,
    providers.gradleProperty("bitchord.arch").orNull,
)
val isArm64 = targetArch == "arm64"
val windowsArm64 = targetOs == "windows" && isArm64
val arm64Natives = providers.gradleProperty("bitchord.windowsArm64Natives").orNull
    ?.let { rootProject.file(it) }
    ?: rootProject.file("desktopApp/native-dependencies/windows-arm64")
val arm64CodecJars = listOf(
    File(arm64Natives, "javacpp-1.5.14-windows-arm64-bitchord.jar"),
    File(arm64Natives, "ffmpeg-8.1.2-1.5.14-windows-arm64.jar"),
)
val arm64OnnxJar = File(arm64Natives, "onnxruntime-1.29.0-windows-arm64.jar")
val arm64QuickJsJar = File(arm64Natives, "quickjs-kt-jvm-1.0.14-windows-arm64-bitchord.jar")

// Windows installer metadata requires MAJOR.MINOR.BUILD even though the app's public version is
// intentionally displayed without a patch number (1.7 rather than 1.7.0).
// The build number is the desktop version code; it also keeps "-beta1" out of the numeric installer version.
val desktopVersionCode = 29
val nativePackageVersion = appVersion.substringBefore('-').split('.').take(2).joinToString(".") + ".$desktopVersionCode"

// FFmpeg decodes audio; see DesktopAudioDecoder.
val javacppVersion = if (windowsArm64) "1.5.14" else "1.5.12"
val ffmpegVersion = if (windowsArm64) "8.1.2-$javacppVersion" else "7.1.1-$javacppVersion"
val nativeClassifier = when (targetOs) {
    "windows" -> if (isArm64) "windows-arm64" else "windows-x86_64"
    "linux" -> "linux-x86_64"
    "macos" -> if (isArm64) "macosx-arm64" else "macosx-x86_64"
    else -> error("BitChord desktop supports Linux, Windows, and macOS only")
}
private fun localProperty(name: String): String = rootProject.file("local.properties")
    .takeIf { it.isFile }
    ?.readLines()
    ?.firstOrNull { it.startsWith("$name=") }
    ?.substringAfter('=')
    ?.trim()
    ?.takeIf { it.isNotBlank() }
    ?: System.getenv(name).orEmpty().trim()

val desktopModuleIndexUrl = localProperty("MODULE_INDEX_URL")

// Last.fm signs every request with these, so signing in from inside the app
// needs them at hand. Supplied locally and never committed, as on Android.
val listenTogetherServer = localProperty("LISTEN_TOGETHER_SERVER")
val lastfmApiKey = localProperty("LASTFM_API_KEY")
val lastfmSecret = localProperty("LASTFM_SECRET")

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

dependencies {
    // Already on the classpath through the NewPipe extractor.
    implementation("org.jsoup:jsoup:1.22.2")
    implementation("com.google.zxing:core:3.5.3")

    implementation(project(":shared"))
    implementation(project(":sharedUi"))
    if (windowsArm64) implementation(files(arm64QuickJsJar))
    // BotGuard, for the PoTokens YouTube's web clients need: the phone runs it in an Android
    // WebView, the desktop in JavaFX's (WebKit). Per-platform jars carry the natives.
    val javafxClassifier = when (targetOs) {
        "windows" -> "win"
        "macos" -> if (isArm64) "mac-aarch64" else "mac"
        else -> "linux"
    }
    if (windowsArm64) {
        // Java APIs only, for compiling the other platforms' adapter. ARM64 uses WebView2.
        listOf("base", "graphics", "controls", "media", "web").forEach { module ->
            compileOnly("org.openjfx:javafx-$module:21.0.10:win")
        }
    } else {
        listOf("base", "graphics", "controls", "media", "web").forEach { module ->
            implementation("org.openjfx:javafx-$module:21.0.10:$javafxClassifier")
        }
    }

    // The shared player loads artwork through Coil, as the phone does.
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.0.4")

    // Automix runs the same two models the Android build does.
    if (windowsArm64) implementation(files(arm64OnnxJar))
    else implementation("com.microsoft.onnxruntime:onnxruntime:1.29.0")

    // Decoding, resampling and demuxing for the audio engine.
    implementation("org.bytedeco:javacpp:$javacppVersion")
    implementation("org.bytedeco:ffmpeg:$ffmpegVersion")
    if (windowsArm64) {
        implementation(files(arm64CodecJars))
    } else {
        implementation("org.bytedeco:javacpp:$javacppVersion:$nativeClassifier")
        implementation("org.bytedeco:ffmpeg:$ffmpegVersion:$nativeClassifier")
    }

    // The same backdrop blur the Android build uses for its floating bars.
    // On Windows, 1.7.2 keeps this Compose Desktop 1.10.3 app on the matching UI/text/animation
    // runtime. Preserve Linux's existing dependency graph exactly as it was.
    val desktopHazeVersion = if (targetOs == "windows") "1.7.2" else "1.7.3"
    implementation("dev.chrisbanes.haze:haze:$desktopHazeVersion")
    implementation("dev.chrisbanes.haze:haze-materials:$desktopHazeVersion")

    implementation(compose.runtime)
    implementation(compose.foundation)
    implementation(compose.material3)
    implementation(compose.components.resources)
    implementation(compose.materialIconsExtended)
    // The Skiko runtime rides in on this, and Skiko is per-platform.
    implementation(
        when (targetOs) {
            "windows" -> if (isArm64) "org.jetbrains.compose.desktop:desktop-jvm-windows-arm64:1.10.3" else compose.desktop.windows_x64
            "macos" -> if (isArm64) compose.desktop.macos_arm64 else compose.desktop.macos_x64
            else -> compose.desktop.linux_x64
        }
    )

    implementation("io.ktor:ktor-client-core:3.0.3")
    implementation("io.ktor:ktor-client-cio:3.0.3")
    implementation("io.ktor:ktor-client-content-negotiation:3.0.3")
    // Listen Together speaks over a WebSocket; the Discord gateway already uses the same engine.
    implementation("io.ktor:ktor-client-websockets:3.0.3")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.0.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
    implementation("org.slf4j:slf4j-nop:2.0.16")

    // Browsers keep their cookies in SQLite, and importing a session from one is how this app signs
    // in — see DesktopBrowserCookies.
    implementation("org.xerial:sqlite-jdbc:3.47.1.0")
    // Chromium protects its Windows master key with DPAPI. JNA's platform helpers let the app
    // unwrap that key in the same Windows user session without invoking a shell or exposing it.
    implementation("net.java.dev.jna:jna-platform:5.19.1")

    // Linux desktop media controls use the standard session-bus MPRIS interfaces.
    if (windowsArm64) {
        implementation("com.github.hypfvieh:dbus-java-core:5.2.0")
        compileOnly("com.github.hypfvieh:dbus-java-transport-junixsocket:5.2.0")
    } else {
        implementation("com.github.hypfvieh:dbus-java-core:5.2.0")
        implementation("com.github.hypfvieh:dbus-java-transport-junixsocket:5.2.0")
    }

    // Same URL deobfuscation helper used by Android's StreamResolver.
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.3")

    // JVM equivalent of Android's QuickJS module host.
    implementation("org.graalvm.polyglot:polyglot:24.1.2")
    implementation("org.graalvm.polyglot:${if (windowsArm64) "js-community" else "js"}:24.1.2")
    implementation("org.graalvm.js:js-language:24.1.2")

    testImplementation(kotlin("test"))
    if (!windowsArm64) testImplementation("io.github.dokar3:quickjs-kt-jvm:1.0.14")
}

if (windowsArm64) configurations.configureEach {
    // The interpreter is supported on this JDK; the optimizing runtime's DLL is x64-only.
    exclude(group = "org.graalvm.truffle", module = "truffle-runtime")
    exclude(group = "io.github.dokar3", module = "quickjs-kt-jvm")
}

tasks.withType<Test> {
    systemProperty("java.awt.headless", "true")
    systemProperty("bitchord.dataDir", temporaryDir.resolve("data").absolutePath)
    systemProperty("java.util.prefs.PreferencesFactory", "com.music.bitchord.desktop.DesktopPortablePreferencesFactory")
    doFirst {
        if (hostIsWindows && targetOs == "windows") {
            WindowsBinary.requireArchitecture(javaLauncher.get().executablePath.asFile, targetArch)
        }
    }
}

tasks.withType<JavaExec>().configureEach {
    doFirst {
        if (hostIsWindows && targetOs == "windows" && mainClass.orNull == "com.music.bitchord.desktop.MainKt") {
            WindowsBinary.requireArchitecture(File(requireNotNull(executable)), targetArch)
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

/** Builds the Automix analyser from the same C++ the Android app compiles. */
// Per target, because both write the library under the same name and a Windows cross-build
// otherwise left a `.dll` where the next Linux run looked for its `.so`.
val analysisNativeDir = layout.buildDirectory.dir("native/$targetOs-$targetArch")

/** The tiny Windows frame bridge is independent of the optional analyser and SMTC libraries. */
val windowNativeDir = layout.buildDirectory.dir("native-window/$targetOs-$targetArch")
val webViewNativeDir = layout.buildDirectory.dir("native-webview/$targetOs-$targetArch")

tasks.register("desktopTargetInfo") {
    group = "help"
    doLast {
        logger.lifecycle("BitChord target: $targetOs-$targetArch; Gradle JVM: $hostArch; codecs: $nativeClassifier")
        if (windowsArm64) logger.lifecycle("Source-built ARM64 dependency directory: $arm64Natives")
    }
}

val validateArm64Dependencies by tasks.registering {
    onlyIf { windowsArm64 }
    doLast {
        val missing = (arm64CodecJars + arm64OnnxJar + arm64QuickJsJar).filterNot(File::isFile)
        check(missing.isEmpty()) {
            "Windows ARM64 requires the prepared JavaCPP, FFmpeg JNI, ONNX Java and QuickJS jars. " +
                "No x64 fallback is permitted. Missing: ${missing.joinToString { it.name }}. " +
                "See docs/windows-arm64.md; set -Pbitchord.windowsArm64Natives=<directory>."
        }
        (arm64CodecJars + arm64OnnxJar + arm64QuickJsJar).forEach { jar ->
            val count = WindowsBinary.verifyJar(jar, "arm64")
            check(count > 0) { "${jar.name} contains no ARM64 Windows natives" }
        }
    }
}
tasks.named("compileKotlin") { dependsOn(validateArm64Dependencies) }

/**
 * Whether this build is producing the analyser for a platform that is not the one running the
 * build.
 */
val crossBuildingForWindows = targetOs == "windows" && !hostIsWindows

/**
 * An executable's absolute path, from the PATH this build was invoked with.
 *
 * Resolved here rather than left to the child process. Gradle runs external
 * commands from the daemon, whose own PATH is whatever it was started with, so
 * a daemon reused from a shell that had no cmake cannot find the one the
 * current shell does — it fails with a bare "No such file or directory" while
 * `cmake --version` works fine in the terminal beside it.
 */
/**
 * Whether a tool on the PATH can actually be started.
 *
 * A PATH entry can be a wrapper script whose own interpreter is gone; it is a file, it is
 * executable, and it fails only at exec time — which surfaces as a build failure rather than as the
 * missing-tool path this build already handles.
 */
fun runs(tool: File): Boolean = runCatching {
    val process = ProcessBuilder(tool.absolutePath, "--version")
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()
    if (!process.waitFor(10, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        false
    } else {
        process.exitValue() == 0
    }
}.getOrDefault(false)

fun findOnPath(tool: String): File? {
    // Windows keeps the extension on the file and off the command line, so a bare name matches
    // nothing on disk: PATHEXT is the list a shell would have tried.
    val names = if (!hostIsWindows) {
        listOf(tool)
    } else {
        val extensions = System.getenv("PATHEXT").orEmpty()
            .ifBlank { ".COM;.EXE;.BAT;.CMD" }
            .split(';')
            .filter(String::isNotBlank)
        listOf(tool) + extensions.map { tool + it.lowercase() }
    }
    return System.getenv("PATH").orEmpty()
        .split(File.pathSeparator)
        .filter(String::isNotBlank)
        .flatMap { directory -> names.map { File(directory, it) } }
        // A PATHEXT match is executable by definition, and Windows' own canExecute is unreliable.
        .firstOrNull { it.isFile && (hostIsWindows || it.canExecute()) && runs(it) }
}

fun findCmake(): File? = providers.gradleProperty("bitchord.cmake").orNull?.let { path ->
    File(path).takeIf { it.isFile && runs(it) }
} ?: findOnPath("cmake")

fun cmakeBinary(): String = findCmake()?.absolutePath ?: "cmake"

fun findNinja(): File? = providers.gradleProperty("bitchord.ninja").orNull?.let { path ->
    rootProject.file(path).takeIf { it.isFile && runs(it) }
} ?: findOnPath("ninja")

fun findWindowsX64Compiler(crossing: Boolean): File? {
    val explicit = providers.gradleProperty("bitchord.mingwCompiler").orNull
    val candidates = if (explicit != null) listOf(rootProject.file(explicit)) else listOfNotNull(
        findOnPath("x86_64-w64-mingw32-g++"),
        if (crossing) null else findOnPath("g++"),
    )
    return candidates.firstOrNull { compiler ->
        if (!compiler.isFile) return@firstOrNull false
        val process = ProcessBuilder(compiler.absolutePath, "-dumpmachine").redirectErrorStream(true).start()
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return@firstOrNull false
        }
        val triple = process.inputStream.bufferedReader().readText().trim().lowercase()
        process.exitValue() == 0 && triple.startsWith("x86_64-") &&
            (triple.contains("mingw") || triple.contains("windows-gnu"))
    }
}

val buildAnalysisNative by tasks.registering {
    val cmakeSource = project.file("native")
    val outputDir = analysisNativeDir.get().asFile
    val crossing = crossBuildingForWindows
    val toolchain = project.file("native/mingw-w64.cmake")
    inputs.dir(rootProject.file("native/analyzer"))
    inputs.dir(rootProject.file("app/src/main/cpp/jni"))
    inputs.dir(project.file("native"))
    inputs.property("target", targetOs)
    inputs.property("architecture", targetArch)
    outputs.dir(outputDir)
    onlyIf {
        val cmake = findCmake()
        if (windowsArm64) {
            check(hostIsWindows && cmake != null) {
                "Windows ARM64 JNI requires a Windows host, CMake and Visual Studio 2026 ARM64 C++ tools."
            }
            return@onlyIf true
        }
        if (cmake == null) {
            logger.lifecycle(
                "no usable cmake — building without the Automix analyser. If cmake is installed, " +
                    "a Gradle daemon started before it was on PATH may be in use: ./gradlew --stop",
            )
        }
        val compilerName = "x86_64 MinGW compiler"
        val compiler = targetOs != "windows" || findWindowsX64Compiler(crossing) != null
        val ninja = targetOs != "windows" || findNinja() != null
        if (cmake != null && (!compiler || !ninja)) {
            logger.lifecycle(
                "$compilerName or Ninja not found — the Windows build will have no Automix analyser. " +
                    "Install a MinGW C++ compiler and Ninja, then rebuild.",
            )
        }
        cmake != null && compiler && ninja
    }
    doLast {
        val javaHome = System.getProperty("java.home")
        val compiler = if (targetOs == "windows" && !windowsArm64) {
            findWindowsX64Compiler(crossing)
                ?: error("the Windows C++ compiler disappeared while building the analyser")
        } else {
            null
        }
        val ninja = if (targetOs == "windows" && !windowsArm64) {
            findNinja() ?: error("Ninja disappeared while building the analyser")
        } else {
            null
        }
        providers.exec {
            commandLine(
                buildList {
                    addAll(listOf(cmakeBinary(), "-S", cmakeSource.absolutePath, "-B", outputDir.absolutePath))
                    if (windowsArm64) {
                        addAll(listOf("--fresh", "-G", "Visual Studio 18 2026", "-A", "ARM64", "-DBITCHORD_TARGET_ARCH=arm64"))
                    } else if (targetOs == "windows") {
                        // The host may not have Visual Studio/nmake. Select the MinGW toolchain we
                        // checked above and discard any generator cached by an earlier configure.
                        addAll(
                            listOf(
                                "--fresh",
                                "-G", "Ninja",
                                "-DCMAKE_MAKE_PROGRAM=${ninja!!.absolutePath}",
                                "-DCMAKE_CXX_COMPILER=${compiler!!.absolutePath}",
                            ),
                        )
                    }
                    add("-DCMAKE_BUILD_TYPE=Release")
                    if (crossing) add("-DCMAKE_TOOLCHAIN_FILE=${toolchain.absolutePath}")
                },
            )
            environment("JAVA_HOME", javaHome)
        }.result.get().assertNormalExitValue()
        providers.exec {
            commandLine(cmakeBinary(), "--build", outputDir.absolutePath, "--config", "Release", "-j", "4")
            environment("JAVA_HOME", javaHome)
        }.result.get().assertNormalExitValue()
    }
}

/**
 * Builds the Win32/DWM frame bridge even when the much larger analyser build is skipped during a
 * desktop UI run. It is intentionally absent from Linux builds, whose window manager remains the
 * sole owner of decoration and behaviour.
 */
val buildWindowNative by tasks.registering {
    val cmakeSource = project.file("native/window")
    val outputDir = windowNativeDir.get().asFile
    val crossing = crossBuildingForWindows
    val toolchain = project.file("native/mingw-w64.cmake")
    inputs.dir(cmakeSource)
    inputs.property("target", targetOs)
    inputs.property("architecture", targetArch)
    outputs.dir(outputDir)
    onlyIf {
        if (targetOs != "windows") return@onlyIf false
        val cmake = findCmake()
        if (windowsArm64) {
            check(hostIsWindows && cmake != null) { "Windows ARM64 frame bridge requires CMake and Visual Studio 2026 ARM64 C++ tools." }
            return@onlyIf true
        }
        val compiler = findWindowsX64Compiler(crossing)
        val ninja = findNinja()
        if (cmake == null || compiler == null || ninja == null) {
            logger.lifecycle(
                "cmake, Ninja, or a MinGW C++ compiler is unavailable — the Windows build will " +
                    "fall back to Compose's frameless window behaviour.",
            )
        }
        cmake != null && compiler != null && ninja != null
    }
    doLast {
        val javaHome = System.getProperty("java.home")
        val compiler = if (windowsArm64) null else (
            findWindowsX64Compiler(crossing)
                ?: error("MinGW C++ compiler disappeared while building the Windows frame")
        )
        val ninja = if (windowsArm64) null else (findNinja()
            ?: error("Ninja disappeared while building the Windows frame"))
        providers.exec {
            commandLine(
                buildList {
                    addAll(
                        listOf(
                            cmakeBinary(),
                            "-S", cmakeSource.absolutePath,
                            "-B", outputDir.absolutePath,
                            "-DCMAKE_BUILD_TYPE=Release",
                        ),
                    )
                    if (windowsArm64) {
                        addAll(listOf("--fresh", "-G", "Visual Studio 18 2026", "-A", "ARM64", "-DBITCHORD_TARGET_ARCH=arm64"))
                    } else {
                        addAll(listOf("--fresh", "-G", "Ninja", "-DCMAKE_MAKE_PROGRAM=${ninja!!.absolutePath}", "-DCMAKE_CXX_COMPILER=${compiler!!.absolutePath}"))
                    }
                    if (crossing) add("-DCMAKE_TOOLCHAIN_FILE=${toolchain.absolutePath}")
                },
            )
            environment("JAVA_HOME", javaHome)
        }.result.get().assertNormalExitValue()
        providers.exec {
            commandLine(cmakeBinary(), "--build", outputDir.absolutePath, "--config", "Release", "-j", "4")
            environment("JAVA_HOME", javaHome)
        }.result.get().assertNormalExitValue()
    }
}

val buildWebViewNative by tasks.registering {
    onlyIf { windowsArm64 }
    inputs.dir(project.file("native/webview"))
    val sdk = providers.gradleProperty("bitchord.webview2Sdk").orNull?.let { rootProject.file(it) }
    inputs.property("sdk", sdk?.absolutePath.orEmpty())
    outputs.dir(webViewNativeDir)
    doLast {
        check(sdk != null && File(sdk, "build/native/arm64/WebView2LoaderStatic.lib").isFile) {
            "Windows ARM64 requires -Pbitchord.webview2Sdk=<extracted Microsoft.Web.WebView2 NuGet directory>"
        }
        providers.exec {
            commandLine(cmakeBinary(), "--fresh", "-S", project.file("native/webview").absolutePath,
                "-B", webViewNativeDir.get().asFile.absolutePath, "-G", "Visual Studio 18 2026", "-A", "ARM64", "-DWEBVIEW2_SDK=${sdk!!.absolutePath}")
            environment("JAVA_HOME", System.getProperty("java.home"))
        }.result.get().assertNormalExitValue()
        providers.exec {
            commandLine(cmakeBinary(), "--build", webViewNativeDir.get().asFile.absolutePath, "--config", "Release", "-j", "4")
        }.result.get().assertNormalExitValue()
    }
}

tasks.named<ProcessResources>("processResources") {
    dependsOn(buildAnalysisNative, buildWindowNative, buildWebViewNative)
    from(analysisNativeDir) {
        include("*.so", "*.dll", "*.dylib")
        into("native")
    }
    from(windowNativeDir) {
        include("*.dll")
        into("native")
    }
    if (windowsArm64) from(webViewNativeDir) {
        include("*.dll")
        into("native")
    }
    // The Automix models, taken from the Android module rather than copied into this one.
    from(rootProject.file("app/src/main/assets")) {
        include("*.onnx")
        into("models")
    }
}

// Kotlin 2.4 no longer contributes this JVM target's compiler output to Gradle's plain `jar`
// task in this project. Compose's dev runner and the portable launcher both use that jar, so add
// the output explicitly instead of producing a resources-only application archive.
val spotifyHelper = providers.gradleProperty("bitchord.spotifyHelper").orNull?.let { rootProject.file(it) }
    ?: layout.buildDirectory.file("native/spotify/windows-$targetArch/bitchord-spotify.exe").get().asFile.takeIf { it.isFile }
tasks.named<Jar>("jar") {
    val kotlinClasses = tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileKotlin")
    dependsOn(kotlinClasses)
    from(kotlinClasses.flatMap { it.destinationDirectory })
    inputs.property("spotifyArchitecture", targetArch)
    if (spotifyHelper != null) {
        val notices = layout.buildDirectory.dir("spotify-licenses").get().asFile
        inputs.file(spotifyHelper)
        inputs.dir(notices)
        doFirst {
            WindowsBinary.requireArchitecture(spotifyHelper, targetArch)
            check(File(notices, "dependencies.json").isFile) { "Build the Spotify helper with packaging/Build-SpotifyNative.ps1 to collect its notices." }
        }
        from(spotifyHelper) { into("native/spotify/windows-$targetArch") }
        from(notices) { into("licenses/spotify") }
    }
}

/** A Windows build that can be assembled here, since an installer cannot be. */
val windowsPortableDir = layout.buildDirectory.dir("windows-portable/$targetArch")

val validateWindowsArm64Runtime by tasks.registering {
    group = "verification"
    description = "Checks ARM64 Java 21/25 architecture and recoverable Windows exception handling."
    onlyIf { windowsArm64 }
    val runtime = providers.gradleProperty("bitchord.windowsRuntime").orNull?.let { rootProject.file(it) }
    inputs.dir(project.file("native/runtime-check"))
    inputs.file(project.file("packaging/WindowsRuntimeProbe.java"))
    if (runtime != null) inputs.dir(runtime)
    val output = layout.buildDirectory.dir("native-runtime-check/windows-arm64")
    outputs.dir(output)
    // Execute the probe on every packaging attempt, including cached native builds.
    outputs.upToDateWhen { false }
    doLast {
        check(runtime != null) { "Set -Pbitchord.windowsRuntime=<native ARM64 Java 21 or 25 JDK>" }
        WindowsBinary.requireArchitecture(File(runtime, "bin/java.exe"), "arm64")
        WindowsBinary.requireArchitecture(File(runtime, "bin/server/jvm.dll"), "arm64")
        check(Regex("JAVA_VERSION=\"(?:21|25)[.\"]").containsMatchIn(File(runtime, "release").readText())) {
            "The bundled runtime must be Java 21 or 25"
        }
        val directory = output.get().asFile
        providers.exec {
            commandLine(cmakeBinary(), "--fresh", "-S", project.file("native/runtime-check").absolutePath,
                "-B", directory.absolutePath, "-G", "Visual Studio 18 2026", "-A", "ARM64")
            environment("JAVA_HOME", runtime.absolutePath)
        }.result.get().assertNormalExitValue()
        providers.exec {
            commandLine(cmakeBinary(), "--build", directory.absolutePath, "--config", "Release")
        }.result.get().assertNormalExitValue()
        val classes = File(directory, "classes")
        classes.mkdirs()
        providers.exec {
            commandLine(File(runtime, "bin/javac.exe"), "--release", "21", "-d", classes.absolutePath,
                project.file("packaging/WindowsRuntimeProbe.java").absolutePath)
        }.result.get().assertNormalExitValue()
        val result = providers.exec {
            commandLine(File(runtime, "bin/java.exe"), "--enable-native-access=ALL-UNNAMED", "-XX:ErrorFile=${File(directory, "hs_err_%p.log").absolutePath}",
                "-cp", classes.absolutePath, "WindowsRuntimeProbe", File(directory, "bitchord_runtime_check.dll").absolutePath)
            isIgnoreExitValue = true
        }.result.get()
        check(result.exitValue == 0) {
            "Bundled Java failed the recoverable Windows exception test. Use a compatible ARM64 Java 21/25 JDK; " +
                "Azul Zulu 21.0.12.1+1 passed. Microsoft 21.0.12.1+1 terminates before the native handler runs."
        }
    }
}

val java25DesktopTest by tasks.registering(Test::class) {
    group = "verification"
    description = "Checks Java 21 desktop bytecode on Java 25 without changing compilation or the default test JVM."
    dependsOn(tasks.named("testClasses"))
    onlyIf {
        check(org.gradle.util.GradleVersion.current() >= org.gradle.util.GradleVersion.version("9.1.0")) {
            "Java 25 runtime tests require Gradle 9.1 or later; use the modernization wrapper."
        }
        true
    }
    val baseline = tasks.named<Test>("test").get()
    testClassesDirs = baseline.testClassesDirs
    classpath = baseline.classpath
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

val windowsPortableAssemble by tasks.registering {
    group = "distribution"
    description = "Lays out a Windows-runnable folder: application jars, Windows natives, launcher."
    dependsOn(tasks.named("jar"))
    dependsOn(validateArm64Dependencies)
    dependsOn(validateWindowsArm64Runtime)
    onlyIf { check(targetOs == "windows") { "windowsPortable requires -Pbitchord.target=windows" }; true }
    val stage = windowsPortableDir.map { it.dir("BitChord") }
    val appJar = tasks.named<Jar>("jar").flatMap { it.archiveFile }
    val runtime = configurations.named("runtimeClasspath")
    val version = appVersion
    val moduleIndex = desktopModuleIndexUrl
    val lastfmKey = lastfmApiKey
    val lastfmSharedSecret = lastfmSecret
    val mainClass = composeMainClass
    // Declared, not implied.
    inputs.file(appJar)
    inputs.files(runtime)
    inputs.property("version", version)
    inputs.property("moduleIndex", moduleIndex)
    inputs.property("lastfm", lastfmKey.isNotBlank())
    inputs.property("mainClass", mainClass)
    inputs.property("architecture", targetArch)
    val bundledRuntime = providers.gradleProperty("bitchord.windowsRuntime").orNull?.let { rootProject.file(it) }
    if (bundledRuntime != null) inputs.dir(bundledRuntime)
    outputs.dir(stage)
    doLast {
        check(spotifyHelper != null) {
            "Windows portable packaging requires the native Spotify playback helper. " +
                "Run desktopApp/packaging/Build-SpotifyNative.ps1 -Architecture $targetArch before packaging."
        }
        val target = stage.get().asFile
        val lib = File(target, "lib")
        val launcherWork = layout.buildDirectory.dir("windows-launcher/arm64").get().asFile
        check(lib.canonicalFile.toPath().startsWith(rootProject.projectDir.canonicalFile.toPath())) {
            "Portable staging must stay inside this repository"
        }
        if (windowsArm64) {
            check(bundledRuntime != null) { "A native ARM64 Java 21/25 runtime is required: -Pbitchord.windowsRuntime=<JDK-or-runtime-directory>" }
            WindowsBinary.requireArchitecture(File(bundledRuntime, "bin/java.exe"), "arm64")
            WindowsBinary.requireArchitecture(File(bundledRuntime, "bin/server/jvm.dll"), "arm64")
            val release = File(bundledRuntime, "release").readText()
            check(Regex("JAVA_VERSION=\"(?:21|25)[.\"]").containsMatchIn(release)) { "The bundled runtime must be Java 21 or 25" }
            val runtimeTarget = File(target, "runtime").canonicalFile
            check(runtimeTarget.toPath().startsWith(target.canonicalFile.toPath())) { "Runtime staging escaped its package directory" }
            val replacedDirectories = listOf(runtimeTarget, lib.canonicalFile, launcherWork.canonicalFile)
            listOf(bundledRuntime, File(System.getProperty("java.home"))).forEach { source ->
                val sourcePath = source.canonicalFile.toPath()
                replacedDirectories.forEach { directory ->
                    val replacedPath = directory.toPath()
                    check(!sourcePath.startsWith(replacedPath) && !replacedPath.startsWith(sourcePath)) {
                        "Source JDK and replaced staging must not contain one another: $source / $directory"
                    }
                }
            }
            project.delete(runtimeTarget)
            project.copy { from(bundledRuntime); into(File(target, "runtime")) }
            File(target, "runtime").walkTopDown().filter { it.isFile && it.extension.lowercase() in listOf("exe", "dll") }
                .forEach { WindowsBinary.requireArchitecture(it, targetArch) }
        }
        lib.deleteRecursively()
        lib.mkdirs()

        // Named by coordinate rather than by file name.
        runtime.get().incoming.artifacts.artifacts.forEach { artifact ->
            val id = artifact.id.componentIdentifier
            val group = (id as? org.gradle.api.artifacts.component.ModuleComponentIdentifier)?.group
            val name = if (group.isNullOrBlank()) artifact.file.name else "$group.${artifact.file.name}"
            artifact.file.copyTo(File(lib, name), overwrite = true)
        }
        appJar.get().asFile.copyTo(File(lib, "bitchord-app.jar"), overwrite = true)
        project.copy {
            from(rootProject.file("LICENSE"))
            from(rootProject.file("docs/windows-arm64-third-party.md"))
            from(project.file("src/main/resources/licenses/WDB-Bangna-NOTICE.txt"))
            into(File(target, "licenses"))
        }
        if (windowsArm64) {
            project.copy { from(File(arm64Natives, "licenses")); into(File(target, "licenses")) }
            val sdk = providers.gradleProperty("bitchord.webview2Sdk").orNull?.let { rootProject.file(it) }
            if (sdk != null) project.copy {
                from(File(sdk, "LICENSE.txt"), File(sdk, "NOTICE.txt"))
                into(File(target, "licenses/webview2"))
            }
        }

        // `lib\*` rather than a spelled-out classpath: the wildcard is what keeps this working when
        // a dependency is added, and the JVM is what expands it.
        File(target, "BitChord.bat").writeText(
            buildString {
                appendLine("@echo off")
                appendLine("setlocal")
                appendLine(if (windowsArm64) "rem Uses the bundled native ARM64 Java runtime." else "rem Needs a Java 21+ runtime on PATH or in JAVA_HOME.")
                appendLine("set \"HERE=%~dp0\"")
                appendLine("if not exist \"%HERE%data\\tmp\" mkdir \"%HERE%data\\tmp\"")
                appendLine("set \"RUNNER=java\"")
                appendLine("if defined JAVA_HOME set \"RUNNER=%JAVA_HOME%\\bin\\java\"")
                if (windowsArm64) appendLine("set \"RUNNER=%HERE%runtime\\bin\\java\"")
                append("\"%RUNNER%\" -Xmx512m -XX:+UseG1GC -XX:G1PeriodicGCInterval=20000 -XX:G1PeriodicGCSystemLoadThreshold=0 -Dbitchord.version=$version")
                if (windowsArm64) append(" --enable-native-access=ALL-UNNAMED")
                append(" -Dbitchord.dataDir=\"%HERE%data\"")
                append(" -Djava.io.tmpdir=\"%HERE%data\\tmp\"")
                if (moduleIndex.isNotBlank()) append(" -Dbitchord.module.index=$moduleIndex")
                if (lastfmKey.isNotBlank() && lastfmSharedSecret.isNotBlank()) {
                    append(" -Dbitchord.lastfm.key=$lastfmKey -Dbitchord.lastfm.secret=$lastfmSharedSecret")
                }
                appendLine(" -cp \"%HERE%lib\\*\" $mainClass %*")
                appendLine("endlocal")
            },
        )

        // What "it builds" is not the same as "it runs", and the difference is exactly what shipped
        // a broken archive.
        val jars = lib.listFiles { file: File -> file.name.endsWith(".jar") }.orEmpty()
        if (windowsArm64) {
            val jpackage = File(bundledRuntime, "bin/jpackage.exe")
            check(jpackage.isFile) { "The ARM64 portable launcher requires the selected JDK's jpackage.exe" }
            WindowsBinary.requireArchitecture(jpackage, targetArch)
            check(launcherWork.canonicalFile.toPath().startsWith(rootProject.projectDir.canonicalFile.toPath())) {
                "Native launcher staging must stay inside this repository"
            }
            project.delete(launcherWork)
            val seed = File(launcherWork, "input").also { it.mkdirs() }
            appJar.get().asFile.copyTo(File(seed, "bitchord-app.jar"))
            val image = File(launcherWork, "image")
            val launcherResult = providers.exec {
                commandLine(buildList {
                    addAll(listOf(jpackage.absolutePath, "--type", "app-image", "--name", "BitChord",
                        "--input", seed.absolutePath, "--main-jar", "bitchord-app.jar", "--main-class", mainClass,
                        "--runtime-image", bundledRuntime!!.absolutePath, "--dest", image.absolutePath,
                        "--icon", project.file("packaging/icons/AppIcon.ico").absolutePath,
                        "--app-version", nativePackageVersion))
                    listOf("-Xmx512m", "-XX:+UseG1GC", "-XX:G1PeriodicGCInterval=20000",
                        "-XX:G1PeriodicGCSystemLoadThreshold=0", "--enable-native-access=ALL-UNNAMED", "-Dbitchord.version=$version",
                        "-Dbitchord.dataDir=\$APPDIR/../data", "-Djava.io.tmpdir=\$APPDIR/../data/tmp").forEach {
                        addAll(listOf("--java-options", it))
                    }
                    if (moduleIndex.isNotBlank()) addAll(listOf("--java-options", "-Dbitchord.module.index=$moduleIndex"))
                    if (lastfmKey.isNotBlank() && lastfmSharedSecret.isNotBlank()) {
                        addAll(listOf("--java-options", "-Dbitchord.lastfm.key=$lastfmKey",
                            "--java-options", "-Dbitchord.lastfm.secret=$lastfmSharedSecret"))
                    }
                })
                isIgnoreExitValue = true
            }
            if (launcherResult.result.get().exitValue != 0) {
                var diagnostic = launcherResult.standardError.asText.get() + launcherResult.standardOutput.asText.get()
                listOf(lastfmKey, lastfmSharedSecret, moduleIndex).filter { it.isNotBlank() }.forEach {
                    diagnostic = diagnostic.replace(it, "[redacted]")
                }
                error("Native launcher packaging failed: ${diagnostic.trim()}")
            }
            val generated = File(image, "BitChord")
            val executable = File(generated, "BitChord.exe")
            WindowsBinary.requireArchitecture(executable, targetArch)
            executable.copyTo(File(target, "BitChord.exe"), overwrite = true)
            val classpath = jars.sortedBy { it.name }.joinToString(";") { "\$APPDIR\\..\\lib\\${it.name}" }
            val config = File(generated, "app/BitChord.cfg").readLines().joinToString("\n", postfix = "\n") {
                if (it.startsWith("app.classpath=")) "app.classpath=$classpath" else it
            }
            File(target, "app").mkdirs()
            File(target, "app/BitChord.cfg").writeText(config)
        }
        fun holds(entry: String) = jars.any { jar ->
            ZipFile(jar).use { zip -> zip.getEntry(entry) != null }
        }
        val problems = buildList {
            if (!holds("com/music/bitchord/desktop/MainKt.class")) {
                add("the BitChord desktop entry point is missing")
            }
            if (!holds("androidx/compose/runtime/Composer.class")) {
                add("the Compose runtime is missing")
            }
            if (jars.none { it.name.contains("skiko-awt-runtime-windows-$targetArch") }) {
                add("the Windows Skiko renderer is missing")
            }
            if (jars.any { it.name.contains("skiko-awt-runtime-linux") }) {
                add("a Linux Skiko renderer is in a Windows archive")
            }
            if (!holds("org/bytedeco/ffmpeg/$nativeClassifier/jniavformat.dll")) {
                add("the Windows FFmpeg natives are missing")
            }
            if (!holds("native/spotify/windows-$targetArch/bitchord-spotify.exe")) {
                add("the native Spotify playback helper is missing")
            }
            if (!holds("licenses/spotify/dependencies.json")) {
                add("the Spotify helper dependency notices are missing")
            }
            if (windowsArm64) {
                listOf("bitchord_analysis.dll", "bitchord_smtc.dll", "bitchord_window.dll", "bitchord_webview.dll").forEach {
                    if (!holds("native/$it")) add("$it is missing; ARM64 must retain Automix and Windows integration")
                }
                if (!holds("ai/onnxruntime/native/win-aarch64/onnxruntime4j_jni.dll")) add("ARM64 ONNX Runtime Java natives are missing")
            }
            jars.forEach { WindowsBinary.verifyJar(it, targetArch) }
        }
        check(problems.isEmpty()) { "windowsPortable: " + problems.joinToString("; ") }

        val analyser = File(analysisNativeDir.get().asFile, "bitchord_analysis.dll").isFile
        logger.lifecycle(
            if (analyser) {
                "windowsPortable: verified — Compose runtime, Windows renderer, Windows FFmpeg, Automix analyser."
            } else {
                "windowsPortable: verified, but no bitchord_analysis.dll — Automix will not run in this archive. " +
                    "Rebuild with: nix-shell -p cmake pkgsCross.mingwW64.buildPackages.gcc"
            },
        )
    }
}

val windowsPortable by tasks.registering(Zip::class) {
    group = "distribution"
    description = "Zips the portable Windows folder for copying to a Windows machine."
    dependsOn(windowsPortableAssemble)
    from(windowsPortableDir)
    include("BitChord/lib/**", "BitChord/runtime/**", "BitChord/BitChord.bat", "BitChord/BitChord.exe",
        "BitChord/app/BitChord.cfg", "BitChord/licenses/**")
    archiveFileName.set("BitChord-$appVersion-windows-$targetArch-portable.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    doLast { logger.lifecycle("windowsPortable: ${archiveFile.get().asFile.absolutePath}") }
}

val composeMainClass = "com.music.bitchord.desktop.MainKt"

compose.desktop {
    application {
        javaHome = javaToolchains.launcherFor(java.toolchain).map {
            it.metadata.installationPath.asFile.absolutePath
        }.get()
        mainClass = composeMainClass
        jvmArgs("-Xmx512m")
        jvmArgs("-XX:+UseG1GC", "-XX:G1PeriodicGCInterval=20000", "-XX:G1PeriodicGCSystemLoadThreshold=0")
        jvmArgs("-Dbitchord.version=$appVersion")
        providers.gradleProperty("bitchord.dataDir").orNull?.let {
            jvmArgs("-Dbitchord.dataDir=${rootProject.file(it).absolutePath}")
        }
        providers.gradleProperty("bitchord.performance").orNull?.let {
            jvmArgs("-Dbitchord.performance=$it")
        }
        if (desktopModuleIndexUrl.isNotBlank()) {
            jvmArgs("-Dbitchord.module.index=$desktopModuleIndexUrl")
        }
        if (lastfmApiKey.isNotBlank() && lastfmSecret.isNotBlank()) {
            jvmArgs("-Dbitchord.lastfm.key=$lastfmApiKey", "-Dbitchord.lastfm.secret=$lastfmSecret")
        }
        if (listenTogetherServer.isNotBlank()) {
            jvmArgs("-Dbitchord.listentogether.server=$listenTogetherServer")
        }

        buildTypes.release.proguard {
            isEnabled.set(false)
            obfuscate.set(false)
            optimize.set(false)
            configurationFiles.from(project.file("packaging/proguard-rules.pro"))
        }

        nativeDistributions {
            targetFormats(
                TargetFormat.Dmg,
                TargetFormat.Pkg,
                TargetFormat.Exe,
                TargetFormat.Msi,
                TargetFormat.Deb,
                TargetFormat.Rpm,
            )
            packageName = "BitChord"
            packageVersion = nativePackageVersion
            description = if (targetOs == "windows") "BitChord" else "Aesthetic YouTube Music client"
            vendor = "BitChord contributors"
            copyright = "Copyright © 2026 BitChord contributors"

            modules(
                "java.base",
                "java.desktop",
                "java.instrument",
                "java.logging",
                "java.management",
                "java.naming",
                "java.net.http",
                "java.prefs",
                "java.scripting",
                "java.sql",
                "java.xml",
                "jdk.crypto.ec",
                "jdk.dynalink",
                // JavaFX WebEngine returns JavaScript objects through netscape.javascript.JSObject.
                // Without this module the slim production runtime crashes in twkExecuteScript,
                // while development runs work because the full JDK already contains it.
                "jdk.jsobject",
                "jdk.httpserver",
                "jdk.localedata",
                "jdk.security.auth",
                "jdk.unsupported",
                "jdk.zipfs",
            )

            macOS {
                iconFile.set(project.file("packaging/icons/AppIcon.icns"))
                bundleID = "com.music.bitchord"
                appCategory = "public.app-category.music"
                dockName = "BitChord"
                infoPlist {
                    extraKeysRawXml = """
                        <key>NSRequiresAquaSystemAppearance</key>
                        <false/>
                    """.trimIndent()
                }
            }

            linux {
                iconFile.set(project.file("packaging/icons/AppIcon.png"))
                menuGroup = "Audio"
                appCategory = "AudioVideo;Audio;Player"
                debMaintainer = "85984486+AbhiTheModder@users.noreply.github.com"
                rpmLicenseType = "GPL-3.0"
            }

            windows {
                iconFile.set(project.file("packaging/icons/AppIcon.ico"))
                upgradeUuid = "8f3c1d24-6a2b-4f5e-9c17-2b8d54e0a913"
                menuGroup = "BitChord"
                shortcut = true
                dirChooser = false
                perUserInstall = true
            }
        }
    }

}
