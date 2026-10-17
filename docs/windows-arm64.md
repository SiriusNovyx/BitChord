# Windows ARM64 build and verification

The local portable package runs natively on Windows ARM64. It includes an AA64
`BitChord.exe`, Azul Zulu Java 21 runtime, Skiko renderer, FFmpeg codecs/JNI,
ONNX Java JNI, QuickJS, analyzer, SMTC, window and WebView2 bridges. This is a
local development build, not a published release or verified installer.

The pinned x64 JavaFX and FFmpeg artifacts cannot be renamed into ARM64 builds.
ARM64 uses the isolated substitutions below; x64 retains JavaFX 21.0.10 and
FFmpeg 7.1.1-1.5.12. Android/common modules have no WebView2 dependency.

| Dependency | ARM64 implementation |
|---|---|
| Compose/Skiko | Compose 1.10.3 Windows ARM64 / Skiko 0.9.37.4 |
| JavaFX WebKit | Native WebView2 adapter, using the installed ARM64 Edge WebView2 Runtime |
| FFmpeg | JavaCPP 1.5.14 / FFmpeg 8.1.2 Java API; MSVC JNI against verified BtbN LGPL shared 8.1 libraries |
| ONNX | v1.29.0 built with MSVC ARM64, including its Java JNI bridge |
| QuickJS | Exact quickjs-kt v1.0.14 JNI and engine sources, built with ARM64 LLVM-MinGW |
| GraalJS | Supported Truffle interpreter; excludes the unavailable optimized native runtime |
| JNA / SQLite | Existing universal JARs contain compatible Windows ARM64 resources |
| Windows integration | Project JNI bridges built with VS 2026/MSVC ARM64 |

See [native provenance and licenses](windows-arm64-third-party.md).
JavaCPP's upstream ARM64 JAR also contains foreign/system CRT files. Preparation
removes them, verifies every retained DLL, and records the original hash. An
`ffmpeg.exe` does not replace JNI libraries.

## Prerequisites

- Windows 11 ARM64 and a Windows ARM64 Java 21 **JDK** with `jpackage.exe`.
  The tested runtime is Azul Zulu 21.52.203 (Java 21.0.12.1+1). Microsoft
  21.0.12.1+1 fails a recoverable Windows exception test and must not be bundled.
- Visual Studio Build Tools 2026 ARM64 C++ tools and Windows SDK 10.0.26100.0.
- CMake supporting `Visual Studio 18 2026`, Ninja, Python and Git.
- ARM64-host LLVM-MinGW for QuickJS.
- Installed ARM64 Edge WebView2 Runtime, which is not redistributed in this ZIP.
- Microsoft.Web.WebView2 SDK 1.0.4258.31 extracted from its NuGet archive.

Tools in this checkout are under `.work`. Default PATH still exposes Java 8;
set `JAVA_HOME` before building. The Android SDK is installed at
`C:\Users\Admin\AppData\Local\Android\Sdk`; the API 37 build uses the separate
[Java 25 / Gradle 9.5.1 / AGP 9.3 candidate](desktop-java25.md).
Desktop run/test tasks reject a JVM whose PE architecture differs from the
selected Windows target; compilation/packaging can still use an emulated build
tool when the packaged runtime and native tools match the target.

## Package or run

Run from the repository root in PowerShell. Prepared local native artifacts
already exist in `desktopApp/native-dependencies/windows-arm64` in this checkout.
Quote dotted Gradle properties to prevent PowerShell argument splitting.

```powershell
$env:JAVA_HOME = "$PWD\.work\tools\zulu21.52.203-ca-jdk21.0.12.1-win_aarch64"
$env:GRADLE_USER_HOME = "$PWD\.work\gradle-home"
$cmake = 'C:\Program Files (x86)\Microsoft Visual Studio\18\BuildTools\Common7\IDE\CommonExtensions\Microsoft\CMake\CMake\bin\cmake.exe'
$sdk = "$PWD\.work\webview2-sdk\extracted"
rtk proxy .\gradlew.bat :desktopApp:desktopTargetInfo '-Pbitchord.arch=arm64'
rtk proxy .\gradlew.bat :desktopApp:validateArm64Dependencies '-Pbitchord.arch=arm64'
rtk proxy .\gradlew.bat :desktopApp:windowsPortable '-Pbitchord.target=windows' '-Pbitchord.arch=arm64' "-Pbitchord.windowsRuntime=$env:JAVA_HOME" "-Pbitchord.webview2Sdk=$sdk" "-Pbitchord.cmake=$cmake"
rtk proxy .\gradlew.bat :desktopApp:run '-Pbitchord.arch=arm64' '-Pbitchord.dataDir=.work/dev-data' "-Pbitchord.webview2Sdk=$sdk" "-Pbitchord.cmake=$cmake"
```

Output is `desktopApp/build/distributions/BitChord-1.8-beta1-windows-arm64-portable.zip`.
The desktop update checker reads published releases from `SiriusNovyx/BitChord`.
Release assets must include the matching architecture in their names; an ARM64
build does not select an x64 installer as a fallback.
Extract to a writable directory and start `BitChord/BitChord.exe`; the batch
launcher is available for console diagnostics. Both use the bundled runtime,
the existing desktop 512 MiB heap limit, and a sibling `data` directory.
Packaging excludes preferences, caches, credentials and JVM crash logs.
`bitchord.version` changes the archive name.

`windowsPortableAssemble` runs `validateWindowsArm64Runtime` before copying the
runtime. This compiles a small JNI fixture and raises `RPC_E_CANTCALLOUT_ININPUTSYNCCALL`
inside a native exception handler. The selected JVM must allow that handler to
catch it. The Microsoft build above instead terminates in its process-wide
exception filter, reproducing the intermittent WebView2/UI Automation startup
crash without a browser. Azul passes the same fixture. Architecture checks alone
do not establish runtime compatibility. The fixture remains in build output;
it is not an application dependency or an exception suppression mechanism.

`bitchord.arch` accepts `arm64` or `x64`. Native Windows detection works even
under x64 shell/JVM emulation. JNI output directories include the architecture.
An explicit CMake path avoids a daemon's stale PATH.
`bitchord.windowsArm64Natives` overrides the native artifact directory.

Required local JARs:

```text
javacpp-1.5.14-windows-arm64-bitchord.jar
ffmpeg-8.1.2-1.5.14-windows-arm64.jar
onnxruntime-1.29.0-windows-arm64.jar
quickjs-kt-jvm-1.0.14-windows-arm64-bitchord.jar
```

## Rebuild external native components

Preserve input archives and exact source revisions. Scripts verify hashes or
commits before building. The FFmpeg API/native pairing passed decoder tests and
real playback; arbitrary FFmpeg archives are not verified substitutes.

Inputs used here:

- `.work/ffmpeg-arm64/`: JavaCPP 1.5.14 API/ARM64 JARs, FFmpeg 8.1.2-1.5.14 API
  and source JARs, and the BtbN LGPL shared ZIP named below.
- `.work/src/onnxruntime`: v1.29.0 commit
  `2e2543fbe9fae542f921d47a72d21d5a4ef0b710`, with submodules.
- `.work/src/quickjs-kt`: v1.0.14 commit
  `6e51ae878be54f22272381e62118535790ba9940`, with exact submodules.
- LLVM-MinGW 20261006 UCRT AArch64 under `.work/tools/`.

```powershell
$native = "$PWD\desktopApp\native-dependencies\windows-arm64"
$vs = 'C:\Program Files (x86)\Microsoft Visual Studio\18\BuildTools'
$ninja = "$vs\Common7\IDE\CommonExtensions\Microsoft\CMake\Ninja\ninja.exe"
$ff = "$PWD\.work\ffmpeg-arm64"
rtk proxy python desktopApp/packaging/prepare_javacpp_arm64.py "$ff/javacpp-1.5.14-windows-arm64.jar" "$native/javacpp-1.5.14-windows-arm64-bitchord.jar"
rtk proxy powershell -NoProfile -ExecutionPolicy Bypass -File desktopApp/packaging/Build-Arm64Ffmpeg.ps1 -Jdk "$env:JAVA_HOME" -VisualStudio "$vs" -FfmpegArchive "$ff/ffmpeg-n8.1-latest-winarm64-lgpl-shared-8.1.zip" -JavaCppJar "$ff/javacpp-1.5.14.jar" -FfmpegApiJar "$ff/ffmpeg-8.1.2-1.5.14.jar" -FfmpegSourcesJar "$ff/ffmpeg-8.1.2-1.5.14-sources.jar" -OutputDirectory "$native"
rtk proxy powershell -NoProfile -ExecutionPolicy Bypass -File desktopApp/packaging/Build-Arm64Onnx.ps1 -Source .work/src/onnxruntime -Jdk "$env:JAVA_HOME" -CMake "$cmake" -BuildDirectory .work/onnx-build -OutputDirectory "$native"
$quickjsApi = (Get-ChildItem .work/gradle-home/caches/modules-2/files-2.1/io.github.dokar3/quickjs-kt-jvm/1.0.14 -Filter '*.jar' -Recurse | Select-Object -First 1).FullName
rtk proxy powershell -NoProfile -ExecutionPolicy Bypass -File desktopApp/packaging/Build-Arm64QuickJs.ps1 -Source .work/src/quickjs-kt -Jdk "$env:JAVA_HOME" -LlvmMingw .work/tools/llvm-mingw-20261006-ucrt-aarch64 -CMake "$cmake" -Ninja "$ninja" -UpstreamJar "$quickjsApi" -BuildDirectory desktopApp/build/native-quickjs/windows-arm64 -OutputDirectory "$native"
```

These helpers require the listed inputs; they are not installation/download
scripts. ONNX's helper disables upstream unit tests; BitChord's initialization
and inference checks are separate. Preserve corresponding source and notices
when distributing a release.

## Optional native Spotify playback helper

Library sign-in uses the fork's registered public Spotify client and
`http://127.0.0.1:43821/callback`. Native playback has a separate streaming-only
browser grant and `http://127.0.0.1:8898/login`, using the public desktop playback
identity used by librespot. Both must authenticate as the same account. Never
enter a client secret or account cookie for these grants. Native playback
requires Premium; library sign-in alone does not establish that audio works.

Build the helper **before** configuring the Gradle package. Use Rust with the
MSVC target component and the matching Visual Studio compiler/linker installed:

```powershell
rtk proxy powershell -NoProfile -File desktopApp/packaging/Build-SpotifyNative.ps1 -Architecture arm64
```

The script uses the locked dependency graph, static MSVC runtime, PE architecture
validation and complete third-party notice collection. `-Cargo` and `-Python`
accept explicit tool paths; `CARGO_TARGET_DIR` can select a workspace cache.
Use `-Architecture x64` with the x64 MSVC target for a Windows x64 helper.
The preparation step verifies the published librespot-core 0.8.0 crate's SHA256
and applies `native/spotify/patches/windows-arm64-platform.patch` beneath the
ignored `native-dependencies/spotify` directory. The patch corrects the obsolete
Windows CE protocol greeting, which Spotify rejects as "Premium required" on
ARM64. Code remains native ARM64; x64 behavior is preserved. Existing generated
source edits cause preparation to stop. The original crate is cached locally.
Gradle automatically bundles the matching prebuilt helper and notices, or accepts
`-Pbitchord.spotifyHelper=<absolute-helper-path>`. A custom helper must have the
matching architecture and generated notices. The bundled Java module set
includes `jdk.httpserver` for the bounded loopback callback.

Builds without the helper retain library matching and explicitly identify that
behavior in the interface. A native Spotify track never silently falls back to
another recording. Audio downloads, Spotify lossless and native Spotify Automix
analysis are unavailable in this adapter. Confirm the current native playback
verification status in `spotify-desktop-design.md` before distributing it.

## Verify and compare

```powershell
rtk proxy .\gradlew.bat :desktopApp:test :shared:jvmTest :sharedUi:jvmTest '-Pbitchord.arch=arm64' "-Pbitchord.webview2Sdk=$sdk" "-Pbitchord.cmake=$cmake"
rtk proxy python desktopApp/packaging/verify_windows.py --arch arm64 --runtime desktopApp/build/windows-portable/arm64/BitChord/runtime
rtk proxy python -m unittest discover -s desktopApp/packaging -p 'test_*.py'
rtk proxy $cmake --fresh -S desktopApp/native/window -B .work/window-tests-arm64 -G 'Visual Studio 18 2026' -A ARM64 -DBITCHORD_TARGET_ARCH=arm64 -DBITCHORD_BUILD_WINDOW_TESTS=ON -DCMAKE_MSVC_RUNTIME_LIBRARY=MultiThreaded
rtk proxy $cmake --build .work/window-tests-arm64 --config Release --target bitchord_window_tests bitchord_audio_tests
rtk proxy .work/window-tests-arm64/bitchord_window_tests.exe
rtk proxy .work/window-tests-arm64/bitchord_audio_tests.exe
rtk proxy powershell -NoProfile -File desktopApp/packaging/Measure-DesktopPerformance.ps1 -ProcessId <application-process-id> -Scenario lyrics -Samples 30 -IncludeChildProcesses -IncludeSystemGpu -OutputDirectory .work/performance/run-name
rtk proxy powershell -NoProfile -File desktopApp/packaging/Test-DesktopGpuCounters.ps1
```

Use the PID owning the window, rather than jpackage's parent launcher. Include
children to count WebView2. The collector records CPU cores, memory, per-engine
GPU counters, window/minimize/foreground flags and available battery status.
Each sample takes approximately two seconds. It does not integrate energy,
measure frame time or detect complete occlusion. Keep track/position, lyrics
source, view, size, quality, warm-up and power state equal throughout paired
runs. With `-IncludeSystemGpu`, `systemGpu` groups all process/context shares
on the same physical engine, keeps adapters and independent engines separate,
and reports the busiest engine. `appGpu` applies the same calculation to the
application/child snapshot. Raw values above 100% remain available alongside
clamped percentages; unavailable counters remain null. Contributor names help
distinguish BitChord from `dwm`, but DWM's shared composition load cannot be
causally assigned to one application from those counters alone. The original
`gpuEngines` field remains app-only for compatibility and includes each counter's
status. A partial PDH error retains valid contexts and is recorded in that
sample's `gpuError`; later samples are retried. Missing counters remain missing,
rather than being reported as zero. Summary/collection regression checks cover
engine grouping, duplicates, fractional values, missing data and partial errors.

For direct battery-driver readings, build the optional measurement helper:

```powershell
rtk proxy powershell -NoProfile -File desktopApp/packaging/Build-DesktopBatteryProbe.ps1 -Architecture arm64 -Cmake $cmake
rtk proxy powershell -NoProfile -File desktopApp/packaging/Test-DesktopBatteryCounters.ps1
rtk proxy powershell -NoProfile -File desktopApp/packaging/Measure-DesktopPerformance.ps1 -ProcessId <application-process-id> -Scenario lyrics -Samples 30 -IncludeChildProcesses -IncludeSystemGpu -BatteryProbe .work/measurement-tools/windows-arm64/Release/battery_probe.exe -OutputDirectory .work/performance/run-name
```

This requires the same Visual Studio 2026 ARM64 tools and CMake used above;
`-Architecture x64` builds the corresponding x64 helper. It is a measurement
tool outside the application package. It opens battery interfaces with read
access and issues only query IOCTLs. No power settings or battery information
are changed. `batteryNative` retains per-battery capability and status fields.
Rates are labeled in milliwatts only for valid, non-relative system batteries;
relative units, unknown-rate sentinels and unavailable probes remain null.
Positive rates indicate charging and are not treated as device consumption.
Even a discharge reading while AC is connected measures battery contribution,
not total wall-socket power. The collector does not integrate energy.

Windows composition can also be investigated with the startup JVM option
`-Dbitchord.window.opaque=true` in the portable launcher's `[JavaOptions]`
section (`java-options=-Dbitchord.window.opaque=true`). Restart is required.
This diagnostic creates an opaque window and makes Mica/Acrylic unavailable
for that process; removing it restores the saved material on the next launch.
Direct3D remains enabled. Both startup paths were inspected on ARM64 and lyrics
comparisons are recorded in the performance report; energy validation is still
pending. It is not a
validated battery-saving recommendation, and the default remains unchanged.

Efficient reduces decorative motion, canvas decoding and blur. Balanced and
Performance preserve foreground effects. All modes suspend purely visual
canvas work while hidden or minimized. Audio quality, playback, downloads and
analyzer settings are independent. Covered-but-visible windows are not yet
detected. `bitchord.performance` can override the saved mode.
Within the application, a full-player sheet marks the covered desktop page
visually inactive, so its playing indicators stop requesting animation frames.
External-window occlusion is a separate, unimplemented detection case.

HLS motion artwork now selects one rendition covering the existing 540-pixel
rendering edge rather than probing every rendition and decoding 2160-pixel
input. The selection uses standard `EXT-X-STREAM-INF` attributes from
[RFC 8216](https://datatracker.ietf.org/doc/html/rfc8216#section-4.3.4.2).
The native decoder also discards unused streams, as supported by
[FFmpeg's HLS demuxer](https://ffmpeg.org/ffmpeg-formats.html#hls).
Unknown manifests or a failed selected rendition retain the original fallback.
This path handles silent artwork only; music decoding and output quality are
unchanged. Startup latency and reduced requests are measured separately from
energy use.

For x64, use a Windows x64 JDK 21 and `-Pbitchord.arch=x64`. ARM64-host
LLVM-MinGW can build the x64 bridges using its explicit
`x86_64-w64-mingw32-g++.exe`. Set `bitchord.mingwCompiler` and `bitchord.ninja`
if they are not on PATH. The build checks the compiler target triple and rejects
an ARM64 `g++` for x64 output.

PE checks establish architecture, not successful execution. Current evidence
includes an ARM64 window, native FFmpeg/WASAPI streaming, lyrics, queue, output
selection, SMTC pause/resume, hidden background playback and the app's Quit
callback. Account sign-in was completed manually by the user in a separate test
copy. Every codec/device, Android/macOS/Linux execution,
installers and controlled battery-life improvements need further verification.
No bit-perfect output claim is made.
