# Security Review: PR #19, PR #17 and Issue #18

**Date:** 2026-10-24
**Reviewed against:** `main` @ `ccfe04f` (v1.0.0-beta.2 + DLNA roadmap docs)
**Scope:** Security assessment of the two open pull requests, root-cause analysis of issue
#18, and the fix prepared for it.

---

## 1. Verdict at a glance

| Item | Verdict | Reason |
|------|---------|--------|
| **PR #19** — open MainActivity on connect | **Accept with conditions** | Adds `SYSTEM_ALERT_WINDOW` (user-gated special access, never used for overlays). No new attack surface, but it should be gated on `hasVideo` and it was never run on a device. |
| **PR #17** — SPS parser / mirroring recovery | **Accept** | The new SPS parser is the app's only new attack surface; it is properly bounded and its output is validated before it reaches `MediaCodec`. Two hardening follow-ups recommended. |
| **Issue #18** — "AirPlay doesn't work on Google TV dongle" | **Root cause found, fix prepared** | Not a device problem. Four defects in the app: settings changes never reached the running service, the mDNS registration had no retry, a spurious re-advertise raced the initial registration, and the UI reported a wrong state. |

---

## 2. PR #19 — "Open MainActivity when a sender connects in the background"

`qdab:fix/bring-to-front-on-connect` · +39 / −0 · 3 files

### 2.1 What it does

On `ProtocolState.CONNECTED`, the service starts `MainActivity` (with
`NEW_TASK | REORDER_TO_FRONT | SINGLE_TOP`) when no video Surface exists, so a mirroring
session that begins while the app is in the background gets a Surface within the 5 s window
of `MirrorStreamServer.awaitSurface()`. Because Android 10+ blocks background activity
starts, the PR declares and checks `SYSTEM_ALERT_WINDOW`.

### 2.2 Security assessment

**No new attack surface is introduced.** The reasoning:

* `SYSTEM_ALERT_WINDOW` is a *special app access* permission, not a runtime permission. It
  is **not** granted at install time — the user has to enable it explicitly in
  *Settings → Apps → Special app access → Display over other apps*. A user who never
  grants it sees no behaviour change at all (the PR logs and returns).
* The permission does expand the app's capabilities (it would allow drawing over other
  apps), but **this PR never draws an overlay**. It is used purely to unlock a background
  activity start for the app's own Activity. There is therefore no tapjacking surface.
* The foreground jump does not change the threat model of the receiver itself. With
  `airPlayPinAuthEnabled` defaulting to `false`, *any* device on the LAN can already open
  an AirPlay session and take over the screen. Requiring the user to grant a permission to
  move the TV's own UI forward is not an escalation relative to that.

**Findings**

| # | Severity | Finding |
|---|----------|---------|
| 1 | Medium | **The Activity is brought to the front for audio-only sessions too.** The author flags this in the PR description. An audio-only session (Apple Music, a podcast) yanks the TV out of whatever the user was watching — a usability regression, and an easy way for any LAN device to interrupt the viewing experience. The trigger should be gated on `session.hasVideo`. |
| 2 | Medium | **Not built or tested on a device.** The PR states this explicitly. `Settings.canDrawOverlays()` + `startActivity()` from a `Service` is exactly the kind of code that behaves differently across OEM skins (Google TV, Fire TV), and this bug report is precisely about a Google TV dongle. |
| 3 | Low | The README tells users to run `adb shell appops set … SYSTEM_ALERT_WINDOW allow`. That is a shell-issued system grant; reasonable for a sideloaded TV app, but it should be marked clearly as optional and only needed for the background-mirroring case. |
| 4 | Info | The PR's own "Related observations" describe the NSD re-registration churn (`NsdService: unregister/Register service`, then `Exceeded max outstanding requests`, after which the AirPlay card reports an error until the app is force-stopped). **That is a real bug in `main`** — it is the platform-side symptom of the race fixed as part of this issue's fix (see §3.3). PR #19 would make it *more* likely to be hit, because it starts the Activity (and therefore the receiver) more often. |

### 2.3 Conditions for acceptance

1. Gate the foreground start on a video session (`session.hasVideo`), not on every
   `CONNECTED`.
2. Build the `googletv` flavour and test on a device: start mirroring from the TV home
   screen, confirm the video appears, and confirm the behaviour is unchanged without the
   permission granted.
3. Make the README section explicitly optional.

---

## 3. PR #17 — "Fix Android TV mirroring freezes across resolution changes"

`aikkkkko:fix/tv-mirroring-recovery` · +661 / −502 · 13 files (DRAFT)

### 3.1 What it does

Extracts the H.264 SPS parser into `H264SpsParser`, teaches it to accept Annex-B
start codes, adds a bounded input retry and a one-shot IDR retry on a rebuilt codec,
switches the display to a fixed-size `TextureView` with matrix-based aspect fitting, and
adds a diagnostics overlay.

### 3.2 Security assessment

The SPS NAL unit is **attacker-controlled input**: any device on the LAN can connect to
the AirPlay receiver (PIN authentication is off by default) and send a crafted
configuration payload. `H264SpsParser` is therefore the only genuinely new parsing surface
in this PR, and it deserves a close look.

**What was checked and is sound**

* **No unbounded loop / CPU exhaustion.** `readUe()` aborts after 31 leading zeros
  (`ArithmeticException`), and the bit reader throws `IndexOutOfBoundsException` on
  underflow. The one dynamically sized loop —
  `repeat(reader.readUe()) { reader.readSe() }` in the `picOrderCntType == 1` branch — is
  bounded by the input length, because every iteration consumes at least one bit and then
  hits the underflow check. A malicious SPS therefore cannot spin the thread.
* **The output is validated before it reaches the codec.** `VideoDecoder` applies
  `isPlausibleSize(w, h)` (`64..8192`) to the parsed dimensions and falls back to the
  hint otherwise. This is the control that makes the parser safe, and it is already
  present.
* **MediaCodec failures are handled.** `configure()` and `start()` are wrapped so a
  failing codec is released instead of leaked. The `setOnFrameRenderedListener` call is
  additionally guarded so a vendor that rejects the diagnostic callback still decodes.
  The PR *improves* the failure handling compared to `main`.
* **No new permissions, no new exported components.** The manifest change is a single
  `LAUNCHER` category alongside the existing TV entry.

**Findings**

| # | Severity | Finding |
|---|----------|---------|
| 1 | Low | **Integer overflow in `readUe()`.** With 31 leading zeros, `(1 shl 31) - 1` overflows `Int` (`1 shl 31` is `Int.MIN_VALUE`), so `readUe()` can return a negative or wrapped value. The same applies to the crop arithmetic (`(cropLeft + cropRight) * cropUnitX`). A crafted SPS can therefore produce a bogus dimension. **Impact is limited**: `isPlausibleSize` rejects out-of-range results, the real size is read by the hardware codec from `csd-0` anyway, and the worst case is a wrong aspect-fit matrix (cosmetic). Recommended hardening: compute in `Long` and clamp, or reject values above a sane bound — defence in depth, not a blocker. |
| 2 | Low | **Frame queue has no byte budget (pre-existing).** `MirrorStreamServer` caps a payload at 8 MB and the queue at 90 items — a worst case of ~720 MB. A malicious sender on the LAN can push that and OOM the app process. PR #17 does not introduce this, but it lengthens the residency time of items in the queue. Recommended follow-up: cap the *total* queued bytes, not just the item count. |
| 3 | Info | `parseConfig()` reads `spsSize`/`ppsSize` from the payload and slices with `copyOfRange` — out-of-range values throw and are caught by the surrounding `try`/`catch`, returning `null`. Safe, but an explicit bounds check would be clearer. |

### 3.3 Verdict

**Accept.** The parser is bounded, its output is validated before use, and the codec
lifecycle is better handled than in `main`. Findings 1 and 2 are follow-ups, not merge
blockers.

---

## 4. Issue #18 — "[BUG] Air play don't work on google tv dongle"

`MariosN20` · opened 2026-10-18 · 5 comments

### 4.1 Symptom

The reporter's screenshots show the two states side by side:

* **Settings screen:** *AirPlay Receiver → **on***, Miracast on, Cast on, device name
  "Using system device name".
* **Home screen:** *AirPlay → **Disabled — "Enable in Settings"***, Miracast → Advertising,
  Cast → Error.

The complaint is "it just says disabled even though it's switched on". Two follow-up
reporters confirm it on a Google TV 4K and on other hardware. A third reports that
changing the device name made it start advertising.

### 4.2 Root causes

This is **not** a Google TV / dongle problem. The dongle merely made the bugs visible.
There are four independent defects in the app, all of which produce exactly this
symptom:

#### Cause A — Settings changes never reached the running service *(primary)*

`SettingsFragment.setupListeners()` used `save { … }` for the AirPlay, Miracast, Cast, DLNA
switches and for the display name. `save()` only writes to DataStore — it never notifies
the running `PhairPlayService`. The service reads the settings **only** in
`startReceivers()`, i.e. on service start or restart.

Consequences:

* Toggling *AirPlay Receiver* **on** while the service runs changed nothing at all. The
  switch showed "on", the receiver stayed off.
* Changing the display name changed nothing either — the advertised mDNS name is fixed at
  registration time.

The only way to apply the settings was the **RESTART** button on the Home screen. That
also explains the last reporter's "changing the device name fixed it": the name was
incidental, the restart was what did it.

Only `mirrorAudioEnabled` and `airPlayPinAuthEnabled` used `saveAndRestart()` — the two
settings that *did* work.

#### Cause B — The mDNS registration had no retry and could not recover *(primary)*

`MdnsService.onRegistrationFailed()` reported `ERROR` and set `isStarted = false`. Nothing
ever retried. A single transient platform rejection left the receiver permanently
invisible in every AirPlay picker until the app was force-stopped.

Two further defects made this worse:

* **`FAILURE_ALREADY_ACTIVE` was treated as success.** The platform uses this code when
  the *previous* registration has not been released yet. Reporting "Advertising" then is
  wrong — our registration was rejected, so the device is not discoverable.
* **`restart()` published `DISABLED` in between.** `MdnsService.restart()` called `stop()`,
  and `stop()` emitted `ProtocolState.DISABLED`. So a re-advertisement — a normal
  operation after every streaming session — briefly published the state that the UI
  renders as *"Disabled — Enable in Settings"*. This is the direct cause of the
  misleading message in the screenshot.

`NsdManager.unregisterService()` is asynchronous, so `stop()` immediately followed by
`start()` could collide with the outgoing registration. Repeated collisions are what
produced the platform-side `Exceeded max outstanding requests` that the author of PR #19
observed.

#### Cause C — The first network callback raced the initial registration *(contributing)*

`PhairPlayService.registerNetworkCallback()` re-advertised on **every**
`NetworkCallback.onAvailable` — including the very first one, which the platform delivers
right after the callback is registered, while the AirPlay receiver is still starting up.

Re-advertising at that moment unregisters a registration that is still in flight, which
produces exactly the `FAILURE_ALREADY_ACTIVE` collision described above. Every further
network transition (VPN up, captive portal, Wi-Fi roaming) repeated the churn.

#### Cause D — The AirPlay card reported a state it never had *(contributing)*

`PhairPlayService.startAirPlay()` set no state of its own, so `_airPlayState` kept its
initial value `DISABLED` until **both** mDNS registrations had been confirmed. Miracast,
Cast and DLNA all set `ADVERTISING` immediately; AirPlay did not.

So during start-up — and forever, if a registration failed — the card said
*"Disabled — Enable in Settings"* even though the protocol was enabled. The hint pointed
the user at the one thing that was already correct.

### 4.3 The fix

| File | Change |
|------|--------|
| `airplay/MdnsService.kt` | Rewritten registration lifecycle: per-service registration with an independent attempt counter, retry with backoff (1 s / 3 s / 6 s), `FAILURE_ALREADY_ACTIVE` treated as a *failure* (not success), a failed service retried **without** tearing down its sibling, `restart()` no longer publishes `DISABLED`, service names sanitized, `registerService()` throws caught and turned into a retryable failure. |
| `airplay/MdnsRegistrationTracker.kt` *(new)* | Pure-Kotlin bookkeeping of the two registrations: attempt counters, "is registered" flags, failure counts. No Android types → directly unit-testable. |
| `airplay/MdnsServiceName.kt` *(new)* | Sanitizes the advertised name: strips control characters, caps at the DNS-SD limit of 63, falls back to the system name. |
| `util/NetworkUtils.kt` | The system device name is capped at 63 characters as well — a vendor name longer than that is rejected by the mDNS daemon. |
| `service/PhairPlayService.kt` | The first `onAvailable` no longer re-advertises; re-advertisement happens only after the last network was actually lost. `startAirPlay()` reports `ADVERTISING` immediately, like the other protocols. |
| `ui/SettingsFragment.kt` | The protocol switches and the display name now use `saveAndRestart()` — the running service picks them up. |
| `ui/HomeFragment.kt` + `values*/strings.xml` | A "Disabled" card now says *"Start the service to advertise"* when the service itself is stopped, instead of the misleading *"Enable in Settings"*. New string in en / de / fr. |

**Resulting state semantics**

| State | Meaning |
|-------|---------|
| `DISABLED` | The user switched this protocol off. (Only path that shows "Enable in Settings".) |
| `ADVERTISING` | The receiver is enabled and advertising — also shown while it is starting up. |
| `CONNECTED` | A sender is streaming. |
| `ERROR` | The receiver is enabled but the advertisement failed (after all retries). Shows "Check Wi-Fi settings" / retry. |

### 4.4 Tests

* `MdnsRegistrationTrackerTest` (new, 9 cases) — attempt accounting, sibling isolation,
  late-success handling, retry-limit counting.
* `MdnsServiceNameTest` (new, 9 cases) — control characters, length cap, fallbacks.
* `MdnsServiceTest` (extended, 5 new cases) — `restart()` must not publish `DISABLED`;
  a failed registration is retried and never reports `DISABLED`; a failed service is
  retried without unregistering its sibling; both services are required before
  advertising.

Verification: `:test-runner:test`, `:app:testGoogletvDebugUnitTest`,
`:app:testFiretvDebugUnitTest`, `:app:lintGoogletvDebug`, `:app:lintFiretvDebug`,
`:app:assembleGoogletvDebug`, `:app:assembleFiretvDebug` — all green.

### 4.5 Not addressed by this fix

* **Boot on Google TV / TCL firmware.** PR #19 reports that `TclAppBoot` blocks the
  `BOOT_COMPLETED` receiver and resets the `APP_AUTO_START` app-op on every boot. This is
  an OEM policy, not an app bug — it needs either a different start mechanism or a
  documented manual start.
* **The frame-queue byte budget** (finding 3.3 #2) and the **`readUe()` overflow
  hardening** (finding 3.3 #1) are recommended follow-ups.
* **The Cast receiver's "Error" state** in the reporter's screenshot is a separate
  problem (the Google Cast SDK on a device without Play Services) and is not touched here.
