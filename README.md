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
| `data`                | Phase 3 | Room history of transfers and persisted partial chunks (`RoomSessionStore`) |
| `feature:transceiver` | Phase 4 | Compose UI: radio dashboard (level, SNR, live chunk map, FEC and loss stats, correction requests), text and image composer with exact size and air time, history, settings (DataStore) |
| `app`                 | Phase 4 | Application shell: Hilt wiring, Room, the service's dependencies, permissions, launcher icon |

## Features

* Text, images (with an early preview that sharpens as chunks arrive) and one-tap quick messages.
* Station callsign on every burst; messages to one station or to all, with confirmations by callsign.
* Two-phone link test that measures each profile and suggests profile and parity.
* End-to-end encrypted GPS position beacons (AES-256-GCM, pre-shared group key) shown on an OpenStreetMap map
  with an offline tile cache. Optional encryption for all messages. Not for amateur bands.

## Build and test

Requires JDK 17+. The Android modules (`core:audio`, `data`, `feature:transceiver`, `app`) are included only when an Android SDK is found
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
