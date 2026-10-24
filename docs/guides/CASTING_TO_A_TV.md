# Casting to a TV

PhairPlay turns your Android TV (or Fire TV) into a receiver: the phone or the
computer sends the picture and the sound, the TV shows them.

This guide explains which protocol to use for which sender, how to connect a
Samsung TV, and — just as important — what each protocol cannot do.

---

## Before you start

- **Same network.** The sending device and the TV have to be in the same Wi-Fi
  network. Guest networks and networks with "AP isolation" (client isolation)
  block the discovery — the TV simply never appears in the picker.
- **Start the receiver.** Open PhairPlay on the TV and switch on the protocol you
  want to use (Settings → Protocols).
- **Name the TV.** The name in every picker is the device name of your TV
  (Settings → Display name). Rename it there and Windows, VLC, the Samsung apps
  and AirPlay all show the new name.

---

## Which protocol should I use?

| Sender | Protocol | What you get |
| --- | --- | --- |
| iPhone, iPad, Mac | AirPlay | Screen mirroring, video, photos |
| Samsung Galaxy phone or tablet | Samsung Smart View | Video, audio, photos |
| Samsung Galaxy phone or tablet (older apps) | AllShare / Device Connect | Video, audio, photos |
| Windows "Cast to device", VLC, UPnP apps | DLNA | Video, audio, photos |
| Android phone with a UPnP/DLNA app | DLNA | Video, audio, photos |

---

## Samsung Smart View (Galaxy phones and tablets)

1. On the phone, swipe down the notification shade and tap **Smart View**
   (or: **Share → Smart View**).
2. Wait for your TV to appear in the list of devices. It is listed under the
   device name of your TV.
3. Tap the TV. The screen of the phone is mirrored on the TV, the sound follows.
4. To stop, open **Smart View** again and tap **Stop**, or disconnect the phone.

**If the TV does not appear in the list**

- Are both devices in the same network? A Wi-Fi network that separates its
  clients blocks the search — see a hotel or café network.
- Is the DLNA renderer switched on in PhairPlay (Settings → Protocols → DLNA)?
  Smart View discovers PhairPlay through the DLNA renderer.
- Restart the Smart View app on the phone. On some Samsung models the app keeps
  an old device list.
- On a Tizen TV: *Settings → General → Network → Device Connect* has to be on.

**What Smart View can do — and what it cannot**

| | Smart View / AllShare over PhairPlay |
| --- | --- |
| Screen mirroring | yes |
| Video from the internet (streaming apps, web videos) | yes |
| Audio from the internet | yes |
| A photo from the phone gallery | yes |
| **A video file that only exists on the phone** | **no** |

The last row is a limitation of the protocol, not a bug in PhairPlay: over DLNA
the sender only tells the TV *which address* to play. A movie in the phone's
gallery has no address the TV could open. To watch such a file, put it on a
media server (or on the TV itself) and send its address, or use AirPlay from a
device that supports it.

---

## AllShare and Device Connect (older Samsung apps)

AllShare on the phone and Device Connect on the TV are the older names of the
same feature. They use the same discovery as Smart View, so the setup is
identical:

1. On the phone, open **AllShare**.
2. Choose the **TV** category, then tap your TV.
3. Pick what to send: video, audio or an image.

If your model still ships **AllShare** (instead of Smart View) it will find
PhairPlay the same way — the older app asks for the same device and shows the
same device name.

The same limitation as above applies: content that only exists on the phone
cannot be sent, only content that has an address.

---

## DLNA (Windows, VLC and other UPnP apps)

1. On the TV: open PhairPlay and enable **DLNA** in Settings → Protocols.
2. On the sending device:
   - **Windows:** Settings → Devices → Bluetooth & other devices →
     *Cast to device* → choose your TV.
   - **VLC:** Media → *Cast* → choose your TV.
3. Play the file or the stream. The picture appears on the TV.

---

## AirPlay (iPhone, iPad, Mac)

1. On the TV: open PhairPlay and enable **AirPlay** in Settings → Protocols.
2. On the iPhone or iPad: open the Control Center (or the share sheet) and tap
   **AirPlay** → choose your TV.
3. On the Mac: click the AirPlay icon in the menu bar → choose your TV.

A photo from the iPhone gallery is transferred to the TV directly. A video is
played from its address, in the same way as over DLNA.

---

## Troubleshooting

| Symptom | What to check |
| --- | --- |
| The TV never appears in any picker | Same network? Client isolation? Is the protocol enabled in PhairPlay? |
| The TV appears, the picture stays black | The sender cannot reach the media address — try the same file from a media server. |
| The name in the picker is wrong | Set the display name in PhairPlay → Settings → Display name. |
| The Samsung app finds nothing | Restart the Smart View app; on a Tizen TV check *Settings → General → Network → Device Connect*. |

---

## Known limitations

- **Local files over Samsung Smart View, AllShare and DLNA.** The sender sends
  an address, not the file. Everything that is only stored on the phone — a
  downloaded movie, a video from the gallery — cannot be transferred this way.
  Use AirPlay for photos, or make the file reachable over the network first.
- **Samsung Smart View depends on the TV model and on the app version.** PhairPlay
  answers the Samsung discovery and serves the DIAL documents the TV asks for;
  whether your TV shows the entry in its list depends on the model (Tizen 2015
  and newer) and on the version of the Smart View app on the phone. Older
  AllShare apps behave the same way.
