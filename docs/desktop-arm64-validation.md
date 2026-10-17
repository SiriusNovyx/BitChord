# BitChord desktop validation — 16 October 2026

A genuine Windows ARM64 portable build has been produced locally. The native
executable and bundled JVM have run on the Surface Pro 11. The broader objective
remains open: the reported 60% GPU usage and battery drain have not yet been
resolved through a controlled comparison. No commit, push or release publication
was performed, and the existing installed BitChord was not replaced.

Hardware queried here: Microsoft Surface Pro, 11th Edition, Snapdragon X
10-core X1P64100 at 3.40 GHz, approximately 16 GB RAM. Windows reports Pro
Insider Preview, build 26340, ARM64. Power state is recorded with each performance sample.

## Runtime crash found during resumed testing

Repeated native startup checks exposed an intermittent fatal `0x8001010d`
exception through WebView2 and Windows UI Automation. A first-chance diagnostic
recorded only module names/offsets and left exception dispatch unchanged. A
message-only browser parent did not fix it and was reverted. A minimal JNI
fixture then reproduced the crash without WebView2: Microsoft OpenJDK
21.0.12.1+1 terminates before a surrounding native exception handler can catch
the recoverable RPC exception. Its exact build source
[`6f26c6b2b098`](https://github.com/microsoft/openjdk-jdk21u/blob/6f26c6b2b098/src/hotspot/os/windows/os_windows.cpp)
registers the fatal exception filter as a vectored handler on ARM64.

Azul Zulu 21.52.203 / Java 21.0.12.1+1 passes the same fixture, an ARM64 PE scan,
a WebView2 JavaScript roundtrip and ten immediate initialization cancellations.
Its archive hash matches the official vendor metadata. Packaging now runs this
JNI compatibility test and rejects the failing runtime before staging a package.
No JVM signal handlers, browser accessibility or audio features are disabled.
Full application restart and package verification after the runtime replacement
are recorded below when completed; earlier GUI successes alone do not establish
startup stability under the newly reproduced condition.

## Existing implementation and validation

The user first moved the checkout to `E:\Work\BitChord-main`. The project was rebuilt
there with its existing ARM64 JDK and VS 2026 tools. Generated native caches
containing the old source path were preserved under
`.work/relocation-cache-backup-20261015-181542`; fresh native outputs were built
at the new location. The running portable copy in Downloads was not replaced.
The relocated build reran all 43 ARM64 tasks successfully. The x64 desktop
suite and explicit shared-suite rerun passed there as well. Native window/audio
fixtures passed again with MSVC ARM64, MSVC x64 and LLVM-MinGW x64; backend
`go test ./...` and `go vet ./...` also passed from the moved checkout.

The current checkout is back at `C:\Users\Admin\Downloads\Compressed\BitChord-main`.
The WebView and x64 window configurations reproduced stale CMake cache failures
after this move. Both now configure with `--fresh`, consistent with the analyzer
and ARM64 window tasks. The ARM64 portable rebuild and x64 tests passed from C:.

The saved lyrics translation language was not connected to the shared desktop
player: its flow always started empty. It now reads `DesktopTranslationSetting`
directly. A regression failed with expected `th`, actual blank before the change,
then passed for Thai, Japanese and restoring the app-language default. Test
workers use an isolated portable preferences directory. In the rebuilt native
GUI, selecting Thai produced Thai lyric sub-lines and `Translated to Thai`.
No signed-in user copy was replaced. Browser-authorized Spotify library access
and native playback now work on the Surface; see [the implementation record](spotify-desktop-design.md).
The native ARM64 session authenticated, decoded nonzero PCM and played a Spotify
playlist through the existing output path. Pause/resume, seeking, manual Next
and natural queue advancement were exercised. The user confirmed audible sound
and continued sound after Bluetooth disconnect/reconnect. The earlier false
Premium rejection came from librespot's obsolete Windows CE handshake platform;
a checksum-pinned core patch fixes that protocol value while retaining native
ARM64 binaries and CPU reporting. Authenticated x64 Spotify playback remains untested.

The requested WDB Bangna font is bundled unmodified with its notice and is used
for a Thai app locale or Thai lyric translation text. Font family, Thai combining
character coverage and packaged font hash checks pass. A live Thai selection
showed the requested typography. A subsequent restart exposed two catalogue
defects: initialization used the escape regex before it existed, and language
notifications preceded the new catalogue. Both now have failing-before/passing-after
regressions, including a fresh isolated classloader starting with saved Thai.
The rebuilt portable GUI subsequently restarted with saved Thai and displayed
the Thai Settings and account/integrations catalogue with the requested font.
After returning the app locale to the user's system-default selection, translating
English lyrics displayed Thai sub-lines with WDB Bangna and `Translated to Thai`.

Discord's publication key now changes on forward/backward seeks and audio
readiness transitions while normal playhead ticks retain the same key. The
regression passes; a real Discord card's elapsed time after seeking is not yet
verified. Line-only lyrics now wake at upcoming timing boundaries; word timing
and visible animated gaps retain frame updates. Forty-one shared UI tests pass,
including the covered playing-bars frame-waiter regression.
The subsequent LRCLIB A/B/A comparison found no meaningful app-GPU reduction;
see the performance record. No energy improvement is claimed.

The desktop remains Kotlin/JVM with Compose and shared Kotlin Multiplatform
data/UI. FFmpeg decodes audio; ONNX and shared native DSP support Automix;
Windows JNI supplies window integration and SMTC, with WASAPI audio output.
Android retains its existing Media3 path. Windows-specific browser code is
confined to the desktop module.

The pinned JavaFX 21 and JavaCPP/FFmpeg 7.1.1 artifacts could not supply the
required ARM64 binaries. ARM64 now uses WebView2, matching FFmpeg 8.1 APIs and
locally built JNI, source-built ONNX 1.29.0, and source-built QuickJS. The native
Graal optimized runtime is unavailable on this target; its supported interpreter
is used. x64 production dependencies retain their original versions. Details,
source revisions and licenses are in [the native component record](windows-arm64-third-party.md).

| Area | Implementation |
|---|---|
| Target/build | Explicit ARM64/x64 selection, native Windows detection under emulation, separate outputs, PE checks, and rejection of a mismatched runtime for desktop execution/tests |
| Windows ARM64 | VS 2026/MSVC ARM64 analyzer, SMTC, window and WebView2 bridges; native JDK 21 and Skiko |
| Browser | WebView2 adapter for ARM64 BotGuard and sign-in flows; bounded callbacks, isolated profiles, native COM thread lifetime fix; JavaFX remains on other platforms |
| Codecs/analysis | Verified JavaCPP core preparation; generated FFmpeg JNI; ONNX Java JNI and both models; QuickJS engine/JNI |
| x64 compatibility | Explicit compiler target-triple checking; static MinGW C++ runtime for the analyzer, correcting a reproduced missing-DLL failure |
| Visual policy | Efficient/Balanced/Performance settings; hidden/minimized decorative work pauses independently of audio; Efficient reduces motion, canvas work and blur |
| Covered content | Search/list playing bars stop requesting frames beneath the full player and resume when exposed; their visible design and audio behavior are preserved |
| Artwork | One HLS rendition covering the existing 540-pixel render edge; bounded metadata cache; discard unused native streams; preserve fallback behavior |
| Desktop controls | Ctrl+F and Ctrl+Comma, player-sheet keyboard focus, direct performance controls, corrected DPI sizing and native mouse resize edges |
| Portable data | Atomic file-backed preferences and app-owned storage beneath `data`; JavaCPP/Skiko extraction and Java temporary files stay there; normal OS storage remains the default for installations |
| Backend | Connection-identity-aware cleanup, bounded immutable FIFO socket writes, write deadlines, graceful bye delivery and regression tests |

Changed files include `desktopApp/build.gradle.kts`, `buildSrc` target/PE helpers,
native CMake and WebView2/QuickJS sources, preparation/validation scripts,
desktop browser/storage/visual-policy adapters and tests, backend hub/cleanup
and regressions, `DESKTOP.md` and the ARM64 documentation. Shared production
source now includes Thai lyric font support and boundary-based line timing; the
release workflow was not changed. The local source-difference
inventory is [the changed-file list](desktop-arm64-changed-files.md); it compares this checkout to the
original downloaded ZIP rather than a Git working tree, since Git metadata is
absent.

| Verification | Result |
|---|---|
| ARM64 desktop suite | 402 tests, zero failures/errors, including translation settings, Thai catalogue startup/notification, font coverage, Discord seek revision and Spotify radio isolation |
| x64 desktop suite | 402 tests, zero failures/errors, including the final covered-page scope, catalogue and Spotify radio changes |
| Shared JVM suites | 46 shared and 41 shared UI tests, zero failures/errors; final x64 tasks passed as well (unchanged shared outputs were up to date) |
| Whole-GPU summary checks | Shared-engine grouping, adapter separation, duplicate contexts, fractional values, invalid/missing values, raw overlap and partial PDH collection errors passed |
| Direct battery measurement checks | ARM64/x64 helper binaries built and their PE architecture verified; both query the Surface driver. Unit/JSON checks and a two-sample collector smoke pass. These are measurement checks, not an energy benchmark |
| PE/parser regressions | Eight tests pass, including malformed/renamed binaries, universal JNA/SQLite resources and executable resources in JARs |
| Spotify source preparation | Four tests pass for pinned checksum validation and strict patch applicability; helper tests and serialized ARM64/x64 handshake regressions pass |
| Scoped source review | No actionable findings in covered-bar gating, partial GPU-counter handling, or recent Spotify auth/PCM/source-preparation paths; GPU and four preparation checks rerun independently. This does not establish release readiness |
| Target-selection checks | Six checks pass, including an emulated JVM/native-OS mismatch and explicit x64 override |
| Runtime guard | Expected rejection: x64 JVM, ARM64 desktop target, before native initialization |
| Backend | Full `go test ./...` and `go vet ./...` pass; replacement/queue/slow-socket/bye regressions also pass |
| Native DSP | ARM64 and x64 bridges load; 44.1 kHz to 11.025 kHz JNI resampling succeeds |
| Window frame regression | Parent edges/corners, covered canvas edges, normal client input and later-created canvas pass with MSVC ARM64, MSVC x64 and LLVM-MinGW x64 |
| Audio discovery/recovery regressions | Cached discovery, coalesced refreshes, playback-lock independence, invalidated-client reopening and bounded stalled-buffer waits pass; second hardware reconnect cycle passed |
| FFmpeg | Native initialization; FLAC, ALAC, PCM 24-bit, MP3, AAC and Opus decoder availability; real audio decoder tests; native video pixel-channel and EOF-loop test on both architectures |
| ONNX | Both shipped beat and vocal models initialize and produce inference outputs in an ARM64 JVM |
| QuickJS/Graal | Native QuickJS JavaScript/Promise execution and restricted Graal module test pass |
| WebView2 | Native page/JavaScript callback roundtrip and repeated early-start cancellation checks pass; real BotGuard/stream token generation works |
| Portable graphics cache | Fresh Skiko initialization extracts/loads the ARM64 library below the selected portable data directory |
| Native package | AA64 launcher/JVM/renderer verified; fresh earlier ZIP extraction launched, created its data directories and loaded ARM64 application libraries |

The fresh native process was identified as AA64 using Windows
`GetProcessInformation(ProcessMachineTypeInfo)`. The x64 comparison process was
identified as AMD64. `IsWow64Process2` alone was inconclusive here: it returned
UNKNOWN for both. Twenty-two loaded executable/runtime/application modules in
the fresh native process were inspected as ARM64, including Skiko, JNA, QuickJS
and the Windows bridges. Separate dependency checks found 14 FFmpeg, eight
prepared JavaCPP, three ONNX and one QuickJS DLLs matching ARM64.

Real ARM64 playback resolved public music, decoded it and fed native WASAPI
through the Qualcomm Aqstic speakers at 48 kHz stereo. Synchronized lyrics,
queue display, choosing the available output, SMTC pause/resume and closing
to the tray were exercised. The application's own Quit callback exited the
test process. This does not verify every source, playlist operation, physical
device, high-resolution output path or bit-perfect hardware output.

The latest HLS/cache refinements passed component and regression checks. After
the user enabled Computer Use access, the portable EXE was extracted afresh
and tested. Search, playback, seeking, synchronized lyrics, visual mode chips,
minimize/restore and system media pause/resume worked. AutoPlay advanced to the
next track while minimized. The application Quit callback exited the test
process; that callback was invoked by a diagnostic agent, rather than the tray UI.

Fresh-window Ctrl shortcuts initially failed until a control received focus.
The desktop frame now takes keyboard focus when its window becomes active and
none of its controls has focus. A fresh rebuilt ZIP verified Ctrl+comma opening
Settings, Ctrl+F focusing Search, typing a search without a mouse click, and
Escape returning to Home. The latest desktop suite passed 357 tests with no
failures/errors. Queue dragging changed the order and AutoPlay followed it.
A local playlist was created with Opalite and retained its track and album
metadata after a clean shutdown and relaunch. Maximize and restore changed
the window dimensions and kept the playlist layout usable. Initial mouse edge
drags did not resize that version; the later diagnosis and correction are below.
No credentials were entered by automation. Touch input and playlist
rename/removal need further checks.

Later the user confirmed manually signing into YouTube Music in the native
portable copy. The account playlists became visible. This is user-reported
sign-in plus an observed signed-in UI, not an automated authentication test.
That copy and its account data were then preserved. A separate fresh ZIP
extraction started signed out with no inherited playlists, and was used for
the remaining AC samples; only that benchmark copy was quit afterwards.

The final compact-layout package downloaded Opalite into its own portable
`data/downloads` folder (3,911,923-byte WebM). The item appeared in Downloads,
survived a clean Quit and relaunch, and played from that page with the caption
"Playing from Downloads", advancing its playhead and feeding WASAPI. Windows
network access remained enabled, so disconnected operation was not tested.
The app also switched live output from the Huawei FreeBuds Pro 5 headphones to
the Qualcomm Aqstic speakers and back. Both opened at 48 kHz stereo with
nonzero output samples. No claim about acoustic fidelity or bit-perfect output
follows from those checks.

A later live geometry check confirmed an existing DPI conversion bug in
`Main.kt`: at 150% scaling, the requested 1220x780 logical window was set to
1830x1170 AWT units and then scaled again to 2745x1755 physical pixels. The
minimum was similarly enlarged to 1350x900 logical units. The extra transform
has been removed. A runtime check first failed against the old ZIP, then passed
against a fresh rebuilt extraction: 1220x780 logical / 1830x1170 physical,
with a 900x600 logical minimum. All 357 desktop tests passed after this change.
Native keyboard sizing (Alt+Space, Size, arrows, Enter) committed a width
change from 1220 to 1209 logical units and the layout followed it.

The user then confirmed that real mouse resizing also failed. Native hit tests
showed the parent reporting resize edges while its covering `SunAwtCanvas`
reported normal client input at the same points. The window bridge now
subclasses that canvas on its owning native thread and passes only resize-edge
hit tests through to the frame. Ordinary content input retains its existing
handler. A hidden native regression fixture failed on all eight covered edges
before the fix and passed afterwards, including a subsequently created canvas.
It passed with MSVC ARM64, MSVC x64 and LLVM-MinGW x64.

A fresh packaged ARM64 EXE then passed real mouse edge resizing from 1220x780
to 1020x780 logical units, followed by corner resizing to 960x700. Maximize and
restore retained the resized dimensions, and the compact navigation appeared.
The resize change uses Windows' existing common-controls library.

That compact layout exposed an existing search bug: hiding the sidebar also
removed the only search field. The frame now passes its compact state to the
page, which shows the shared search field when the sidebar is absent. Ctrl+F
opens Search and requests focus. A fresh rebuild at 960x700 verified typing,
suggestions, submitted results and playback. All 357 desktop tests passed.

The user subsequently reproduced a freeze by connecting/disconnecting Bluetooth
headphones in the separate test copy. Repeated thread snapshots found its AWT
UI thread inside `DesktopWindowsAudio.nativeDevices`; the signed-in copy's UI
thread was idle normally. Discovery ran synchronously during composition and
shared the native playback mutex and enumerator. The exact Windows internal
wait was not resolved from Java stacks.

Device discovery now runs on one background coroutine, publishes a cached
snapshot and coalesces pending refreshes. Driver failures retain the last list.
Native discovery uses a private enumerator, independent of the playback mutex;
endpoint notifications have a separate process-lifetime enumerator. UI device
name reads use a nonblocking snapshot. Notifications remain enabled from audio
startup, including before the output picker is opened.

Two Kotlin regressions passed, including a blocked discovery and 100 pending
refresh requests. A native fixture with the playback lock held passes on
ARM64/x64; a scratch negative fixture restoring the shared discovery lock fails
as expected. A real Bluetooth disconnect/reconnect on the rebuilt EXE remains
the acceptance check; previous successful manual output selection did not
establish that hotplug was reliable.

The first hardware retest kept the UI responsive, but playback stalled around
45 seconds after reconnect. Pause/seek requests reached the app while its audio
thread remained in `nativeWrite`. The existing native loop could retain an
invalidated client when its endpoint ID still matched, or wait indefinitely for
a full buffer. Invalidation now forces client recreation. One second without
write progress returns control to the playback pump and schedules a forced
reopen, allowing queued pause/seek/quit commands to run. Successful writes reset
that timer; normal sample rates, formats and buffer size are unchanged.

A fake WASAPI client reproduces invalidated padding, invalidated buffers and a
permanently full buffer without playing test audio. The recovery tests pass with
MSVC ARM64, MSVC x64 and LLVM-MinGW x64. In the second hardware reconnect test,
the user confirmed that sound, pause/resume and seeking kept working. The
rebuilt native player's playhead subsequently advanced beyond 2:11; its output
caption showed the Huawei headphones, and logs recorded completed seeks after
the reconnect. This verifies the exercised cycle, rather than every Bluetooth
device/driver or a long stress test. The first retest remains a recorded failure.

The update checker also had a hard-coded x64 installer suffix. Six architecture
selection tests were added; four failed against that behavior. Selection now
matches the running JVM architecture, accepts ARM64 installer/MSI/portable assets
and never substitutes an x64 asset when an ARM64 asset is absent. Existing x64
installer selection and Linux behavior are preserved. Publishing an ARM64
update was not tested. At the user's request, the desktop update endpoint now
targets `SiriusNovyx/BitChord`. All six selection tests pass. The physical
reconnect result was obtained with the earlier audio-recovery package. After
the checkout moved, native binaries were rebuilt and no longer have identical
bytes; native window/audio regression tests passed again. The physical
reconnect cycle has not been repeated with this rebuilt binary.
The fork's public releases collection was empty when checked, and its latest
release endpoint returned HTTP 404. The checker treats that as no available
update. No changes were pushed to the fork and no release was published.

The active native policy was inspected during artwork checks: Balanced,
visible, not minimized, and animatedCanvas=true. Catalogue cache entries
contained motion artwork, but the captured landscape player continued to show
its static cover. No whole-app animated-artwork performance result is claimed.
The separate live HLS decoder and local MP4 loop checks remain valid, but do
not establish that the player presented moving artwork in this UI session.

A paused-seek check reproduced another desktop integration bug: the app moved
from 1:03 to 2:06 while the Windows media timeline stayed at 63,760 ms. The
Windows publishing effect now reacts to the engine's seek counter, separately
from scrobbling state notifications. After rebuilding, a paused seek to 2:11
published 131,941 ms to Windows. The system Previous command restarted the
track at zero and system pause/resume returned paused status 5 then playing 4.
The desktop suite again passed all 357 tests. This verifies the system API
integration, rather than physical keyboard media-key input.

Performance evidence is deliberately separated by what was measured:

| Scenario | Observation | Limits |
|---|---|---|
| Original x64, idle home | 20 foreground samples: CPU cores used 0, GPU 3D 0% | Isolated preferences; first baseline analyzer runtime gap was subsequently repaired; no active playback/analysis in this sample |
| Earlier native ARM64, synchronized lyrics | 20 foreground samples: mean CPU 0.3065 cores, aggregate app/child GPU 3D 33.192%, private memory 1693.9 MiB | Exploratory older batch run before aligning its heap limit; not paired with x64 |
| Earlier native ARM64, hidden playback window | 10 samples: CPU 0, GPU 3D 0.003% | Different track/position from the lyrics sample; closing-to-tray case, not a matched before/after |
| HLS startup on ARM64, before selection | Three runs: 17.37, 17.49, 48.43 seconds to decode ten frames; median process CPU 0.9531 seconds | Remote network variability; same source master; original decoder selected 2160-pixel input |
| HLS startup on ARM64, after selection | Three runs: 1.30, 1.35, 1.51 seconds; median process CPU 1.1719 seconds | 768-pixel input rendered at the same 540-pixel edge; extra cold Java HTTP initialization costs CPU |
| Longer HLS decode | Selected rendition decoded 100 further frames in 0.40 seconds, using 0.2813 CPU seconds | Original multi-rendition run stalled and was stopped; this is not a completed paired steady-state benchmark |

The HLS startup change probed one rendition rather than 28. Median wall time
improved from 17.49 to 1.35 seconds; cold CPU time increased by approximately
0.22 seconds. Captured frames preserve the artwork and framing but are not
pixel-identical, since different compressed source renditions are decoded.
The selected-rendition run did not reproduce the original's unneeded-rendition
NAL/parser errors. This supports reduced network/probing work and startup
latency, not a measured reduction in battery energy or whole-application GPU use.

The user subsequently unplugged the charger and Windows confirmed battery
discharge. Initial native/x64 comparisons now cover idle, static cover,
synchronized lyrics and minimized windows. The detailed measurements and
limits are in [the performance record](desktop-performance-results.md).
Native execution alone did not lower GPU use in the foreground views; Efficient
mode reduced native lyrics GPU use in the first comparison. Battery telemetry
is recorded per sample, but it is whole-device data and its absolute-unit
capability has not been independently verified. There is no established
battery-life gain. Complete occlusion is not detected by the visual policy.
The user clarified that the reported 60% was whole-GPU usage in the full player
or lyrics view. New native lyrics measurements reproduce 72.35% whole-GPU mean
(23.54% BitChord, 47.89% DWM contribution on the same engine). Earlier app-only
samples cannot establish absence of that symptom. DWM's shared composition
workload is not yet causally isolated. Details and scope limits are in
[the performance record](desktop-performance-results.md#whole-gpu-scope-correction-and-minimized-lyrics-15-october).

A minimized native lyrics view still requested about 60 draw callbacks/second
even when the desktop policy reported visual activity false. The shared
lifecycle helper now also reads host visual activity, supplied by the desktop
window. A Compose runtime test failed before this connection and passed after
it, including hide/show and lifecycle stop/resume. A fresh packaged EXE restored
with advancing lyrics, responsive pause/resume and seeking. Its warm minimized
probe recorded 8.40 draw callbacks/second; residual rendering remains. A
separate minimized capture averaged 1.35% app GPU, but power state, track and
other-window load differed from the earlier run. These are not controlled
energy comparisons or a resolution of foreground compositor cost.

Run/package commands and external native preparation commands are in
[Windows ARM64 build and verification](windows-arm64.md). The current output is:

```text
desktopApp/build/distributions/BitChord-1.8-beta1-windows-arm64-portable.zip
```

Current baseline ZIP verification: 398,877,166 bytes; 80 executable/runtime EXE/DLL files
and 34 selected native resources inside JARs (33 DLLs and the Spotify EXE)
verified as ARM64. Packaged JARs match
the staged build. SHA256:

```text
6077d33c749069eedac2a87e71f69c158e56fe540c5c62fab32189c339613c03
```

Extract to a writable folder and run `BitChord/BitChord.exe`. Java 21 is bundled;
the ARM64 Edge WebView2 Runtime remains an installed prerequisite. The ZIP
allowlist excludes test/user data and crash logs. Prefer the ZIP for now:
ARM64 MSI/setup installers and release CI have not been validated. The Android
SDK is now installed; the AGP 9.3 candidate produced debug APKs for ARM64,
ARMv7, x64 and universal packaging. Native ELF architecture and Java 17 bytecode
checks passed. After correcting pre-existing localization defects, all 971 app
tests and 39 shared Android host tests pass, as does production release assembly.
Android 17 ARM64 development-app playback is device-tested as detailed below.
macOS/Linux execution is unavailable
on this host. Go's race detector is unsupported on Windows ARM64.

Next verification: isolate the heavy whole-GPU composition workload and complete
whole-app animated-artwork and calibrated energy checks. Repeat paired workloads
at the corrected window size; retain the original-size samples as a separate
dataset. Keep source, quality, dimensions, warm-up and background load equivalent.
Finish touch and remaining platform/installer checks before treating this as a
fully validated release. No battery-life gain or release readiness is claimed.

The latest covered-animation comparison isolated one contributor: with LRCLIB
line timings and Search underneath the player, the clean fixed sample averaged
52.40% whole GPU / 6.21% app GPU, versus 63.02% / 18.52% before and 64.11% / 18.57%
after restoring the old packaged JARs. All had 15 foreground battery samples.
An additional fixed run was affected by another compute workload and is recorded
separately. Render probes fell from 59.99 to 13.66 draw callbacks per second.
This verifies a reduction for that exercised path; DWM cost, word timing and
calibrated battery savings remain open. See the detailed performance record.

## Build infrastructure candidate

The opt-in `gradlew-modern` entry uses native ARM64 Azul Java 25, Gradle 9.5.1,
AGP 9.3.3 and Kotlin 2.4.21. The original four wrapper files and default plugin
versions remain unchanged. Android uses built-in Kotlin and the new Android KMP
library plugin without legacy opt-outs. The candidate aligns library compilation
to API 37 for the existing InnerTubeX requirement. Android minimum 26, target 36,
shared/Android bytecode 17 and desktop bytecode/minimum runtime 21 remain intact.

The candidate passed 402 desktop tests on native ARM64 Java 21 and the same
402 on Java 25, plus 46 shared and 41 shared UI JVM tests. Windows x64 desktop
tests and portable assembly passed; 139 selected JAR natives passed the x64 scan.
The ARM64 native bridges and runtime exception probe passed on the new build JVM.
The resulting Java 21 package passed 80 executable/runtime and 34 selected JAR
native architecture checks. Its preserved archive is
`BitChord-1.8-beta1-windows-arm64-modern-build-java21-portable.zip`,
398,926,131 bytes, SHA256
`c97de23afce1137bdc97355ce9cb6d7420add3e5fee8b13b7b17856e99c08cea`.

A separate test copy's 116 JARs and launcher matched that ARM64 stage. Its prior
launcher options, Java 21 runtime and data were retained, with old executable,
configuration and JARs backed up. Native startup, Home, recent-search playback,
full-player/lyrics rendering and graceful Alt+F4 shutdown were observed. No new
audibility, Bluetooth or power claim is inferred from this smoke check. Java 25
application runtime migration remains deferred behind build validation.

The original Java 21 / Gradle 8.11.1 ARM64 build was rerun after these changes:
all 44 tasks succeeded, including tests and portable packaging. The baseline
Android build fails at SDK discovery because AGP 8.10.1 cannot resolve the
installed `android-37.0` platform. The initial 17 candidate app test failures concerned
Chinese locale folder/tag mismatches and missing language labels in original-ZIP
source. Those defects are now corrected with strict coverage assertions retained. See
[the migration record and commands](desktop-java25.md).

After aligning the candidate libraries to compile SDK 37, the Android debug
assembly and tests were rerun: all 971 app tests and 39 shared Android host tests
pass after the localization fixes. Debug APK signatures,
16 KB ZIP alignment, and all 16 ARM64/x64 native ELF LOAD alignments pass.
Full production release assembly, R8 and release-vital lint now pass. The six
initial `ExtraTranslation` errors were removed by deleting unused orphan keys
from three Android Chinese catalogs; no lint checks were disabled. Release APKs
are unsigned because no production key was supplied. A connected SM-S721B phone
(Android 17/API 37, ARM64) passed development-app startup, public search, audible
playback confirmed by the user, pause/resume, seeking, synchronized lyrics, queue
skipping and background playback. The regular installed app was not replaced.
See [Android validation](android-build-validation.md) for scope and remaining checks.
The separate non-debuggable R8 benchmark build also passes on that phone, including
user-confirmed audible playback, pause/resume, seek, lyrics, Next and background
playback. Test APKs remain installed and paused; production package metadata is
unchanged. No Android power comparison or production-signed release is claimed.

Both baseline and candidate x64 shared suites were explicitly executed on PE-
verified Java 21 x64, passing 46 shared and 41 shared UI tests. The candidate also
passed desktop-only configuration with Android SDK variables removed, preserving
desktop development without Android setup. Baseline x64 desktop tests and portable
assembly pass with 139 selected JAR natives verified x64. All x64 execution here
uses Windows ARM's emulation; a physical Windows x64 host remains unverified.
