# RadioMe

Offline Android app that sends text and images over ordinary VHF/UHF FM walkie-talkies by turning data into
audio tones (MFSK) and back, with Reed-Solomon FEC and half-duplex selective-repeat ARQ.

The air protocol (frame layout, tone tables, FEC parameters) is in [docs/PROTOCOL.md](docs/PROTOCOL.md).

## Modules

| Module                | Status  | What it is                                                                  |
|-----------------------|---------|-----------------------------------------------------------------------------|
| `core:dsp`            | Phase 1 | Pure Kotlin: MFSK modem, chirp sync, Reed-Solomon, interleaver, link frames, radio channel simulator |
| `core:protocol`       | Phase 2 | Packetizer, DEFLATE, image pipeline, chunk tracker, half-duplex NACK/ARQ, `LinkController` |
| `core:audio`          | Phase 3 | `AudioPort` on AudioRecord (UNPROCESSED) / AudioTrack, half-duplex guard, acoustic vs cable routing, foreground service hosting the link |
| `core:voice`          | Features | Codec2 (vendored C sources, LGPL 2.1) over JNI, voice note recorder and player |
| `data`                | Phase 3 | Room history of transfers and persisted partial chunks (`RoomSessionStore`) |
| `feature:transceiver` | Phase 4 | Compose UI: radio dashboard (level, SNR, live chunk map, FEC and loss stats, correction requests), text and image composer with exact size and air time, history, settings (DataStore) |
| `app`                 | Phase 4 | Application shell: Hilt wiring, Room, the service's dependencies, permissions, launcher icon |

## Features

* Text, images (with an early preview that sharpens as chunks arrive) and one-tap quick messages.
* Station callsign on every burst; messages to one station or to all, with confirmations by callsign.
* Two-phone link test that measures each profile and suggests profile and parity.
* End-to-end encrypted GPS position beacons (AES-256-GCM, pre-shared group key) shown on an OpenStreetMap map
  with an offline tile cache. Optional encryption for all messages. Not for amateur bands.
* Group mode for messages to all: every receiver's corrections are merged into one repair and each confirmation
  is shown as a delivery receipt. Auto speed picks the profile from recent results; a send queue goes one by one.
* Voice notes of up to 30 s, coded with Codec2 700C (about 88 bytes per second of speech).
* Small files (PDF, GPX, contact cards, up to 64 KB), history export and sharing, pictures saved to the gallery.
* Live waterfall of the input, larger text and vibration options.
* Transmit keying by the radio's VOX, a right-channel VOX tone, or USB serial RTS/DTR (Digirig, CP210x, FTDI, CH34x,
  CDC-ACM).
* Home-screen widget, quick-settings tile and a lock-screen Stop button.
* English, Armenian and Russian. Translations live in `feature/transceiver/.../i18n/` keyed by the English text;
  untranslated strings fall back to English.

## Third-party code

`core/voice/src/main/cpp/codec2` is Codec2 1.2.0 by David Rowe and contributors, under the GNU LGPL 2.1 (see its
`COPYING`). It is built as a separate shared library (`libcodec2.so`) so it can be replaced on its own.

## Build and test

Requires JDK 17+. The Android modules (`core:audio`, `core:voice`, `data`, `feature:transceiver`, `app`) are included only when an Android SDK is found
(`ANDROID_HOME`, `ANDROID_SDK_ROOT` or `sdk.dir` in `local.properties`); without one, the pure-Kotlin modules
still build and test. CI builds and tests everything.

```
./gradlew test            # all unit tests (JVM + Robolectric)
./gradlew assembleDebug   # Android libraries and the debug APK (app/build/outputs/apk/debug)
```

The tests run encode -> modulate -> simulated radio channel (300-3000 Hz band-pass, AWGN, squelch drop-outs,
clock drift, clipping) -> demodulate -> decode, including a full NACK and selective-retransmission round trip.
`core:protocol` adds a two-station test: two `LinkController`s talk over a simulated radio on a virtual clock,
covering auto and manual NACK, selective repeat in ROBUST, ACK, and recovery from a lost NACK by header poll.
