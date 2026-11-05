# Data Safety Form — Draft for PhairPlay

**Version:** 1.0
**Owner:** Project Manager
**Date:** 2026-11-05
**Measured on:** commit `34a166c` (branch `main`), release `v1.0.0-alpha.7`
**Applies to:** the `googletv` flavour, `com.phairplay.googletv`
**Form:** *Play Console → Grow users → App content → Data safety*

> **How this document was produced.** Every answer below was derived by
> reading the source code of `34a166c` — not from the store listing, not from
> the privacy policy, and not from a template. The privacy policy was used only
> as the **cross-check** (§6), because Google requires the Data safety section
> and the privacy policy to agree:
> *"The information in this section must consistently match your app's privacy
> policy disclosures."* — [User Data, Play Console
> Help](https://support.google.com/googleplay/android-developer/answer/10144311),
> retrieved 2026-11-05.
>
> **Three questions are not answered here.** They are marked **⚠ OWNER** and
> collected in §5. Until they are answered, the form must **not** be
> submitted — a wrong Data safety declaration is a policy violation, not a
> formatting mistake.

---

## 1. The inventory: what the app actually touches

Derived by reading every store, every network call and every permission in the
repository.

| Data | Where it comes from | Where it goes | Where it is stored | Evidence |
|---|---|---|---|---|
| Device name (the display name the user sets, or the device name) | `SettingsRepository`, key `display_name` | mDNS service name and TXT record, DLNA `friendlyName` | DataStore `phairplay_settings.preferences_pb` | `app/src/main/kotlin/com/phairplay/settings/SettingsRepository.kt:144` |
| MAC address of the television | `java.net.NetworkInterface` — **no permission needed** | mDNS TXT record (`deviceid`), `GET /info`, `GET /server-info` | nowhere — read at runtime | `PRIVACY.md` §2; `app/src/main/kotlin/com/phairplay/util/NetworkUtils.kt` |
| A random device identifier (`phairplay_device_uuid`, the DLNA `UDN`) | `UUID.randomUUID()` | mDNS TXT record, DLNA device description | SharedPreferences `phairplay_prefs` | `app/src/main/kotlin/com/phairplay/util/NetworkUtils.kt:114-125` |
| The 32-byte Ed25519 seed of the television | generated on first start | the public part in `GET /info` (`pk`) | SharedPreferences `phairplay_prefs`, key `pairing_ed25519_seed` | `app/src/main/kotlin/com/phairplay/airplay/handshake/PairingKeys.kt:26-43` |
| The public keys of the paired controllers + the failed-attempt counter | the PIN pairing | nowhere — never transmitted | SharedPreferences `phairplay_pairings`, keys `ltpk_*`, `pair_failed_attempts` | `app/src/main/kotlin/com/phairplay/airplay/handshake/PairingStore.kt:12-50` |
| The settings (protocol switches, PIN auth, start on boot, debug overlay, high resolution, mirror audio) | the settings screen | nowhere | DataStore `phairplay_settings.preferences_pb` | `SettingsRepository.kt:144-153` |
| The mirrored picture and sound | the sender | the sender, over the LAN (AirPlay TCP 7000, UDP 6001/6002; Miracast TCP 7236) | **nowhere** — only in memory, while it is on screen | `PRIVACY.md` §2, §3 |
| The shared photo and the album cover | the sender | the sender | **nowhere** — only in memory | `PRIVACY.md` §3 |
| The title, the artist and the album of the playing track | the sender | the sender (AirPlay `SET_PARAMETER`) | **nowhere** | `PRIVACY.md` §2 |
| The IP address of the television in the LAN | the network interfaces | SSDP `LOCATION`, the DLNA description URL, the reverse DACP/NTP connections | nowhere | `PRIVACY.md` §2 |
| The system log | the runtime | the Android system log (never to a server) | **nowhere** — a release build plants no Timber tree | `app/src/main/kotlin/com/phairplay/PhairPlayApp.kt:37-44` |

**What is *not* in the inventory** — no analytics, no telemetry, no
advertising identifier, no crash reporter, no usage counter, no push
notification, no cloud backup, no account, no e-mail, no update check against
a server. There is no crash-reporter or analytics dependency in
`gradle/libs.versions.toml`; the only networking code in the app is
`DacpClient` (the AirPlay reverse channel), `DlnaReceiver` (the DLNA media
fetch) and `GenaEventManager` (the UPnP event channel) — all three connect to
a host on the local network.

---

## 2. The bundled SDKs

| Component | Where it comes from | What it does | Data relevance |
|---|---|---|---|
| **Google Cast SDK for TV** (`com.google.android.gms:play-services-cast-tv:20.0.0`) | `gradle/libs.versions.toml:29`, wired in as `"googletvImplementation"(libs.play.services.cast.tv)` (`app/build.gradle.kts:224`) — **only** in the `googletv` flavour | Starts the Cast Connect receiver framework (`CastReceiver.start()` → `CastReceiverContext.initInstance`), registers the receiver options (`CastReceiverOptionsProvider`) | **The one component that is not written by this project.** Its data practices are the reason §5 question 1 exists. The Fire TV flavour never touches it (`app/src/firetv/kotlin/com/phairplay/cast/CastReceiver.kt` reports `DISABLED`) |
| **AndroidX DataStore** (`androidx.datastore:datastore-preferences:1.1.1`) | `gradle/libs.versions.toml:27` | Stores the ten settings locally, asynchronously | On-device only, no transmission |
| **Google Play services** (`com.google.android.gms`) | a **runtime** dependency of the device, not a bundled one: `CastReceiver.isAvailable()` checks that the package `com.google.android.gms` is installed (`app/src/googletv/kotlin/com/phairplay/cast/CastReceiver.kt:63-70`) | Provides the Cast framework on the device | Only the Cast path uses it |
| **Timber** 5.0.1, **BouncyCastle** 1.78.1, **dd-plist** 1.28, **AndroidX** (appcompat, constraintlayout, core-ktx, leanback) | `gradle/libs.versions.toml` | Logging wrapper, AES-128-CTR, binary property lists, the UI toolkit | No data collection. The release build plants no Timber tree, so a release build writes no log at all |

**The transitive dependency graph was not resolved.** No Gradle run was
permitted in this session, so the list above is the **direct** dependency set
from the version catalog plus what the code touches. The full transitive graph
of `play-services-cast-tv:20.0.0` should be confirmed once before the
submission — see §5, question 1.

---

## 3. The form, question by question

### Q1 — "Does your app collect or share any of the required user data types?"

> **Recommended answer: NO.**
>
> *No data is collected* — the developer never receives anything. There is no
> server, no endpoint, no API key and no account in the repository. The
> definition Google gives is: *"Data is generally considered 'collected' when
> the developer uses their app to retrieve data off your device"* and
> *"An app accesses the data only on your device and it is not sent off your
> device" → it does not need to be disclosed as collected*
> ([Understand app privacy & security practices with Google Play's Data safety
> section](https://support.google.com/googleplay/answer/11416267), retrieved
> 2026-11-05). PhairPlay retrieves nothing off the device.
>
> *No data is shared* — *"Data is generally considered 'shared' when it is
> accessed by the app and transferred to a third party"*, and the exception
> covers *"data that is shared with a developer's service provider"* (same
> source). PhairPlay transfers nothing to a server, to an analytics service or
> to the developer. The only party that receives data is the **sender on the
> same local network**, which is the feature and not the developer's
> processing.
>
> **The one honest caveat**, already disclosed in `PRIVACY.md` §2: while the
> receiver runs, the television announces its **name, its MAC address and its
> random identifier** on the local network. That data does leave the device.
> It goes to the LAN, not to a server, and the developer never sees it. This is
> the point where the answer could reasonably be drawn the other way — see
> **⚠ OWNER §5, question 3** for the conservative alternative.
>
> **⚠ OWNER** — question 1 in §5.

### Q2 — "Is all of the user data collected by your app encrypted in transit?"

> **Answer: not applicable**, because no user data is collected (Q1 = No).
> If the owner chooses the conservative variant below, the answer becomes
> **"No"** for the LAN transport, with the honest explanation that the AirPlay,
> DLNA and Miracast protocols of the local network are not encrypted, which is
> a property of those protocols and not a choice of the app.

### Q3 — "Do you provide a way for users to request that their data be deleted?"

> **Answer: YES.**
>
> The app stores no user data on a server, so there is nothing to delete
> remotely. The equivalent is documented in `PRIVACY.md` §3 *How to remove it*:
>
> * *Everything at once:* Android → *Settings* → *Apps* → *PhairPlay* →
>   *Storage* → *Clear storage*. The television forgets its identity and every
>   paired Mac has to pair again.
> * *Everything, including the app:* uninstall PhairPlay — Android removes the
>   private storage with it.
>
> Evidence: `android:allowBackup="false"` (`app/src/main/AndroidManifest.xml:133`)
> plus `android:dataExtractionRules="@xml/data_extraction_rules"` (`:134`)
> and `android:fullBackupContent="@xml/backup_rules"` (`:135`), with both rule
> files present in `app/src/main/res/xml/`. Together they keep the data out of
> the Android cloud backup and out of the device-to-device transfer. The three
> stores are app-private (`SettingsRepository.kt:18-19`, `PairingKeys.kt:26`,
> `PairingStore.kt:48`, `NetworkUtils.kt:120`).

### Q4 — The remaining *App content* declarations (not part of the Data safety form)

These belong to the same console page and are covered in
[`STORE_SUBMISSION_CHECKLIST.md`](STORE_SUBMISSION_CHECKLIST.md):

| Declaration | Answer | Evidence |
|---|---|---|
| Ads | No, my app does not contain ads | No advertising dependency in `gradle/libs.versions.toml` |
| App access | All functionality in my app is available without any access restrictions | No account, no login: `PRIVACY.md` §1 |
| Content rating | IARC: utility / productivity — no violence, no user-generated content, no sharing, no location | The app has no content of its own |
| Target audience | 18 and over (or 13–15, 16–17, 18 and over); "Does your app appeal to children": No | `PRIVACY.md` §7 |
| News | My app is not a news app | — |
| Government / Financial / Health | No / does not provide any financial features / does not provide any health features | No such permission is declared |

---

## 4. The conservative variant — if the owner decides to declare the LAN identifiers

If the owner judges that the mDNS/DLNA announcement of the device name, the
MAC address and the random identifier has to be declared, this is the table to
fill in. **Do not use it as a default** — it would overstate the data
processing of an app that has no server.

| Data type | Collected? | Shared? | Ephemeral? | Required? | Purpose | Encrypted in transit? | Deletion request? |
|---|---|---|---|---|---|---|---|
| **Device or other IDs** (the MAC address and the random device identifier) | **Yes** — transferred off the device to the local network | **Yes** — to the other devices on the same LAN | No | Yes — the AirPlay and UPnP protocols require the receiver to be identifiable | App functionality: the receiver announces itself so that a sender can find it | **No** — the local network protocols are plain by design | Yes — *Clear storage* and uninstall |

No other data type of the taxonomy is touched. In particular: **Location**
(the location permissions are never requested at runtime), **Personal info**
(no name, no e-mail, no phone number), **Photos and videos** (a photo is
received over the AirPlay channel and rendered in memory; the media store is
never read), **App activity** (no analytics, no usage counter), **Crash logs**
(no crash reporter is bundled) and **Diagnostics** (a release build writes no
log at all).

---

## 5. ⚠ OWNER — the three questions that must be answered before the form is submitted

| # | Question | Why it is not answered here | What changes depending on the answer |
|---|---|---|---|
| 1 | **Is the Google Cast receiver shipped with a registered Cast Application ID?** | The registration needs the owner's Google account and the signing certificate. Without an ID the receiver reports `ERROR` and does not start (`app/src/googletv/kotlin/com/phairplay/cast/CastReceiver.kt:23-27`) — but the Cast SDK is **still in the bundle** and its framework starts only when an ID is present | If the receiver is **not** shipped with an ID, the Cast SDK never starts and the "no data collected" answer is unchallenged. If it **is** shipped with an ID, the Cast framework runs on every television that has Google Play services, and the SDK's own data practices have to be reflected in the form |
| 2 | **Is the receiver enabled by default?** | The default is a product decision in the settings, not a fact of the code | If the Cast receiver is **off** by default, it starts only after a user action. If it is **on** by default, the Cast framework is active from the first start. This does not change the data answer, but it is the context the reviewer will read |
| 3 | **Is the Miracast claim removed from the store listing?** | The listing is the owner's text (`docs/guides/GOOGLE_PLAY_STORE.md:140-156`) | If Miracast is removed (the recommended step), the listing and the code agree. If it stays, the listing describes a feature that cannot start on Android 10+ — and the reviewer may look at the data the (non-functional) Wi-Fi Direct path would have touched |

**A fourth, lower-priority item:** confirm the transitive dependency graph of
`play-services-cast-tv:20.0.0` (no Gradle run was permitted in this session).
If the resolved graph brings in a component that phones home, the answer to Q1
has to be revisited.

---

## 6. The consistency proof — the policy and the code

The privacy policy is in the repository (`PRIVACY.md`, last updated
2026-11-02) and is **coupled to the manifest by a gate**, so the statement in
the store listing cannot drift away from the code silently:

```
$ bash scripts/check-privacy-policy.sh
OK: all 13 declared permissions are documented in the privacy policy.
OK: privacy policy check passed.
```

> **State of the gate on 2026-11-05, 16:10.** The gate was **green** at the
> baseline `34a166c` (measured 15:58). It is **red** in the current working
> tree: "the privacy policy (2026-11-02) is older than the manifest
> (2026-11-05)". The permission set did not change — the manifest was edited
> for the backup and data-extraction rules — but the gate compares the dates,
> so the policy header has to be brought up to date before the next release.
> The answers in this document are unaffected by that: the permission set is
> the same 13 permissions.

The gate checks three things (`scripts/check-privacy-policy.sh`):

1. **Every permission declared in the manifest is named in `PRIVACY.md`** —
   all 13, including the ones with a privacy dimension
   (`ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `SYSTEM_ALERT_WINDOW`).
2. **The policy states that it is reachable from inside the app** — the
   requirement of Play, which asks for the policy inside the app as well as in
   the store listing.
3. **The policy is not older than the manifest** — a policy that predates a
   permission change is a stale policy.

The gate runs in the CI job `quality-gate`
(`.github/workflows/ci.yml:34-66`) and in the release pipeline
(`scripts/release/build.sh:15`), so a permission change without a policy change
makes the build red.

The policy itself is honest about the two things the Data safety form does not
capture: the LAN announcement of the device name, the MAC address and the
random identifier (§2), and the fact that the location permissions are
declared but never requested (§6).

---

## 7. Self-check of this draft

| Check | Result |
|---|---|
| Every answer derived from the source code | **yes** — every row of §1 names a file and a line; nothing is copied from the store listing |
| Every answer carries a proof | **yes** — the Q1, Q2, Q3 answers each cite the code or the privacy policy section that backs them |
| No answer invented | **yes** — where the code cannot answer, the question is escalated (§5) instead of guessed |
| Consistency with the privacy policy | **yes** — the policy is the cross-check (§6); both say the same thing about the LAN announcement |
| Consistency with the store listing | **partly** — the listing claims Miracast, the code cannot deliver it. Tracked as **⚠ OWNER §5, question 3** and as risk R-26 |
| The form can be submitted now? | **No** — three owner questions are open (§5) |
