# Google Play Store — Submission Checklist for the Owner

**Version:** 1.0
**Owner:** Project Manager
**Date:** 2026-11-05
**Measured on:** commit `34a166c` (branch `main`), release `v1.0.0-alpha.7`
**For:** the owner of the Google Play developer account, working through the
Play Console in person.

**How to use this list.** Work through it **in the order below** — the steps
build on each other. The `Done?` column is left empty on purpose: tick it
yourself as you go. The **Quelle** column names the section of
[`../guides/GOOGLE_PLAY_STORE.md`](../guides/GOOGLE_PLAY_STORE.md) that belongs
to the step; that guide holds the exact texts and paths.

**Three things to decide before you start** (they change what you type):

1. **Is your developer account a *personal* account created after 13 November
   2023?** If yes, you must run a closed test with **12 testers over 14 days**
   before you can publish to production. Start recruiting them in parallel with
   step 1 — it is the longest pole in the whole process.
2. **Will Play generate the app signing key, or do you export your existing
   release key?** This decides which fingerprint you register for Google Cast
   and whether a GitHub-installed PhairPlay can later be upgraded by the Play
   build without an uninstall.
3. **Do you remove the Miracast claim from the listing?** The app cannot start
   the Wi-Fi Direct advertisement on Android 10+, because the permissions are
   never requested at runtime. The recommendation is to remove it.

---

## Block 0 — Before the console (outside Play)

| # | Step | Where / what | File from the repo | Done? |
|---|---|---|---|---|
| 0.1 | Back up the release keystore outside the team storage | `~/.config/phairplay/phairplay-release.jks` + `~/.config/phairplay/release.env` | `scripts/release.sh --setup` creates both; the guide is `../guides/RELEASING.md` | ☐ |
| 0.2 | Read the current fingerprint out of the key | `./scripts/cast-registration-info.sh` — it prints the package name, the SHA-1 and the SHA-256 | `scripts/cast-registration-info.sh` | ☐ |
| 0.3 | Decide the Play app signing branch | Branch A: Play generates the key → the Play fingerprint differs from the local one. Branch B: you export the release key as the app signing key → both channels share the certificate | `../guides/GOOGLE_PLAY_STORE.md` §5 | ☐ |
| 0.4 | Remove the Miracast claim from the listing text (recommended) | The full description currently advertises "Miracast discovery" | `../guides/GOOGLE_PLAY_STORE.md` §4 | ☐ |
| 0.5 | Re-encode the store graphics without the alpha channel | The TV banner must be "JPEG or 24-bit PNG (no alpha)"; all five PNGs in the assets folder are RGBA | `docs/playstore/assets/*.png` | ☐ |
| 0.6 | Read the answers of the Data safety form | Answer only after the three owner questions are answered | `DATA_SAFETY_DRAFT.md` §5 | ☐ |
| 0.7 | **Get a Google TV** and install the published APK on it | This is needed for the foreground-service demonstration video (step 4.6) and for the device test plan | `docs/guides/DEVICE_TEST_PLAN.md` | ☐ |
| 0.8 | Record the foreground-service demonstration video | The video must show the in-app setup and the device interaction, not the system settings | — (the video itself) | ☐ |
| 0.9 | **Check that the five repository gates are green** | `bash scripts/check-file-size.sh && bash scripts/check-kdoc-headers.sh && bash scripts/check-i18n-completeness.sh && bash scripts/check-privacy-policy.sh && bash scripts/check-docs-links.sh`. **Measured 2026-11-05 17:20: all five exit 0.** They were red at 16:10 and the parallel tracks repaired them. The upload must carry the bundle that was audited | `scripts/check-*.sh`; the gate list is `scripts/release/build.sh:15` | ☐ |

---

## Block 1 — The account and the app

| # | Step | Where in the console | What exactly to type / click | File from the repo | Done? |
|---|---|---|---|---|---|
| 1.1 | Create the developer account (if it does not exist) | [play.google.com/console](https://play.google.com/console) → the developer account enrolment | Pay the one-time fee, complete the identity check, fill in the contact details | — | ☐ |
| 1.2 | **Note the account type** — personal or organisation | Account settings | If it is *personal* and was created after 2023-11-13, the 12-testers / 14-days rule applies to production | — | ☐ |
| 1.3 | Create the app | *Create app* | **App name:** `PhairPlay` · **Default language:** `English (United States) – en-US` · **App or game:** `App` · **Free or paid:** `Free` → accept the Developer Program Policies and the US export laws → **Create app** | `../guides/GOOGLE_PLAY_STORE.md` §3 Step 1 | ☐ |
| 1.4 | Note the application ID | *Grow users → Store presence → Main store listing* | It must read `com.phairplay.googletv` — the `googletv` flavour | `app/build.gradle.kts:59` | ☐ |
| 1.5 | Confirm the package name for the **Fire TV** variant | a **second** app entry | `com.phairplay.firetv` — a separate app ID, a separate listing, a separate review | `app/build.gradle.kts:64` | ☐ |

---

## Block 2 — The app content declarations

*Menu: **Policy and programs → App content**.* Every card has to be answered
before a release can be reviewed.

| # | Step | Where in the console | What exactly to type / click | File from the repo | Done? |
|---|---|---|---|---|---|
| 2.1 | Privacy policy | *App content → Privacy policy* | `https://github.com/mazer666/PhairPlay/blob/main/PRIVACY.md` — the same policy is reachable in the app under *Settings → About → Privacy policy* | `PRIVACY.md`; the gate is `scripts/check-privacy-policy.sh` | ☐ |
| 2.2 | Ads | *App content → Ads* | **No, my app does not contain ads** | `../guides/GOOGLE_PLAY_STORE.md` §3 Step 3 | ☐ |
| 2.3 | App access | *App content → App access* | **All functionality in my app is available without any access restrictions** | `PRIVACY.md` §1 (no account) | ☐ |
| 2.4 | Content rating | *App content → Content rating* | Fill out the IARC questionnaire: *Utility, Productivity, Communication, or Other* → answer **No** to all violence / user-generated content / sharing / location questions | `../guides/GOOGLE_PLAY_STORE.md` §3 Step 3 | ☐ |
| 2.5 | Target audience and content | *App content → Target audience and content* | Select **18 and over** (or 13–15, 16–17, 18 and over — never under 13). "Does your app appeal to children?" → **No** | `PRIVACY.md` §7 | ☐ |
| 2.6 | News apps | *App content → News apps* | **My app is not a news app** | `../guides/GOOGLE_PLAY_STORE.md` §3 Step 3 | ☐ |
| 2.7 | Data safety | *App content → Data safety* | **No data collected, no data shared.** Transfer the answers from the draft — including the deletion-request answer (Q3) and the encryption answer (Q2) | `DATA_SAFETY_DRAFT.md` §3 | ☐ |
| 2.8 | Government apps | *App content → Government apps* | **No** | `../guides/GOOGLE_PLAY_STORE.md` §3 Step 3 | ☐ |
| 2.9 | Financial features | *App content → Financial features* | **My app doesn't provide any financial features** | `../guides/GOOGLE_PLAY_STORE.md` §3 Step 3 | ☐ |
| 2.10 | Health | *App content → Health* | **My app does not provide any health features** | `../guides/GOOGLE_PLAY_STORE.md` §3 Step 3 | ☐ |
| 2.11 | Foreground service permissions | *App content → Foreground service permissions* | Check **Connected device** (Wi-Fi Direct / LAN media receiver). Paste the description from the guide. Provide the **20-second video link** from step 0.8. Choose the use case **"Continuous Data Transfer to an External Device"** | `../guides/GOOGLE_PLAY_STORE.md` §3 Step 3; the manifest side is `app/src/main/AndroidManifest.xml:180-183` | ☐ |
| 2.12 | Permissions declaration (if the console asks) | *App content → Permissions declaration* | It appears because the manifest declares the location permissions. The answer: they are a **Wi-Fi Direct prerequisite on API ≤ 32**, are **never requested at runtime**, and `NEARBY_WIFI_DEVICES` carries `neverForLocation` | `app/src/main/AndroidManifest.xml:62-72`; `PRIVACY.md` §6 | ☐ |

---

## Block 3 — The store listing

*Menu: **Grow users → Store presence → Main store listing**.*

| # | Step | Where in the console | What exactly to type / click | File from the repo | Done? |
|---|---|---|---|---|---|
| 3.1 | App name | *Main store listing* | `PhairPlay – AirPlay & DLNA TV` (max 30 characters) | `../guides/GOOGLE_PLAY_STORE.md` §3 Step 4 | ☐ |
| 3.2 | Short description | *Main store listing* | `Open-source AirPlay 2 & UPnP/DLNA receiver for Android TV. Zero ads, LAN-only.` (max 80 characters) | `../guides/GOOGLE_PLAY_STORE.md` §3 Step 4 | ☐ |
| 3.3 | Full description | *Main store listing* | The long text from the guide. **It must mention "Android TV"** — that is an explicit requirement of the TV distribution step | `../guides/GOOGLE_PLAY_STORE.md` §3 Step 4 | ☐ |
| 3.4 | App icon | *Main store listing → Graphics* | `icon-512x512.png` — 512 × 512 px | `docs/playstore/assets/icon-512x512.png` | ☐ |
| 3.5 | Feature graphic | *Main store listing → Graphics* | `feature-graphic-1024x500.png` — 1024 × 500 px | `docs/playstore/assets/feature-graphic-1024x500.png` | ☐ |
| 3.6 | TV screenshots | *Main store listing → Graphics → Android TV tab* | `tv-screenshot-1-home-1920x1080.png` and `tv-screenshot-2-diagnostics-1920x1080.png` — at least one is required, 16:9 | `docs/playstore/assets/` | ☐ |
| 3.7 | Android TV banner | *Main store listing → Graphics → Android TV tab* | `tv-banner-1280x720.png` — 1280 × 720 px, **24-bit PNG without alpha** | `docs/playstore/assets/tv-banner-1280x720.png` | ☐ |
| 3.8 | Contact details | *Main store listing* | Your e-mail address and the website. The website may point at the GitHub repository — the privacy policy URL is already set in step 2.1 | `README.md` | ☐ |

---

## Block 4 — The build, the signature and the Cast registration

*This is the block where the order matters: the Cast fingerprint can only be
read **after** the first upload, because the Play app signing key does not
exist before that.*

| # | Step | Where in the console | What exactly to type / click | File from the repo | Done? |
|---|---|---|---|---|---|
| 4.1 | Build the signed bundle | outside the console | `./scripts/release.sh v1.0.0-<version>` builds it, or: `. ~/.config/phairplay/release.env && export KEYSTORE_PATH KEYSTORE_PASSWORD KEY_ALIAS KEY_PASSWORD && ./gradlew --no-daemon -Dorg.gradle.jvmargs="-Xmx1024m" :app:bundleGoogletvRelease` | `scripts/release.sh`; `../guides/GOOGLE_PLAY_STORE.md` §2 | ☐ |
| 4.2 | Upload the bundle | *Test and release → Testing → Internal testing → Create new release* | Upload `PhairPlay-<version>-GoogleTV.aab`. Use the **"Android TV only"** track if you want the TV release separated from a mobile one | `docs/compliance/GOOGLE_PLAY_COMPLIANCE.md` §C | ☐ |
| 4.3 | Enrol in Play App Signing | *Test and release → Setup → App signing* | A new app is enrolled automatically. If you decided **Branch B** in step 0.3, choose *"Export and upload a key from Java keystore"* and upload the release key | `../guides/GOOGLE_PLAY_STORE.md` §5 | ☐ |
| 4.4 | **Read the app signing SHA-1** | *Test and release → Setup → App signing* → *App signing key certificate* | Copy the **SHA-1** fingerprint. This is the certificate of the key that signs the APKs the devices install — **not** the upload key | `../guides/GOOGLE_PLAY_STORE.md` §5 | ☐ |
| 4.5 | Register the Google Cast Application ID | [cast.google.com/publish](https://cast.google.com/publish) → *Add new application* | **Application name:** `PhairPlay` · **Application type:** *Receiver app* · **Platform:** *Android TV* · **Android package name:** `com.phairplay.googletv` · **SHA-1 fingerprint:** the one from step 4.4 (or the local one if you chose Branch B) | `../guides/CAST_APP_ID.md`; the script is `scripts/cast-registration-info.sh` | ☐ |
| 4.6 | Bake the Application ID into the build | outside the console | `export PHAIRPLAY_CAST_APP_ID=<CC…>` before the Gradle call — the empty default makes the receiver report `ERROR` instead of advertising | `app/build.gradle.kts:17-20`, `:35` | ☐ |
| 4.7 | Save the release notes | *Internal testing → Create new release → Release notes* | Paste the notes and add the limitations: no device test executed, Miracast is discovery and control plane only, the Cast path needs the registered ID | `../guides/RELEASING.md` | ☐ |

---

## Block 5 — The Android TV opt-in

| # | Step | Where in the console | What exactly to type / click | File from the repo | Done? |
|---|---|---|---|---|---|
| 5.1 | Open the form factors | **Setup → Advanced settings** → **Form factors** tab | Click **+ Add release type** and select **Android TV** | `../guides/GOOGLE_PLAY_STORE.md` §3 Step 2 | ☐ |
| 5.2 | Confirm the opt-in | the confirmation dialog | Confirm and **agree to the Android TV review policy** | `../guides/GOOGLE_PLAY_STORE.md` §3 Step 2 | ☐ |
| 5.3 | Upload the TV assets | the two prompts that appear | The banner and the two screenshots from `docs/playstore/assets/` | `../guides/GOOGLE_PLAY_STORE.md` §3 Step 2 | ☐ |
| 5.4 | Select the opt-in | the prompt | Select **"Opt-in to Android TV"** | `../guides/GOOGLE_PLAY_STORE.md` §3 Step 2 | ☐ |
| 5.5 | Check the review status | *Pricing and Distribution → Android TV* | The status of the Android TV quality review | `developer.android.com/training/tv/publishing/distribute` | ☐ |

---

## Block 6 — The internal test

| # | Step | Where in the console | What exactly to type / click | File from the repo | Done? |
|---|---|---|---|---|---|
| 6.1 | Create the tester group | *Test and release → Testing → Internal testing → Testers* | Add your own Google account. The internal track has **no** requirement — it is optional | `../compliance/GOOGLE_PLAY_COMPLIANCE.md` §E | ☐ |
| 6.2 | Roll out to internal testing | *Internal testing → Create new release → Start rollout* | Start the rollout to the internal testers | — | ☐ |
| 6.3 | Install and smoke-test | on the television | Install from the Play link and run the decisive cases: DT-01 install, DT-03 AirPlay video, DT-08 SSDP, DT-09 VLC renderer, DT-12 the German and the French texts | `docs/guides/DEVICE_TEST_PLAN.md` | ☐ |
| 6.4 | **Start the closed test in parallel** | *Test and release → Testing → Closed testing* | Add at least **12** Google accounts (aim for 15–18) and send them the opt-in link. They must stay opted in **continuously for 14 days** | `../compliance/GOOGLE_PLAY_COMPLIANCE.md` §E | ☐ |

---

## Block 7 — Production (only after the closed test has run)

| # | Step | Where in the console | What exactly to type / click | File from the repo | Done? |
|---|---|---|---|---|---|
| 7.1 | Wait for the 14 days | the Dashboard | The 14-day countdown starts when the 12th tester opts in. If a tester opts out, the clock restarts for them | `../compliance/GOOGLE_PLAY_COMPLIANCE.md` §E | ☐ |
| 7.2 | Apply for production access | **Dashboard → Apply for production** | Answer the three sections: *About your closed test* (how you recruited, how they engaged), *About your app*, *About the testing* | `../compliance/GOOGLE_PLAY_COMPLIANCE.md` §E | ☐ |
| 7.3 | Cut the stable release | outside the console | `./scripts/release.sh v1.0.0` — the version bump, the tag and the three assets happen there. The version in `app/build.gradle.kts` must be `1.0.0` | `scripts/release.sh`; `../guides/RELEASING.md` | ☐ |
| 7.4 | Write the release notes | *Production → Create new release → Release notes* | Include the limitations: the AirPlay stack was never validated on a device, Miracast is discovery and control plane only, the DLNA media path depends on the cleartext decision, and the only component that talks beyond the LAN is the Google Cast SDK on the Google TV flavour | `docs/project/RELEASE_READINESS.md` §7 | ☐ |
| 7.5 | Roll out to production | *Production → Start rollout* | Start with a **staged rollout** (a percentage of users), then raise it | — | ☐ |
| 7.6 | Decide the Fire TV store channel | a second app entry | Amazon Appstore (the native channel on Fire TV) or a second Play listing for `com.phairplay.firetv` | `docs/project/RELEASE_READINESS.md` §5 | ☐ |

---

## The shortest path to the first real device

If you want a television running PhairPlay **today**, without the console:

1. Download `PhairPlay-1.0.0-alpha.7-GoogleTV.apk` from the GitHub release.
2. Enable *unknown sources* on the television and install it.
3. Run the device test plan — `docs/guides/DEVICE_TEST_PLAN.md`, cases DT-01 …
   DT-16, and fill in the result sheet at the end of the file.
4. Record the foreground-service demonstration video while you are there
   (step 0.8) — it is the one artefact that the console needs and that only a
   television can produce.

**One warning:** the GitHub APK and the Play build carry different
certificates unless you chose Branch B in step 0.3. Android refuses to
upgrade across different signatures — if you later install the Play build over
this one, you have to uninstall it first.

---

## What is not on this list, and why

* **The device test plan** — it is a separate document with 16 cases
  (`docs/guides/DEVICE_TEST_PLAN.md`). It is step 6.3 in practice; the
  hardware has to exist before anything else on this list can be finished.
* **The Amazon Appstore submission** — it is a separate process with its own
  account and its own review; the decision is step 7.6.
* **The source-code changes** — the DLNA scheme allowlist, the cleartext
  configuration and the Miracast claim are tracked in
  `docs/project/REMEDIATION_BACKLOG.md` (RB-09, RB-10, RB-11). They are
  engineering work, not console work.
