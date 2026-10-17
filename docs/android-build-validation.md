# Android modernization validation

Verified locally on 2026-10-16 with the opt-in Gradle 9.5.1 wrapper, native Windows
ARM64 Zulu Java 25 build JVM, AGP 9.3.3 and Kotlin 2.4.21. Compilation still uses
Java 21 toolchains and Java/Kotlin 17 bytecode. Minimum API 26 and target API 36
are unchanged; compile API 37 matches the existing dependency requirement.

## Build results

- `assembleDevDebug`, 971 app unit tests and 39 shared Android host tests pass.
  Shared UI has no Android host test sources.
- `assembleProdRelease` passes, including R8 and release-vital lint (`No issues found`).
  ARM64, ARMv7, x64 and universal APKs are generated. Production APKs are unsigned;
  a production signing key was not supplied.
- All four production APKs pass packaged native architecture, ELF/ZIP 16 KB
  alignment, DEX presence, minimum/target SDK and non-debuggable checks.
- `assembleDevBenchmark` passes with R8 and release-vital lint enabled. Its ARM64
  APK passes signature and 16 KB ZIP alignment checks and is non-debuggable.
- Debug native ELF machines match each packaged ABI. All 16 ARM64/x64 debug native
  libraries have LOAD alignment of at least 16 KB. APK signature and 16 KB ZIP
  alignment checks pass for the installed ARM64 debug APK and universal debug APK.
- 1,986 compiled app Kotlin classes and seven Java classes have maximum classfile
  major 61. This is a build-JVM upgrade, not a Java 25 application-runtime requirement.

The first test run failed 17 localization checks and release lint reported six
ExtraTranslation errors. The affected files matched the original downloaded ZIP.
The fixes add missing Chinese language labels in 14 languages, retain legacy `zh`
while declaring the existing `zh-CN`/`zh-TW` app choices, map those tags to Android's
actual resource directories in the coverage test, and remove two unused orphan
lyrics keys from each of three Android Chinese catalogs. The simplified labels
reuse each locale's existing `chinese` string; AAPT's compiled resource dump confirms
all 14 are resource references. Strict test assertions and lint remain enabled.

## Device smoke check

Device: SM-S721B, Android 17/API 37, ARM64-only application ABI. USB debugging was
authorized manually by the user. `com.music.bitchord` was already installed;
`com.dev.bitchord` was absent. The ARM64 debug APK was signature/alignment-checked,
then installed without a replacement flag under the development identifier.

Observed in the separate development app:

- Cold launch succeeds; Android reports 2,154 ms activity launch time for this
  single uncalibrated run. This is not a startup performance comparison.
- Home browsing and public YouTube Music search load without account sign-in.
- `Sweater Weather` plays; the user confirms audible output.
- Pause publishes PAUSED, speed 0. Seeking while paused moves 1:15 to 2:41;
  resume advances the playhead and loads synchronized lyrics.
- The queue opens and Next switches to `Reflections`.
- While Home is foreground, the development media session advances from 2,864 ms
  to 35,950 ms over approximately 34 seconds. Returning to the app retains playback.
- The running debug process maps the 64-bit ART runtime on the ARM64-only device.
  No fatal Java/native crash is recorded for that live development process.
- Existing production package version and installation timestamp are unchanged.

This smoke check does not verify every Android feature, Bluetooth switching,
account sign-in, audio fidelity, Android Auto, physical media keys, all supported
OS versions or all ABI devices. It supplies no CPU/GPU/battery comparison.

## Optimized device check

The existing release-equivalent benchmark variant was installed separately as
`com.dev.bitchord.benchmark`, after confirming that identifier was absent. No
replacement install or production signing key was used. Android reports its
primary ABI as `arm64-v8a`; the APK contains only matching ARM64 native libraries.

Cold launch, Home, public search, playback, pause/resume, seeking, synchronized
lyrics and Next pass in this non-debuggable R8-optimized build. The user confirmed
audible `Opalite` playback. Seeking while paused publishes position 157,840 ms and
speed 0. After resuming, the media session advances from 163,737 to 190,060 ms across
approximately 28 seconds with Home foreground. Returning to the app retains the
lyrics view; Next changes the track to `The Fate of Ophelia`. No fatal Java/native
crash was recorded for the live benchmark process. The regular installed app's
version and installation timestamp remain unchanged. Both test copies were left
installed with playback paused; no app data was cleared.

Installed optimized APK:
`app/build/outputs/apk/dev/benchmark/app-dev-arm64-v8a-benchmark.apk`, 59,727,478 bytes.
SHA256: `5ad3d9122d58d960cd26820872b3701cd0bfed04951f34874a68895c822d1de6`.
This is a test-signed artifact, not a production release.

## Reproduction

Run from the repository root with the installed SDK and explicit Java 21 compiler:

```powershell
$env:JAVA_HOME = "$PWD\.work\tools\zulu25.36.205-ca-jdk25.0.4.1-win_aarch64"
$env:GRADLE_USER_HOME = "$PWD\.work\gradle-home"
$env:ANDROID_HOME = 'C:\Users\Admin\AppData\Local\Android\Sdk'
$jdk21 = "$PWD\.work\tools\zulu21.52.203-ca-jdk21.0.12.1-win_aarch64"
$jdkPaths = "$jdk21,$env:JAVA_HOME"
rtk proxy .\gradlew-modern.bat :app:assembleDevDebug :app:testDevDebugUnitTest :shared:testAndroidHostTest :sharedUi:testAndroidHostTest '-Pbitchord.modernBuild=true' "-Porg.gradle.java.installations.paths=$jdkPaths" '-Porg.gradle.java.installations.auto-detect=false' --no-daemon
rtk proxy .\gradlew-modern.bat :app:assembleProdRelease '-Pbitchord.modernBuild=true' "-Porg.gradle.java.installations.paths=$jdkPaths" '-Porg.gradle.java.installations.auto-detect=false' --max-workers=2 --no-daemon
```

The existing `assembleDevBenchmark` variant provides R8-optimized, non-debuggable
code signed with the debug key under `com.dev.bitchord.benchmark`, for separate
release-equivalent testing. Check for an existing installation before installing
either test variant; never replace the regular app for a smoke check.

The default Java 21/Gradle 8.11.1 wrapper remains intact. Its Android build cannot
resolve the installed `android-37.0` platform with AGP 8.10.1; the candidate handles
that platform without directory renaming. See [build infrastructure](desktop-java25.md)
for the compatibility limits and desktop/native results.
