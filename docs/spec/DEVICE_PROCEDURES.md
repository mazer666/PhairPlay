# PhairPlay – Device Procedures of the v4.0 baseline

Version: 4.0
Status: Active
Date: 2026-11-05
Owner: product track (`docs/spec/`)

> **Why this document exists.** The acceptance criteria of the project distinguish
> between a criterion that a build server can prove and a criterion that needs a
> real television. The second kind is written as a **device procedure** with the
> identifier `DT-xx`.
>
> * **DT-01 … DT-16** are defined in
>   [`../guides/DEVICE_TEST_PLAN.md`](../guides/DEVICE_TEST_PLAN.md) — the plan the
>   owner runs on a Google TV and a Fire TV. That document belongs to the QA track
>   and is not changed here.
> * **DT-17 … DT-19** are defined **here**. They are the procedures that the
>   requirements of the v4.0 baseline needed and that the existing plan does not
>   cover: the cleartext configuration of the LAN, the log of a release build and
>   the installation from the store.
>
> **The contract between the two documents.** A requirement may only be proven on
> a device by a `DT-xx` that is **defined** — a procedure that nobody wrote is not
> a proof. The generator of the traceability matrix checks this:
> `scripts/build-traceability.sh` reads the `DT-xx` of a requirement and looks it
> up in this document and in the device test plan. A `DT-xx` that is named but not
> defined makes the generator fail.

---

## DT-17 — Cleartext HTTP inside the LAN

**Proves:** NFR-28 — the app declares a network security configuration that
permits cleartext only where the protocol needs it, and the features that need
cleartext actually work.

**Why a device is needed:** the platform default for an app that targets API 28
or later refuses cleartext traffic. Whether the app overrides that default — and
whether the override is narrow enough — is only visible on a real device, with a
real media server in the real network.

**Preconditions:** a Google TV (or a Fire TV) with PhairPlay installed and
running; a PC in the same LAN with `python3 -m http.server 8080` serving a
directory that contains one MP4.

**Steps:**

```bash
# 1. the configuration exists and names no public domain
adb shell run-as com.phairplay.googletv cat res/xml/network_security_config.xml
grep -c "cleartextTrafficPermitted" app/src/main/res/xml/network_security_config.xml

# 2. the media file is reachable over plain HTTP from the television
#    (the file name must be the one that is served)
adb shell am start -a android.intent.action.VIEW -d "http://<PC-IP>:8080/sample.mp4"

# 3. the GENA notification reaches the control point
#    subscribe with a control point (see DT-11), then press Play on the
#    television and watch the NOTIFY arrive in the log of the control point
```

**Pass condition:** the file `app/src/main/res/xml/network_security_config.xml`
exists, it names no public domain, the picture of the MP4 appears on the
television within 5 s, and the `NOTIFY` of the GENA subscription arrives at the
control point. A `Cleartext HTTP traffic … not permitted` message in the log
means the configuration is missing or too narrow — the criterion is **not met**.

**Fails today.** The file does not exist in the repository (verified with
`find app/src -name "network_security_config*"` → 0 matches), so both steps 2
and 3 fail. Backlog B-13.

---

## DT-18 — The release build writes to the log

**Proves:** NFR-31 — a release build writes warnings and errors to the log (after
the reduction of NFR-29); a release build that writes nothing makes a field
failure undiagnosable.

**Why a device is needed:** the release build has no `DEBUG` flag and no
`Timber.DebugTree`. Whether a tree is planted at all is a property of the
release binary, and the message only appears on a real device.

**Preconditions:** a **release** APK (not a debug APK) installed on a Google TV;
the `adb` connection open.

**Steps:**

```bash
# 1. clear the log and start the receiver
adb logcat -c
adb shell am start -n com.phairplay.googletv/com.phairplay.MainActivity
sleep 3

# 2. provoke a warning: switch a receiver off and on again in the app
#    (or: disconnect the network for two seconds)

# 3. read the log
adb logcat -d -s PhairPlay:* AndroidRuntime:E
```

**Pass condition:** the log contains at least one **warning** of the receiver
itself, and it contains **no** debug message. The address of a peer in the log is
reduced (`192.168.x.y`, not the full address) — that proves that the reduction of
NFR-29 is active in the release build too.

**Fails today.** `PhairPlayApp.kt:40` plants a tree only under
`BuildConfig.DEBUG`, so a release build writes nothing at all. Backlog B-15.

---

## DT-19 — The installation from the store and the privacy policy inside the app

**Proves:** FR-72, FR-73 and NFR-21 — the privacy policy is reachable inside the
app, it names every declared permission, and the published build targets the API
level that the Play policy demands.

**Why a device is needed:** the store installation is signed by Google, not by
the local release key. The in-app policy entry and the Play App Signing
fingerprint can only be checked on a device that received the build from the
store.

**Preconditions:** the `.aab` of the release uploaded to the **internal testing**
track of the Play Console; a television that is enrolled in the internal test
track.

**Steps:**

```bash
# 1. the target API level of the build that is uploaded
grep -n "targetSdk" app/build.gradle.kts

# 2. install the store build on the television (the Play console offers it in
#    the internal track; the package is com.phairplay.googletv)
#    then open Settings -> Privacy policy on the television

# 3. compare the fingerprint of the store build with the one of a sideloaded build
keytool -printcert -jarfile app-googletv-release.apk | grep SHA1
```

**Pass condition:** the target API level is at least the minimum that the Play
console shows on the day of the upload; the privacy policy opens **inside the
app** and names every permission of the manifest; the fingerprint of the store
build is **not** the fingerprint of the local release key (Play App Signing).

**Not executed yet.** The internal track has not been used on a real television.
Backlog B-21.

---

## Summary

| Procedure | Proves | Requirements | State on 2026-11-05 |
|---|---|---|---|
| DT-01 … DT-16 | the classical device plan | see the plan | not executed — no device |
| DT-17 | the cleartext configuration of the LAN | NFR-28 | **fails** — the configuration does not exist |
| DT-18 | the log of a release build | NFR-31 | **fails** — a release build writes nothing |
| DT-19 | the store installation and the in-app policy | FR-72, FR-73, NFR-21 | not executed — no internal track yet |
