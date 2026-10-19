param(
    [Parameter(Mandatory)][string]$Jdk,
    [Parameter(Mandatory)][string]$VisualStudio,
    [Parameter(Mandatory)][string]$FfmpegArchive,
    [Parameter(Mandatory)][string]$JavaCppJar,
    [Parameter(Mandatory)][string]$FfmpegApiJar,
    [Parameter(Mandatory)][string]$FfmpegSourcesJar,
    [Parameter(Mandatory)][string]$OutputDirectory,
    [string]$Python = 'python'
)
$ErrorActionPreference = 'Stop'
function Verify-Hash([string]$Path, [string]$Hash) {
    $stream = [IO.File]::OpenRead((Resolve-Path -LiteralPath $Path).Path)
    $sha = [Security.Cryptography.SHA256]::Create()
    try { $actual = [BitConverter]::ToString($sha.ComputeHash($stream)).Replace('-', '').ToLowerInvariant() }
    finally { $stream.Dispose(); $sha.Dispose() }
    if ($actual -ne $Hash) { throw "Checksum mismatch: $Path" }
}
Verify-Hash $FfmpegArchive '8b647ad0a5a5e12e9da5cd6b6600cce9e58c50430114d76185f5533bb451b713'
Verify-Hash $JavaCppJar 'cb95a82ce1c337690436017eb8886597de71c1463dc16a3570f6a96b7c98a82b'
Verify-Hash $FfmpegApiJar '1bc7d6db5ef4d8fee1b3be8cd6b2e3ef8409cb7be630f290c4d5d82d2a2fa079'
Verify-Hash $FfmpegSourcesJar '8f15502bd5651b0814dee9eebc8addbeaf10293456ec058694704ab2a9df18f4'
$jdkPath = (Resolve-Path -LiteralPath $Jdk).Path
$apiPath = (Resolve-Path -LiteralPath $FfmpegApiJar).Path
$cppPath = (Resolve-Path -LiteralPath $JavaCppJar).Path
$outputPath = [IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Force -Path $outputPath | Out-Null
$buildPath = Join-Path $outputPath 'ffmpeg-jni-build'
New-Item -ItemType Directory -Force -Path $buildPath | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $FfmpegArchive).Path)
try {
    foreach ($entry in $archive.Entries) {
        $destination = [IO.Path]::GetFullPath((Join-Path $buildPath $entry.FullName))
        if (-not $destination.StartsWith($buildPath + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
            throw 'Archive entry escapes the build directory'
        }
        if ($entry.Name) {
            New-Item -ItemType Directory -Force -Path ([IO.Path]::GetDirectoryName($destination)) | Out-Null
            [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $destination, $true)
        }
    }
} finally { $archive.Dispose() }
$shared = Join-Path $buildPath 'ffmpeg-n8.1-latest-winarm64-lgpl-shared-8.1'
$jni = Join-Path $buildPath 'jni'
New-Item -ItemType Directory -Force -Path $jni | Out-Null
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'msvc_avutil_compat.h') -Destination $jni
$source = [IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $FfmpegSourcesJar).Path)
try {
    $entry = $source.Entries | Where-Object { $_.FullName.EndsWith('/log_callback.h') } | Select-Object -First 1
    if (-not $entry) { throw 'JavaCPP FFmpeg log_callback.h is missing' }
    [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, (Join-Path $jni 'log_callback.h'), $true)
} finally { $source.Dispose() }
& (Join-Path $VisualStudio 'Common7/Tools/Launch-VsDevShell.ps1') -Arch arm64 -HostArch arm64 -SkipAutomaticLocation
$env:JAVA_HOME = $jdkPath
& (Join-Path $jdkPath 'bin/java.exe') -Xmx1024m -cp "$cppPath;$apiPath" org.bytedeco.javacpp.tools.Builder `
    -classpath $apiPath -d $jni -nodelete '-Dplatform=windows-arm64' `
    "-Dplatform.includepath=$shared/include" "-Dplatform.linkpath=$shared/lib" "-Dplatform.preloadpath=$shared/bin" `
    -Xcompiler "/FI$jni/msvc_avutil_compat.h" 'org.bytedeco.ffmpeg.**'
if ($LASTEXITCODE -ne 0) { throw 'FFmpeg JNI build failed' }
& $Python (Join-Path $PSScriptRoot 'package_ffmpeg_arm64.py') $shared $jni $FfmpegArchive `
    (Join-Path $outputPath 'ffmpeg-8.1.2-1.5.14-windows-arm64.jar')
if ($LASTEXITCODE -ne 0) { throw 'FFmpeg JNI architecture verification failed' }
