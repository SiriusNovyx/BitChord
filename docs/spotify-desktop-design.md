# Browser authorization and native Spotify playback

Requested on 15 October 2026: replace manual `sp_dc` setup and add Spotify library
access and playback, using Spotifast's connection flow as the reference.
This document records the design and the verification boundary of the implementation.

## Existing implementation

The desktop already lists Spotify playlists and Liked Songs through the shared
`SpotifyLibrary` client. It uses private web-player endpoints and a cookie-derived
bearer. Playlist tracks are matched to YouTube Music for playback. Canvas uses
the same web-player session. This is not native Spotify audio playback.

The translation-language defect is separate: `DesktopPlayerSettings` exposed an
empty flow instead of `DesktopTranslationSetting.language`. The player now reads
the saved flow, including changes made while the player is open.

## Implementation boundaries

1. Desktop browser authorization uses PKCE S256, a cryptographic state value and
   a listener bound only to a loopback IP. Bind before opening the browser, enforce
   an expiry and cancel outstanding work on disconnect or replacement sign-in.
   Never request a client secret in a desktop app. Library access uses the user's
   registered fork client; playback's separate public librespot identity is
   documented below. Developer-app configuration and Spotify's
   current account/permission requirements must be checked before live verification.
2. Web API library access uses the verified account's access grant, a serialized
   refresh path and bounded pagination. Request only the scopes implemented by
   the UI. Handle denied consent, revoked grants, account replacement and
   `Retry-After` explicitly. Do not feed an API grant into private Canvas endpoints
   and assume it is equivalent to a web-player credential.
3. Local playback uses a separate authenticated playback session. The preferred
   feasibility path is maintained Rust librespot, isolated behind a small desktop
   process adapter, with genuine Windows ARM64 and x64 builds. The deprecated
   librespot-java implementation is not a suitable new dependency. Browser Web
   Playback SDK support in embedded WebView2 must not be assumed.
4. Keep Compose, the existing queue and the existing audio output/recovery path.
   Investigate a PCM source adapter for librespot so output selection, media keys,
   seeking and background behavior retain one owner. Do not substitute a YouTube
   recording and label it Spotify playback. Do not claim Spotify lossless, Automix
   compatibility or Canvas access without an exercised implementation.
5. Persist grants only through a platform-protected credential store. On Windows,
   the existing DPAPI bridge is available. Portable credentials remain protected
   to the current Windows user; portable data does not imply transferable sign-in.
   Never print credentials, place them in process arguments or include them in
   benchmark/archive outputs. Preserve existing sign-in until migration is tested.

## Validation before delivery

- PKCE/state/callback binding, timeout, cancellation and refresh concurrency tests.
- API pagination, stale-account results, 401/revocation and 429 cooldown tests.
- ARM64/x64 native binary inspection and local startup.
- User-completed Spotify approval; verify account identity, library results and
  actual Spotify audio, pause/resume, seeking, device change and shutdown.
- Shared-player queue, lyrics, SMTC and Bluetooth regression checks.
- Foreground and background GPU/power samples including the playback process.
- License/provenance record and complete portable packaging.

## Evidence and current limits

No Rust toolchain was initially found on PATH or in the current user's standard
Cargo bin directory. A portable ARM64 Rust 1.99 toolchain was extracted under
`.work/tools` from the official manifest with SHA256 checks, without installation
scripts or machine-wide changes. A throwaway librespot 0.8.0/custom PCM sink
prototype compiled for `aarch64-pc-windows-msvc`, passed PE architecture validation
and ran on the Surface. Its dependency lock pins vergen 9.0.6: the newer 9.1.0
resolved incompatible vergen-lib versions for librespot's build script.

The implementation now contains browser PKCE, a single-use bounded loopback
callback, DPAPI-protected Windows grants, serialized refresh and account-revision
checks, read-only Web API playlists/Liked Songs, and a bounded native PCM adapter.
The user manually approved both library and streaming access. The account and a
13-track playlist loaded, and sign-in restored after a full application restart.
No client secret was requested. The existing Canvas cookie was preserved.

Native helpers compile with static MSVC CRT for ARM64 and x64, pass PE checks and
run `--version` on this Surface (x64 under emulation). Six Rust helper tests
pass on ARM64. The Kotlin reader validates format, size, interleaving, finite
samples and explicit end-of-stream. Seeking retires the entire old helper/queue
before starting a new decoder at the requested position; decoder restart latency
still needs measurement. Audio is passed into the existing desktop output and
Bluetooth recovery path. Audio data is never written to a BitChord download.

The first integrated Spotify track attempt failed before PCM became available.
That failure was retained while the authentication path was investigated.
The fork's personal Web API grant reported Premium, but its streaming
token was rejected by the native access point. Playback now has a separate public
librespot desktop identity (`65b708073fc0480ea92a077233ca87bd`), streaming-only
approval and callback `http://127.0.0.1:8898/login`, following the pinned reference
flow. This is independent of the user's registered fork client and its library
callback. No client secret or shared catalogue client is used.

The first separate-grant attempt returned HTTP 429 inside BitChord after browser
approval. Inspection found an incorrect Web API `/me` request using that playback
token. The original error did not record the rejected request stage, so the exact 429
endpoint is not established. The identified token/profile misuse is corrected.
Playback authorization now authenticates through the native session and
compares its server-confirmed username with the current Web API profile's user
ID. The immutable `account_id` remains the protected, persistent account binding;
the current user ID is used only for the live native identity check. Both
connection and restore must pass this check before the grant becomes available.
The helper repeats the check on each decoder connection. Its verification mode
does not start an audio decoder or report identifiers to logs.

Playback keeps a separate DPAPI-protected OAuth refresh grant, rather than the
reference application's reusable access-point credential. Refresh retains the
immutable account binding. A mismatched account is rejected; user names and
tokens are never printed. API 429 handling honors `Retry-After`, suppresses further
requests during the cooldown, and now exposes that delay in the sign-in dialog.
The first corrected live attempt reached native authentication but returned
311 (`Premium account required`), also observed in the GUI. The user confirms
Premium and the personal Web API profile had returned `product=premium`.
This error is not treated as proof that the subscription is Free.

The upstream [Windows ARM report](https://github.com/crmne/spotifast/issues/123)
identifies an obsolete Windows CE platform value in librespot's AP handshake.
The published 0.8.0 core has that same mapping. A native ARM64 regression against
the actual serialized `ClientHello` failed with `PLATFORM_WINDOWS_CE_ARM` and now
passes with `PLATFORM_WIN32_X86_64`. This is a protocol compatibility value; the
compiler target, PE architecture, CPU reporting, decoder and JVM remain ARM64.
The helper also supplies the already verified Web API username in token login,
then compares the server-confirmed identity before accepting it. Its username
regression failed with `None` before the correction and now passes.

`Prepare-SpotifyCore.py` retrieves the exact checksum-pinned 0.8.0 crate, applies
the reviewed narrow patch and records source/patch hashes. It preserves edits in
the generated source instead of overwriting them. The locked helper uses this
local core patch; the other librespot crates retain their pinned registry versions.
Notices include the base source URL/checksum and the actual patch. The standard
Git patch applicability check, serialized handshake regression and all six helper
tests pass on ARM64; the serialized handshake test also passes on x64 under
emulation. The corrected live sign-in succeeded on the Surface. A read-only
probe of the protected account-bound grant authenticated the native session and
decoded 1,152 nonzero PCM samples at 44.1 kHz stereo. In the packaged application,
Spotify playback advanced, pause/resume and seeking worked, manual Next selected
the next queued track, and reaching a track's end advanced naturally to the next
Spotify track. The user confirmed audible playback, including after disconnecting
and reconnecting Bluetooth headphones. These checks establish the exercised
ARM64 path, not long-duration reliability or authenticated x64 playback.
After the final rendering rebuild and a full restart, Account & integrations
showed both the saved library account and the separate playback grant for that
same verified account, without another browser approval.

The UI distinguishes browser library access, streaming authorization and the
legacy Canvas session. Without native authorization it explicitly describes the
existing YouTube matching behavior. Once native playback is selected, failures
remain failures: a Spotify identity is not silently played as another recording.
Spotify lossless and native Automix analysis are unsupported by this adapter;
other sources retain the existing processing and analysis behavior. The helper
requests librespot's 320 kbps setting, but no measured codec/bitrate claim is made.
Native Spotify seeds do not request YouTube radio or automatic station top-ups.
The existing queue continues normally; an explicit radio request or enabling
AutoPlay explains the limitation. Other source matching remains available.

Third-party notices cover 261 ARM64 / 262 x64 locked dependencies. Missing notice
files in published crates were obtained from the exact upstream source commits;
their URLs and SHA256 values are retained in `native/spotify/licenses/sources.json`.
No installer, CI or authenticated x64 Spotify playback is verified.

References:

- [Spotifast connection model](https://spotifast.rocks/how-it-connects/)
- [Spotify PKCE](https://developer.spotify.com/documentation/web-api/tutorials/code-pkce-flow)
- [Spotify loopback redirect requirements](https://developer.spotify.com/documentation/web-api/concepts/redirect_uri)
- [librespot](https://github.com/librespot-org/librespot)
- [librespot-java maintenance status](https://github.com/librespot-org/librespot-java)
