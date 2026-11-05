# PhairPlay — Permission Inventory

**Date:** 2026-11-05
**Applies to:** `main` @ `34a166c` (release `v1.0.0-alpha.7`) plus the hardening
changes of this cycle
**Source of truth for the declarations:** `app/src/main/AndroidManifest.xml`
**Reference:** the public Android manifest documentation —
<https://developer.android.com/guide/topics/manifest/uses-permission> (read
2026-11-02, as the task requires; the URL is the canonical description of every
permission below and of the runtime-grant rules).

---

## 1. How this inventory is kept in sync

`scripts/check-privacy-policy.sh` is the gate that keeps the declarations and the
explanation together. It

1. reads every `<uses-permission android:name="…">` of
   `app/src/main/AndroidManifest.xml`,
2. checks that every one of them is named in `PRIVACY.md` by its bare permission
   name (`ACCESS_WIFI_STATE`, …),
3. checks that `PRIVACY.md` states that the policy is reachable from inside the app,
4. and checks that `PRIVACY.md` is **not older** than the manifest.

Measured on 2026-11-05:

```
$ bash scripts/check-privacy-policy.sh
OK: all 13 declared permissions are documented in the privacy policy.
OK: privacy policy check passed.
```

**What that gate does *not* do:** it does not check *whether* a permission is needed
— only that it is documented. The judgement in §2 is therefore a human one, and this
document is its record.

**Note on this cycle:** the hardening added two attributes to the `<application>`
element — `android:dataExtractionRules` and `android:fullBackupContent` (SEC-18).
Neither is a permission, but the manifest changed, so the "Last updated" line of
`PRIVACY.md` was bumped to 2026-11-05 and the data-protection section was extended
accordingly. The set of permissions itself did not change.

---

## 2. The inventory

| # | Permission | Purpose | Really needed? | Privacy relevant? | Evidence | Recommendation |
|---|---|---|---|---|---|---|
| 1 | `INTERNET` | every TCP and UDP socket of the receiver: RTSP on TCP 7000 (`airplay/RtspServer.kt:101`), the RTP channels on UDP 6001/6002 (`airplay/AirPlayMediaPipeline.kt:131`), the DLNA description server (`dlna/DlnaDescriptionServer.kt:79`), SSDP on UDP 1900 (`dlna/SsdpAdvertiser.kt:189`), the WFD RTSP server on TCP 7236 (`miracast/WfdRtspServer.kt:77`), and the outbound HTTP of the media fetch | **Yes** — Android requires it for every socket; there is no local-only variant | No — it grants the *ability* to reach the network, not the right to leave it. The app contains no endpoint outside the LAN | the eleven files that open a socket, e.g. `airplay/RtspServer.kt`, `dlna/DlnaDescriptionServer.kt` | **Keep.** The absence of any internet endpoint is a property of the code, not of the permission — it is checked in `docs/security/THREAT_MODEL.md` §3 |
| 2 | `CHANGE_WIFI_MULTICAST_STATE` | the multicast lock that keeps the mDNS announcement alive — without it `createMulticastLock` throws and the television disappears from every AirPlay picker | **Yes** — `wifi.createMulticastLock("PhairPlay-mdns")` (`airplay/MdnsService.kt:106`) | No | `airplay/MdnsService.kt:106`, acquired at `:160` | **Keep** |
| 3 | `ACCESS_WIFI_STATE` | required by the Wi-Fi Direct framework (Miracast) and by the Google Cast SDK | **Yes** | No | `miracast/MiracastReceiver.kt:54` (`WifiP2pManager`), the Cast SDK | **Keep.** It is **not** used to read the IP or the MAC address: those are read with `java.net.NetworkInterface` (`util/NetworkUtils.kt`), which needs no permission |
| 4 | `ACCESS_NETWORK_STATE` | the network callback that tells the service that the Wi-Fi came back, so the advertisement is repeated | **Yes** | No | `service/NetworkRecoveryMonitor.kt:49` (`registerNetworkCallback`) | **Keep** |
| 5 | `FOREGROUND_SERVICE` | the receiver must keep running while the app is in the background — that is the whole product | **Yes** | No | `service/PhairPlayService.kt:117` (`startForeground`) | **Keep** |
| 6 | `FOREGROUND_SERVICE_CONNECTED_DEVICE` | the `connectedDevice` foreground service type, required from Android 14 on for a service that manages a connected device (Wi-Fi, P2P) | **Yes** | No | `app/src/main/AndroidManifest.xml:169` (`android:foregroundServiceType="connectedDevice"`) | **Keep** |
| 7 | `CHANGE_WIFI_STATE` | the Wi-Fi Direct framework (Miracast) | **Yes** | No | `miracast/MiracastReceiver.kt:54` | **Keep** |
| 8 | `NEARBY_WIFI_DEVICES` | the Wi-Fi Direct framework on Android 13+ (API 33+), where the platform replaced the location permission | **Yes** | No — and the declaration carries `android:usesPermissionFlags="neverForLocation"`, the platform's own way of promising that the app never derives a location from a Wi-Fi result | `app/src/main/AndroidManifest.xml:62`–`:65` | **Keep, with the flag.** The attribute was introduced with API 31, so the element carries `tools:targetApi="31"` — the manifest comment explains why |
| 9 | `ACCESS_FINE_LOCATION` | the Wi-Fi Direct framework up to Android 12 (API 32) | **Yes, but only up to API 32** | **Yes** — a location permission is the most privacy-sensitive permission on Android | `app/src/main/AndroidManifest.xml:66`–`:68` (`android:maxSdkVersion="32"`) | **Keep with the cap.** A television running Android 13 or newer does not declare it at all. It is **never requested at runtime** — see §3 |
| 10 | `ACCESS_COARSE_LOCATION` | the mandatory companion of `ACCESS_FINE_LOCATION` on Android 12+: the system ignores a `FINE` request that is not accompanied by `COARSE` | **Yes, as the companion** | **Yes** | `app/src/main/AndroidManifest.xml:69`–`:71` (`android:maxSdkVersion="32"`) | **Keep with the cap.** It cannot be dropped while `ACCESS_FINE_LOCATION` is declared |
| 11 | `RECEIVE_BOOT_COMPLETED` | the optional "start on boot" switch | **Yes** | No — the receiver is only started when the owner asked for it, and the switch is off by default | `service/BootReceiver.kt:28` | **Keep.** The receiver is `exported="false"`, so no other app can trigger it |
| 12 | `SYSTEM_ALERT_WINDOW` | brings the streaming screen to the front when a session starts while the app is in the background — Android 10+ blocks background activity starts otherwise | **Yes**, for that one case | **Low** — it is a *special app access*, not a runtime permission: it is not granted at install time, the user has to enable it in the system settings | `service/PhairPlayService.kt:256` (`Settings.canDrawOverlays`) | **Keep.** The app never draws an overlay — it uses the access for exactly one `startActivity`. Without the grant the old behaviour remains, and the app logs that |
| 13 | `POST_NOTIFICATIONS` | the foreground service notification | **Yes** | No | `MainActivity.kt:298` (`ActivityCompat.requestPermissions`) | **Keep.** It is the **only** runtime permission the app ever requests, and it can be declined |

**Nothing could be removed outright.** Every declared permission has a traced use in
the source. The two that looked superfluous — `ACCESS_COARSE_LOCATION` and the claim
that `ACCESS_WIFI_STATE` is used to read the IP and the MAC address — turned out to be
required respectively by the Android 12+ location rule and by Wi-Fi Direct, while the
MAC and the IP are read with `java.net.NetworkInterface`, which needs no permission
at all.

**What *was* reduced:** the location ask on current devices. With the API-32 cap, a
television running Android 13 or newer declares **no** location permission, and the
one that is declared for the older systems carries the `neverForLocation` promise.

---

## 3. The permissions that are declared but never requested

| Permission | Declared for | Requested at runtime? | Consequence |
|---|---|---|---|
| `ACCESS_FINE_LOCATION` | Wi-Fi Direct up to API 32 | **No** | on Android 10 and later the permission is never granted, so `hasWifiP2pPermission()` is `false`, the Miracast receiver reports `ERROR`, and the feature does not work |
| `ACCESS_COARSE_LOCATION` | companion of the above | **No** | same |
| `NEARBY_WIFI_DEVICES` | Wi-Fi Direct on API 33+ | **No** | same |

**Measured:** `grep -rn "requestPermissions" app/src/main` returns exactly one call —
`MainActivity.kt:301`, and it asks for `POST_NOTIFICATIONS`.

**Why they are not requested:** the location permission is the most privacy-sensitive
request in the manifest, and it would be asked for in order to mirror a screen — a
feature that ADR-001 already describes as unreliable. The decision whether to make the
feature work is an **owner decision** (O-5 in `docs/security/FINDINGS.md`); the
safer variant — declared but never requested — is what the code does today.

---

## 4. The permissions that are explicitly not requested

`RECORD_AUDIO`, `CAMERA`, `READ/WRITE_EXTERNAL_STORAGE`, `READ_MEDIA_IMAGES`,
`BLUETOOTH`, `BLUETOOTH_CONNECT`, `QUERY_ALL_PACKAGES`, `ACCESS_BACKGROUND_LOCATION`,
`BODY_SENSORS` — none of them appears in the manifest, and none of them is requested.

The app **cannot** record anything, **cannot** read the user's files and **cannot**
see where the user is. The only runtime request is `POST_NOTIFICATIONS`.

---

## 5. The other manifest declarations

| Declaration | Purpose | Assessment |
|---|---|---|
| `android:allowBackup="false"` | the app takes no part in the cloud backup | **Keep.** On its own it is not enough on Android 12+ — see §6 |
| `android:dataExtractionRules="@xml/data_extraction_rules"` | the rules of the device-to-device transfer (Android 12+) | **New in this cycle** — SEC-18 |
| `android:fullBackupContent="@xml/backup_rules"` | the rules of the backup on Android 11 and older | **New in this cycle** — SEC-18 |
| `android:exported="false"` on the service and the boot receiver | only the app itself may start them | **Keep.** `MainActivity` is the only exported component besides the launcher intent |
| `uses-feature android.software.leanback = true` | the app appears in the TV launcher | **Keep** |
| `uses-feature android.hardware.touchscreen = false` | TV remotes, not touch screens | **Keep** |
| `uses-feature android.hardware.location.* = false` | the app does not use the GPS — the declaration says so explicitly | **Keep** |

---

## 6. The backup declarations and why they exist

`android:allowBackup="false"` switches the **cloud** backup off on every Android
version. It does **not** switch the device-to-device transfer off: the platform
documentation states that on devices of some manufacturers a D2D migration of the
app's files happens anyway, and that the rules for it are the ones of
`android:dataExtractionRules`
(<https://developer.android.com/guide/topics/manifest/application-element> and
<https://developer.android.com/about/versions/12/behavior-changes-12>, read
2026-11-05).

The app holds the long-term Ed25519 identity of the receiver and the public keys of
the paired controllers. Both rule files therefore exclude **every** domain — from
the cloud backup and from the device transfer, in the credential-encrypted and in the
device-protected storage. The proof is the test `BackupRulesTest`
(`app/src/test/kotlin/com/phairplay/security/BackupRulesTest.kt`).

---

## 7. What would change the inventory

| Change | Consequence for the inventory |
|---|---|
| The Miracast feature is made to work | `NEARBY_WIFI_DEVICES` (and `ACCESS_FINE_LOCATION` up to API 32) would be **requested at runtime** — the row would move from §3 to §2 and the privacy policy would need a new entry about the location prompt |
| A network security configuration is added | no permission changes; the *traffic* policy changes (see O-4 in the findings register) |
| The diagnostics export is extended (a file export, a share intent) | no permission changes, but the export would need the same reduction — and a share intent needs a permission of its own to read the target |
