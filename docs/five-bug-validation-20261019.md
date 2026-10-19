# Five bug fixes and selective upstream integration

## Scope

This update extends PR #1's development head
`efc2079d18847efe06798ecca4878fd3731d6e09`. The upstream import and subsequent
repairs are separate commits. The source downloaded before integration is
preserved; build tools, account data, caches and generated packages are excluded.

The reviewed upstream snapshot is
`edc1e6f36febf71a118e649411d2fc21b70ead45`, with common ancestor
`fe95bb60aa9cd205cf4ab8bd25a5bec71a595b4f`. The audit examined 222 upstream-only
commits and seven development-only commits. It selected 300 upstream paths and
added one backend regression test. Of the initial 301 paths, 298 match upstream
exactly and three contain compatibility changes or the new test. Two selected
files subsequently receive focused AutoMix repairs in the second commit.

The independent web workspace is included without installing its dependencies.
The desktopUi module migration, TV/Wear build changes and overlapping build
configuration changes remain deferred. Java 21 / Gradle 8.11.1 remains the
baseline; Java 25 / Gradle 9.5.1 / AGP 9.3.3 remains an opt-in build candidate.
Desktop application bytecode stays at Java 21; shared modules stay at Java 17.

## Fixes

1. **Desktop network recovery:** distinguish temporary starvation, EOF and
   transport failure; cancel stale opens and seeks; retry bounded HTTP ranges
   and reopen a fresh source at the last heard position. Playback can be retried
   explicitly after automatic recovery is exhausted.
2. **Heard-audio timeline:** retain partially accepted PCM, count accepted output
   frames and hold the playback clock after buffered audio drains. Crossfade
   recovery retains incoming audio ownership and the following queue item.
   Shutdown waits for cancelled opens and decoder-owned cleanup within its
   existing deadline. Native state is freed only by its owner.
3. **Lyrics mode:** persist Original/Translated/Romanized independently of the
   current song. New lyrics request the selected operation automatically.
   Cancellation rejects stale results, and shared requests preserve each
   caller's timestamps. Errors retain the preference and show original lyrics.
4. **Discord startup/reconnect:** restore the saved enabled setting through one
   cancellable session, retain the current activity, and reconnect if Discord
   starts later or its pipe disappears. Windows pipe operations have bounded
   deadlines; READY and activity acknowledgements are checked. Seek anchoring
   remains intact.
5. **AutoMix status:** separate source eligibility from analysis progress. Show
   absent, pending source, unsupported source/video, unavailable analyser,
   no-match, queued, active, measured and refining states truthfully. Cached
   analysis cannot override unsupported eligibility. Paused queue changes update
   without starting playback. Six new messages are supplied in 18 locales.

## Validation on Windows ARM64

| Check | Result |
|---|---|
| Shared JVM tests | 58 passed |
| Shared UI JVM tests | 46 passed |
| Desktop tests, native ARM64 Java 21 | 468 passed |
| Desktop tests, native ARM64 Java 25 | 468 passed |
| Android Dev debug unit tests | 999 passed |
| Windows x64 production Kotlin compilation | Passed; fresh x64 execution not tested |
| Backend tests and vet | Passed in the import batch |
| Android lint | Failed: 199 errors, 608 warnings, five hints |

Lint was compared with the development baseline (202 errors, 580 warnings,
five hints). No new errors were identified. The lyrics preference setter adds
one UseKtx style warning relative to the import checkpoint. Existing findings
were not suppressed. Web dependency installation, TypeScript/Vitest builds,
Linux/macOS execution and remote CI remain unverified.

The 19 October validation portable ZIP was checked for 80 standalone ARM64 PE
files and 34 selected ARM64 natives inside JARs. Its Dev APK contains eight
ARM64 ELF libraries. Representative repaired classes were compared with compiled
outputs. No portable account data is packaged. Native startup created a
responding window using the bundled ARM64 Java 25.0.4.1 runtime and loaded Skiko,
Windows integration and WebView bridges.

The user confirmed Thai translation remains selected across song changes and
closing/reopening the Windows player. On an authorized ARM64 Android API 37
device, the Dev APK installed without clearing data, launched and played audio.
The user confirmed audible playback after Bluetooth disconnect/reconnect.
Targeted Dev media-session checks verified pause, resume and next with no
reported session error.

The existing validation ZIP/APK predate the subsequent shutdown ownership fix;
their manifests and hashes remain preserved. They must not be described as
containing that follow-up. The two shutdown regressions failed before the fix;
the 468-test desktop runs above include them after the fix.

## Remaining acceptance checks

- Real network interruptions while listening, including seek and crossfade.
- Live lyric timing, changing translation target and full process relaunch.
- Real Discord cold startup, delayed startup, reconnect and seek display.
- AutoMix completion while paused and the status pair after an actual blend.
- Shutdown when native work exceeds its deadline. Analyzer/ONNX teardown is
  outside the decoder wait guarantee.
- Paired full-player/lyrics GPU and battery measurements. No percentage
  improvement or battery savings is claimed for these repairs.

## Reproduce

Use the existing ARM64 native preparation described in
[windows-arm64.md](windows-arm64.md) and the opt-in modern build setup in
[desktop-java25.md](desktop-java25.md). With those prerequisites configured:

```powershell
.\gradlew-modern.bat :shared:jvmTest :sharedUi:jvmTest :desktopApp:test '-Pbitchord.modernBuild=true' '-Pbitchord.arch=arm64'
.\gradlew-modern.bat :desktopApp:java25DesktopTest '-Pbitchord.modernBuild=true' '-Pbitchord.arch=arm64'
.\gradlew-modern.bat :app:testDevDebugUnitTest '-Pbitchord.modernBuild=true'
.\gradlew-modern.bat :app:lintDevDebug '-Pbitchord.modernBuild=true'
.\gradlew-modern.bat :desktopApp:windowsPortable '-Pbitchord.modernBuild=true' '-Pbitchord.target=windows' '-Pbitchord.arch=arm64' "-Pbitchord.windowsRuntime=$env:JAVA_HOME"
.\gradlew-modern.bat :app:assembleDevDebug '-Pbitchord.modernBuild=true'
```

Select verified ARM64 JDKs explicitly when multiple architectures are installed.
The build JVM and packaged runtime are independent; using Java 25 for Gradle
does not require Java 25 application bytecode.

## Spotify profile compatibility follow-up

The `/v1/me` parser now accepts responses containing only `id`, including
responses where `account_id` is null or blank. It preserves a nonblank
`account_id` when supplied, following the current
[Spotify profile reference](https://developer.spotify.com/documentation/web-api/reference/get-current-users-profile),
and keeps the validated `id` as the native playback username. Responses
with neither usable identity still fail explicitly. Protected grant
storage, account matching and token refresh are unchanged.

Two new HTTP cases reproduced 502 failures before the fix. All 34 Spotify
tests pass after the fix, and the full desktop suite now passes 468 tests
on each of native ARM64 Java 21 and Java 25. Windows x64 production Kotlin
compilation passed. Three added API tests cover id-only profiles, blank
account_id fallback and missing-identity rejection; the existing dual-field
test retains documented account_id precedence. No saved account data was
read or changed, and a new live OAuth sign-in has not been retested.
