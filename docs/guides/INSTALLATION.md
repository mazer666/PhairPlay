# Installation Guide

This guide covers every way to install PhairPlay on your Android TV or Fire TV device.

---

## Prerequisites

- A Google TV or Fire TV device (see [supported devices](../spec/REQUIREMENTS.md))
- A computer (Windows, macOS, or Linux) with ADB installed — OR — a direct APK sideload method
- Both devices on the same Wi-Fi network

---

## Method 1: ADB (Recommended for developers)

### Step 1: Enable ADB on your TV

**Google TV (Chromecast with Google TV):**
1. Settings → System → About → Android TV OS Build → click 7 times
2. Settings → System → Developer Options → USB debugging → ON

**Fire TV:**
1. Settings → My Fire TV → About → Build → click 7 times
2. Settings → My Fire TV → Developer Options → ADB debugging → ON
3. Settings → My Fire TV → Developer Options → Apps from Unknown Sources → ON

### Step 2: Find your TV's IP address

**Google TV:** Settings → Network & Internet → your Wi-Fi → scroll down to see IP
**Fire TV:** Settings → My Fire TV → About → Network

### Step 3: Connect ADB

```bash
adb connect <TV-IP-ADDRESS>:5555
# Example: adb connect 192.168.1.42:5555
```

Confirm the connection prompt that appears on your TV.

### Step 4: Install

```bash
# For Google TV:
adb install app-googletv-release.apk

# For Fire TV:
adb install app-firetv-release.apk
```

### Step 5: Launch

Find **PhairPlay** in your app list and launch it.

---

## Method 2: Direct Sideload via USB (Fire TV Stick only)

Use the **Downloader** app (available in the Fire TV app store) to download the APK directly to your Fire TV from a URL.

1. Install "Downloader" from the Fire TV app store
2. Open Downloader and enter the APK download URL
3. Follow the prompts to install

---

## Method 3: Build from Source

```bash
git clone https://github.com/mazer666/PhairPlay.git
cd PhairPlay

# Build for Google TV
./gradlew assembleGoogletvRelease

# Build for Google TV with a registered Cast App ID
./gradlew assembleGoogletvRelease -Pphairplay.castAppId=<APP_ID>

# Build for Fire TV
./gradlew assembleFiretvRelease
```

APKs are in `app/build/outputs/apk/`.

Google Cast requires a registered Cast App ID for real testing. See
[Google Cast App ID](CAST_APP_ID.md) before testing Cast on Google TV.

---

## After Installation

1. Launch PhairPlay — the **HomeScreen** appears showing three service cards
2. All services (AirPlay, Cast) are enabled by default
3. On your Mac: click the AirPlay icon → select your TV
4. On Windows: Settings → Display → Connect to wireless display → select your TV
5. In Chrome: Menu → Cast → select your TV

See [Troubleshooting](TROUBLESHOOTING.md) if the device doesn't appear in your sender's list.
