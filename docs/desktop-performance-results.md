# Desktop performance measurements, 2026-10-15

The measurements below precede the DPI-size correction in `Main.kt`. Their
window was 1830x1170 logical / 2745x1755 physical pixels at 150% scaling. The
corrected requested default is 1220x780 logical / 1830x1170 physical pixels.
Do not mix measurements at these different viewport sizes into an architecture
or renderer before/after claim. The geometry correction is verified separately
in the validation record; it does not establish an energy saving by itself.

These are short local observations on the Surface Pro 11, rather than a battery
life benchmark. The reported 60%+ GPU symptom has not been reproduced in these
workloads. Native execution alone did not reduce GPU 3D use in the static-cover
view. Efficient visuals reduced it relative to native Balanced in the first
lyrics comparison. Repeated comparisons remain necessary.

The original downloaded source was built without source changes and ran on an
x64 Java 21 runtime. Its native bridges used static compiler-runtime linker
flags to repair missing runtime imports in the local comparison build. The
modified application ran from its ARM64 portable EXE. Both used Java 21.0.12.1+1,
a 512 MiB heap, G1 GC and separate preferences/caches. Both requested a
1220 by 780 dp window; the shared sizing bug produced the enlarged viewport
described above. UI captures were scaled by the automation tool. The original
uses JavaFX; ARM64 uses WebView2.
The FFmpeg and JNI versions necessarily differ. These comparisons cannot
attribute every difference to CPU architecture alone.

Playback was Opalite by Taylor Swift, selected from Search, resolved through
YouTube and played through Qualcomm WASAPI shared output at 48 kHz, stereo.
Static-cover runs restarted the track. Lyrics runs sought near 1:36 and used
synchronized lyrics from lrc.red. All rows below were on battery power. No
builds or tests ran during the samples. Window visibility, foreground state
and battery state were recorded. CPU is processor cores used, not a percentage
of the ten-core device. Memory is aggregate private memory. GPU 3D is the sum
of the app and snapshotted child-process 3D counters; other GPU engines were
zero in these samples.

| Run | Samples | Foreground / minimized | CPU cores | GPU 3D % | Private MiB | Mean reported DischargeRate |
|---|---:|---|---:|---:|---:|---:|
| Original x64, idle | 10 | 10 / 0 | 0.0266 | 0 | 1219.5 | 11903 |
| Native ARM64 Balanced, idle | 10 | 10 / 0 | 0 | 0.2855 | 834.5 | 9458 |
| Original x64, static cover | 20 | 20 / 0 | 0.7075 | 10.3925 | 1563.4 | 15145 |
| Native ARM64 Balanced, static cover | 20 | 20 / 0 | 0.3951 | 11.2247 | 1742.8 | 13384 |
| Original x64, synchronized lyrics | 15 | 15 / 0 | 0.8247 | 19.5557 | 1706.6 | 14642 |
| Native ARM64 Balanced, synchronized lyrics, run 1 | 15 | 15 / 0 | 0.5898 | 26.7438 | 1740.5 | 14106 |
| Native ARM64 Efficient, synchronized lyrics, run 1 | 15 | 15 / 0 | 0.4196 | 18.2467 | 1620.3 | 11336 |
| Native ARM64 Balanced, synchronized lyrics, run 2 | 15 | 15 / 0 | 0.5783 | 25.9500 | 1753.1 | 13144 |
| Native ARM64 Efficient, synchronized lyrics, run 2 | 15 | 15 / 0 | 0.4010 | 19.4490 | 1605.2 | 10550 |
| Original x64, minimized | 10 | 0 / 10 | 0.5292 | 6.0465 | 1992.3 | 12105 |
| Native ARM64 Balanced, minimized | 10 | 0 / 10 | 0.0942 | 0.7516 | 1777.1 | 10023 |
| Installed x64 application code, static cover | 20 | 20 / 0 | 0.7170 | 10.0793 | 1604.9 | 12916 |
| Installed x64 application code, synchronized lyrics | 15 | 15 / 0 | 0.8700 | 19.0991 | 1696.8 | 12537 |
| Installed x64 application code, minimized | 10 | 0 / 10 | 0.5054 | 5.8482 | 1666.2 | 10483 |
| Native ARM64 Balanced, minimized, same-track repeat | 10 | 0 / 10 | 0.0314 | 0.7726 | 1666.7 | 12176 |

The installed-code comparison loads the existing installation's application
JARs, configuration and graphics libraries under the same x64 Java 21.0.12.1+1
used for the source baseline, with 512 MiB/G1 options. A temporary preferences
factory and private home/temp directories isolate the test from the installed
user profile. The installed launcher and its bundled runtime were not used,
and installed files were not changed. All samples reported battery discharge
and no AC power. The static view began around 0:30, whereas the earlier source
sample began near the start; lyrics were sought to the same middle section.
The results closely resemble the source baseline, but do not constitute a
comparison of the two distributions' launchers and bundled runtimes.

Both repeated minimized runs stayed on Opalite, and restoring the native
window confirmed continued playback. The native underlying page was the local
test playlist and the x64 page was Search; neither had the full player open.
Native app CPU and GPU activity were lower, while the whole-device reported
discharge rate was higher in this last native sample. That disagreement is a
reason to retain the short-run and whole-device limits, rather than claim
battery savings from app counters alone.

After the charger was reconnected, the same middle lyrics section was sampled
on AC. Each run had 15/15 foreground and AC samples, with no discharging samples.

| AC lyrics run | CPU cores | GPU 3D % | Private MiB |
|---|---:|---:|---:|
| Installed x64 application code | 0.7268 | 23.6742 | 1779.0 |
| Native ARM64 Balanced | 0.5361 | 22.6942 | 1753.2 |
| Native ARM64 Efficient | 0.2539 | 16.5232 | 1604.1 |

The x64 AC sample ended at 01:43 UTC; the native samples were collected at
05:30 and 05:34 UTC. Other desktop activity changed in between, and the user's
signed-in native copy remained open during the later measurements. Treat the
architecture and AC/battery differences as exploratory, not a controlled
causal comparison. The two native modes used the same fresh signed-out copy,
same track section, window dimensions and audio settings. This third native
pair again supports reduced visual CPU/GPU work in Efficient mode. AC samples
do not measure battery discharge or wall-socket energy consumption.

The native minimized run included AutoPlay advancing to the next track; the
original minimized run stayed on Opalite. They establish working background
playback and low native background rendering, but are not a strict matched
track comparison. Restore succeeded and system media pause/resume worked on
the native EXE. The first native idle run included interaction and the first
native lyrics run included a charger transition and lost foreground samples;
both are excluded from this table.

The two native pairs consistently reduced GPU and CPU load with Efficient
visuals: Balanced GPU 3D means were 25.95–26.74%, versus Efficient at 18.25–19.45%.
Reported discharge rates also fell in both pairs. This supports the cost of
the foreground visual effects and the Efficient policy. The runs are short,
sequential and include other whole-device activity; they do not establish a
long-term battery-life gain. These earlier figures count BitChord and its
children only; the user subsequently clarified that the original 60%+ reading
was for the whole GPU, with the full player or lyrics open.

`DischargeRate` comes from Windows `root/WMI:BatteryStatus` for the whole device,
including its display and other running applications. It is normally a rate
in milliwatts, but Windows battery drivers may report relative units. The
host's `BatteryStaticData` query failed, so the absolute-unit capability has
not yet been independently verified. Keep the raw rates above and do not
treat their division by 1000 as calibrated watts or promise battery-life gains.
Microsoft documents the [battery status rate and relative-unit exception](https://learn.microsoft.com/en-us/windows/win32/power/battery-status-str)
and [battery WMI fields](https://learn.microsoft.com/en-us/windows/win32/api/batclass/ns-batclass-battery_wmi_status).

The original x64 lyrics renderer was additionally instrumented for 15 seconds
using a temporary wrapper around its existing Skiko render delegate, restored
after sampling. It reported Direct3D on the Qualcomm Adreno X1-85 GPU, 688 draw
callbacks, 45.78 callbacks/second, a 17.67 ms median callback interval and
47.15 ms p95. These are draw callbacks, not measured display presentation times.
The corresponding native ARM64 probe used Direct3D on the same GPU, with 831
draw callbacks in 15 seconds: 55.39 callbacks/second, a 16.99 ms median interval
and 25.82 ms p95. Native rendering therefore delivered more draw callbacks and
fewer long callback gaps in this short test. That is consistent with some of
the increased GPU load serving smoother rendering; it does not isolate every
source of the difference or establish presented frame rates. Both probes used
the same track's middle lyrics section, but were separate short runs.

Raw reports are under `.work/performance/original-x64-battery`,
`.work/performance/final-native-battery` and
`.work/performance/final-native-efficient-battery`. The x64 render probe report
is `.work/render-x64-lyrics.txt`; the native report is
`.work/render-arm64-lyrics.txt`. Fresh native idle data is under
`.work/performance/native-focus-battery`.
Second Efficient data is under `.work/performance/native-focus-efficient-battery`.
The installed-code comparison is under `.work/performance/installed-x64-battery`;
the same-track native minimized repeat is under
`.work/performance/native-repeat-battery`.
AC reports are under `.work/performance/installed-x64-ac`,
`.work/performance/native-balanced-ac` and `.work/performance/native-efficient-ac`.
The collector is
`desktopApp/packaging/Measure-DesktopPerformance.ps1`; its per-sample battery
telemetry avoids silently treating a charger transition as a single power mode.

HLS decoder startup measurements and architecture/build validation are in
[the validation record](desktop-arm64-validation.md). HLS rendition selection
reduced median time to decode the first ten frames from 17.49 to 1.35 seconds
on the same ARM64 host. Cold process CPU time increased by approximately
0.22 seconds. This establishes startup and network/probing improvement, rather
than an energy or whole-app GPU improvement.

## Whole-GPU scope correction and minimized lyrics, 15 October

The user clarified that the original 60%+ value was whole-GPU usage while the
full player or lyrics were open. Earlier app-only tables cannot establish that
this symptom was absent. The collector now optionally records all GPU process
shares, grouped by physical engine, alongside the application snapshot.
Microsoft describes Task Manager's overall percentage as the
[busiest GPU engine](https://devblogs.microsoft.com/directx/gpus-in-the-task-manager/),
rather than the sum of independent engines.

The native audio-recovery portable copy (GUI PID 10000), playing Sweater Weather,
was sampled at its corrected 1220 by 780 logical size, 150% display scaling,
Balanced mode, Mica, static cover and Huawei Bluetooth output. All samples below
reported AC power. No build or render-delegate probe ran during these samples.
The foreground flags were true for every visible sample and minimized flags
were true for every minimized sample.

| View | Samples | Whole busiest-engine mean / max | BitChord mean | DWM contribution mean |
|---|---:|---:|---:|---:|
| Full player | 20 | 23.23% / 26.68% | 7.23% | 14.38% |
| Full player minimized | 15 | 48.24% / 49.22% | 6.88% | 40.25% |
| Synchronized lyrics | 15 | 72.35% / 75.64% | 23.54% | 47.89% |

The GPU was the Adreno's same 3D engine in these samples. DWM is shared desktop
composition work; its counters do not prove that BitChord caused all of it.
Other desktop applications and an animated wallpaper process remained open.
The runs were sequential with different song positions, and the desktop exposed
behind the minimized window differed. They reproduce a high whole-GPU workload
and identify contributors, rather than isolate an architecture or energy gain.
In particular, the minimized whole-GPU increase prevents treating DWM utilization
as a direct measure of BitChord's visual cost.

A separate 15-second temporary render-delegate probe, restored after sampling,
found 898 draw callbacks (59.85/second) with the lyrics window minimized. A
read-only state probe confirmed AWT frame state 1 (iconified) and desktop visual
activity false. The shared lyrics frame loop was gated only by lifecycle, so it
continued requesting frames despite the desktop visibility policy. The shared
foreground helper now additionally respects a host-provided visual activity
composition local, supplied by the desktop window; the default leaves Android's
lifecycle behavior intact. A Compose runtime regression test exercises frame
work stopping/resuming across host visibility changes while lifecycle remains
RESUMED, and also verifies lifecycle stop/resume behavior.

The rebuilt portable EXE was launched from a fresh signed-out extraction (GUI
PID 16516). Search, playback and lyrics worked. After minimizing the lyrics
view, a warm 15-second render probe recorded 126 callbacks (8.40/second), rather
than the previous continuous roughly 60/second. Residual drawing remains. The
first fixed-build probe overlapped build contention and took 19.31 seconds; it
is not used as a stable rate comparison. On restore, lyrics advanced; pause,
resume and seeking to 3:04 worked. The test player was muted, so this repeat
verifies playback progress/controls rather than acoustic output.

A subsequent passive minimized capture had 15/15 minimized samples, 1.35% app
GPU mean and 0.0627 CPU cores. Whole-GPU mean remained 58.72%, with 48.77% DWM
and other live BitChord windows contributing separately. AutoPlay had advanced
the test copy to Reflections, and Windows reported battery operation rather
than the earlier AC state. Thus these figures are not a matched before/after
power or track comparison and cannot establish an energy gain. The behavioral
regression and removal of the hidden continuous lyric clock are verified;
foreground composition cost and remaining background draw requests are open.

Raw captures are `.work/performance/whole-gpu-native-player-corrected`,
`.work/performance/whole-gpu-native-minimized` and
`.work/performance/whole-gpu-native-lyrics`. An earlier exploratory player run
reached 62.66%; its raw counter values are usable, but derived percentage fields
were initially rounded by PowerShell's integer overload of `Math.Min`. That
rounding is fixed and covered by a fractional-value regression test; the table
uses only subsequent corrected captures. No energy reduction is established.
The fixed minimized capture is in
`.work/performance/whole-gpu-native-lyrics-fixed-minimized`; read-only policy
and render reports are `.work/minimized-policy-fixed.txt` and
`.work/render-native-minimized-lyrics-fixed-warm.txt`.

## Relocation and window-path experiment

The checkout moved to `E:\Work\BitChord-main` during this work. The ARM64
package and native bridges were rebuilt there; desktop/shared tests and native
window/audio fixtures were rerun. A startup-only diagnostic option,
`bitchord.window.opaque=true`, permits an opaque Windows window without changing
the default. Inspection showed the same 1220 by 780 logical / 1830 by 1170
physical client bounds for both paths. Normal startup had alpha 0 and extended
style `0x80100`; the opaque path had alpha 255 and style `0x100`, removing
`WS_EX_LAYERED`. Its Skiko report still identified Direct3D on the Adreno X1-85.
These are initialization/architecture observations, not a visual click-through
or matched playback test of the opaque path.

Before the move, with all BitChord processes closed, 15 foreground samples of
the Codex window averaged 3.84% whole GPU and 2.04% DWM contribution. Later,
the isolated opaque initial window averaged 0.05% app GPU and 6.99% whole GPU
(14/15 foreground samples, AC). The normal initial window averaged 0% app GPU
and 62.10% whole GPU, but it was not foreground for any sample. Another,
user-operated BitChord copy's contribution changed from roughly 4.5% to 37.9%
between these runs. Thus the large whole-GPU difference cannot be attributed
to opacity. Do not use it as evidence for a default change or battery saving.
The default remains unchanged pending controlled full-player/lyrics comparisons.

Reports are `.work/performance/whole-gpu-no-bitchord`,
`.work/performance/window-opaque-idle-relocated` and
`.work/performance/window-transparent-idle-relocated`. Startup inspection is
in `.work/window-experiment-opaque-graphics.txt` and
`.work/window-experiment-transparent-relocated-geometry.txt`. The isolated
copies were stopped after these non-interactive checks and their test launcher
configuration restored. The user's portable copy in Downloads was retained.
Desktop-control tools were unavailable at that point. They were subsequently
restored; the controlled foreground comparisons below supersede that limitation.

## Foreground lyrics opacity and blur comparisons after the return to C:

Single isolated native copy, Sweater Weather, nearby verse intervals, Balanced,
Mica, 1220 by 780 logical / 1830 by 1170 physical at 150% scaling, muted. Each
15-sample run was foreground on AC for every sample. These use the old continuous
lyrics clock and the previous fallback font, before the WDB Bangna font change.
The busiest measured engine was Adreno 3D. Percentages are capped aggregate
engine counters, with DWM shown separately rather than attributed entirely to BitChord.

| Workload | Whole GPU mean | App GPU mean | DWM mean | CPU cores mean |
| --- | ---: | ---: | ---: | ---: |
| Transparent A1 | 68.7435% | 24.3077% | 43.5620% | 0.5198 |
| Opaque B | 62.6084% | 33.4631% | 28.2574% | 0.3365 |
| Transparent A2 | 69.0927% | 24.6108% | 43.6264% | 0.4446 |
| Transparent, unfocused-lyrics blur/glow off | 60.8896% | 16.4723% | 43.4465% | 0.3085 |

Opacity reduced whole-GPU use by roughly six percentage points but increased app
GPU use. The existing blur setting reduced app use by about eight points while
the whole GPU remained near 61%. Neither warrants a default appearance change
or establishes an energy saving. Verse timing was nearby rather than exactly
aligned. Animated-artwork policy was enabled, but actual Canvas motion was not
proved during these samples. DWM composition is shared with other windows.
Driver battery rates on AC do not measure wall power or whole-device energy.

Evidence directories under `.work/performance`: `opacity-lyrics-C-A1`,
`opacity-lyrics-C-B`, `opacity-lyrics-C-A2`, `lyrics-blur-off-C-B`. Geometry probes
confirmed alpha 0 / transparency true versus alpha 255 / false with equal bounds
and the Direct3D backend on the Adreno X1-85. The default remains transparent.

Line-only lyrics now schedule visual deadlines instead of requesting a frame
continuously; word timing and animated gaps retain frame scheduling. Unit and
Compose runtime tests pass. A matched continuous-clock control JAR fails the
line-only frame-waiter assertion. The hardware comparison below did not establish
a GPU or battery improvement for this scheduling change.

## Line-only clock comparison, 16 October

The continuous-clock control and scheduled-clock shared UI JARs differed only in
the compiled lyric-clock branch and its generated inline caller. The desktop JAR,
font, native libraries and runtime were identical. One ARM64 portable copy ran
Sweater Weather with LRCLIB line timings, static artwork, Balanced mode, lyric
blur enabled, muted audio, and the same 1220 by 780 logical window. Each run
resumed from 1:01 before opening lyrics. The Search page remained underneath the
full player. Windows reported battery discharge and no AC for every sample.

| Clock | Whole GPU mean | App GPU mean | DWM mean | CPU cores mean |
| --- | ---: | ---: | ---: | ---: |
| Continuous A1 | 63.4324% | 18.1499% | 43.5790% | 0.2334 |
| Scheduled B | 63.0197% | 18.5167% | 43.7355% | 0.2642 |
| Continuous A2 | 63.9091% | 18.5076% | 43.3907% | 0.2639 |

Each run contains 15 valid foreground samples spanning approximately 57 seconds,
with no invalid GPU counters. The small whole-GPU difference and unchanged app
GPU do not establish a useful improvement. Continuous work elsewhere remains;
the source contains animated playing bars on the covered Search page. DWM is
shared, and this is a short battery-powered rendering comparison, not a calibrated
energy or battery-life result. Word-timed lyrics retain their frame clock.

Evidence: `.work/performance/line-clock-valid-A1`, `line-clock-valid-B` and
`line-clock-valid-A2`. An earlier `line-clock-A1` changed provider and is excluded.
`line-clock-pinned-A1` retained LRCLIB but had only one valid GPU batch and is also
excluded. It exposed a collector defect: one partial PDH error disabled all later
GPU collection. The collector now keeps valid contexts, records the per-sample
error, and retries subsequent samples; its partial-error regression passes.

## Covered playing-bars animation

The Search page's playing indicator used three infinite transitions even while
the full-player sheet covered it. `SearchPlayingBars` now uses the existing host
visual-activity/lifecycle signal, and desktop page content receives inactive
visual state while that sheet is open. Visible bars keep their original motion;
covered or inactive bars retain a static indicator. Audio state is independent.
A Compose regression failed because covered bars retained frame waiters, then
passed after the change, including uncover/resume and lifecycle stop.

The same line-timed workload was repeated with the scheduled lyric clock on both
sides. The old packaged desktop/shared UI JARs were preserved and restored for
the final control; the runtime, native libraries and profile were unchanged.

| Workload | Whole busiest-engine mean | App GPU mean | DWM on that engine | CPU cores | Private MiB | Driver discharge W |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Control before (`line-clock-valid-B`) | 63.0197% | 18.5167% | 43.7355% | 0.2642 | 1083.4 | 18.309 |
| Fixed B, exploratory | 60.6152% | 8.0735% | 26.7242% | 0.1687 | 1086.6 | 20.661 |
| Fixed B2 | 52.4031% | 6.2118% | 44.6232% | 0.1104 | 1101.5 | 17.413 |
| Preserved control after | 64.1054% | 18.5699% | 44.1117% | 0.2579 | 1083.3 | 18.146 |

All rows contain 15 valid foreground samples on battery, with LRCLIB still
selected at the end. The exploratory fixed B run had a large
`WorkloadsSessionHost` contribution on a separate compute engine/adapter; its
busiest-engine and DWM columns therefore cannot be compared directly with the
Adreno 3D rows. It is retained to show the interference, rather than pooled with
B2. No unrelated process was stopped.

In the cleaner B2 run, app GPU fell by about 12.3 percentage points and whole
GPU by about 11.2 points relative to the two surrounding control means. Returning
to the old JARs restored the higher workload. Separate 15-second Direct3D render
probes recorded 59.99 draw callbacks/second for the control and 13.66 for the fix.
These are callback rates, not display FPS. Lyrics continued advancing and their
appearance was retained. No hardware acceleration or frame-rate cap was changed.

The driver discharge rates are whole-device, short, uncalibrated observations.
The clean fixed rate was lower, while the interfered run was higher; this does
not establish a battery-life improvement. Substantial DWM use remains, and this
result does not cover word-timed lyrics, all full-player entry paths or animated
artwork. The line-clock-only comparison above remains a negative result.

Evidence: `.work/performance/covered-bars-fixed-B`, `covered-bars-fixed-B2`,
`covered-bars-control-A2`, `.work/render-covered-bars-fixed.txt` and
`.work/render-covered-bars-control.txt`. Temporary visual/provider/volume settings
were restored from a snapshot after collection, and the test copy was returned
to the latest production JARs and original launcher configuration.

## Rendering-backend feasibility at the pinned version

The checksum-verified official `skiko-awt-0.9.37.4-sources.jar` explicitly rejects
`OPENGL` on Windows ARM64 in `SkikoProperties.parseRenderApi`. Its fallback list
contains ANGLE, Direct3D and software paths, and `AngleSupport` states that ANGLE
libraries are not included by default. Maven Central returned HTTP 404 for
`skiko-awt-runtime-angle-windows-arm64:0.9.37.4`, while the existing normal ARM64
runtime and source JAR returned 200. This is an availability check at this pinned
version, not a claim about newer versions or source-build feasibility.

No production backend override was introduced. A future ANGLE experiment must
supply verified compatible native libraries and inspect the actual initialized
backend; requesting ANGLE and silently falling back would not be a comparison.
The current measured backend remains hardware Direct3D on Adreno X1-85.
Source: [official pinned source JAR](https://repo.maven.apache.org/maven2/org/jetbrains/skiko/skiko-awt/0.9.37.4/skiko-awt-0.9.37.4-sources.jar).

## Direct battery-driver measurement

The earlier WMI static-capability query failed. A new read-only native query
successfully retrieved the Surface battery's capabilities as `0x80000000`:
system battery, with `BATTERY_CAPACITY_RELATIVE` clear. Microsoft documents
[absolute capacity/rate units when that flag is clear](https://learn.microsoft.com/en-us/windows/win32/power/battery-information-str)
and the [battery device query procedure](https://learn.microsoft.com/en-us/windows/win32/power/enumerating-battery-devices).
The ARM64 and x64 measurement executables were built with MSVC, verified by
their PE machine fields and run against the driver.

The optional `-BatteryProbe` collector input now records per-battery driver
capabilities and signed status/rates. Valid absolute system-battery rates are
labeled in milliwatts; relative/unknown/missing data remain null. Charging is
kept separate from discharge. Regression checks cover those cases, non-system
and short-term batteries, multiple JSON battery records and unavailable probes.
Tests caught and corrected PowerShell's nested single-array parsing behavior.
A two-sample collector format smoke passed with the real ARM64 helper; it
overlapped other validation work and is not a performance benchmark.

This resolves the driver's declared-unit ambiguity for future direct readings.
It does not calibrate the hardware, retroactively validate all historical WMI
readings, measure AC wall power or establish a battery-life improvement. The
driver can report battery discharge while AC is also present, so that rate
must not be treated as the whole device's power draw under AC operation.
`energyMeasured` remains false. Evidence is `.work/native-battery-capability.json`
and `.work/performance/native-battery-collector-smoke`; reproducible commands
are in [the Windows build/measurement guide](windows-arm64.md).
