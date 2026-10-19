param(
    [Parameter(Mandatory = $true)][ValidateSet('arm64', 'x64')][string]$Architecture,
    [string]$Cargo = 'cargo',
    [string]$Python = 'python'
)
$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
& $Python (Join-Path $PSScriptRoot 'Prepare-SpotifyCore.py')
if ($LASTEXITCODE -ne 0) { throw 'Could not prepare the pinned Spotify core source' }
$triple = if ($Architecture -eq 'arm64') { 'aarch64-pc-windows-msvc' } else { 'x86_64-pc-windows-msvc' }
$flagName = 'CARGO_TARGET_' + $triple.ToUpperInvariant().Replace('-', '_') + '_RUSTFLAGS'
$oldFlags = [Environment]::GetEnvironmentVariable($flagName, 'Process')
try {
    [Environment]::SetEnvironmentVariable($flagName, ($oldFlags + ' -C target-feature=+crt-static').Trim(), 'Process')
    $ErrorActionPreference = 'Continue'
    & $Cargo build --manifest-path (Join-Path $repoRoot 'desktopApp/native/spotify/Cargo.toml') --release --locked --target $triple
    $buildExit = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    if ($buildExit -ne 0) { throw 'Spotify native build failed' }
    $cargoTarget = if ($env:CARGO_TARGET_DIR) { $env:CARGO_TARGET_DIR } else { Join-Path $repoRoot 'desktopApp/native/spotify/target' }
    $binary = Join-Path $cargoTarget "$triple/release/bitchord-spotify.exe"
    $bytes = [IO.File]::ReadAllBytes($binary)
    $pe = [BitConverter]::ToInt32($bytes, 0x3c)
    $expected = if ($Architecture -eq 'arm64') { 0xaa64 } else { 0x8664 }
    if ([BitConverter]::ToUInt32($bytes, $pe) -ne 0x4550 -or [BitConverter]::ToUInt16($bytes, $pe + 4) -ne $expected) {
        throw 'Spotify helper architecture does not match the requested target'
    }
    $destination = Join-Path $repoRoot "desktopApp/build/native/spotify/windows-$Architecture"
    New-Item -ItemType Directory -Path $destination -Force | Out-Null
    Copy-Item -LiteralPath $binary -Destination (Join-Path $destination 'bitchord-spotify.exe') -Force
    $metadataFile = Join-Path $destination 'cargo-metadata.json'
    $ErrorActionPreference = 'Continue'
    & $Cargo metadata --offline --locked --manifest-path (Join-Path $repoRoot 'desktopApp/native/spotify/Cargo.toml') --format-version 1 --filter-platform $triple | Out-File -Encoding utf8 -LiteralPath $metadataFile
    $metadataExit = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    if ($metadataExit -ne 0) { throw 'Could not resolve Spotify license provenance' }
    & $Python (Join-Path $PSScriptRoot 'Collect-SpotifyNotices.py') $metadataFile (Join-Path $repoRoot 'desktopApp/build/spotify-licenses')
    if ($LASTEXITCODE -ne 0) { throw 'Spotify notices are incomplete' }
    Write-Output "Verified Spotify PCM helper: windows-$Architecture"
} finally {
    [Environment]::SetEnvironmentVariable($flagName, $oldFlags, 'Process')
}
