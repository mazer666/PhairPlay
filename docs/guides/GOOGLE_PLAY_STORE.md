# Publishing PhairPlay on Google Play Store

This guide walks the project owner through every step required to publish the
`googletv` flavour of PhairPlay (`com.phairplay.googletv`) on the **Google Play
Store** for Android TV and Google TV devices.

All required store graphics are ready in [`../playstore/assets/`](../playstore/assets/),
and the release pipeline (`./scripts/release.sh`) automatically builds and
verifies the signed **Android App Bundle (`.aab`)** alongside the two APKs.

---

## 1. Prerequisites and files ready in the repository

| Requirement | Value / Location | Status |
|---|---|---|
| Package name (`applicationId`) | `com.phairplay.googletv` | Configured in [`../../app/build.gradle.kts`](../../app/build.gradle.kts) |
| Target SDK (`targetSdk`) | `35` (Android 15) — required by Google Play | Configured in [`../../app/build.gradle.kts`](../../app/build.gradle.kts) |
| Minimum SDK (`minSdk`) | `29` (Android 10+ for Google TV) | Configured in [`../../app/build.gradle.kts`](../../app/build.gradle.kts) |
| 64-bit native libraries | `arm64-v8a`, `x86_64` (plus `armeabi-v7a`, `x86`) | Configured in [`../../app/build.gradle.kts`](../../app/build.gradle.kts) |
| Android TV Leanback declaration | `android.software.leanback` (`required="true"`), `LEANBACK_LAUNCHER` | Configured in [`../../app/src/main/AndroidManifest.xml`](../../app/src/main/AndroidManifest.xml) |
| Public Privacy Policy URL | `https://github.com/mazer666/PhairPlay/blob/main/PRIVACY.md` | Public in [`../../PRIVACY.md`](../../PRIVACY.md) |
| Signed Android App Bundle (`.aab`) | `app/build/outputs/bundle/googletvRelease/app-googletv-release.aab` | Built by `./scripts/release.sh` or `:app:bundleGoogletvRelease` |
| App icon (`512 × 512 px`) | [`../playstore/assets/icon-512x512.png`](../playstore/assets/icon-512x512.png) | Ready |
| Feature graphic (`1024 × 500 px`) | [`../playstore/assets/feature-graphic-1024x500.png`](../playstore/assets/feature-graphic-1024x500.png) | Ready |
| Android TV banner (`1280 × 720 px`) | [`../playstore/assets/tv-banner-1280x720.png`](../playstore/assets/tv-banner-1280x720.png) | Ready |
| Android TV screenshot 1 (`1920 × 1080 px`) | [`../playstore/assets/tv-screenshot-1-home-1920x1080.png`](../playstore/assets/tv-screenshot-1-home-1920x1080.png) | Ready |
| Android TV screenshot 2 (`1920 × 1080 px`) | [`../playstore/assets/tv-screenshot-2-diagnostics-1920x1080.png`](../playstore/assets/tv-screenshot-2-diagnostics-1920x1080.png) | Ready |

---

## 2. Building the signed Android App Bundle (`.aab`)

Google Play requires a signed `.aab` bundle instead of an `.apk`.

### Option A — Part of a full release (`scripts/release.sh`)

When you cut a release with [`../../scripts/release.sh`](../../scripts/release.sh),
the script builds both APKs **and** the signed `.aab` bundle, verifies the
signature with `jarsigner`, and uploads `PhairPlay-<version>-GoogleTV.aab` to
the GitHub release:

```bash
./scripts/release.sh v1.0.0-alpha.5
```

### Option B — Build only the `.aab` locally

```bash
(
  . ~/.config/phairplay/release.env
  export KEYSTORE_PATH KEYSTORE_PASSWORD KEY_ALIAS KEY_PASSWORD PHAIRPLAY_CAST_APP_ID
  JAVA_HOME=/opt/homebrew/opt/openjdk@17 \
    ./gradlew --no-daemon -Dorg.gradle.jvmargs="-Xmx1024m" :app:bundleGoogletvRelease
)
```

The signed bundle is written to:

```text
app/build/outputs/bundle/googletvRelease/app-googletv-release.aab
```

---

## 3. Step-by-step setup in Google Play Console

### Step 1 — Create the application

1. Open the [Google Play Console](https://play.google.com/console) and click **Create app**.
2. Fill in the initial details:
   - **App name:** `PhairPlay`
   - **Default language:** `English (United States) – en-US` (you can add German `de-DE` and French `fr-FR` translations later)
   - **App or game:** `App`
   - **Free or paid:** `Free`
3. Accept the Developer Program Policies and US export laws, then click **Create app**.

### Step 2 — Opt into Android TV (Form factors)

> **Why this step is critical:** An app that declares `LEANBACK_LAUNCHER` in its
> manifest is **not** distributed to Android TV / Google TV devices until you
> explicitly opt into the Android TV form factor in Play Console.

1. In the left menu, open **Test and release → Setup → Advanced settings**.
2. Open the **Form factors** tab.
3. Click **+ Add form factor** and select **Android TV**.
4. Follow the two prompts that appear for Android TV:
   - Upload the Android TV banner ([`../playstore/assets/tv-banner-1280x720.png`](../playstore/assets/tv-banner-1280x720.png)) and the two TV screenshots ([`../playstore/assets/tv-screenshot-1-home-1920x1080.png`](../playstore/assets/tv-screenshot-1-home-1920x1080.png), [`../playstore/assets/tv-screenshot-2-diagnostics-1920x1080.png`](../playstore/assets/tv-screenshot-2-diagnostics-1920x1080.png)).
   - Select **Opt-in to Android TV**.

### Step 3 — Complete the App content declarations (Policy & Privacy)

In the left menu, open **Policy and programs → App content** and complete each
card with the exact values below:

| Declaration | What to select |
|---|---|
| **Privacy policy** | Enter `https://github.com/mazer666/PhairPlay/blob/main/PRIVACY.md` |
| **Ads** | **No, my app does not contain ads** |
| **App access** | **All functionality in my app is available without any access restrictions** |
| **Content ratings** | Fill out the IARC questionnaire: select **Utility, Productivity, Communication, or Other** → answer **No** to all violence/user-generated content/sharing/location questions |
| **Target audience and content** | Select **18 and over** (or **13–15, 16–17, 18 and over** — never select under-13 to avoid Families Policy restrictions). Answer **No** to "Appeal to children" |
| **News apps** | **My app is not a news app** |
| **Data safety** | Does your app collect or share any of the required user data types? → **No** |
| **Government apps** | **No** |
| **Financial features** | **My app doesn't provide any financial features** |
| **Health** | **My app does not provide any health features** |
| **Foreground service permissions (`FOREGROUND_SERVICE_CONNECTED_DEVICE`)** | Check **Connected device** (Wi-Fi Direct / LAN media receiver). Description to paste: *"PhairPlay turns the Android TV into a local-network AirPlay 2, UPnP/DLNA, Miracast and Cast media receiver. The foreground service keeps the local mDNS/SSDP receiver sockets active while the user navigates the TV home screen so incoming streams from a phone or laptop on the same Wi-Fi are accepted immediately."* Provide a short 20-second video link showing a phone connecting to PhairPlay while the service is running. |

### Step 4 — Store listing texts and graphics

Open **Grow users → Store presence → Main store listing** and paste the texts below:

- **App name (max 30 chars):**
  ```text
  PhairPlay – AirPlay & DLNA TV
  ```
- **Short description (max 80 chars):**
  ```text
  Open-source AirPlay 2 & UPnP/DLNA receiver for Android TV. Zero ads, LAN-only.
  ```
- **Full description (max 4000 chars):**
  ```text
  PhairPlay turns your Android TV or Google TV into a fast, privacy-first receiver for AirPlay 2, UPnP/DLNA, Google Cast Connect, and Miracast discovery.

  WHY PHAIRPLAY?
  • 100% Local Network Only: PhairPlay communicates exclusively within your home Wi-Fi/LAN.
  • Zero Ads & Zero Telemetry: No analytics SDKs, no crash reporters, no cloud accounts, no tracking, and no paywalls.
  • 100% Free & Open Source: Licensed under Apache 2.0 (https://github.com/mazer666/PhairPlay).

  SUPPORTED PROTOCOLS
  • AirPlay 2 Receiver: Stream H.264 screen mirroring, AAC/ALAC audio, and photos from iPhone, iPad, and Mac using hardware-accelerated MediaCodec decoding.
  • UPnP / DLNA MediaRenderer: Discoverable via SSDP on your home network. Stream network-accessible HTTP/HTTPS video, audio, and photos from DLNA controllers such as VLC, BubbleUPnP, or local media servers.
  • Google Cast Connect: Integrates with the Android TV Cast Receiver framework.
  • Built-in Network Diagnostics: Verify IPv4 binding, multicast lock status, hardware H.264 decoder availability, and live memory/CPU budgets directly on your TV.

  BUILT FOR ANDROID TV REMOTES
  • D-pad first Leanback interface with clear focus indicators.
  • Available in English, German, and French.
  ```

Upload the graphics from [`../playstore/assets/`](../playstore/assets/):
- **App icon:** `icon-512x512.png` (`512 × 512 px`)
- **Feature graphic:** `feature-graphic-1024x500.png` (`1024 × 500 px`)
- **Phone / Tablet / TV screenshots:** `tv-screenshot-1-home-1920x1080.png` and `tv-screenshot-2-diagnostics-1920x1080.png` (`1920 × 1080 px`)
- **Android TV banner (under the Android TV tab):** `tv-banner-1280x720.png` (`1280 × 720 px`)

### Step 5 — Play App Signing and Google Cast SHA-1 fingerprint

When you upload your first `.aab` under **Test and release → Production** (or
**Closed testing / Internal testing**):

1. Google Play enables **Play App Signing**.
2. Your local release key (`~/.config/phairplay/phairplay-release.jks`, SHA-1
   `D3:79:31:20:0D:67:7A:58:C1:58:17:27:65:B2:49:84:2A:3B:F8:77`) acts as your
   **Upload Key**.
3. If you let Google generate a separate **App Signing Key**, open
   **Test and release → Setup → App signing** and copy the **SHA-1 certificate
   fingerprint** of the *App signing key certificate*. If you later register a
   custom Cast Application ID in the [Google Cast Developer Console](CAST_APP_ID.md),
   use that Play App Signing SHA-1 for the Play Store distribution (or choose
   *Export and upload a key from Java keystore* during setup so Play Store and
   GitHub Releases share the exact same certificate `D37931200D677A58C158172765B249842A3BF877`).
