param(
    [Parameter(Mandatory)][string]$Source,
    [Parameter(Mandatory)][string]$Jdk,
    [Parameter(Mandatory)][string]$LlvmMingw,
    [Parameter(Mandatory)][string]$CMake,
    [Parameter(Mandatory)][string]$Ninja,
    [Parameter(Mandatory)][string]$UpstreamJar,
    [Parameter(Mandatory)][string]$BuildDirectory,
    [Parameter(Mandatory)][string]$OutputDirectory,
    [string]$Python = 'python'
)
$ErrorActionPreference = 'Stop'
$sourcePath = (Resolve-Path -LiteralPath $Source).Path
$revision = & git -C $sourcePath rev-parse HEAD
if ($LASTEXITCODE -ne 0 -or $revision -ne '6e51ae878be54f22272381e62118535790ba9940') { throw 'Expected quickjs-kt v1.0.14' }
foreach ($entry in @(
    @('quickjs/native/quickjs', '04be246001599f5995fa2f2d8c91a0f198d3f34c'),
    @('quickjs/native/c-vector', '774773d4cb1e66dd736e90e7f482d4f22af464c6')
)) {
    $submodule = & git -C (Join-Path $sourcePath $entry[0]) rev-parse HEAD
    if ($LASTEXITCODE -ne 0 -or $submodule -ne $entry[1]) { throw "Wrong source revision: $($entry[0])" }
}
$env:JAVA_HOME = (Resolve-Path -LiteralPath $Jdk).Path
$compiler = Join-Path (Resolve-Path -LiteralPath $LlvmMingw).Path 'bin/aarch64-w64-mingw32-clang.exe'
$buildPath = [IO.Path]::GetFullPath($BuildDirectory)
$cmakeSource = Join-Path (Split-Path $PSScriptRoot -Parent) 'native/quickjs'
& $CMake -S $cmakeSource -B $buildPath -G Ninja '-DCMAKE_SYSTEM_NAME=Windows' `
    "-DCMAKE_C_COMPILER=$compiler" "-DCMAKE_MAKE_PROGRAM=$Ninja" "-DQUICKJS_KT_SOURCE=$sourcePath"
if ($LASTEXITCODE -ne 0) { throw 'QuickJS ARM64 configuration failed' }
& $CMake --build $buildPath -j 4
if ($LASTEXITCODE -ne 0) { throw 'QuickJS ARM64 compilation failed' }
$outputPath = [IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Force -Path $outputPath | Out-Null
& $Python (Join-Path $PSScriptRoot 'package_quickjs_arm64.py') $UpstreamJar `
    (Join-Path $buildPath 'libquickjs.dll') $sourcePath `
    (Join-Path $outputPath 'quickjs-kt-jvm-1.0.14-windows-arm64-bitchord.jar')
if ($LASTEXITCODE -ne 0) { throw 'QuickJS ARM64 packaging failed' }
