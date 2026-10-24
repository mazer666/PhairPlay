# Privacy policy of PhairPlay

**Last updated:** 2026-10-24
**Applies to:** PhairPlay for Google TV and Fire TV, version 1.0.0-beta.3

PhairPlay shows the screen of your phone or laptop on your television. It is a
receiver, not a service: there is no account, no cloud, and no company behind it
that could collect anything. This policy is short because the application does
not do what most applications do.

---

## 1. The short version

* PhairPlay **does not collect** personal data.
* PhairPlay **does not send** anything to a server, because it has no server and
  makes no connection to the internet. The only network it ever talks to is your
  local Wi-Fi.
* PhairPlay **does not** ask for an account and it **does not** contain
  advertising, analytics or telemetry. There is no crash reporter, no usage
  counter and no "send diagnostics" button.
* The data PhairPlay stores stays **on the television**: a few settings, a random
  device identifier, and the key that identifies the TV to your phone.
* While a mirroring session runs, the picture, the sound and the title of the
  track that is playing travel over your local network — that is the feature.
  They are not written to the disk and not forwarded anywhere else.

## 2. What leaves the television, and where it goes

Everything below stays inside your local network. The entries are the places
where data is *put on the wire*; the "who sees it" column is the honest answer
for a home network.

| What | Transport | Who can see it |
|---|---|---|
| The name of the TV (your device name, or the name you set in Settings) | mDNS service name and TXT record, DLNA `friendlyName` | Every device in the same Wi-Fi that listens for AirPlay/UPnP |
| The MAC address of the TV | mDNS TXT record (`deviceid`), `GET /info`, `GET /server-info` | The same — the AirPlay protocol requires it |
| A random identifier of the TV (`pi`, and the DLNA `UDN`) | mDNS TXT record, DLNA device description | The same |
| The public key of the TV | `GET /info` (`pk`) | The same |
| The mirrored picture and sound | AirPlay (TCP 7000, UDP 6001/6002) and Miracast (TCP 7236) | The sender that started the session |
| The title, artist and album of the playing track, and its cover image | AirPlay `SET_PARAMETER` | The sender that started the session |
| The picture you share with "AirPlay photo" | AirPlay `PUT /photo` | The sender that started the session |
| The volume you set on the TV remote | AirPlay `SET_PARAMETER`, and the reverse DACP channel back to the sender | The sender that started the session |
| The TV's IP address in the LAN | SSDP `LOCATION`, the DLNA description URL, the reverse DACP/NTP connections | Every device in the same Wi-Fi |

**Not on the list, and not happening:** no analytics, no telemetry, no
advertising identifier, no crash report, no "device fingerprint" uploaded
anywhere, no push notification, no cloud backup, no account, no e-mail, no
update check against a server.

**The one thing to know:** while the receiver is switched on, the TV announces
itself on the local network. A guest in your Wi-Fi can therefore see the name
and the MAC address of your television. That is what the AirPlay protocol
requires — a sender has to be able to find the receiver — and it cannot be
switched off while the receiver is running. If that bothers you, switch the
AirPlay receiver off in Settings; the TV then disappears from the network
completely.

## 3. What is stored on the television, and where

All of it lives in the private storage of the application
(`/data/data/com.phairplay.<flavour>/`). No other application can read it, and
because `android:allowBackup="false"` is set, **none of it is included in the
Android cloud backup**.

| What | Where | Why |
|---|---|---|
| Your settings: display name, the switches for AirPlay / Miracast / Cast / DLNA, PIN authentication, "start on boot", debug overlay, high resolution, mirror audio | `datastore/phairplay_settings.preferences_pb` | The settings belong to you and must survive a restart. |
| A random identifier for the TV (`phairplay_device_uuid`) and the 32-byte seed of the TV's Ed25519 key pair (`pairing_ed25519_seed`) | `shared_prefs/phairplay_prefs.xml` | macOS and iOS recognise the receiver by these two values. Without them the TV would appear as a new device after every restart. |
| The public keys of the controllers that completed the PIN pairing, and the number of failed pairing attempts | `shared_prefs/phairplay_pairings.xml` | So a paired Mac does not have to enter the PIN again, and so that a wrong PIN cannot be guessed endlessly. |
| The mirrored picture, the shared photo, the album cover | **nowhere** — only in memory, while it is on screen | A stream is not a file. Nothing is written to the disk. |
| The system log | **nowhere** — a release build writes no log at all (see §5) | — |

Nothing else is stored. There is no cache directory, no download directory, no
"recent files" list, and no crash dump on the disk.

### How to remove it

* **Everything at once:** Android → *Settings* → *Apps* → *PhairPlay* →
  *Storage* → *Clear storage*. The TV forgets its identity and appears in the
  AirPlay menu as a new device; every paired Mac has to pair again.
* **Only the pairing:** clear the storage as above — the paired controllers are
  part of it.
* **Everything, including the app:** uninstall PhairPlay. Android removes the
  private storage with it.

## 4. The permissions PhairPlay asks for, and what it does with them

| Permission | Why it is needed | Is it privacy relevant? |
|---|---|---|
| `INTERNET` | AirPlay, Miracast and DLNA are socket protocols. Android requires this permission for every TCP or UDP socket. It does **not** mean that the app talks to the internet — the code contains no internet endpoint. | No. The app never opens a connection outside your LAN. |
| `CHANGE_WIFI_MULTICAST_STATE` | The multicast lock that keeps mDNS (and therefore the AirPlay entry in the sender's menu) alive. | No. |
| `ACCESS_WIFI_STATE` | Required by Wi-Fi Direct (Miracast) and by the Google Cast SDK. It is **not** used to read the IP or MAC address — those are read with `java.net.NetworkInterface`, which needs no permission. | No. |
| `ACCESS_NETWORK_STATE` | Detects that the TV came back from standby or changed Wi-Fi, so the advertisement can be repeated. | No. |
| `CHANGE_WIFI_STATE` | Required by the Wi-Fi Direct framework (Miracast). | No. |
| `NEARBY_WIFI_DEVICES` | Android 13+ replaced the location permission for Wi-Fi Direct with this one. The app declares it with `neverForLocation`: it never derives a location from a Wi-Fi result. | No — the flag promises exactly that. |
| `ACCESS_FINE_LOCATION` and `ACCESS_COARSE_LOCATION` | Android asks for a location permission for Wi-Fi Direct up to Android 12. Both are capped at API 32, so a current TV does not receive them at all. | **Yes, in principle** — a location permission is the most privacy-sensitive permission on Android. That is why it is limited to the versions that need it, and why it is never requested at runtime (see §6). |
| `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_CONNECTED_DEVICE` | The receiver must keep running while the app is in the background. | No. |
| `RECEIVE_BOOT_COMPLETED` | The optional "start on boot" switch. | No. |
| `SYSTEM_ALERT_WINDOW` | A *special app access*, not a runtime permission, and not granted at install time. It is used for one thing only: to bring the streaming screen to the front when a mirroring session starts while the app is in the background. The app never draws an overlay. Without the grant, the app simply does not come forward on its own. | Low. It would allow drawing over other apps in principle; the app does not do that. |
| `POST_NOTIFICATIONS` | The foreground service notification. Requested at runtime, and it can be declined. | No. |

PhairPlay asks for **no** camera, **no** microphone, **no** storage, **no**
contacts, **no** phone, **no** location at runtime, and **no** Bluetooth. It
cannot record anything, cannot read your files, and cannot see where you are.

## 5. The logs

* A **release build writes nothing to the system log.** No logging backend is
  installed, so there is no log file to collect, and no log that could be read
  by anyone.
* A **debug build** writes to the Android system log, as every development
  build does. Before a message is written, it passes through a central
  reduction (`com.phairplay.util.LogRedactor`): the host part of an IP address,
  the device-specific half of a MAC address, complete UUIDs, and the
  credentials and query string of a URL are replaced by placeholders. The
  reduction is on by default in every build, so a log excerpt that you paste
  into a public issue tracker does not carry the addresses of your network.
* The reduction cannot recognise free text. A device name, the name of the
  sender, and the title of a playing track are logged as they are — that is a
  known and accepted limitation, listed in
  `docs/audit/2026-10-24-security-audit.md`.

## 6. The permissions that are declared but never requested

`ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` and `NEARBY_WIFI_DEVICES` are
declared in the manifest, but the app never asks for them at runtime — only
`POST_NOTIFICATIONS` is requested. On Android 10 and later the permissions are
therefore never granted, and the Miracast (Wi-Fi Direct) advertisement cannot
start. This is a functional gap, not a privacy feature: the location
permission is not being collected, it is simply not working. It is recorded as
finding **SEC-08** in `docs/audit/2026-10-24-security-audit.md`.

## 7. The children

PhairPlay is a tool for a television. It collects nothing, shows no advertising
and asks for no account, so there is nothing it would collect from a child
either.

## 8. The source code

The source of PhairPlay is open, and the source is the proof: every statement
in this document can be checked against the code. The manifest that the
permissions come from is
`app/src/main/AndroidManifest.xml`, the log reduction is
`app/src/main/kotlin/com/phairplay/util/LogRedactor.kt`, and the security
review of the current release is
`docs/audit/2026-10-24-security-audit.md`.

## 9. The changes of this policy

Every change of this policy is a change of the repository, with the date in the
header of this file and in `CHANGELOG.md`.

## 10. The contact

Questions about this policy or about the privacy of a mirroring session belong
into the issue tracker of the project:
<https://github.com/mazer666/PhairPlay/issues> — every question and every report
about the privacy of PhairPlay belongs there, and the report is public.
