# BitChord Desktop

BitChord has a Kotlin Multiplatform shared module and a Compose Multiplatform
desktop application for macOS, Linux, and Windows. Desktop uses the JVM target, which is
the supported Compose Multiplatform desktop model.

The desktop target uses Java 21.

The opt-in [build infrastructure candidate](docs/desktop-java25.md) uses Java 25,
Gradle 9.5.1 and AGP 9.3 while retaining Java 21 desktop and Java 17 Android/shared
bytecode. The original wrapper remains the default until validation is complete.

On macOS (Apple Silicon or Intel), run it with a Java 21 JDK available in your environment:

```bash
./gradlew :desktopApp:run
```

On Linux, run it with the JDK and native libraries available in the shell:

On NixOS:
```bash
nix-shell -p jdk21 libglvnd glib gtk3 pango atk cairo cmake gdk-pixbuf libXtst libXxf86vm alsa-lib ffmpeg_6 --run '\
  export LD_LIBRARY_PATH="$(nix eval --raw nixpkgs#libglvnd.outPath)/lib:$(nix eval --raw nixpkgs#glib.out)/lib:$(nix eval --raw nixpkgs#gtk3.outPath)/lib:$(nix eval --raw nixpkgs#pango.out)/lib:$(nix eval --raw nixpkgs#atk.outPath)/lib:$(nix eval --raw nixpkgs#cairo.outPath)/lib:$(nix eval --raw nixpkgs#gdk-pixbuf.outPath)/lib:$(nix eval --raw nixpkgs#libXtst.outPath)/lib:$(nix eval --raw nixpkgs#libXxf86vm.outPath)/lib:$(nix eval --raw nixpkgs#alsa-lib.outPath)/lib:$(nix eval --raw nixpkgs#ffmpeg_6.lib)/lib:$HOME/.openjfx/cache/21.0.6+3/amd64:/run/opengl-driver/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"; \
  bash ./gradlew :desktopApp:run
'
```

On Windows, use a Java 21 shell or install and select a Java 21 JDK before
running `gradlew.bat :desktopApp:run`.

## Installing a release

Every download on the [releases page](https://github.com/SiriusNovyx/BitChord/releases) carries its own Java
runtime, the FFmpeg and ONNX natives, the Automix analyser and its models.
Nothing else has to be installed first — no JDK, no codec pack.

| Platform | Download | Notes |
|---|---|---|
| macOS | `BitChord-<version>-macos-arm64.dmg` | For Apple Silicon Macs (M1/M2/M3/M4/later). Drag to Applications. |
| macOS | `BitChord-<version>-macos-x64.dmg` | For Intel Macs. Drag to Applications. |
| macOS | `BitChord-<version>-macos-*.pkg` | Standard macOS installer package for automated or managed installations. |
| Linux | `BitChord-<version>-linux-x86_64.AppImage` | `chmod +x` it and run. No install, works on any distribution. |
| Linux | `BitChord-<version>-linux-amd64.deb` | `sudo apt install ./BitChord-*.deb` on Debian, Ubuntu and derivatives. |
| Linux | `BitChord-<version>-linux-x86_64.rpm` | `sudo dnf install ./BitChord-*.rpm` on Fedora, RHEL and openSUSE. |
| Windows | `BitChord-<version>-windows-x64-setup.exe` | The ordinary installer. Installs for the current user, so it never asks for an administrator. |
| Windows | `BitChord-<version>-windows-x64.msi` | The same thing for anyone who deploys by MSI. |
| Windows | `BitChord-<version>-windows-x64-portable.zip` | Unzip and run `BitChord.exe`. Release app images use normal OS preferences and user-directory caches. |

**macOS Gatekeeper.** Unsigned binaries built locally or downloaded outside the Mac App Store
may trigger a macOS security prompt ("BitChord is damaged and can't be opened" or "unidentified developer").
To clear the quarantine flag, run:

```bash
xattr -cr /Applications/BitChord.app
```

The Linux packages are built on Ubuntu 22.04 against its glibc, so they install
on that release and anything newer.

### Windows ARM64 development

See [the ARM64 build and verification commands](docs/windows-arm64.md). A native
ARM64 portable package now bundles Java 21, Skiko, FFmpeg JNI, ONNX, QuickJS and
the Windows bridges. It has launched and played music on Windows ARM64. The
ARM64 browser adapter uses the installed Edge WebView2 Runtime; other platforms
keep JavaFX. Existing x64 packages remain supported through their original
dependency graph. Installer and release CI validation is still pending.

### Portable data

`-Dbitchord.dataDir=<writable directory>` opts into file-backed preferences and
app-owned caches, extracted Skiko/JavaCPP natives, analyses, logs and downloads under that directory. Pass it
before startup; normal installations retain their existing OS-backed storage.
No existing preferences or credentials are migrated or deleted. The custom
`windowsPortable` launchers select `data` beside themselves and keep Java's
temporary directory there. The ARM64 ZIP includes a native `BitChord.exe` and
its runtime. This is distinct from the release workflow's zipped
jpackage app image. It is not a sandbox: explicitly chosen music/browser files,
OS credential facilities and third-party runtime state can still involve other
locations. Windows DPAPI credentials remain tied to the Windows user.

### Visual performance

Desktop playback settings offer Efficient, Balanced (default) and Performance.
Efficient uses static artwork and reduced effects. Balanced and Performance
retain foreground effects; purely visual canvas work pauses when the window is
hidden or minimized in every mode. Audio decoding, downloads and analysis are
not governed by this policy. Covered-but-visible windows are not yet detected.

For controlled runs, `:desktopApp:run -Pbitchord.performance=efficient` sets the
process's mode; `-Pbitchord.dataDir=.work/test-data` isolates test preferences.
Use `desktopApp/packaging/Measure-DesktopPerformance.ps1` with the app process ID
and a scenario to record CPU cores used, memory and per-engine GPU counters.
The collector does not measure energy consumption. Compare identical tracks,
screens, window sizes, visual settings and AC/battery states after warm-up.

**Linux system libraries.** The packages carry their own Java runtime and codecs
but not the desktop's own graphics stack, and jpackage cannot derive that list —
so the deb and rpm declare only `xdg-utils`. Any machine running a desktop
session already has what is needed (GTK 3, X11 or XWayland, GL, ALSA); a minimal
or headless install does not, and will fail at startup rather than at install
time.

**Windows native dependencies.** The project bridges use a static C runtime;
their architecture must match the JVM. The ARM64 preparation scripts verify
each selected DLL and retain the ARM64 VC runtime files needed by JavaCPP.
Windows 11 supplies UCRT. Do not install an x64 runtime to repair an ARM64
library-loading error: check the failing component and architecture first.
The ARM64 browser adapter also requires the Edge WebView2 Runtime.

## Building the packages yourself

jpackage builds a package by driving the target platform's own tooling, so each
one has to be built on the platform it is for. This is what CI does; see
[`.github/workflows/release.yml`](.github/workflows/release.yml).

On macOS:

```bash
# Builds the disk image (.dmg) installer
./gradlew :desktopApp:packageDmg

# Builds the .pkg installer
./gradlew :desktopApp:packagePkg

# Standalone BitChord.app bundle
./gradlew :desktopApp:createDistributable
# Output is at: desktopApp/build/compose/binaries/main/app/BitChord.app
```

On Linux — `rpm`, `fakeroot` and `binutils` must be installed for the packages,
and `file` for the AppImage:

```bash
bash ./gradlew :desktopApp:packageDeb
bash ./gradlew :desktopApp:packageRpm

# The AppImage is wrapped around the app image jpackage produces.
bash ./gradlew :desktopApp:createDistributable
desktopApp/packaging/appimage.sh \
  desktopApp/build/compose/binaries/main/app/BitChord \
  dist/BitChord-linux-x86_64.AppImage
```

On Windows — [WiX Toolset 3](https://github.com/wixtoolset/wix3/releases) must
be on `PATH`, which is what jpackage builds both the `.exe` and the `.msi` with:

```bat
gradlew.bat :desktopApp:packageExe
gradlew.bat :desktopApp:packageMsi

REM The portable build is the app image, zipped.
gradlew.bat :desktopApp:createDistributable
```

Pass `-Pbitchord.version=1.2.3` to stamp a version other than the one in
`desktopApp/build.gradle.kts`; a release build takes it from the tag.
