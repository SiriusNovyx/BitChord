param(
    [Parameter(Mandatory)][string]$Source,
    [Parameter(Mandatory)][string]$Jdk,
    [Parameter(Mandatory)][string]$CMake,
    [Parameter(Mandatory)][string]$BuildDirectory,
    [Parameter(Mandatory)][string]$OutputDirectory,
    [string]$Python = 'python'
)
$ErrorActionPreference = 'Stop'
$sourcePath = (Resolve-Path -LiteralPath $Source).Path
$revision = & git -C $sourcePath rev-parse HEAD
if ($LASTEXITCODE -ne 0 -or $revision -ne '2e2543fbe9fae542f921d47a72d21d5a4ef0b710') {
    throw 'Expected the verified ONNX Runtime v1.29.0 source revision'
}
$env:JAVA_HOME = (Resolve-Path -LiteralPath $Jdk).Path
$buildPath = [IO.Path]::GetFullPath($BuildDirectory)
$ctest = Join-Path (Split-Path (Resolve-Path -LiteralPath $CMake).Path -Parent) 'ctest.exe'
if (-not (Test-Path -LiteralPath $ctest)) { throw 'ctest.exe must be beside the selected CMake executable' }
& $Python (Join-Path $sourcePath 'tools/ci_build/build.py') --build_dir $buildPath --config Release `
    --update --build --build_java --build_shared_lib --arm64 --skip_tests --parallel 4 `
    --cmake_generator 'Visual Studio 18 2026' --cmake_path $CMake --ctest_path $ctest `
    --cmake_extra_defines onnxruntime_BUILD_UNIT_TESTS=OFF
if ($LASTEXITCODE -ne 0) { throw 'ONNX ARM64 build failed' }
$outputPath = [IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Force -Path $outputPath | Out-Null
$jar = Join-Path $outputPath 'onnxruntime-1.29.0-windows-arm64.jar'
Copy-Item -LiteralPath (Join-Path $buildPath 'Release/java/build/libs/onnxruntime.jar') -Destination $jar
& $Python (Join-Path $PSScriptRoot 'verify_windows.py') --arch arm64 --jar $jar
if ($LASTEXITCODE -ne 0) { throw 'ONNX architecture verification failed' }
$notices = Join-Path $outputPath 'licenses/onnxruntime'
New-Item -ItemType Directory -Force -Path $notices | Out-Null
Copy-Item -LiteralPath (Join-Path $sourcePath 'LICENSE'),(Join-Path $sourcePath 'ThirdPartyNotices.txt') -Destination $notices
