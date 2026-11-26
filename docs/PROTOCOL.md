# RadioMe air protocol (v1)

Everything below is implemented in `core:dsp` and covered by its JUnit tests.

## 1. Burst on air

```
| lead-in pilot | sync chirp      | PHY header        | body                         | tail   |
| 1500 Hz       | 700 -> 2300 Hz  | ROBUST, 24 sym    | profile from header          | 1500Hz |
| 200-1500 ms   | 100 ms          | 480 ms            | n * count bytes, interleaved | 60 ms  |
| (default 600) |                 |                   |                              |        |
```

* 48 kHz, 16-bit mono, continuous phase across the whole burst, 10 ms raised-cosine fade in and out, peak 0.7 FS.
* The pilot keys VOX and opens squelch. The chirp is the sync word: an FFT matched filter
  (analytic template, so it ignores carrier phase) normalised by local energy, so detection works at any input level.
  Time-bandwidth product 160 gives about 22 dB of processing gain.
* Everything sits between 700 and 2300 Hz, inside the 600-2400 Hz budget and well inside a 300-3000 Hz radio filter.

## 2. MFSK profiles

Tones are `f_k = base + k * spacing`. The detection window is an integer number of `1/spacing`, so tones are
orthogonal over it. The rest of the symbol is a guard, half before and half after the window, to absorb timing
error, filter group delay and room echo. Symbol values are Gray-mapped onto tones.

| Profile  | Tones | Frequencies (Hz)            | Spacing | Symbol  | Window | Guard (each side) | Baud | Raw bit rate |
|----------|-------|-----------------------------|---------|---------|--------|-------------------|------|--------------|
| ROBUST   | 8     | 800, 1000, 1200 ... 2200    | 200 Hz  | 20.0 ms | 15 ms  | 2.5 ms            | 50   | 150 bit/s    |
| STANDARD | 16    | 750, 850, 950 ... 2250      | 100 Hz  | 12.5 ms | 10 ms  | 1.25 ms           | 80   | 320 bit/s    |
| FAST     | 8     | 800, 1000, 1200 ... 2200    | 200 Hz  | 5.0 ms  | 5 ms   | none              | 200  | 600 bit/s    |

Full tone tables:

| k | ROBUST / FAST | STANDARD |   | k  | STANDARD |
|---|---------------|----------|---|----|----------|
| 0 | 800           | 750      |   | 8  | 1550     |
| 1 | 1000          | 850      |   | 9  | 1650     |
| 2 | 1200          | 950      |   | 10 | 1750     |
| 3 | 1400          | 1050     |   | 11 | 1850     |
| 4 | 1600          | 1150     |   | 12 | 1950     |
| 5 | 1800          | 1250     |   | 13 | 2050     |
| 6 | 2000          | 1350     |   | 14 | 2150     |
| 7 | 2200          | 1450     |   | 15 | 2250     |

Receiver per symbol: Goertzel bank over the window, hard decision, early/late gate on the winning tone for clock-drift
tracking (tested at +/-300 ppm), and an energy test 10 dB below a running reference that marks squelch drop-outs as
**erasures**, which doubles what Reed-Solomon can repair inside a drop-out.

## 3. PHY header (always ROBUST)

```
byte 0 : [7:6] version = 1 | [5:4] profile id (0 ROBUST, 1 STANDARD, 2 FAST) | [3:0] interleave depth - 1
byte 1 : k     = codeword data bytes (= link frame length, 11 + P)
byte 2 : nsym  = RS parity bytes per codeword
byte 3 : codeword count in this burst (1..255)
byte 4 : CRC-8 (poly 0x07) over bytes 0..3
byte 5..8 : RS(9,5) parity
```

The receiver learns the profile and FEC geometry from the header, so the sender can switch profile per burst
(for example step down to ROBUST for a repair burst) without the receiver being configured.

## 4. Link frame (= RS codeword data part)

Big-endian. All frames in one burst share the same payload capacity P (default 48, allowed 16..200).

| Offset | Size | Field                                                       |
|--------|------|-------------------------------------------------------------|
| 0      | 1    | Magic `0xA5`                                                |
| 1      | 1    | Version (high nibble, = 1) / FrameType (low nibble)         |
| 2      | 2    | SessionId (u16)                                             |
| 4      | 2    | ChunkIndex (u16)                                            |
| 6      | 2    | TotalChunks (u16)                                           |
| 8      | 1    | PayloadLen (u8, <= P)                                       |
| 9      | P    | Payload, zero padded to P                                   |
| 9+P    | 2    | CRC-16/CCITT-FALSE over bytes 0 .. 8+P                      |
| 11+P   | nsym | Reed-Solomon parity (added per codeword by `BurstCodec`)    |

FrameType: `DATA = 1`, `HEADER = 2` (session metadata, defined in Phase 2), `NACK = 3`, `ACK = 4`,
`STATION = 5`, `PROBE = 6`, `PROBE_REPORT = 7` (section 10). Older receivers drop frame types they do not know.

NACK payload, whichever form covers more indices per frame:

```
bitmap : 0x00 | base u16 | bitmap (bit 7 of first byte = chunk base)   -> up to 360 chunks in one 48-byte payload
list   : 0x01 | count u8 | count x u16                                 -> up to 23 scattered chunks
```

## 5. Reed-Solomon and interleaving

* RS over GF(2^8), field polynomial `0x11D`, alpha = 2, first consecutive root 0, systematic, shortened to
  `n = k + nsym <= 255`. Decoder: Berlekamp-Massey with erasure initialisation, Chien search, Forney.
  Corrects any `2 * errors + erasures <= nsym`. Uncorrectable words are reported, and the frame CRC catches the rare
  mis-decode.
* Parity ratio `nsym / k` is configurable 20-50% (rounded up to even):

| Profile  | Default ratio | k (P = 48) | nsym | n  | Fixes per frame (errors / erasures) | Interleave depth |
|----------|---------------|------------|------|----|-------------------------------------|------------------|
| ROBUST   | 50%           | 59         | 30   | 89 | 15 / 30                             | 8                |
| STANDARD | 33%           | 59         | 20   | 79 | 10 / 20                             | 6                |
| FAST     | 20%           | 59         | 12   | 71 | 6 / 12                              | 4                |

* Block interleaver: codewords are grouped by `depth`; inside a group byte j of every codeword goes out before byte
  j+1 of any. A burst of B bad bytes costs each codeword at most ceil(B / depth). Depth is bounded on purpose: a
  drop-out too long for FEC then loses one group of frames, which ARQ re-requests, instead of damaging every frame.

## 6. Throughput (P = 48, defaults)

| Profile  | Air time per 48-byte chunk | Effective payload rate | 2 KB image (43 chunks) |
|----------|----------------------------|------------------------|------------------------|
| ROBUST   | 4.75 s                     | ~81 bit/s              | ~205 s                 |
| STANDARD | 1.98 s                     | ~194 bit/s             | ~86 s                  |
| FAST     | 0.95 s                     | ~405 bit/s             | ~42 s                  |

Plus about 1.24 s fixed per burst (600 ms lead-in, chirp, header, tail).

## 7. Measured margins (channel simulator, 1.2 KB payload, 4 random 50-150 ms drop-outs, +/-300 ppm drift, 8 seeds)

| Profile  | SNR 3 dB   | SNR 0 dB    | SNR -3 dB   |
|----------|------------|-------------|-------------|
| ROBUST   | 0 lost     | 0 lost      | 0 lost      |
| STANDARD | 0 lost     | 0 lost      | 0 lost      |
| FAST     | 0 lost     | 2/200 lost  | all lost    |

SNR is measured in a 2500 Hz bandwidth.

## 8. Session layer (`core:protocol`)

A transfer is one HEADER frame (ChunkIndex `0xFFFF`) plus `TotalChunks` DATA frames, all with the same SessionId.

HEADER frame payload:

| Offset | Size | Field                                              |
|--------|------|----------------------------------------------------|
| 0      | 1    | Header version (= 1)                               |
| 1      | 1    | Content type: TEXT = 1, IMAGE_WEBP = 2, BINARY = 3, IMAGE_PROGRESSIVE = 4 |
| 2      | 1    | Flags: bit 0 = raw DEFLATE                         |
| 3      | 4    | Wire length (bytes carried by DATA chunks)         |
| 7      | 4    | Original length                                    |
| 11     | 4    | CRC-32 of the original bytes                       |
| 15     | 1    | Chunk payload size P (32..64)                      |
| 16     | 1    | Name length                                        |
| 17     | n    | Name, UTF-8, truncated to fit the frame            |

Text and binary payloads are DEFLATE-compressed only when that makes them smaller. Images are resized
(128 / 240 / 320 px long edge, area averaging), optionally converted to grayscale, and encoded as lossy WebP at
10-40% quality. The compose screen gets the exact byte size and air time before sending.

### Half-duplex ARQ

```
Sender                                   Receiver
  | HEADER + DATA bursts (chosen profile)  |
  |--------------------------------------->|  tracks OK / FEC-repaired / missing per chunk
  |      ... turnaround (2 s of quiet) ... |
  |<---------------------------------------|  NACK (ROBUST): missing chunk list or bitmap,
  |                                        |  0xFFFF if the HEADER frame was lost
  | only the requested chunks (ROBUST)     |
  |--------------------------------------->|  merged into the stored partial session
  |<---------------------------------------|  ACK (ROBUST) once complete and CRC-32 verified
```

* Auto NACK is the default. With it off, the receiver reports "corrections needed" and waits for the user.
* If the sender hears nothing for 6 s of quiet, it re-sends only its HEADER frame as a poll (up to 2 times).
  A receiver that already has the session answers the poll with a NACK, or with an ACK if it is already complete.
* A sender serves at most 5 repair rounds.
* All timers run on the audio clock (input samples), so the behaviour matches between devices and in tests.
* Half-duplex guard: captured audio is dropped while transmitting, the demodulator is reset afterwards, and a
  queued burst waits until the channel is quiet (listen before talk).

### Progressive images (IMAGE_PROGRESSIVE = 4)

The payload holds the same picture at increasing sizes, smallest first, sent uncompressed so payload offsets equal
wire offsets:

```
level count n (1..4) | n x length u16 | level 0 WebP | level 1 WebP ...
```

The sender adds a preview about 1/8 of the long edge at the minimum quality (typically 100-250 bytes) when that
costs at most a third of the picture. The receiver shows the preview as soon as its chunks are in, then draws the
gap-free start of the full level over it (Android 9+ decodes incomplete WebP row by row), so the picture sharpens
from the top while chunks arrive.

## 9. Android audio path (`core:audio`)

* Capture: `AudioRecord` at 48 kHz mono 16-bit, `UNPROCESSED` source when the device reports support, else
  `VOICE_RECOGNITION`. AGC, noise suppression and echo cancellation are switched off on the session if the
  platform attaches them anyway. Capture runs on its own urgent-audio thread in 50 ms blocks from a fixed pool,
  so nothing is allocated per block.
* Playback: `AudioTrack` in streaming mode on a dedicated urgent-audio thread. `play()` returns only after the
  last sample has actually played, so the link's turnaround timing is accurate.
* Half-duplex guard: capture is muted while transmitting and for 150 ms after, on top of the link's own guard.
* Routing: acoustic (built-in mic and speaker) or cable (wired TRRS, USB audio such as Digirig). Auto picks
  cable when a wired/USB input and output are both present. The chosen devices are set as preferred devices.
* `TransceiverService` is a foreground service (`microphone|mediaPlayback`) that owns the engine and the
  `LinkController`, so transfers continue with the screen off.

## 10. Stations and link test

### STATION frame (type 5)

With a callsign set, every burst (data, poll, NACK, ACK, link test) starts with one STATION frame carrying the
burst's SessionId. It is in clear on purpose: amateur rules require station identification.

```
flags u8 (bit 0 = broadcast) | source length s (0..10) | source ASCII | destination length d (0..10, 0 = all) | destination ASCII
```

Callsigns are upper case `A-Z 0-9 / -`, at most 10 characters. A receiver whose callsign differs from a non-empty
destination drops the session (no NACK, no ACK, nothing shown). A session with no STATION frame, or an empty
destination, is for everyone. NACK and ACK bursts carry the receiver's callsign with the sender as destination, so
the sender learns who confirmed.

### Link test (PROBE = 6, PROBE_REPORT = 7)

The tester sends three bursts back to back: FAST with 6, STANDARD with 4 and ROBUST with 2 PROBE frames
(P = 48, parity 20%, about 25 s in all). PROBE payload: `profile id | index | count | filler`.

After the turnaround the receiver answers with one PROBE_REPORT frame (ROBUST):

```
version u8 (= 1) | entries u8 | entries x (profile id, sent, received, repaired, corrected bytes u16, SNR i8 in 0.5 dB, 127 = not heard)
```

Both sides suggest the fastest profile whose probes all arrived at or above its SNR floor (FAST 6 dB,
STANDARD 0 dB), with parity `2 * ceil(2 * errors per frame + 1)` bytes, rounded up to 5% and kept within 20..50%;
otherwise ROBUST at 50%. The tester gives up after twice the response timeout with no report.
