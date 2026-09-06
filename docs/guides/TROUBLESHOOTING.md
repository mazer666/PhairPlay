# Troubleshooting

---

## Device not appearing in AirPlay / Cast / DLNA menu

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

## Windows "Cast to Device" does not list the TV

- The PC's network profile must be **Private** (Settings → Network → properties). On a Public profile Windows blocks discovery.
- Turn on **Network discovery** in Advanced sharing settings.
- The **Windows Media Player Network Sharing Service** must be running (services.msc) — it hosts the files Windows streams to the TV.
- Router AP isolation or multicast filtering blocks SSDP exactly like mDNS (see the first section).
- On the TV, `adb logcat -s DlnaReceiver` should show "DLNA renderer … ready at http://<ip>:49494"; `curl http://<tv-ip>:49494/description.xml` from the PC must return XML.

## DLNA plays audio but no video, or Windows says the file can't be played

- The format is not in the advertised sink list (Android MediaPlayer cannot decode it, e.g. WMV/WMA). Windows either transcodes or gives up. Re-encode to MP4 (H.264 + AAC) or MP3.
- A DLNA **video** is refused while an AirPlay session is mirroring; stop the AirPlay session first.

## Still stuck?

Open a GitHub Issue at `https://github.com/mazer666/PhairPlay/issues` with:
- Your TV model and OS version
- The protocol you were trying to use
- A description of what happened
- `adb logcat -d | grep PhairPlay` output
