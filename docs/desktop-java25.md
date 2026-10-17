# Build infrastructure modernization candidate

The chosen target is Java 25 LTS with Gradle 9.5.1 and AGP 9.3 for the build infrastructure.
Java 21 with Gradle 8.11.1 remains the tested baseline until Android, Windows x64,
Windows ARM64 and native workflows are verified. The candidate is not promoted.

Upgrading the build JVM does not raise the application's minimum runtime.
Desktop compilation and minimum runtime remain Java 21. Shared and Android JVM
targets remain 17. Android minimum and target SDK levels are unchanged. The portable
application continues bundling a compatible Java 21 JDK.

`gradlew-modern` and `gradlew-modern.bat` select the separate candidate wrapper.
All four original wrapper files remain byte-for-byte unchanged. Candidate hashes:

- Gradle 9.5.1 distribution: `bafc141b619ad6350fd975fc903156dd5c151998cc8b058e8c1044ab5f7b031f`.
- Official wrapper JAR: `497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7`.

The ARM64 preflight on this infrastructure passed 402 desktop tests on Java 21,
402 opt-in desktop tests on Java 25, 46 shared tests and 41 shared UI tests.
It produced a Java 21 portable package; 80 executable/runtime files and 34 selected
native resources in JARs passed the ARM64 scan. The maximum desktop application
classfile major version is 65 (Java 21). The initial Windows x64 candidate build
also passed desktop/shared tests and portable assembly. Its 139 selected JAR
natives pass the x64 scan. These results have also passed with the opt-in
AGP 9.3.3 / Kotlin 2.4.21 configuration: ARM64 desktop 402 tests on each JVM,
46 shared and 41 shared UI tests, and x64 desktop tests/portable assembly.
Native startup, playback progress, full-player/lyrics rendering and graceful
shutdown were exercised in the separate Java 21 test copy with the new compiler's
JARs. The original Java 21 / Gradle 8.11.1 ARM64 build was then rerun successfully
through all 44 tasks, including tests and packaging.

`-Pbitchord.modernBuild=true` opts into AGP 9.3.3 and Kotlin 2.4.21 with the modern
wrapper. The app uses AGP's built-in Kotlin support; shared libraries use
`com.android.kotlin.multiplatform.library`. Neither legacy Kotlin nor legacy DSL
opt-outs are enabled. The small Groovy script `gradle/android-kmp-modern.gradle`
configures the new Android DSL only in this mode, keeping that API out of the
baseline Kotlin build scripts. Android host tests are explicitly enabled and
retain the common test source tree. Candidate compilation uses Java 21 toolchains,
with Android/shared bytecode 17 and desktop bytecode 21. The baseline keeps its
original Android plugin and R8 override; the candidate uses AGP's bundled R8.

## Compatibility boundary

- The existing wrapper is Gradle 8.11.1. It supports running through Java 23;
  Java 25 requires Gradle 9.1.0 or later, not 9.0.0. The chosen candidate is 9.5.1.
- Kotlin 2.3.20 officially supports Gradle 7.6.3 through 9.3.0. Compose stays
  1.10.3, Skiko stays 0.9.37.4 and baseline AGP stays 8.10.1. Gradle 9.5.1 is outside
  Kotlin 2.3.20's published fully supported range. Local builds alone cannot
  establish official plugin support. Evaluate compatible plugin candidates
  separately if required, then rerun all workflows before promotion.
- AGP 9.3 supports API 37 and requires Gradle 9.5.0 or newer. The installed
  released Android 17 platform is `platforms/android-37.0`, revision 2. Google's
  API-level table requires AGP 9.1.1 or newer for 37.0. The original AGP 8.10.1
  build fails looking for `android-37`; no SDK directories are renamed to hide
  that incompatibility. App `compileSdk=37`, `targetSdk=36`, `minSdk=26` remain
  unchanged. The candidate's shared modules also compile against API 37 because
  the existing transitive InnerTubeX AAR requires it; the release AAR metadata
  check fails at API 36. The baseline library compile SDK remains 36.
- Kotlin 2.4.21's published range includes Gradle 9.5.1 and AGP through 9.3.1.
  The candidate selects maintenance release AGP 9.3.3, including Windows build
  fixes. That patch is newer than Kotlin's stated tested AGP maximum; local
  validation must not be described as published full compatibility.
- Desktop compilation remains Java/Kotlin 21. Shared and Android JVM targets
  remain 17. The compiler uses an explicit ARM64 Java 21 toolchain even when
  Gradle runs on Java 25.
- Compose's documented minimums are JDK 11 for execution and JDK 17 for native
  packaging. These minimums do not prove Java 25 runtime compatibility; the
  concrete application and JNI paths require testing.
- The classpath desktop launcher enables native access explicitly, as described
  in JEP 472. This is confined to Windows ARM64 packaging and runtime verification.

Official references: [Gradle Java compatibility](https://docs.gradle.org/current/userguide/compatibility.html#java_runtime),
[Kotlin Gradle compatibility](https://kotlinlang.org/docs/gradle-configure-project.html),
[Compose requirements](https://www.jetbrains.com/help/kotlin-multiplatform-dev/compose-compatibility-and-versioning.html),
[AGP 8.10 compatibility](https://developer.android.com/build/releases/past-releases/agp-8-10-0-release-notes),
[JEP 472](https://openjdk.org/jeps/472).
Candidate references: [AGP 9.3](https://developer.android.com/build/releases/agp-9-3-0-release-notes),
[API-level tool requirements](https://developer.android.com/build/releases/about-agp#api-level-support),
[Android KMP plugin](https://developer.android.com/kotlin/multiplatform/plugin),
[built-in Kotlin migration](https://developer.android.com/build/migrate-to-built-in-kotlin).

## Runtime candidates and fallback

The Windows ARM64 Java 25 candidate is Azul Zulu 25.36.205, Java 25.0.4.1+1 LTS.
Its official archive SHA256 is
`654b92047fcc4e5106f7b73a2f93d9c5fcb399f4658ce4d693228c7acb52e4ad`.
All its Windows EXE/DLL files pass the ARM64 PE scan. The recoverable Windows
RPC exception JNI fixture passes. [Vendor metadata](https://api.azul.com/metadata/v1/zulu/packages/1b39f50f-d426-4b04-a4ba-bd7f05f4a7d5).

The verified Java 21 fallback uses Azul Zulu 21.52.203 / Java 21.0.12.1+1.
`desktopApp/build/distributions/BitChord-1.8-beta1-windows-arm64-java21-portable.zip`
is preserved at 398,877,579 bytes, SHA256
`fd304559330be1fefe6c81184fa2362b641ca887446304dafee9ffbaf621ee9d`.
Its full ARM64 desktop/shared suites passed. Microsoft Java 21.0.12.1+1 is not
the fallback: its vectored handler terminates on an otherwise recoverable
WebView2/UI Automation RPC exception.

`validateWindowsArm64Runtime` accepts Java 21 and 25 and tests native exception
handling on the exact packaging JVM. The opt-in standard `java25DesktopTest`
task runs the compiled desktop tests on an alternate JVM; it uses the same
compiled Java 21 classes without changing the default test JVM or packaged runtime.
The desktop test configuration isolates preferences and checks JVM architecture.

## Validation still required

Gradle 9.5.1 runs on native ARM64 Java 25. The ARM64 configuration, tests and
Java 21 packaging checks above passed; other promotion gates remain open.
The default wrapper is preserved until every required workflow is verified.
An earlier package preflight exposed a read-only generated launcher that
Kotlin's recursive deletion left behind. A real Windows fixture reproduced the
failure; Gradle's deletion API removes it. Staging now uses that API, and checks
that source/build JDKs overlap none of the directories to be replaced before any
staging mutation. A selected JDK inside the generated image was rejected with all
440 source files unchanged. Java 25 application packaging remains unverified.
Application runtime migration
and paired runtime measurements are deferred behind build infrastructure work.
Windows x64 retains its existing Java 21 execution until Java 25 is tested there.
The Android SDK is now installed at `C:\Users\Admin\AppData\Local\Android\Sdk`.
AGP 9.3 candidate configuration passes with built-in Kotlin and the new KMP
plugin. Debug APK assembly and native CMake builds passed for ARM64, ARMv7 and
x64. All packaged native ELF files match their ABI. The universal APK also
contains four x86 vendor natives; no x86 application native build was claimed.
The 1,986 app Kotlin classes and seven Java classes have maximum classfile major
61 (Java 17), and the APK declares minimum API 26, target API 36, compile API 37.
All 39 shared Android host tests passed; shared UI has no Android host test source.
The app now passes all 971 tests. The initial run exposed 17 localization failures
in source identical to the original ZIP. Those defects were corrected: all offered
languages have the Chinese language labels, the locale configuration includes the
existing Chinese region tags, and the coverage test resolves Android's actual
Chinese resource folders. Its strict coverage assertions remain enabled.
The universal debug APK's signature and 16 KB ZIP alignment pass. All 16 ARM64/x64
native shared libraries have ELF LOAD alignment of at least 16 KB.

Full `:app:assembleProdRelease` now passes, including R8 and release-vital lint
(`No issues found`). Its initial six `ExtraTranslation` errors were two unused
lyrics keys in three Chinese resource files, with no defaults or source references;
only those orphan Android entries were removed. Production APKs are unsigned
because no release signing key was supplied. No test or lint exclusions were added.
An Android 17 ARM64 phone passed native app startup, public search, audible playback
(user confirmed), pause/resume, seeking, lyrics, queue skipping and background
playback in the separate development app. The installed production app's version
and installation time were unchanged. See [Android validation](android-build-validation.md)
for the exact scope, artifacts and remaining device checks.
The separate non-debuggable R8 benchmark build also passed startup, public search,
user-confirmed audible playback, pause/resume, seek, lyrics, Next and background
playback on that phone. Both test identifiers were absent before installation;
neither replaced the regular app. These checks establish no battery improvement.
The baseline Android failure described above remains recorded separately.
The baseline Windows x64 build
also passed all 402 desktop, 46 shared and 41 shared UI tests and portable assembly
after the migration; the shared test launcher was verified as Java 21 x64 before
execution. Its 139 selected JAR natives pass the x64 scan.
The candidate's 46 shared and 41 shared UI tests were also forced to execute on
verified Java 21 x64 with the SDK environment removed. Desktop-only configuration
and these suites passed without including the Android app module.

Runtime comparisons must hold the application JARs, view, source, track,
visual policy, dimensions and power state constant. Idle, static cover and
lyrics samples, startup observations and memory/CPU/GPU telemetry are recorded
separately. A Java version change alone establishes no battery or GPU benefit.

## Candidate command

Run from the repository root. Keep an explicit Java 21 compiler toolchain.

```powershell
$env:JAVA_HOME = "$PWD\.work\tools\zulu25.36.205-ca-jdk25.0.4.1-win_aarch64"
$env:GRADLE_USER_HOME = "$PWD\.work\gradle-home"
$jdk21 = "$PWD\.work\tools\zulu21.52.203-ca-jdk21.0.12.1-win_aarch64"
$jdkPaths = "$jdk21,$env:JAVA_HOME"
$sdk = "$PWD\.work\webview2-sdk\extracted"
$cmake = 'C:\Program Files (x86)\Microsoft Visual Studio\18\BuildTools\Common7\IDE\CommonExtensions\Microsoft\CMake\CMake\bin\cmake.exe'
rtk proxy .\gradlew-modern.bat :desktopApp:test :shared:jvmTest :sharedUi:jvmTest :desktopApp:windowsPortable '-Pbitchord.modernBuild=true' '-Pbitchord.arch=arm64' "-Porg.gradle.java.installations.paths=$jdkPaths" '-Porg.gradle.java.installations.auto-detect=false' "-Pbitchord.windowsRuntime=$jdk21" "-Pbitchord.webview2Sdk=$sdk" "-Pbitchord.cmake=$cmake"
```

For Android, keep the same build JVM and explicit Java 21 toolchain, then run:

```powershell
$env:ANDROID_HOME = 'C:\Users\Admin\AppData\Local\Android\Sdk'
rtk proxy .\gradlew-modern.bat :app:assembleDevDebug :app:testDevDebugUnitTest :shared:testAndroidHostTest :sharedUi:testAndroidHostTest '-Pbitchord.modernBuild=true' "-Porg.gradle.java.installations.paths=$jdkPaths" '-Porg.gradle.java.installations.auto-detect=false' --no-daemon
```

Use a new daemon after changing the SDK environment. Candidate configuration
does not require `android.builtInKotlin=false` or `android.newDsl=false`.

For the baseline use the original wrapper with `JAVA_HOME` set to Java 21,
as documented in [Windows ARM64 commands](windows-arm64.md).
