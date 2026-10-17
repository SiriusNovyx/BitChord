# Build infrastructure validation plan

The user-selected candidate is native ARM64 Java 25, Gradle 9.5.1 and AGP 9.3.
The original Java 21 / Gradle 8.11.1 wrapper remains the baseline. Desktop bytecode
and minimum runtime stay Java 21; Android and shared targets stay JVM 17.

1. Record SDK packages and run the original Android build before changing plugins.
2. Opt into AGP 9.3.3 and Kotlin 2.4.21 with `bitchord.modernBuild=true`.
   Leave default versions and wrapper files intact. Use AGP's bundled R8 in the
   candidate, preserving the existing R8 override only for the baseline.
3. Migrate shared Android targets and app Kotlin to the supported AGP 9
   configuration. Built-in Kotlin and the Android KMP plugin now configure and
   compile without legacy opt-outs. Shared library compilation must use API 37
   to satisfy the existing transitive InnerTubeX AAR; minSdk/targetSdk are retained.
4. Run Android app/unit/shared tests and native builds, then desktop ARM64 and
   x64 tests, native architecture scans, packaging and application startup.
5. Record failures and prerequisites without renaming platform directories or
   substituting binaries. Promote neither the wrapper nor runtime before the
   required validation gates pass.

Initial evidence: the installed platform is `platforms/android-37.0`, revision 2,
API 37.0 with no preview marker. Build Tools 36.0.0 and 37.0.0 are installed.
The baseline installed NDK 27.0.12077973 and Build Tools 35.0.0 under the existing
SDK licenses, then failed because AGP 8.10.1 searches for `android-37`.

Kotlin 2.4.21's published compatibility table covers Gradle through 9.7.0 and AGP
through 9.3.1. The candidate uses the maintenance fixes in AGP 9.3.3; this patch
is newer than the table's tested AGP maximum, so local checks must be reported
separately from published compatibility. Compose remains 1.10.3.

Current validation: candidate ARM64 tests pass on Java 21 and 25, desktop x64
tests/portable assembly pass, ARM64 native startup/playback/lyrics/shutdown pass,
and the baseline ARM64 build passes after the migration. Android debug APKs and
all three configured native ABIs build. Shared Android host tests pass 39 cases.
The initial 17 localization failures and six ExtraTranslation release-lint errors
were traced to unchanged source and corrected without weakening checks. All 971
app tests and full production release assembly now pass. A connected Android 17
ARM64 phone passed development-app startup, search, user-confirmed audible playback,
pause/resume, seek, lyrics, queue skipping and background playback. Production APKs
remain unsigned; the separate non-debuggable R8 benchmark build also passes
device startup, search, audible playback, controls, lyrics and background playback,
as recorded in
`android-build-validation.md`. Baseline x64 tests/assembly and candidate shared tests on Java
21 x64 pass, including candidate desktop-only configuration without SDK variables.
The baseline and candidate remain separate. Physical Windows x64 execution,
remaining platform/installer checks and release CI are not established by local builds.
