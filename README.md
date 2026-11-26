# RadioMe

Offline Android app that sends text and images over ordinary VHF/UHF FM walkie-talkies by turning data into
audio tones (MFSK) and back, with Reed-Solomon FEC and half-duplex selective-repeat ARQ.

The air protocol (frame layout, tone tables, FEC parameters) is in [docs/PROTOCOL.md](docs/PROTOCOL.md).

## Modules

| Module                | Status  | What it is                                                                  |
|-----------------------|---------|-----------------------------------------------------------------------------|
| `core:dsp`            | Phase 1 | Pure Kotlin: MFSK modem, chirp sync, Reed-Solomon, interleaver, link frames, radio channel simulator |
| `core:protocol`       | Phase 2 | Packetizer, compression, image pipeline, chunk tracker, NACK/ARQ state machine |
| `core:audio`, `data`  | Phase 3 | AudioRecord/AudioTrack engine, foreground service, Room persistence         |
| `feature:transceiver` | Phase 4 | Compose UI                                                                  |

## Build and test

Requires JDK 17+.

```
./gradlew :core:dsp:test
```

The tests run encode -> modulate -> simulated radio channel (300-3000 Hz band-pass, AWGN, squelch drop-outs,
clock drift, clipping) -> demodulate -> decode, including a full NACK and selective-retransmission round trip.
