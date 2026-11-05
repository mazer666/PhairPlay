# PhairPlay – Remediation Backlog

Version: 1.0
Status: Active
Date: 2026-11-05
Owner: product track (`docs/spec/`)

> **What this backlog is.** The prioritised, assignable measures that come out
> of the security, compliance and quality findings of the cycle of 2026-11-05.
> It is **not** the risk register — a risk is a possibility with a likelihood
> ([`../project/RISKS.md`](../project/RISKS.md)); a backlog entry is **work that
> is known to be missing**.
>
> **The sibling list.** The release and store track keeps its own list in
> [`../project/REMEDIATION_BACKLOG.md`](../project/REMEDIATION_BACKLOG.md) (the
> identifiers `RB-xx`). The two lists do not overlap: **`RB-xx` is the store,
> the console and the release process**, **`B-xx` is the code and the
> specification**. The measures that both tracks would name are listed here only
> once, and the owner is named in the row.
>
> **Every entry is measured.** The reference column names the finding or the
> requirement; the severity follows the scale of the security audit
> ([`../audit/2026-10-24-security-audit.md`](../audit/2026-10-24-security-audit.md));
> the effort is an estimate for a developer who knows the codebase.
>
> **Status.** `open`, `in progress` or `done` (with
> the commit). **Nothing in this backlog is "erledigt"** — no commit of this
> cycle exists, because this cycle produced documents and scripts, not code.
>
> **Priority.** P1 blocks a distribution channel or a stable release. P2 blocks
> a beta. P3 is quality debt that does not block anything today.

## The scale

| Severity | Meaning |
|---|---|
| **high** | A remote device in the network can reach an unintended effect, or a store rejects the app. |
| **medium** | A promise of the project is not kept, or a required feature does not work. |
| **low** | Quality debt, defence in depth, or a documentation defect. |

| Effort | Meaning |
|---|---|
| **S** | up to 2 hours |
| **M** | up to 1 day |
| **L** | more than 1 day |

---

## P1 — blocks a distribution channel

| ID | Measure | Reference | Severity | Effort | Dependency | Status |
|---|---|---|---|---|---|---|
| **B-01** | Restrict the scheme allow-list of the media URI to `http` and `https` and **change the test** that asserted the opposite | FR-50, SEC-04, C-10, AC-M12.15, AC-V4.6 | **high** | M | — | **erledigt (2026-11-05)** — another track removed the own allow-list; `DlnaMediaUri` delegates to `FetchableUrlPolicy` (commits `b4e5afa`, `55acde4`) |
| **B-13** | Add `app/src/main/res/xml/network_security_config.xml` that permits cleartext **only** for the private LAN ranges, and reference it in the manifest. Without it the GENA `NOTIFY` and the DACP reverse command cannot work on a device that enforces the platform default | NFR-28, SEC-10, AC-V4.9, **DT-17** | **medium** | M | B-01 (the media fetch must be narrow before cleartext is opened) | open |
| **B-15** | Plant a release tree that writes warnings and errors (and drops the debug messages), and add a test class that asserts both. `PhairPlayApp.kt:40` plants a tree only under `BuildConfig.DEBUG`, so a release build writes **nothing** | NFR-31, SEC-07, AC-V4.8, **DT-18** | **medium** | S | none | open |
| **B-17** | Verify the 16 KB page alignment of the shipped native libraries (`libplayfair.so`, `libalac.so`) with `zipalign -c -P 16 -v 4` on the release APK and the `.aab`, and correct the packaging if it fails | NFR-22, AC-V4.12 | **medium** | S | a release build of the team lead | open |
| **B-19** | Produce and verify the signed release artefacts with `apksigner verify`, and record the fingerprint of the **production** key — separately from the fingerprint of the Play App Signing key | NFR-33, AC-V4.14, channel 3 | **medium** | S | the owner's keystore | open |
| **B-21** | Use the Play **internal** track on a real Google TV: install from the store, open the privacy policy from the settings, read the Play App Signing fingerprint and register the Cast application ID with it | FR-72, FR-73, NFR-21, **DT-19**, channel 1 | **medium** | M | the owner (console access) | open |

## P2 — blocks a beta

| ID | Measure | Reference | Severity | Effort | Dependency | Status |
|---|---|---|---|---|---|---|
| **B-06** | Stop setting `ProtocolState.ADVERTISING` in `ReceiverSupervisor.startMiracast()` (line 175) before the receiver can report its own state; the card must show *not available on this device* | FR-59, SEC-06, C-07, AC-M6.6, AC-V4.7 | **medium** | S | none | open |
| **B-11** | Decide the Miracast permission: **Option A** — request `NEARBY_WIFI_DEVICES` on API 33+ and `ACCESS_FINE_LOCATION` (with `ACCESS_COARSE_LOCATION`) on API 29–32 only when the user switches Miracast on, with a rationale; **Option B** — remove the Miracast receiver, its permissions and its card. The decision belongs to the owner | SEC-06, NFR-14, NFR-30 | **medium** | M (A) / S (B) | an **owner decision** | open |
| **B-12** | Decide whether a **public** address is an acceptable media address. Today the loopback, the link-local and the obfuscated notations are refused, but a routable public address passes. This is a product decision (the sender chooses the media), not a security decision | NFR-26, SEC-04 | **medium** | M | an **owner decision** | open |
| **B-22** | Correct the legacy acceptance criteria that name commands which do not run what they claim: AC-0.5 (a branch that does not exist), AC-1.7 / AC-8.6 (`./gradlew assembleDebug`), AC-1.8 / AC-8.1 (`./gradlew test` does not run the suite — the suite runs as `:test-runner:test`) | C-01 … C-03, AC-V4.15 | **medium** | S | the file is already corrected in v3.0 — the remaining work is the *decision* per row | open |
| **B-24** | Write the control-point probe `DlnaControlPointProbeTest` that AC-M12.3 names, or change the criterion to the procedure that exists. The criterion names a test that was never written | C-09, AC-M12.3 | **medium** | M | none | open |
| **B-25** | Stop logging free text: the sender's name, the title, the artist and the album of the playing track. A truncated hash keeps two log lines correlatable and is useless to a reader | SEC-05, NFR-29 | **medium** | S | none | open |
| **B-03** | Give the Miracast and the Cast receiver a re-advertisement path. Today only the AirPlay receiver and the DLNA renderer have one; a receiver that was switched off in Settings stays off, and a receiver whose advertisement failed never comes back | FR-11, FR-61 | **medium** | M | B-11 (if the Miracast receiver is removed, the measure disappears) | open |
| **B-16** | Execute the 16 device test cases on a Google TV **and** on a Fire TV, and fill in the *Result* column of the device test plan. Every performance number of the project is still unmeasured | NFR-01 … NFR-04, NFR-14 … NFR-20, NFR-24, the beta trigger | **medium** | L | hardware — the owner | open |
| **B-18** | Write the licence inventory (`NOTICE`) for every dependency — including the Google Cast SDK, whose terms constrain the distribution channel — and keep it in step with `gradle/libs.versions.toml` | NFR-32, AC-V4.13 | **medium** | S | none | open |
| **B-27** | Decide whether the `googletv` flavour shall support Android 9 (API 28). The requester of issue #22 has implemented it in a fork and offered a pull request. A decision needs the `minSdk` change, the compatibility work, a device procedure for the Android 9 path and the change of the requirement baseline | NFR-08, issue #22 | **medium** | M | **an owner decision** | open |
| **B-26** | Close the TOCTOU window of the GENA callback: the guard resolves the host name and checks the address, then `sendNotify` resolves it **again**. Keep the validated address with the subscription and connect to it | SEC-09, FR-49 | **low** | M | none | open |

## P3 — quality debt

| ID | Measure | Reference | Severity | Effort | Dependency | Status |
|---|---|---|---|---|---|---|
| **B-02** | Add the missing unit test for the RTSP `503` of a second concurrent connection (`RtspServer.kt:239`). The behaviour exists and is correct, but no test proves it | FR-10, AC-3.3 | **low** | S | none | open |
| **B-05** | Implement the RTL baseline for a future Arabic / Hebrew locale (the layout direction, the mirrored margins) | NFR-41, M10 | **low** | M | none | open |
| **B-07** | Derive the advertised protocol list from the runtime capability snapshot of FR-65 instead of the fixed constant of `DlnaProtocolInfo` | NFR-66 | **low** | M | none | open |
| **B-08** | Extend `scripts/check-i18n-completeness.sh` with the **value** comparison of the `protocol_*` keys. The gate compares the key set and the placeholders — not the values, which is what FR-68 and AC-M10.3 demand | FR-68, C-08, AC-V4.10 | **low** | S | none | open |
| **B-09** | Give the binary plist decoder its own size limit (`PlistCodec.MAX_PLIST_BYTES` = 1 MB). Today the 1 MB budget of the RTSP reader is the only guard in front of it | FR-74 (new in v4.0), SEC-11, NFR-12, NFR-27, AC-V4.16 | **low** | S | none | open |
| **B-10** | Add a contrast check for the colour resources of the TV theme. NFR-09 asks for a sufficient contrast ratio, and nothing proves it | NFR-09 | **low** | S | none | open |
| **B-14** | Replace the **12** direct `Timber.` call sites outside `util/Logger.kt` by `Logger.`, so that the reduction of NFR-29 is active on every line. The files are `MainActivity.kt`, `PhairPlayApp.kt`, `BootReceiver.kt`, `ServiceConnectionBinder.kt`, `LogRedactor.kt` | NFR-29, SEC-08 | **low** | S | none | open |
| **B-20** | Re-check the target API level against the Play policy at every submission — the obligation changes with the calendar, it is not a one-off decision | NFR-21, AC-V4.11 | **low** | S | none — a rule, not a task | open |
| **B-23** | Split `ui/DiagnosticsFragment.kt`. It grew to **407** lines and is the only file above the soft limit of 400 — the file-size gate reports 1 warning today (measured 2026-11-05: `221 file(s) checked, none above the hard limit of 550 lines, 1 warning(s)`) | NFR-23, `scripts/check-file-size.sh` | **low** | S | none | open |

---

## Summary

| Priority | Count | Of which "erledigt" |
|---|---|---|
| P1 — blocks a distribution channel | 6 | 1 (B-01, closed by another track) |
| P2 — blocks a beta | 10 | 0 |
| P3 — quality debt | 9 | 0 |
| **Total** | **25** | **1** |

**Two entries need a decision of the owner before they can be started:**
**B-11** (the Miracast permission) and **B-12** (a public address as a media
address). Both are product decisions, not security decisions — the audit says so
explicitly, and the specification must not pretend otherwise.

**One entry needs hardware:** **B-16** — the 16 device test cases. It is the
condition that separates a beta from an alpha.
