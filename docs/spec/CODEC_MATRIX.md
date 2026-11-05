# PhairPlay – Codec Matrix (v4.0)

Version: 4.0
Status: Active
Date: 2026-11-05
Owner: product track (`docs/spec/`)

> **Why this document exists.** The three codec matrices were part of the long
> text of [`REQUIREMENTS.md`](REQUIREMENTS.md) until v4.0. They describe what a
> **sender** may send — not what PhairPlay decodes — and they are read together
> with the exclusion table of §3. Splitting them out keeps the requirement
> baseline short and reviewable and gives the matrices one place where their
> "announced, not decoded" status can be stated once instead of three times.
>
> **The rules that go with the tables**
>
> 1. A row marked **Required** is a requirement of the **protocol**, not a promise
>    of PhairPlay. The column *Status* says whether the project decodes it.
> 2. A codec that the device cannot decode is never advertised — the runtime
>    check of FR-65 decides that, not this document.
> 3. The tables are subordinate to §3 of `REQUIREMENTS.md`: what is listed there
>    as excluded is not in the tables below, except where a table marks a row as
>    *announced, not decoded*.

---

## 1. AirPlay 2

> **Status: these are the codecs of the AirPlay 2 sender.** PhairPlay decodes the
> **mandatory** rows with the hardware `MediaCodec` (FR-33); the optional rows are
> gated by a runtime capability check (FR-65).

| Category | Codec / Format | Status | Notes |
|---|---|---|---|
| Video (Mandatory) | H.264 AVC — up to High Profile Level 5.2 | **Required** | Hardware MediaCodec decode |
| Video (Optional / 4K) | H.265 HEVC | Optional | From Apple TV 4K baseline; hardware check required |
| Audio (Mandatory – Mirroring) | AAC-LC or LPCM (depending on latency requirements) | **Required** | AAC-LC for low bandwidth, LPCM for minimal latency |
| Audio (Mandatory – Music) | ALAC (Apple Lossless) 16-bit / 44.1 kHz | **Required** | Default for music/podcast streaming |
| Audio (Surround – Optional) | Dolby Atmos (E-AC3 with JOC), AC-3 | Optional | Only on capable hardware |
| Container | MP4, MOV, M4V; HLS (HTTP Live Streaming) | **Required** | As used by AirPlay media sessions |
| Image / Photo | JPEG, PNG (via `/photo` HTTP endpoint) | **Required** | See FR-42 |
| Max Resolution | Up to 4K UHD @ 60fps (HDR10, Dolby Vision) | Optional | 1080p @ 60fps is mandatory baseline |
| DRM | FairPlay (evaluation only — see §3 of `REQUIREMENTS.md`) | Not in v1 | Requires Apple licensing |

---

## 2. Miracast (WFD)

> **Status: none of these codecs is decoded by PhairPlay.** The table describes the
> WFD standard and what a Miracast sink would need; the project implements the
> discovery and the control plane only (FR-14, FR-60). The rows are the
> *capability announcement* of the control plane, not a promise of playback.

| Category | Codec / Format | Status | Notes |
|---|---|---|---|
| Video (Mandatory) | H.264 AVC — Constrained High Profile (CHP) and Constrained Baseline Profile (CBP), up to Level 4.2 | **Required** | WFD spec primary codec — *announced, not decoded* |
| Video (Optional / 4K) | H.265 HEVC | Optional | Newer senders (Windows 11 / Android 10+) |
| Audio (Mandatory) | LPCM 16-bit / 48 kHz (mono, stereo) | **Required** | WFD baseline audio — *announced, not decoded* |
| Audio (Optional) | AAC-LC, AAC-HE | Optional | More efficient than LPCM |
| Audio (Surround – Optional) | AC-3 (Dolby Digital) | Optional | When sender/display supports it |
| Container | MPEG-TS (MPEG Transport Stream) | **Required** | Standard WFD stream encapsulation — *no demuxer in the project* |
| Max Resolution | 1080p @ 60fps | **Required** | Mandatory WFD baseline |
| Max Resolution (Optional) | 4K UHD @ 60fps | Optional | Only on hardware that supports it |
| DRM / Copy Protection | HDCP 2.x (hardware-based link protection) | **Required** | Negotiated during WFD setup — *the control plane reports `wfd_content_protection: none`* |

---

## 3. Google Cast

> **Status: the decode happens in the Cast SDK, not in PhairPlay.** The `googletv`
> flavour starts the Cast Connect receiver; the `firetv` flavour cannot (no Google
> Play Services) and reports "disabled" (FR-69). The rows below describe what the
> Cast runtime offers, not a decoder inside this project.

| Category | Codec / Format | Status | Notes |
|---|---|---|---|
| Video (Mandatory) | H.264 AVC (Baseline, Main, High Profile); VP8 | **Required** | Cast baseline codecs |
| Video (Optional / 4K) | H.265 HEVC, VP9, AV1 | Optional | Newer hardware (Android TV API 31+ for AV1) |
| Audio (Mandatory) | AAC-LC, AAC-HE, MP3, WAV (LPCM) | **Required** | Cast baseline audio |
| Audio (Surround – Optional) | Dolby Digital Plus (E-AC3), Dolby Atmos, Opus, FLAC | Optional | High-quality audio on capable devices |
| Container | MP4, WebM | **Required** | Native Cast containers |
| Container (Adaptive) | DASH (Dynamic Adaptive Streaming over HTTP), HLS | **Required** | For adaptive bitrate streaming |
| Max Resolution | Up to 4K UHD @ 60fps (HDR10+) | Optional | 1080p mandatory, 4K hardware-dependent |
| DRM | Widevine L1 / L3, PlayReady | **Required** | For DRM-protected content streams |

---

## 4. UPnP / DLNA

The DLNA renderer has **no codec matrix of its own**: it plays a finished media
file that a control point announces over `http`/`https` (FR-50). The formats it
can therefore play are the formats of the media pipeline of the AirPlay stack —
H.264 / HEVC video, AAC / ALAC / LPCM audio, JPEG and PNG images — and the
container handling is the one of `MediaPlayer`. The protocol list that the
renderer announces is `DlnaProtocolInfo`; its status as a fixed constant is an
open point (FR-66, backlog B-07).
