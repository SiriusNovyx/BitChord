# Spotify desktop implementation plan

Goal: browser sign-in, official library access and native Spotify PCM playback
inside the existing desktop player. Follow `spotify-desktop-design.md`.
No commits or publishing are authorized. Preserve the existing Canvas session.

1. Add `DesktopSpotifyOAuth.kt` and focused tests. Use the supplied public app
   ID, S256 PKCE and single-use state. Bind `127.0.0.1:43821` before opening the
   browser. Reject wrong paths, hosts, methods, duplicate parameters and stale
   callbacks. Test cancellation, expiry and port release with real loopback HTTP.
2. Add a bounded HTTPS token/API client and protected grant repository. Test
   refresh serialization, disconnect during refresh, account changes, denied
   access and rate limits with local fixtures. Never log response bodies or
   authorization URLs. Windows persistence uses DPAPI; unavailable protection
   means session-only sign-in, with visible status.
3. Expose browser connection in integrations and use official read-only library
   endpoints when connected. Keep the legacy Canvas controls separately named.
   Verify account and library through user-completed browser approval.
4. Promote the native librespot feasibility build to a versioned PCM helper.
   Bound protocol and audio buffering, protect credentials on stdin, require
   seek acknowledgements and terminate promptly. Add an adapter at
   `DesktopSampleSource`, preserving output recovery and the queue owner.
   Exercise native ARM64 and x64 before bundling; record third-party licenses.
5. Verify Spotify audio, pause, seeking, queue, Bluetooth changes, SMTC and exit.
   Rebuild the portable archive, inspect binaries and refresh validation docs.

Each implementation step needs a failing behavioral test before its production
change and a passing focused check afterward. Live sign-in and playback remain
unverified until exercised. The existing YouTube matching path must remain
explicitly described as matching, never as native Spotify playback.
