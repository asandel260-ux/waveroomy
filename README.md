# HoMit for Android

A native Android port of HoMit (Kotlin, Jetpack Compose, native WebRTC). **No server of any kind.**
Every feature of the web app is rebuilt to run phone-to-phone.

## Status: read this first
This project was written without an Android SDK, so it has **not been compiled or run**.
The non-UI core (`core/`, `net/Sdp.kt`, `room/RoomEngine.kt`) was type-checked against stubs, and every file was syntax-checked.
Expect a handful of compile errors on first build, mostly in the Compose UI and the WebRTC glue. They should be small.
No Gradle wrapper jar is included.

## Build
1. Open the folder in Android Studio (Ladybug or newer, JDK 17).
2. Let Gradle sync. Android Studio will fetch Gradle 8.9 and generate the wrapper itself.
3. Run on a real device (Android 8.0+). Emulators cannot do mic, camera or LAN discovery reliably.

Toolchain: Kotlin 2.0.21, AGP 8.7.3, compileSdk 35, minSdk 26, Compose BOM 2024.12.01.
WebRTC: `io.github.webrtc-sdk:android:125.6422.07`. If it does not resolve, switch to `org.webrtc:google-webrtc:1.0.32006` in `app/build.gradle.kts`. Voice effects then switch off automatically.

## How people connect without a server
- **QR or text code.** The host shows an invite (a QR or a `WR1.` text code). The guest scans it and shows back an answer code. Long codes are split into multi-frame QRs.
- **Same Wi-Fi.** Devices announce themselves by UDP beacon on port 47877 and exchange offer/answer over a TCP socket, with no scanning needed.
- **Mesh.** After the first link, further peers negotiate through existing links, so a room fills in by itself.
- **Identity.** Each install has an EC P-256 key; your user id is derived from it, and links are authenticated with a signed handshake.
- **Room state.** The earliest-joined host runs the room rules. If they leave, every member computes the same successor, with no election messages.
- **STUN / TURN.** Off by default (direct connections on the same LAN or with open NAT). Turn STUN on in Settings for different networks, and add your own TURN if both sides are behind strict NATs. This is the one case where "no server" is physically impossible.

## Features
Voice rooms with stage and audience, hand-raise, mute, deafen and push-to-talk; chat and reactions; DMs; profiles, follow and block; reports and moderation; 8 voice effects; screen share with quality control; Watch Together (YouTube); shared browser; shared infinite blackboard with PNG/JPG/SVG/PDF export; saved rooms; lock and invite.

## Known limitations
- No system-audio sharing in screen share.
- A mesh is practical to about 8-10 people (hard cap 24).
- Relayed messages from non-adjacent peers are not individually authenticated.
- DMs are delivered only while both people are connected; otherwise they queue.
- Boards are limited to 8 MB and are sent as snapshots to late joiners.
- Watch Together needs internet to load YouTube; the shared browser likewise.
- Unverified on a device: YouTube IFrame bridge, WebView behaviour, voice-FX reflection hook, several WebRTC calls.

## Layout
`core/` models and storage. `net/` SDP, peer links, mesh, LAN. `room/` engine, session, DMs. `media/` audio, screen share, service. `ui/` Compose screens. `tools/gen_icons.py` regenerates icons.


## Connecting people

* **Add by @username or link** – search a username, or paste a `waveroom://add?...` link, and send a request. Requests and public cards travel through a public message board (ntfy.sh by default; configurable in Settings → Network). Only signed public cards pass through it.
* **Friends reconnect automatically** – accepting a request derives a shared secret from both identity keys (ECDH). From then on the phones find each other again via encrypted notes on the board, or directly on the same Wi-Fi, with no codes.
* **Fallbacks** – QR / text codes and the "On this Wi-Fi" list still work with no internet at all.
