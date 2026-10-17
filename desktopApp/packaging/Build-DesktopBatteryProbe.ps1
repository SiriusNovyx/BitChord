param(
    [ValidateSet('arm64','x64')][string]$Architecture = 'arm64',
    [string]$Cmake = 'cmake',
    [string]$Generator = 'Visual Studio 18 2026'
)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$build = [IO.Path]::GetFullPath((Join-Path $projectRoot ".work/measurement-tools/windows-$Architecture"))
if (-not $build.StartsWith($projectRoot.TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Measurement build directory escaped the project'
}
$platform = if ($Architecture -eq 'arm64') { 'ARM64' } else { 'x64' }
& $Cmake -S "$PSScriptRoot/battery-probe" -B $build --fresh -G $Generator -A $platform
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& $Cmake --build $build --config Release
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Write-Output (Join-Path $build 'Release/battery_probe.exe')
