# Desktop ARM64 and power implementation plan

Goal: a native Windows ARM64 BitChord package with its existing audio and account features, followed by measured reductions in visual/background work. The user has authorized implementation in this checkout; do not commit or publish.

Architecture: retain Kotlin/JVM desktop, shared Compose UI, FFmpeg decoding, ONNX analysis and Windows JNI bridges. Use explicit target architecture, verify PE machine fields rather than trusting filenames, and keep visual activity independent of audio lifecycle.

## Audit and implementation sequence

- [x] Inspect README, DESKTOP, CONTRIBUTING, Gradle modules, release workflow, native CMake and the desktop playback/rendering paths.
- [x] Verify graphics/JNA/SQLite artifacts; replace unavailable ARM64 browser runtime with WebView2, build FFmpeg JNI/ONNX/QuickJS, and verify actual binary architecture.
- [x] Add explicit architecture, native Windows detection under emulation, separate native directories and strict packaging validation. Preserve x64 production dependency versions.
- [x] Build and exercise analyzer, window and SMTC bridges with MSVC ARM64; reject ARM64 compilers for x64 builds. Native DLLs verified AA64.
- [x] Assemble a portable ZIP with AA64 native launcher and Java 21 runtime; launch a fresh extraction. Installer/release CI remains pending.
- [ ] Establish six repeatable workloads: idle, static cover, lyrics, animated artwork, minimized/hidden, battery versus AC. Collect CPU, private memory and GPU engine counters; label energy measurement separately.
- [x] Add Efficient/Balanced/Performance visual policy; gate hidden/minimized canvas decoding, frame capture and decorative motion independently of playback. Preserve appearance preferences.
- [x] Run new regression tests and existing shared/desktop tests, native initialization, launch and playback checks where tools permit. Missing platform tools and remaining hardware checks are recorded in the validation report.
- [x] Update DESKTOP and ARM64 build/source preparation commands. Complete the validation report after remaining controlled performance and functionality checks. Keep the objective active.

## Verification contract

Architecture tests must reject x64 PE binaries for an ARM64 package, malformed PE headers and missing required components. Target tests must cover x64 JVM on an ARM64 OS and explicit x64 override. Visual tests must prove background work pauses and resumes, and that audio state is unaffected. Runtime success requires Java/FFmpeg/Skiko/ONNX initialization plus a real window, audio playback, lyrics, queue, device switching, media controls and shutdown. A reduced counter is not evidence of reduced energy use.

Build dependencies and runtime tools downloaded for this task belong under `.work`, not a machine-wide install. No user account data is part of the inspection or test fixtures.

## Added reliability and storage scope

- [x] Reproduce and fix WebSocket replacement cleanup using current connection identity.
- [x] Move socket writes to bounded immutable queues, preserving FIFO order and graceful bye delivery. Backend suite and repeated regressions pass; Go race testing is unsupported on Windows ARM64.
- [x] Correct portable-build isolation documentation and implement explicit file-backed data mode; persistence tests pass.
- [x] Complete native ARM64 dependencies and real playback, lyrics, queue, output selection, SMTC and background checks. Fresh EXE shortcuts, downloads, playlist persistence, DPI sizing and mouse resizing were exercised.
- [x] Reproduce Bluetooth hotplug UI/audio stalls; isolate background discovery, force invalidated-client recreation and bound stalled-buffer waits. Native fixtures pass across ARM64/x64 toolchains, and the second physical reconnect cycle passed.
- [x] Select Windows update assets by the running JVM architecture; never substitute an x64 installer for ARM64.
- [x] Reproduce whole-GPU usage above 60% in native lyrics; record app and compositor shares separately. Connect desktop visual activity to the shared lyrics clock and verify minimize/restore in a fresh portable extraction.
- [x] Rebuild after relocation to E:\Work; preserve old generated native caches. Verify an optional opaque startup path and add direct battery-driver measurement with capability/unknown-value checks. Idle opacity samples were confounded and do not support a default change.
- [ ] Isolate foreground composition cost and residual background draw requests, verify moving artwork in the complete player and measure calibrated energy under equivalent conditions. Installers, release CI, touch and remaining platform runs are still open.
- [x] Rebuild after the return to C:\Users\Admin\Downloads\Compressed\BitChord-main; reproduce and fix stale WebView/x64 window caches. Verify Thai translation in the shared desktop player and isolate test preferences.
- [ ] Replace manual Spotify cookie setup with browser authorization and add Spotify library/native playback, as requested. Preserve account data and verify ARM64 dependencies before packaging; see spotify-desktop-design.md.
- [x] Repair Thai translation target binding; add the user's unmodified WDB Bangna font for Thai app/lyrics text and preserve its notice. Live Thai translation and Thai app typography were exercised.
- [x] Refresh Discord presence revisions after seeking and buffering completion; regression tests pass. Live Discord timeline verification is pending.
- [x] Schedule line-only lyric deadlines while retaining per-frame word/gap animation; unit and Compose runtime checks pass. A matched hardware comparison remains pending.
- [x] Add browser PKCE and protected grants; user-approved library access and restart restoration were exercised. ARM64/x64 native PCM helpers build and start.
- [ ] Resolve the first native Spotify PCM startup failure, then exercise native playback, seeking, queue, output switching and shutdown. Keep native-playback claims separate from OAuth/library success.
