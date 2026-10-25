# Device Test Plan — PhairPlay

**Scope:** every acceptance criterion that cannot be proven on a build server
needs real hardware. This document lists those criteria and gives a
copy-pasteable script for each of them, so the owner can run the plan on a
Google TV and a Fire TV without guessing a single command.

**Owner:** the person who runs the plan fills in the *Result* column of the
summary table at the end and attaches the log output as evidence.

**Related documents:**

- [Acceptance criteria](../spec/ACCEPTANCE_CRITERIA.md) — the acceptance criteria of the project
- [Installation guide](INSTALLATION.md) — how the APK reaches the device
- [Troubleshooting](TROUBLESHOOTING.md) — what to do when a step fails
- [Network check](NETWORK_CHECK.md) — the command the owner runs from the laptop before a playback test
- [Cast App ID](CAST_APP_ID.md) — what the Cast case needs
- [QA acceptance records](../acceptance/) — the acceptance record of this cycle

---

## 1. Why a device test plan exists

The automated test suite (`./gradlew :test-runner:test`) proves the protocol
logic on a JVM. It cannot prove the things that only exist on a television:

| What only the device can prove | Why the JVM cannot |
|---|---|
| The TV appears in the AirPlay picker of macOS | Needs real mDNS on a real Wi-Fi |
| The picture arrives on the TV screen | Needs a real Surface, a real decoder and a real TV |
| The TV appears in the VLC renderer list | Needs a real SSDP responder on a real LAN |
| The notification is shown by the system UI | Needs the Android system UI |
| The German and French texts really appear | Needs the resource system of the device |
| The receiver survives a reboot | Needs a real boot and the real `BootReceiver` |
| The frame rate, the latency and the memory | Needs the real SoC of the TV |

Everything else (parsing, limits, state machines, crypto) is covered by the
automated suite and must **not** be re-tested by hand.

---

## 2. Prerequisites

### 2.1 Hardware

| # | Item | Minimum | Note |
|---|---|---|---|
| H1 | Google TV device | Chromecast with Google TV, Android 10 (API 29) | e.g. Chromecast with Google TV 4K |
| H2 | Fire TV device | Fire TV Stick, Android 7.1 (API 25) | the oldest supported target |
| H3 | Sender computer, macOS | macOS 12 or newer with AirPlay | for the AirPlay cases |
| H4 | Sender computer, Windows 10/11 | with *Projecting to this PC* / *Cast to device* | for the Miracast case |
| H5 | Sender computer, Linux/Windows/macOS | with VLC 3.0 or newer | for the DLNA/VLC case |
| H6 | Router / Wi-Fi | 5 GHz or 2.4 GHz, same SSID for TV and sender | multicast must not be filtered |

### 2.2 Software

| # | Item | Version | Used for |
|---|---|---|---|
| S1 | `adb` platform tools | any recent | every case |
| S2 | The APK under test | this cycle's build | every case |
| S3 | VLC | 3.0 or newer on H5 | case DT-09 |
| S4 | A local HTTP server on H5 | `python3 -m http.server 8080` | case DT-10 |
| S5 | A media file reachable over HTTP | e.g. `sample.mp4` | case DT-10 |

### 2.3 One-time preparation on the TV

**Google TV:** Settings → System → About → *Android TV OS Build* → click 7 times
→ Settings → System → Developer Options → *USB debugging* → **ON**.

**Fire TV:** Settings → My Fire TV → About → *Build* → click 7 times →
Settings → My Fire TV → Developer Options → *ADB debugging* → **ON** and
*Apps from Unknown Sources* → **ON**.

### 2.4 Shell variables used by every command below

```bash
# ── set these once ────────────────────────────────────────────────────────────
TV_IP="192.168.1.42"          # IP address of the TV (Settings → Network)
PKG="com.phairplay.googletv"  # com.phairplay.firetv on Fire TV
APK="/path/to/PhairPlay-googletv-debug.apk"
SENDER_IP="192.168.1.10"      # IP address of the sending computer

# ── connect and check ─────────────────────────────────────────────────────────
adb connect "$TV_IP":5555
adb devices                          # expect: <ip>:5555   device
adb shell getprop ro.build.version.release   # Google TV: 10..15, Fire TV: 7.1..11
adb shell getprop ro.product.model
```

> **Note:** the package name differs per flavour
> (`com.phairplay.googletv` / `com.phairplay.firetv`). Everything else in this
> document is identical for both flavours.

---

## 3. Test cases

Every case has: **Preconditions**, **Steps** (numbered, with the exact
commands), **Expected result** (what the tester must see), **Time limit**
(how long it may take) and **Evidence** (what to attach to the record).

---

### DT-01 — Install and first start

**Needs hardware:** yes (H1 and H2).
**Covers:** AC-1.1, AC-1.2, AC-1.3, AC-1.4, AC-1.5, AC-1.6.

**Preconditions:** ADB connected (section 2.4).

**Steps**

```bash
# 1. remove a previous build so the test starts from a clean state
adb uninstall "$PKG" || true

# 2. install the build under test
adb install -r "$APK"

# 3. check that the package is installed and the launcher activity is exported
adb shell pm list packages | grep "$PKG"
adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER "$PKG"

# 4. start the app and read the first log lines
adb shell am start -n "$PKG/com.phairplay.MainActivity"
sleep 3
adb logcat -d -s PhairPlay:V AndroidRuntime:E | tail -40

# 5. confirm that the foreground service runs
adb shell dumpsys activity services "$PKG" | grep -i "phairplay"

# 6. take a screenshot of the home screen
adb exec-out screencap -p > /tmp/phairplay-home.png
```

**Expected result**

1. `adb install` prints `Success`.
2. `pm list packages` prints exactly one line with the package name.
3. The app shows the home screen with the four protocol cards (AirPlay,
   Miracast, Cast, DLNA) and the "Visible as: *device name*" line.
4. `logcat` shows no `AndroidRuntime` error and the receiver start messages.
5. `dumpsys` shows `ServiceRecord{... com.phairplay/.service.PhairPlayService}`.
6. The screenshot shows the home screen, not a black screen.

**Time limit:** 60 s from `adb install` to the screenshot.
**Evidence:** the screenshot and the `logcat` excerpt.

**Before the first playback test:** run the network check on the sender
computer — `./scripts/device-check.sh`. It measures whether the television is
visible in the network, whether the description document is readable and
whether the advertised address is still the current one. The guide with the
expected output is [NETWORK_CHECK.md](NETWORK_CHECK.md).

---

### DT-02 — Start, stop and restart the receiver

**Needs hardware:** yes.
**Covers:** AC-2.3 (the service stops advertising). The spec has no AC for
the start/stop buttons of the home screen — see the gap list in section 4.

**Preconditions:** DT-01 passed.

**Steps**

```bash
# 1. stop the service
adb shell am startservice -a com.phairplay.action.STOP -n "$PKG/com.phairplay.service.PhairPlayService"
sleep 2
adb shell dumpsys activity services "$PKG" | grep -i "phairplay"   # expect: no running record

# 2. start it again
adb shell am startservice -a com.phairplay.action.START -n "$PKG/com.phairplay.service.PhairPlayService"
sleep 2
adb shell dumpsys activity services "$PKG" | grep -i "phairplay"   # expect: one record

# 3. restart it
adb shell am startservice -a com.phairplay.action.RESTART -n "$PKG/com.phairplay.service.PhairPlayService"
sleep 4
adb logcat -d -s PhairPlay:V | tail -30
```

**Expected result**

- After step 1 the home screen shows the service state "Stopped".
- After step 2 the state is "Running".
- After step 3 the log shows a stop followed by a start, and the state is
  "Running" again.

**Time limit:** 5 s per state change.
**Evidence:** the three `dumpsys` outputs and the log excerpt.

---

### DT-03 — AirPlay mirroring (video) from macOS

**Needs hardware:** yes (H1 **or** H2 plus H3). This is the flagship case.
**Covers:** AC-2.1, AC-2.2, AC-3.1, AC-3.2, AC-4.1, AC-4.3, AC-4.4,
AC-4.6, AC-4.7.

**Preconditions:** TV and Mac on the same Wi-Fi, PhairPlay running, AirPlay
enabled in the settings.

**Steps**

```bash
# 1. clear the log so the session is easy to find
adb logcat -c

# 2. on the Mac: open the AirPlay menu and pick the TV
#    (the TV must be listed with the name shown on the PhairPlay home screen)
#    GUI step, no command possible — note the exact name you picked.

# 3. start a stream on the Mac (any video window or a YouTube video)
#    GUI step.

# 4. while the stream runs, collect the evidence
adb shell dumpsys gfxinfo "$PKG" framestats | head -30
adb logcat -d -s PhairPlay:V | tail -60
adb exec-out screencap -p > /tmp/phairplay-airplay-video.png
```

**Expected result**

1. The TV is listed in the AirPlay menu of the Mac under the name shown in
   PhairPlay ("Visible as: …").
2. The TV screen shows the mirrored picture within 5 s of the click.
3. The picture is not distorted and has no visible tearing.
4. The log contains the RTSP handshake (`SETUP`, `RECORD`, `FLUSH`) and no
   exception.
5. `logcat` shows no `FATAL EXCEPTION` and no `E/` entries of the app.

**Time limit**

- TV visible in the AirPlay menu: **10 s** after the receiver is running.
- First picture on the screen: **5 s** after the click in the AirPlay menu.
- The stream must run for at least **60 s** without a visible interruption.

**Evidence:** screenshot, log excerpt, name of the device as shown in the picker.

---

### DT-04 — AirPlay audio only (now playing screen)

**Needs hardware:** yes (H3).
**Covers:** AC-5.1, AC-5.3, AC-5.5, NFR-10.

**Steps**

```bash
adb logcat -c
# on the Mac: play an audio file in Music (no video window)
# GUI step
sleep 5
adb exec-out screencap -p > /tmp/phairplay-now-playing.png
adb logcat -d -s PhairPlay:V | tail -40
```

**Expected result**

1. The TV shows the now-playing screen with the title, the artist and the
   name of the sender ("Audio from …").
2. The audio is played by the TV (audible, no stuttering over 30 s).
3. The title and the artist are not empty.

**Time limit:** screen visible within **5 s**, metadata within **10 s**.
**Evidence:** screenshot + log excerpt.

---

### DT-05 — AirPlay pairing PIN

**Needs hardware:** yes (H3).
**Covers:** no AC in `docs/spec/ACCEPTANCE_CRITERIA.md` — the pairing PIN is
new in this cycle, see the gap list in section 4.

**Preconditions:** "Require pairing PIN" is switched **on** in the settings.

**Steps**

```bash
adb logcat -c
# on the Mac: pick the TV in the AirPlay menu again
# GUI step — the TV shows a PIN
adb exec-out screencap -p > /tmp/phairplay-pin.png
# enter the PIN on the Mac
# GUI step
sleep 3
adb logcat -d -s PhairPlay:V | tail -40
```

**Expected result**

1. The TV shows a PIN (4–6 digits) instead of the picture.
2. After the correct PIN is entered on the Mac, the picture appears.
3. With a wrong PIN the TV refuses the stream and shows no picture.

**Time limit:** PIN visible within **3 s**, stream after the correct PIN within
**5 s**.
**Evidence:** screenshot of the PIN screen + log excerpt.

---

### DT-06 — Miracast from Windows

**Needs hardware:** yes (Windows sender + Fire TV or Google TV).
**Covers:** no AC in `docs/spec/ACCEPTANCE_CRITERIA.md` — see the gap list in
section 4.

> Fire TV has no Wi-Fi Direct in many builds. If the TV cannot open a P2P
> group, the case is marked **"not applicable — hardware limitation"** and the
> reason is written into the record. Do not report a failure in that case.

**Steps**

```bash
adb logcat -c
# on Windows: Settings → System → Display → Cast / "Connect to wireless display"
#            → pick the TV
# GUI step
sleep 10
adb exec-out screencap -p > /tmp/phairplay-miracast.png
adb logcat -d -s PhairPlay:V | tail -60
```

**Expected result**

1. The TV is listed as a wireless display.
2. The picture appears on the TV.
3. The log shows the WFD RTSP server on port **7236**.

**Time limit:** TV listed within **15 s**, picture within **10 s**.
**Evidence:** screenshot + log excerpt.

---

### DT-07 — Google Cast (Google TV only)

**Needs hardware:** yes (H1 + a Chrome device).
**Covers:** no AC in `docs/spec/ACCEPTANCE_CRITERIA.md` — see the gap list in
section 4.

> The Cast receiver needs a registered Cast App ID
> (`docs/guides/CAST_APP_ID.md`). Without it, build with
> `-Pphairplay.castAppId=<APP_ID>`; otherwise the case cannot run and is
> recorded as **"blocked — missing Cast App ID"**.

**Steps**

```bash
adb logcat -c
# in Chrome on the sender: menu → Cast… → pick the TV
# GUI step
sleep 8
adb exec-out screencap -p > /tmp/phairplay-cast.png
adb logcat -d -s PhairPlay:V | tail -60
```

**Expected result:** the TV appears in the Cast picker and the cast page is
shown on the TV.
**Time limit:** TV listed within **15 s**, picture within **10 s**.
**Evidence:** screenshot + log excerpt.

---

### DT-08 — DLNA: SSDP discovery from the network

**Needs hardware:** yes (the TV) plus a sender computer on the same LAN.
**Covers:** no AC in `docs/spec/ACCEPTANCE_CRITERIA.md` — see the gap list in
section 4.

**Preconditions:** the sender runs the script below on the same Wi-Fi. The TV
must not be in a "client isolation" network.

**Steps** (run on the sender computer, not on the TV)

```bash
# 1. send an M-SEARCH for "ssdp:all" and print the answers
python3 - <<'PY'
import socket, time
msg = ("M-SEARCH * HTTP/1.1\r\n"
       "HOST: 239.255.255.250:1900\r\n"
       'MAN: "ssdp:discover"\r\n'
       "MX: 2\r\n"
       "ST: ssdp:all\r\n\r\n")
s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
s.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 2)
s.settimeout(3)
s.sendto(msg.encode(), ("239.255.255.250", 1900))
deadline = time.time() + 5
answers = []
while time.time() < deadline:
    try:
        data, addr = s.recvfrom(4096)
    except socket.timeout:
        break
    text = data.decode(errors="replace")
    if text.startswith("HTTP/1.1 200"):
        answers.append(text)
        st = [l for l in text.split("\r\n") if l.lower().startswith("st:")]
        usn = [l for l in text.split("\r\n") if l.lower().startswith("usn:")]
        print("--- answer from", addr[0])
        print(st[0] if st else "no ST", "|", usn[0] if usn else "no USN")
print("answers:", len(answers))
PY
```

**Expected result**

1. **Three** answers arrive, all from the IP address of the TV.
2. The three `ST` headers are, in any order:
   - `upnp:rootdevice`
   - `urn:schemas-upnp-org:device:MediaRenderer:1`
   - the UDN of the device (`uuid:…`)
3. Every answer contains a `LOCATION: http://<TV-IP>:<port>/description.xml`
   header.

**Time limit:** answers arrive within **5 s** of the `M-SEARCH`.
**Evidence:** the printed output of the script (keep it in the record).

---

### DT-09 — VLC renderer test (dedicated case)

**Needs hardware:** yes — this is the case that decides whether a normal user
can push a video from VLC to the TV.
**Covers:** no AC in `docs/spec/ACCEPTANCE_CRITERIA.md` — see the gap list in
section 4.

> **Limitation that belongs in every user-facing guide:** a UPnP renderer
> **fetches the media itself**. A `file:///` URI points at the sender's disk and
> is not readable by the TV. Rendering a *local* file therefore only works for
> network-reachable URLs (http/https). This is a property of the protocol, not
> a bug of PhairPlay.

**Preconditions**

- VLC 3.0 or newer on the sender computer (H5).
- Sender and TV on the same Wi-Fi.
- A media file that VLC can play, **and** an HTTP URL that reaches the same
  file (for the part of the case that needs the network path).

**Steps**

1. Start VLC on the sender computer.
2. In VLC, open the menu **Playback → Renderer**. The list of renderers opens.
   VLC scans the network for UPnP/DLNA renderers (SSDP).
3. **PhairPlay must be listed** in that renderer list, under the device name
   that is shown on the PhairPlay home screen ("Visible as: …").
   - If it is not listed: run the SSDP script of case **DT-08** on the same
     computer, or run the network check (`./scripts/device-check.sh`) — it
     tests the discovery, the description document, the advertised address and
     the AirPlay port in one run and names the step that fails; the guide is
     [NETWORK_CHECK.md](NETWORK_CHECK.md).
     If DT-08 fails as well, the problem is the network (multicast
     blocked, client isolation) — see `docs/guides/TROUBLESHOOTING.md`.
     If DT-08 succeeds but VLC does not show the device, record it as a
     **VLC interoperability defect** with the DT-08 output attached.
4. Select **PhairPlay** in the renderer list. VLC now sends the control
   commands to the TV.
5. In VLC open **Media → Open File…**, select a **network URL** (not a local
   path), for example `http://<SENDER_IP>:8080/sample.mp4`, and play it.
   - Start the server first on the sender:
     ```bash
     cd /path/to/your/media && python3 -m http.server 8080
     ```
6. Check the screen of the TV:
   - the TV must show the video (or the first frame of it),
   - the video must start within **5 s** of the play command,
   - the video must not show a "cannot open file" or an empty screen.
7. Test the transport commands from VLC:
   - **pause** → the picture freezes, the TV state becomes `PAUSED_PLAYBACK`,
   - **play** → the picture continues,
   - **stop** → the picture disappears and the TV returns to the home screen.
8. Collect the evidence:

```bash
# while the stream is running, on the TV:
adb logcat -d -s PhairPlay:V | tail -80
adb exec-out screencap -p > /tmp/phairplay-vlc-renderer.png

# on the sender, the description document must be reachable:
curl -s "http://<TV-IP>:<PORT>/description.xml" | head -30
# <PORT> is the port from the LOCATION header of the DT-08 answer
```

**Expected result**

1. The renderer list of VLC contains the PhairPlay device within **10 s** of
   opening the menu.
2. After selecting the renderer and playing the network URL, the TV shows the
   video within **5 s**.
3. Pause, play and stop work as described.
4. The log of the TV shows the SOAP actions (`SetAVTransportURI`, `Play`,
   `Pause`, `Stop`) without an exception.

**Time limit:** renderer listed in **10 s**, picture in **5 s**, each transport
command in **2 s**.
**Evidence:** screenshot of the TV during playback, the VLC renderer list
(photo of the screen is fine), the log excerpt.

---

### DT-10 — DLNA: SOAP control from the command line

**Needs hardware:** yes (the TV) plus a sender computer.
**Covers:** no AC in `docs/spec/ACCEPTANCE_CRITERIA.md` — see the gap list in
section 4.

**Steps** (run on the sender computer)

```bash
# 0. read the port from the LOCATION header of the DT-08 answer
TV_DESC="http://<TV-IP>:<PORT>/description.xml"

# 1. a normal SetAVTransportURI + Play
curl -s -i -X POST "http://<TV-IP>:<PORT>/upnp/control/AVTransport" \
  -H 'SOAPACTION: "urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI"' \
  -H 'Content-Type: text/xml; charset="utf-8"' \
  --data-binary '<?xml version="1.0"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:SetAVTransportURI xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
      <u:CurrentURI>http://'"$SENDER_IP"':8080/sample.mp4</u:CurrentURI>
      <u:CurrentURIMetaData></u:CurrentURIMetaData>
    </u:SetAVTransportURI>
  </s:Body>
</s:Envelope>'

# 2. the XXE attempt must be rejected (no file read, no error detail)
curl -s -i -X POST "http://<TV-IP>:<PORT>/upnp/control/AVTransport" \
  -H 'SOAPACTION: "urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI"' \
  --data-binary '<?xml version="1.0"?>
<!DOCTYPE foo [<!ENTITY xxe SYSTEM "file:///etc/hostname">]>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
  <s:Body>
    <u:SetAVTransportURI xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
      <u:CurrentURI>&xxe;</u:CurrentURI>
      <u:CurrentURIMetaData></u:CurrentURIMetaData>
    </u:SetAVTransportURI>
  </s:Body>
</s:Envelope>'

# 3. the SSRF attempt must be refused (no callback to a non-private address)
curl -s -i -X POST "http://<TV-IP>:<PORT>/upnp/control/AVTransport" \
  -H 'SOAPACTION: "urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI"' \
  --data-binary '<?xml version="1.0"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
  <s:Body>
    <u:SetAVTransportURI xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
      <u:CurrentURI>http://169.254.169.254/latest/meta-data/</u:CurrentURI>
      <u:CurrentURIMetaData></u:CurrentURIMetaData>
    </u:SetAVTransportURI>
  </s:Body>
</s:Envelope>'
```

**Expected result**

1. Step 1 answers `200 OK` with a SOAP response; the TV plays the file.
2. Step 2 answers with a SOAP **Fault**; the answer must **not** contain the
   content of `/etc/hostname` and must not contain a stack trace or a file
   path.
3. Step 3 is answered with a Fault or is rejected; the TV must not try to
   reach `169.254.169.254`.
4. In all three cases the TV stays responsive.

**Time limit:** every answer within **3 s**.
**Evidence:** the three `curl -i` outputs (paste them into the record).

---

### DT-11 — GENA event subscription

**Needs hardware:** yes (the TV) plus a sender computer.
**Covers:** no AC in `docs/spec/ACCEPTANCE_CRITERIA.md` — see the gap list in
section 4.

**Steps** (on the sender computer)

```bash
# 1. subscribe to the events of the AVTransport service
curl -s -i -X SUBSCRIBE "http://<TV-IP>:<PORT>/upnp/event/AVTransport" \
  -H 'CALLBACK: <http://<SENDER_IP>:8081/upnp/event>' \
  -H 'NT: upnp:event' \
  -H 'TIMEOUT: Second-120'

# 2. trigger a change on the TV (press Play in the VLC renderer of DT-09)
# 3. the callback server must receive a NOTIFY with the LastChange body
```

**Expected result**

1. The `SUBSCRIBE` answer contains a `SID: uuid:…` and a `TIMEOUT: Second-…`.
2. A `NOTIFY` request arrives at the callback URL after the transport state
   changes, with a body that contains `LastChange`.
3. A subscription to a **public** callback address is refused (SSRF guard).

**Time limit:** `SUBSCRIBE` answer within **3 s**, `NOTIFY` within **5 s** of
the state change.
**Evidence:** the `curl -i` output and the captured `NOTIFY`.

---

### DT-12 — German and French user interface

**Needs hardware:** yes.
**Covers:** no AC in `docs/spec/ACCEPTANCE_CRITERIA.md` — see the gap list in
section 4.

**Steps**

```bash
# 1. switch the device language to German
adb shell "setprop persist.sys.locale de-DE; stop; start"   # reboots the device
sleep 30
adb shell settings put system system_locales de-DE     # Android 13+ variant
#   (on Android 13+ the command above is enough, no reboot needed)

adb shell am start -n "$PKG/com.phairplay.MainActivity"
sleep 3
adb exec-out screencap -p > /tmp/phairplay-home-de.png

# 2. switch to French the same way
adb shell "setprop persist.sys.locale fr-FR; stop; start"
sleep 30
adb shell am start -n "$PKG/com.phairplay.MainActivity"
sleep 3
adb exec-out screencap -p > /tmp/phairplay-home-fr.png

# 3. open the settings screen in both languages
adb shell input keyevent KEYCODE_DPAD_DOWN   # navigate with the remote
adb exec-out screencap -p > /tmp/phairplay-settings.png
```

**Expected result**

1. With `de-DE` the home screen shows German texts
   ("Bereit für Verbindungen", "Protokolle", "Starten"/"Stoppen").
2. With `fr-FR` the home screen shows French texts
   ("Prêt pour les connexions", "Protocoles", "Démarrer"/"Arrêter").
3. **No English text** remains on the home screen or in the settings in these
   two languages — with the exception of the protocol names themselves
   (AirPlay, Miracast, Cast, DLNA) and the product name "PhairPlay".
4. The strings with a placeholder show the value in it
   ("Sichtbar als: *Wohnzimmer*", "Visible sous le nom : *Salon*").

**Time limit:** the language switch must be visible within **60 s** (reboot on
old devices).
**Evidence:** the four screenshots.

---

### DT-13 — Start on boot

**Needs hardware:** yes.
**Covers:** no AC in `docs/spec/ACCEPTANCE_CRITERIA.md` — see the gap list in
section 4.

**Steps**

```bash
adb shell pm grant "$PKG" android.permission.RECEIVE_BOOT_COMPLETED 2>/dev/null || true
# enable "Start on boot" in the settings (GUI step)
adb reboot
sleep 60
adb connect "$TV_IP":5555
adb shell dumpsys activity services "$PKG" | grep -i phairplay
adb logcat -d -s PhairPlay:V | grep -i boot | tail -20
```

**Expected result:** the service is running **without** the user opening the
app after the reboot.
**Time limit:** the service is running **60 s** after the reboot.
**Evidence:** the `dumpsys` output.

---

### DT-14 — Notification and its actions

**Needs hardware:** yes.
**Covers:** NFR-10, no AC in `docs/spec/ACCEPTANCE_CRITERIA.md` — see the gap
list in section 4.

**Steps**

```bash
adb shell dumpsys notification --noredact | grep -A 20 phairplay_service_channel
# trigger a stream (see DT-03), then look at the notification again
adb shell cmd notification list
```

**Expected result**

1. A notification of the channel "PhairPlay Service" exists.
2. Its text is localised (in the language of the device).
3. The actions "Stop" and "Restart" work when they are selected.

**Time limit:** the notification appears within **3 s** of the service start.
**Evidence:** the `dumpsys notification` excerpt.

---

### DT-15 — Debug overlay and stability under load

**Needs hardware:** yes.
**Covers:** AC-4.3, AC-4.4, AC-6.1, AC-6.2, AC-6.3, AC-6.4.

**Steps**

```bash
# enable the debug overlay in the settings (GUI step)
# start a stream (DT-03) and let it run for 10 minutes
adb shell dumpsys meminfo "$PKG" | head -25
adb shell top -n 1 -b | grep -i phairplay
adb exec-out screencap -p > /tmp/phairplay-debug-overlay.png
```

**Expected result**

1. The overlay shows a frame rate and the queue statistics.
2. The Java heap of the app stays below **150 MB** during the run.
3. The app is not killed by the system and the stream does not stop.

**Time limit:** 10 minutes of continuous streaming without an interruption.
**Evidence:** the `meminfo` output, the screenshot, the duration.

---

## 4. Acceptance criteria that require real hardware

| Criterion | Why the JVM cannot prove it | Case | In the spec? |
|---|---|---|---|
| AC-1.1 / AC-1.2 — the APK installs on Google TV and on Fire TV | needs the package manager of the device | DT-01 | yes |
| AC-1.3 / AC-1.4 — the app starts without crashing | needs the real process and the real system UI | DT-01 | yes |
| AC-1.5 / AC-1.6 — the waiting screen shows the device name and the instructions | needs the real view hierarchy | DT-01 | yes |
| AC-2.1 / AC-2.2 — the TV appears in the AirPlay picker of macOS | needs real mDNS multicast | DT-03 | yes |
| AC-2.3 — the device disappears from the picker when the service stops | needs the real service and the real network | DT-02 | yes |
| AC-3.1 / AC-3.2 — the RTSP handshake runs without an error response | needs a real macOS sender | DT-03 | yes |
| AC-4.1 — the video fills the screen | needs a real Surface and decoder | DT-03 | yes |
| AC-4.3 / AC-4.4 — frame rate and latency on the device | needs the real SoC | DT-03, DT-15 | yes |
| AC-4.6 / AC-4.7 — the screen switches between waiting and streaming | needs the real lifecycle | DT-03 | yes |
| AC-5.1 / AC-5.3 / AC-5.5 — audio is audible and stable | needs the real audio path | DT-04 | yes |
| AC-6.1 / AC-6.2 — 30 minutes without a crash or a disconnect | needs the real device over time | DT-15 | yes |
| AC-6.3 / AC-6.4 — memory and CPU inside the budget | needs the real memory manager | DT-15 | yes |
| Pairing PIN blocks an unpaired sender | needs a second device | DT-05 | **no — gap** |
| Miracast accepts a Windows sender | needs Wi-Fi Direct | DT-06 | **no — gap** |
| Google Cast accepts a Chrome sender | needs the Cast SDK and Play Services | DT-07 | **no — gap** |
| The device answers `M-SEARCH` with three answers | needs real SSDP on a real LAN | DT-08 | **no — gap** |
| The description document is served over HTTP | needs the ephemeral port on the device | DT-08 | **no — gap** |
| VLC lists PhairPlay as a renderer | needs a real VLC against a real network | DT-09 | **no — gap** |
| VLC can play a network URL on the TV | needs the real fetch of the media | DT-09 | **no — gap** |
| The SOAP actions work from an external control point | needs a real socket | DT-10 | **no — gap** |
| XXE is rejected on the device | needs the real parser on the real device | DT-10 | **no — gap** |
| SSRF (GENA callback, media URI) is refused | needs the real network stack | DT-10, DT-11 | **no — gap** |
| GENA delivers the LastChange event | needs a real callback server | DT-11 | **no — gap** |
| The German texts appear on the device | needs the resource system of the device | DT-12 | **no — gap** |
| The French texts appear on the device | needs the resource system of the device | DT-12 | **no — gap** |
| The receiver starts after a reboot | needs a real boot | DT-13 | **no — gap** |
| The notification and its actions work | needs the system notification UI | DT-14 | **no — gap** |

**Gap list for the spec track.** `docs/spec/ACCEPTANCE_CRITERIA.md` has no
criterion for the DLNA MediaRenderer, for Miracast, for Google Cast, for the
pairing PIN, for the German and French user interface, for "start on boot" and
for the notification — although all of them are implemented or in this cycle's
scope. The DLNA work of round 1 even contradicts
`docs/spec/REQUIREMENTS.md` line 180 ("DLNA / UPnP — Out of scope — Not
planned"). The cases above use the identifiers **DT-01 … DT-15** of this plan
until the spec track adds real AC identifiers.

---

## 5. Log collection

```bash
# collect everything that belongs to one test run
OUT="phairplay-device-$(date +%Y%m%d-%H%M).log"
{
  echo "== device =="
  adb shell getprop ro.product.model
  adb shell getprop ro.build.version.release
  adb shell getprop ro.build.display.id
  echo "== package =="
  adb shell dumpsys package "$PKG" | grep -E "versionName|versionCode|firstInstallTime"
  echo "== service =="
  adb shell dumpsys activity services "$PKG"
  echo "== logcat =="
  adb logcat -d -v threadtime | grep -iE "phairplay|AndroidRuntime|ANR in"
} > "$OUT"
echo "wrote $OUT"
```

---

## 6. Result sheet

Copy this table into the acceptance record and fill it in.

| Case | Google TV | Fire TV | Evidence | Notes |
|---|---|---|---|---|
| DT-01 Install and first start | ☐ pass ☐ fail ☐ n/a | ☐ pass ☐ fail ☐ n/a | | |
| DT-02 Start / stop / restart | ☐ pass ☐ fail ☐ n/a | ☐ pass ☐ fail ☐ n/a | | |
| DT-03 AirPlay video | ☐ pass ☐ fail ☐ n/a | ☐ pass ☐ fail ☐ n/a | | |
| DT-04 AirPlay audio | ☐ pass ☐ fail ☐ n/a | ☐ pass ☐ fail ☐ n/a | | |
| DT-05 Pairing PIN | ☐ pass ☐ fail ☐ n/a | ☐ pass ☐ fail ☐ n/a | | |
| DT-06 Miracast | ☐ pass ☐ fail ☐ n/a | ☐ pass ☐ fail ☐ n/a | | |
| DT-07 Google Cast | ☐ pass ☐ fail ☐ n/a | ☐ pass ☐ fail ☐ n/a | | |
| DT-08 SSDP discovery | ☐ pass ☐ fail ☐ n/a | ☐ pass ☐ fail ☐ n/a | | |
| DT-09 VLC renderer | ☐ pass ☐ fail ☐ n/a | ☐ pass ☐ fail ☐ n/a | | |
| DT-10 SOAP control | ☐ pass ☐ fail ☐ n/a | ☐ pass ☐ fail ☐ n/a | | |
| DT-11 GENA event | ☐ pass ☐ fail ☐ n/a | ☐ pass ☐ fail ☐ n/a | | |
| DT-12 German / French UI | ☐ pass ☐ fail ☐ n/a | ☐ pass ☐ fail ☐ n/a | | |
| DT-13 Start on boot | ☐ pass ☐ fail ☐ n/a | ☐ pass ☐ fail ☐ n/a | | |
| DT-14 Notification | ☐ pass ☐ fail ☐ n/a | ☐ pass ☐ fail ☐ n/a | | |
| DT-15 Debug overlay / stability | ☐ pass ☐ fail ☐ n/a | ☐ pass ☐ fail ☐ n/a | | |

`n/a` needs a reason in the notes column (for example "Fire TV has no Wi-Fi
Direct on this hardware" or "no Cast App ID registered").
