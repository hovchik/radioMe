# RadioMe

Offline Android app that sends text and images over ordinary VHF/UHF FM walkie-talkies by turning data into
audio tones (MFSK) and back, with Reed-Solomon FEC and half-duplex selective-repeat ARQ.

The air protocol (frame layout, tone tables, FEC parameters) is in [docs/PROTOCOL.md](docs/PROTOCOL.md).

## Modules

| Module                | Status  | What it is                                                                  |
|-----------------------|---------|-----------------------------------------------------------------------------|
| `core:dsp`            | Phase 1 | Pure Kotlin: MFSK modem, chirp sync, Reed-Solomon, interleaver, link frames, radio channel simulator |
| `core:protocol`       | Phase 2 | Packetizer, DEFLATE, image pipeline, chunk tracker, half-duplex NACK/ARQ, `LinkController` |
| `core:audio`, `data`  | Phase 3 | AudioRecord/AudioTrack engine, foreground service, Room persistence         |
| `feature:transceiver` | Phase 4 | Compose UI                                                                  |

## Build and test

Requires JDK 17+.

```
./gradlew test
```

The tests run encode -> modulate -> simulated radio channel (300-3000 Hz band-pass, AWGN, squelch drop-outs,
clock drift, clipping) -> demodulate -> decode, including a full NACK and selective-retransmission round trip.
`core:protocol` adds a two-station test: two `LinkController`s talk over a simulated radio on a virtual clock,
covering auto and manual NACK, selective repeat in ROBUST, ACK, and recovery from a lost NACK by header poll.
