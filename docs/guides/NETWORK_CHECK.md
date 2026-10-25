# Network check — is the television visible on this network?

This guide is for the **owner**, not for a developer. You do not need to know
anything about Android. You need a laptop, the same Wi-Fi as the television and
one command.

The command answers the question that comes up before every playback test:

> **Does the television see PhairPlay at all?**

Without a command for it, every failed attempt costs a guess — the wrong Wi-Fi,
a firewall, a stopped service or an address that survived a change of the
network. The script replaces the guess with four measurements. Each measurement
gets a verdict, an explanation and the next step.

---

## 1. What you need

| Requirement | How to check it |
|---|---|
| A laptop in the **same Wi-Fi** as the television | The television and the laptop have to be in the same network, same router, same SSID. A **guest network** or a **VPN** on the laptop breaks the discovery. |
| **Bash** | Comes with macOS (Terminal) and with every Linux. |
| **curl** | Comes with macOS. On Linux: `sudo apt install curl`. |
| **python3** | macOS: `xcode-select --install`. It is only needed for the discovery (the SSDP search). |

Nothing else. The script does not install anything, does not change any
setting and does not talk to anything outside your network.

> The script only **reads**. It never writes to the television and never
> changes your router.

---

## 2. The command

Open a terminal on the laptop and run:

```bash
cd /path/to/PhairPlay
./scripts/device-check.sh
```

That runs every check. The output ends with a summary and one line with the
next action — **that line is what you paste into the issue.**

Useful variants:

| Command | What it does |
|---|---|
| `./scripts/device-check.sh` | every check (the default) |
| `./scripts/device-check.sh --ssdp` | only the discovery |
| `./scripts/device-check.sh --description` | only the description document |
| `./scripts/device-check.sh --address` | only the comparison of the addresses |
| `./scripts/device-check.sh --airplay` | only the AirPlay port |
| `./scripts/device-check.sh --host 192.168.178.23` | tell the script the address of the television |
| `./scripts/device-check.sh --timeout 5` | wait up to 5 seconds per check (default: 3) |
| `./scripts/device-check.sh --help` | the full list of options |

**Exit codes** — useful if you want to use the script in a script of your own:

| Code | Meaning |
|---|---|
| `0` | every check passed |
| `1` | some checks passed, some did not |
| `2` | the renderer did not answer at all |
| `64` | you typed an option that does not exist |

---

## 3. What the output looks like

### 3.1 Everything is fine

```text
PhairPlay network check
  when      : 2026-10-25 11:43:07
  laptop    : 192.168.178.31
  discovery : 239.255.255.250:1900 (M-SEARCH, 3 s)

[1] SSDP discovery -- is the renderer visible in the network?
      -> FOUND -- the television answers the discovery
        LOCATION      : http://192.168.178.23:41234/description.xml
        SERVER        : Android/1.0 UPnP/1.0 PhairPlay
        USN           : uuid:550e8400-e29b-41d4-a716-446655440000::urn:schemas-upnp-org:device:MediaRenderer:1
        ST            : urn:schemas-upnp-org:device:MediaRenderer:1
        from          : 192.168.178.23:1900
        bonus         : no Samsung / multiscreen answer -- that is normal unless the television is a Samsung model
      Meaning: the renderer answers the M-SEARCH, so it is visible in the network of this laptop.
      Action : none -- go on with the next check.

[2] Description document -- can a control point read it?
        URL           : http://192.168.178.23:41234/description.xml
        HTTP status   : 200
      -> COMPLETE -- the description names the device and all three services
        friendlyName  : Living Room TV
        services      : AVTransport RenderingControl ConnectionManager
        UDN           : uuid:550e8400-e29b-41d4-a716-446655440000
      Meaning: the description document is readable and complete, so a control point on this network can drive the renderer.
      Action : none -- go on with the next check.

[3] Description URL -- does it point at the address the renderer really has?
        URL address   : 192.168.178.23:41234
        this laptop   : 192.168.178.31
        device address: 192.168.178.23 (the address the renderer answered from)
      -> MATCH -- the description URL points at the address of the device
      Meaning: the renderer advertises the address it really has, so a control point fetches the description from the right host.
      Action : none -- go on with the next check.

[4] AirPlay port 7000 -- can a sender reach the receiver?
        target        : 192.168.178.23:7000
        probe         : bash /dev/tcp
      -> REACHABLE -- the port accepts a connection
      Meaning: a sender on this network can open the AirPlay connection on the television.
      Action : none -- go on with the next check.

SUMMARY: OK -- the renderer is visible in the network, its description is complete, the description URL points at the right address and the AirPlay port is open.
NEXT ACTION: let the television search for the renderer again (case DT-09 of the device test plan) and play a file.
Copy the summary above into the issue.
```

The exit code is `0`.

### 3.2 The description cannot be read

```text
[2] Description document -- can a control point read it?
        URL           : http://192.168.178.23:41234/description.xml
        HTTP status   : 404
      -> INCOMPLETE -- the server answered with HTTP 404 instead of 200
      Meaning: a control point stops here: it cannot read the device description.
      Action : restart the app on the television; if the status stays wrong, the description server of the app is the problem, not the network.

SUMMARY: PARTIAL -- the description document is not readable.
NEXT ACTION: restart the PhairPlay app on the television (Home -> Restart) and run this script again.
Copy the summary above into the issue.
```

The exit code is `1`. The discovery worked — the television **is** visible —
but the document behind the address is not served. That is an app problem, not
a network problem: restart the app on the television.

### 3.3 The description points at the wrong address

```text
[3] Description URL -- does it point at the address the renderer really has?
        URL address   : 192.168.178.99:41234
        this laptop   : 192.168.178.31
        device address: 192.168.178.23 (the address given with --host)
      -> MISMATCH -- the description URL points at another address
      Meaning: the renderer answers from 192.168.178.23 but advertises 192.168.178.99. A control point that trusts the LOCATION header fetches a host that does not exist any more -- the symptom of an address that survived a change of the network.
      Action : restart the PhairPlay app on the television (Home -> Restart) so that it advertises its current address, then run this script again.

SUMMARY: PARTIAL -- the description URL points at another address.
NEXT ACTION: restart the PhairPlay app on the television (Home -> Restart) so that it advertises its current address, then run this script again.
Copy the summary above into the issue.
```

The exit code is `1`. The television answers from `192.168.178.23`, but the
address it advertises is `192.168.178.99` — an address from an earlier network
or a stale one. Every program that follows the advertised address fails, even
though everything is fine in the network. Restarting the app makes it
advertise its current address again.

---

## 4. The four checks

| Check | What it measures | Verdict | Next step |
|---|---|---|---|
| **1 — SSDP discovery** | Does the television answer a search (`M-SEARCH`) in the network? | `FOUND` / `NOT FOUND` | If nothing answers: is the app running? Are laptop and television in the same Wi-Fi (same SSID, no guest network)? Does the router filter multicast (AP isolation, IGMP snooping)? |
| **2 — Description document** | Can the document behind the advertised address be read — and does it name the device and its three services (`AVTransport`, `RenderingControl`, `ConnectionManager`)? | `COMPLETE` / `INCOMPLETE` | Restart the app on the television. A wrong status or a missing service belongs to the app, not to the network. |
| **3 — Description URL** | Does the advertised address equal the address the television really has? | `MATCH` / `MISMATCH` | Restart the app on the television so that it advertises its current address. If you know the address of the television, run the script again with `--host <ip>`. |
| **4 — AirPlay port 7000** | Does a TCP connection to port 7000 succeed? | `REACHABLE` / `NOT REACHABLE` | Restart the app on the television. If the port stays closed, the AirPlay receiver did not start — look at the log with `adb logcat -s PhairPlay`. |

**Bonus:** if a Samsung television answers the Samsung multiscreen search as
well, the discovery reports it. That is the way a Samsung TV finds the
renderer; on other models it is normal that it does not happen.

---

## 5. What this script does **not** prove

Read this before you file an issue — it saves a round of questions.

- **It does not prove that a picture appears.** The script measures the
  *network* side: discovery, the description document, the address and the
  port. Whether a video actually plays is a separate question — the playback
  path (codecs, the media fetch, the decoder) is **not** covered by it. A
  green run with a black screen is a playback problem, not a network problem.
- **It does not prove that the television shows the device in a picker.** VLC,
  Windows or a phone may still hide the device for their own reasons.
- **It does not prove that the app works.** It reads what the app advertises.
  An app that advertises a wrong document produces a red result here even
  though the app itself is running.
- **It says nothing about the internet.** PhairPlay only works in the local
  network; the script never leaves it.
- **A "not found" is not always a defect.** A router with client isolation or
  with multicast filtering makes the television invisible to every device in
  the network — the script reports that as "not found", and the fix belongs
  to the router.
- **One run is a snapshot.** The network changes; a run that was green a
  minute ago says nothing about the next minute.

---

## 6. The self-test

The tool itself is tested without a television:

```bash
./scripts/test-device-check.sh
```

It starts a local HTTP server and a fake renderer on the loopback interface,
runs the tool against them and checks the verdicts — a complete document, a
`404`, a broken document, a wrong address and a network without a renderer.
It needs no network, no multicast rights and no television, and it finishes in
a few seconds.

---

## 7. Related documents

- [`TROUBLESHOOTING.md`](TROUBLESHOOTING.md) — the failure catalogue of the
  product (no picture, no sound, the device stays invisible).
- [`DEVICE_TEST_PLAN.md`](DEVICE_TEST_PLAN.md) — the hardware test cases
  DT-01 … DT-15; DT-09 is the VLC renderer case this check prepares.
- [`INSTALLATION.md`](INSTALLATION.md) — how the app gets onto the television.
