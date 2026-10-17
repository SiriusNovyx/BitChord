#![cfg_attr(all(windows, not(test)), windows_subsystem = "windows")]

use std::io::{self, BufRead, BufWriter, Read, Write};
use std::sync::{Arc, Mutex};
use std::time::Duration;
use librespot_core::{authentication::Credentials, config::SessionConfig, session::Session, spotify_uri::SpotifyUri};
use librespot_core::Error;
use librespot_playback::{audio_backend::{Sink, SinkError, SinkResult}, config::{Bitrate, PlayerConfig},
    convert::Converter, decoder::AudioPacket, mixer::NoOpVolume, player::{Player, PlayerEvent}};
use serde::Deserialize;

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Start {
    version: u32,
    client_id: String,
    token: String,
    username: String,
    #[serde(default)]
    authenticate_only: bool,
    #[serde(default)]
    track_id: String,
    #[serde(default)]
    position_ms: u32,
}

fn parse_start(bytes: &[u8]) -> Result<Start, ()> {
    if bytes.len() > 16384 { return Err(()); }
    let start: Start = serde_json::from_slice(bytes).map_err(|_| ())?;
    if start.version != 1 || start.client_id.len() != 32 || !start.client_id.bytes().all(|v| v.is_ascii_hexdigit())
        || start.token.is_empty() || start.token.len() > 8192 || start.token.bytes().any(|v| v.is_ascii_control())
        || start.username.is_empty() || start.username.len() > 256 || start.username.chars().any(char::is_control)
        || (start.authenticate_only && (!start.track_id.is_empty() || start.position_ms != 0))
        || (!start.authenticate_only && (start.track_id.len() != 22 || !start.track_id.bytes().all(|v| v.is_ascii_alphanumeric()))) {
        return Err(());
    }
    Ok(start)
}

fn write_pcm<W: Write>(out: &mut W, samples: &[f64]) -> io::Result<()> {
    if samples.len() % 2 != 0 || samples.iter().any(|v| !v.is_finite() || !(*v as f32).is_finite()) {
        return Err(io::Error::from(io::ErrorKind::InvalidData));
    }
    for chunk in samples.chunks(8192) {
        out.write_all(&[1])?;
        out.write_all(&(chunk.len() as u32).to_le_bytes())?;
        for value in chunk { out.write_all(&(*value as f32).to_le_bytes())?; }
        out.flush()?;
    }
    Ok(())
}

type Output = Arc<Mutex<BufWriter<io::Stdout>>>;

struct PcmSink(Output);

impl Sink for PcmSink {
    fn write(&mut self, packet: AudioPacket, _: &mut Converter) -> SinkResult<()> {
        let AudioPacket::Samples(samples) = packet else {
            return Err(SinkError::InvalidParams("PCM output required".into()));
        };
        let mut out = self.0.lock().map_err(|_| SinkError::OnWrite("PCM channel closed".into()))?;
        write_pcm(&mut *out, &samples).map_err(|_| SinkError::OnWrite("PCM channel closed".into()))
    }
}

fn status(out: &Output, kind: u8, value: u32) -> io::Result<()> {
    let mut writer = out.lock().map_err(|_| io::ErrorKind::BrokenPipe)?;
    writer.write_all(&[kind])?;
    writer.write_all(&value.to_le_bytes())?;
    writer.flush()
}

fn authentication_code(failure: &Error) -> u32 {
    // The upstream reason type is private. Match only pinned, public reason text;
    // never forward an arbitrary error string across the process boundary.
    match failure.error.to_string().as_str() {
        "Login failed with reason: Bad credentials" => 310,
        "Login failed with reason: Premium account required" => 311,
        "Login failed with reason: Invalid app key" => 312,
        "Login failed with reason: Application banned" => 313,
        "Login failed with reason: Extra verification required" => 314,
        "Login failed with reason: Could not validate credentials" => 315,
        _ => 100 + failure.kind as u32,
    }
}

fn playback_credentials(start: &mut Start) -> Credentials {
    let mut credentials = Credentials::with_access_token(std::mem::take(&mut start.token));
    credentials.username = Some(start.username.clone());
    credentials
}

async fn play(mut start: Start, out: Output) -> Result<(), u32> {
    let credentials = playback_credentials(&mut start);
    let config = SessionConfig { client_id: start.client_id, autoplay: Some(false), ..SessionConfig::default() };
    let session = Session::new(config, None);
    tokio::time::timeout(Duration::from_secs(25), session.connect(credentials, false)).await
        .map_err(|_| 104u32)?.map_err(|failure| authentication_code(&failure))?;
    // Compare the server-authenticated session identity, never a display name.
    if session.username() != start.username { session.shutdown(); return Err(316); }
    if start.authenticate_only {
        status(&out, 0, 0).map_err(|_| 204u32)?;
        status(&out, 2, 0).map_err(|_| 204u32)?;
        session.shutdown();
        return Ok(());
    }
    let sink_output = out.clone();
    let config = PlayerConfig { bitrate: Bitrate::Bitrate320, normalisation: false, gapless: false,
        ..PlayerConfig::default() };
    let player = Player::new(config, session.clone(), Box::new(NoOpVolume), move || Box::new(PcmSink(sink_output)));
    let mut events = player.get_player_event_channel();
    let uri = SpotifyUri::from_uri(&format!("spotify:track:{}", start.track_id)).map_err(|_| 203u32)?;
    status(&out, 0, 0).map_err(|_| 204u32)?;
    player.load(uri, true, start.position_ms);
    while let Some(event) = events.recv().await {
        match event {
            PlayerEvent::EndOfTrack { .. } => {
                status(&out, 2, 0).map_err(|_| 204u32)?;
                player.stop();
                session.shutdown();
                return Ok(());
            },
            PlayerEvent::Unavailable { .. } => return Err(201),
            _ => {},
        }
    }
    Err(205)
}

#[tokio::main]
async fn main() {
    if std::env::args().nth(1).as_deref() == Some("--version") {
        println!("BitChord Spotify PCM 1; librespot 0.8.0; {}", std::env::consts::ARCH);
        return;
    }
    // No logger is installed: upstream trace output can contain authentication data.
    std::panic::set_hook(Box::new(|_| {}));
    let out = Arc::new(Mutex::new(BufWriter::with_capacity(65536, io::stdout())));
    {
        let mut writer = out.lock().unwrap();
        writer.write_all(b"BCSP\x01\x00\x00\x00").unwrap();
        writer.write_all(&44100u32.to_le_bytes()).unwrap();
        writer.write_all(&2u32.to_le_bytes()).unwrap();
        writer.flush().unwrap();
    }
    let mut line = Vec::new();
    let read = io::stdin().lock().take(16385).read_until(b'\n', &mut line);
    let start = match read.ok().and_then(|_| parse_start(&line).ok()) {
        Some(start) => start,
        None => { let _ = status(&out, 3, 10); std::process::exit(2); },
    };
    line.fill(0);
    std::thread::spawn(|| {
        let mut byte = [0u8; 1];
        // A closed parent pipe ends this session, including a blocked PCM writer.
        let _ = io::stdin().read(&mut byte);
        std::process::exit(0);
    });
    if let Err(code) = play(start, out.clone()).await {
        let _ = status(&out, 3, code);
        std::process::exit(1);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn token_login_supplies_the_verified_username_and_consumes_the_token() {
        let mut start = parse_start(br#"{"version":1,"client_id":"65b708073fc0480ea92a077233ca87bd","token":"synthetic","username":"expected-account","authenticate_only":true}"#).unwrap();
        let credentials = playback_credentials(&mut start);
        assert_eq!(credentials.username.as_deref(), Some("expected-account"));
        assert!(start.token.is_empty());
    }

    #[test]
    fn authentication_only_requires_a_bounded_expected_account_and_no_track() {
        let valid = br#"{"version":1,"client_id":"65b708073fc0480ea92a077233ca87bd","token":"synthetic","username":"expected-account","authenticate_only":true}"#;
        assert!(parse_start(valid).is_ok());
        assert!(parse_start(&String::from_utf8_lossy(valid).replace("expected-account", "").into_bytes()).is_err());
        assert!(parse_start(&String::from_utf8_lossy(valid).replace("expected-account", &"x".repeat(257)).into_bytes()).is_err());
        assert!(parse_start(&String::from_utf8_lossy(valid).replace("\"authenticate_only\":true", "\"authenticate_only\":true,\"track_id\":\"0123456789012345678901\"").into_bytes()).is_err());
    }

    #[test]
    fn authentication_rejections_preserve_only_the_public_reason_number() {
        let failure = Error::permission_denied(io::Error::new(io::ErrorKind::PermissionDenied,
            "Login failed with reason: Bad credentials"));
        assert_eq!(authentication_code(&failure), 310);
    }

    #[test]
    fn pcm_frames_are_bounded_little_endian_float_stereo() {
        let mut output = Vec::new();
        write_pcm(&mut output, &[0.25, -0.5, 1.0, 0.0]).unwrap();
        assert_eq!(&output[..5], &[1, 4, 0, 0, 0]);
        assert_eq!(&output[5..9], &0.25f32.to_le_bytes());
        assert_eq!(&output[9..13], &(-0.5f32).to_le_bytes());
        let mut large = Vec::new();
        write_pcm(&mut large, &vec![0.0; 16386]).unwrap();
        assert_eq!(large.len(), 16386 * 4 + 3 * 5);
    }

    #[test]
    fn invalid_or_partial_pcm_is_rejected() {
        assert!(write_pcm(&mut Vec::new(), &[f64::NAN, 0.0]).is_err());
        assert!(write_pcm(&mut Vec::new(), &[0.0]).is_err());
    }

    #[test]
    fn input_rejects_invalid_version_identity_and_unbounded_credentials() {
        let valid = br#"{"version":1,"client_id":"aaa50631fe1f4db78234e8674ac9a16d","token":"synthetic","username":"expected-account","track_id":"0123456789012345678901","position_ms":1000}"#;
        assert!(parse_start(valid).is_ok());
        assert!(parse_start(&String::from_utf8_lossy(valid).replace("\"version\":1", "\"version\":2").into_bytes()).is_err());
        assert!(parse_start(&String::from_utf8_lossy(valid).replace("synthetic", "").into_bytes()).is_err());
        assert!(parse_start(&String::from_utf8_lossy(valid).replace("synthetic", &"x".repeat(20_000)).into_bytes()).is_err());
        assert!(parse_start(&String::from_utf8_lossy(valid).replace("0123456789012345678901", "../other").into_bytes()).is_err());
    }
}
