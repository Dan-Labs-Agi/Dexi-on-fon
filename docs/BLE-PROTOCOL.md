# Dani Glasses: Bluetooth LE protocol

This guide is for any phone app that wants to use the glasses as its microphone and speaker.
The glasses only move audio. The app does everything else: speech recognition, the AI model,
accounts and keys. Nothing secret is stored on the glasses.

```
glasses mic ──(MIC notify)──► app ──► your audio model (Gemini Live, OpenAI Realtime, ...)
glasses speaker ◄──(SPK write)── app ◄──────────── model's spoken reply
```

## Connecting

| Item | Value |
|---|---|
| Advertised name | `Dani-XXXX` (last 4 hex digits of the Bluetooth address) |
| Service UUID (in the advertisement) | `7b9a0001-3c2e-4f6a-8d1b-2e5f6a7b8c9d` |
| Pairing | None needed (see "Security" below) |
| MTU | Request **517**. The glasses send one 20 ms frame (165 bytes) per packet when MTU ≥ 168. |
| Connection priority | Request **high** on Android (`CONNECTION_PRIORITY_HIGH`); the glasses ask for a 15–30 ms interval. |

Scan for the service UUID, connect, request MTU 517, discover services, then **enable
notifications on CTRL first, then MIC** (write `0x0001` to each one's 0x2902 descriptor).

## Characteristics

| Name | UUID | Properties | Direction |
|---|---|---|---|
| MIC | `7b9a0002-3c2e-4f6a-8d1b-2e5f6a7b8c9d` | notify | glasses → app: mic audio |
| SPK | `7b9a0003-3c2e-4f6a-8d1b-2e5f6a7b8c9d` | write without response | app → glasses: audio to play |
| CTRL | `7b9a0004-3c2e-4f6a-8d1b-2e5f6a7b8c9d` | write, notify, read | text commands and events, UTF-8 |

## Audio packets (MIC and SPK use the same format)

16 kHz, mono, 16-bit audio compressed with **IMA ADPCM** (4 bits per sample, 4× smaller than PCM).

| Bytes | Field |
|---|---|
| 0–1 | `seq`, uint16 little-endian, +1 per packet (wraps at 65535) |
| 2–3 | `predictor`, int16 little-endian: codec state at the start of this packet |
| 4 | `step index`, uint8 (0–88): codec state at the start of this packet |
| 5… | ADPCM data, two samples per byte, **first sample in the high nibble** |

- Samples in a packet = 2 × (packet length − 5). From the glasses this is 320 samples (20 ms) per packet.
- Because every packet carries the codec state, each one decodes on its own. A lost packet costs 20 ms of audio, not the rest of the stream.
- To send audio, encode with a running encoder state and put the state *before* each chunk in the header. Recommended: 320 samples (165 bytes) per packet.
- The ADPCM tables and bit order are the standard IMA/DVI ones, the same as Python's `audioop.lin2adpcm`. A Kotlin implementation is in `app/app/src/main/java/com/danny/glasses/Audio.kt`, and C in `firmware/main/main.cpp`.

**Gaps (`seq` jumping)** mean lost packets: fill the missing audio with silence.

### Sending reply audio (SPK)

- The glasses buffer up to **8 s** and start playing after 100 ms is buffered (or when data stops arriving, for short sounds).
- **Pace the sending.** Don't dump a whole reply at once; stay about 0.3–0.5 s ahead of real time. The `buffer=` value in `stat` events shows how much is queued.
- Many AI models output **24 kHz**: resample to 16 kHz before encoding.
- To **interrupt** playback (e.g. the user starts talking), send `flush` on CTRL.

### Echo protection

The speaker is close to the mic. While the glasses play audio, and for 250 ms after, the MIC
stream carries **silence** instead of the mic (`gate on`, the default). This stops the AI hearing
itself, but it also means the user can't interrupt by voice while it talks. Use the button
(below) or `gate off` with an echo-cancelling model to allow interrupting.

## CTRL commands (app → glasses)

| Command | Effect | Reply |
|---|---|---|
| `ping` | | `pong` |
| `status` | | `status name=… fw=… connected=1 mtu=517 mic=on gate=on volume=80 gain=6 …` |
| `volume N` | speaker volume 0–100 (saved) | `ok volume=N` |
| `gain N` | mic gain 1–64, default 6 (saved) | `ok gain=N` |
| `gate on\|off` | echo protection (saved) | `ok gate=…` |
| `mic on\|off` | start or stop the MIC stream | `ok mic=…` |
| `flush` | stop playback now, drop queued audio | `ok flush` |
| `tone` | 0.5 s test beep | `ok tone` |
| `restart` | reboot the glasses | `ok restart` |

## CTRL events (glasses → app)

| Event | When |
|---|---|
| `hello name=Dani-XXXX fw=1.0 rate=16000 codec=ima-adpcm frame=320` | right after you enable CTRL notifications |
| `stat level=N buffer=MS playing=0/1 drops=N` | every 0.5 s: mic loudness (RMS: quiet ≈ 200–500, speech 2,000–15,000), queued playback in ms |
| `button down` / `button up` | the glasses' button (the board's boot button), e.g. push-to-talk or "start assistant" |
| `ok …`, `error …` | replies to commands |

## Security

This first version has **no pairing**, so anyone nearby running a Bluetooth app could connect
while the glasses are free and hear the mic. Only one device can be connected at a time.
Before real-world use (e.g. at an event), add bonding with LE Secure Connections so only the
paired phone can connect.

## Throughput

At MTU 517: mic ≈ 50 packets/s × 165 bytes ≈ 8.3 KB/s, speaker the same. Tested from a
Windows laptop: 0 packets lost over 4 s of speech, round trip working.
