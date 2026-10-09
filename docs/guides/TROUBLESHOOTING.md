# Troubleshooting

---

## Device not appearing in AirPlay / Miracast / Cast menu

**Cause 1: Not on the same network**
- Ensure your Mac/PC and the TV are connected to the **same Wi-Fi network** (same router, same subnet).
- Check: if your router has both 2.4 GHz and 5 GHz bands with different SSIDs, make sure both devices use the same one.

**Cause 2: AP Isolation / Client Isolation**
- Some routers have "AP isolation" that prevents devices from seeing each other.
- Log into your router and disable "AP Isolation", "Client Isolation", or "Wireless Isolation".

**Cause 3: Multicast filtering**
- mDNS (used by AirPlay) requires multicast traffic. Some routers block this.
- Look for "Enable Multicast", "IGMP Snooping", or "mDNS" options in your router's advanced settings.

**Cause 4: PhairPlay service is stopped**
- Check the HomeScreen: all service cards should show "Running".
- If stopped, press the **Start** button or swipe to the control card.

**Cause 5: Wi-Fi P2P disabled (Miracast)**
- Miracast requires Wi-Fi Direct. Some Android TVs disable this.
- Check: Settings → My Fire TV / Google TV → About → verify Wi-Fi Direct is available.

---

## TV goes to sleep / screensaver starts during streaming

- **Fixed in v1.0.0-beta.2:** PhairPlay now keeps the screen awake (`FLAG_KEEP_SCREEN_ON`) throughout active mirroring and audio streaming.
- If you are experiencing a blackout or screensaver after ~15–20 minutes on Fire TV or Google TV, make sure you have upgraded to **v1.0.0-beta.2** or later.

---

## TV not discoverable after waking from standby

- **Fixed in v1.0.0-beta.2:** PhairPlay now monitors network connectivity changes and automatically re-advertises mDNS when the TV wakes up and reconnects to Wi-Fi. In addition, a Wi-Fi Multicast Lock is held to prevent packet dropping during sleep.
- Ensure your device is updated to **v1.0.0-beta.2**.

---

## iOS 17+ connects briefly then drops with black screen

- **Fixed in v1.0.0-beta.2:** Recent iOS versions send larger RTSP setup plists (~77 KB) which exceeded older buffer limits. This limit was raised to 1 MB in **v1.0.0-beta.2**.

---

## Connected but black screen

**Cause 1: FairPlay-protected content**
- Netflix, Disney+, Apple TV+, and other streaming services use FairPlay DRM.
- Apple blocks mirroring of protected content by design. This is not a PhairPlay limitation.
- Solution: use a different app/tab on your Mac.

**Cause 2: MediaCodec decoder unavailable**
- Rare: some cheap Android TV boxes lack H.264 hardware decode.
- Check logcat: `adb logcat -s PhairPlay` — look for "MediaCodec" errors.
- Solution: not fixable in software; the TV box needs hardware H.264 support.

---

## High latency (>200ms)

1. Switch from 2.4 GHz Wi-Fi to **5 GHz Wi-Fi** or **Ethernet**.
2. Move the TV closer to the router.
3. Check if other devices are using the same Wi-Fi band heavily.

---

## Audio out of sync

1. Try stopping and restarting the stream from your Mac.
2. Restart the PhairPlay service (HomeScreen → Restart button).
3. If persistent, check logcat for NTP timing errors.

---

## App crashes on startup

1. Check you're using the correct flavor APK for your device.
2. Try reinstalling: `adb uninstall com.phairplay.googletv` then install again.
3. Report the crash: attach `adb logcat -d` output to a GitHub Issue.

---

## Cast not available on Fire TV

Google Cast requires Google Play Services, which is not available on Amazon Fire TV.
The Cast toggle in Settings will be automatically hidden on Fire TV devices.
This is by design and cannot be changed.

---

## Still stuck?

Open a GitHub Issue at `https://github.com/mazer666/PhairPlay/issues` with:
- Your TV model and OS version
- The protocol you were trying to use
- A description of what happened
- `adb logcat -d | grep PhairPlay` output
