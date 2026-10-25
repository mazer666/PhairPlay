# Google Cast: getting the Application ID

PhairPlay cannot invent a Google Cast Application ID. Google assigns it, once,
to an account that registers a receiver application in the
[Google Cast Developer Console](https://cast.google.com/publish).

> **Can someone else do it for me?** No — and it does not need to be done by
> anyone else either. The registration is tied to **your Google account** and to
> **the signing certificate of your app**, so it has to happen once, in your
> browser, with your account. It takes about ten minutes. This page tells you
> exactly what to click and what to paste; `scripts/cast-registration-info.sh`
> prints the values you need.

---

## 1. What you need before you start

| What | Value | Where it comes from |
|---|---|---|
| Google account | your own | — |
| Android package name | `com.phairplay.googletv` | fixed in the project |
| SHA-1 fingerprint of the signing certificate | `F4:BF:7B:F0:E1:40:1D:0C:33:EE:85:C2:51:FF:24:12:1B:D5:7C:E4` | `scripts/cast-registration-info.sh` |
| Serial number of the test television | e.g. `ABCD1234` | on the TV: *Settings → About → Build → Serial number* |

**Get the fingerprint yourself** — it changes if the signing key changes:

```bash
./scripts/cast-registration-info.sh
```

The script prints the package name, the SHA-1 and the SHA-256 fingerprint, the
owner of the certificate and the command that bakes the ID into the build. It
reads the fingerprint out of the keystore, so it cannot be a typo.

> **Why the fingerprint matters:** the console compares it with the certificate
> that actually signed the APK. If the two differ, the Cast framework refuses
> to start the receiver — with a log message that says nothing about the
> fingerprint. This is the most common reason a registered receiver still does
> not start.

---

## 2. Register the application (ten minutes)

1. Open <https://cast.google.com/publish> and sign in with the Google account
   that should own the receiver.
2. Accept the *Cast SDK Terms of Service* (they appear on the first use).
3. Choose **Add new application**.
4. Fill in the form:

   | Field | Value |
   |---|---|
   | Application name | `PhairPlay` |
   | Description | `Ad-free, telemetry-free receiver for Android TV and Fire TV.` |
   | Application type | **Receiver app** |
   | Platform | **Android TV** |

5. In the **Android package name** field enter:
   ```
   com.phairplay.googletv
   ```
6. In the **SHA-1 fingerprint** field enter the fingerprint **without colons**:
   ```
   F4BF7BF0E1401D0C33EE85C251FF24121BD57CE4
   ```
   The script prints this exact line for you.
7. The console now shows an **Application ID** — a number like `1234567890`.
   **Copy it.**
8. Open the **Devices** tab of the same entry and **add your television** by its
   serial number. Without this step the console will not issue a *device* that
   may cast to your receiver — the sender app filters the device list by it.
9. Back on the overview page: while the application is in the state
   *Draft*, the ID works for your own test devices. **Publish** it only when
   the receiver works — publishing is what allows other people's senders to find
   it, and it makes the ID a public thing.

---

## 3. Build with the ID

Three ways — pick one:

```bash
# a) on the command line
./gradlew :app:assembleGoogletvRelease -Pphairplay.castAppId=<APP_ID>

# b) as an environment variable
PHAIRPLAY_CAST_APP_ID=<APP_ID> ./gradlew :app:assembleGoogletvRelease

# c) in the user-level Gradle file — not part of the repository
printf 'phairplay.castAppId=<APP_ID>\n' >> ~/.gradle/gradle.properties
```

Without an ID the build still succeeds — and that is deliberate. PhairPlay then
reports Cast as **not configured** in the diagnostics instead of pretending that
Cast works:

```kotlin
CastReceiver.isConfigured()   // false for an empty id, "00000000" and the placeholder
```

**Fire TV is not affected:** Fire TV has no Google Play Services, so there is no
Cast there — not a missing ID but a missing platform.

---

## 4. Check that it works

1. Install the Google TV flavour **that was built with the ID** (the ID is baked
   into `BuildConfig.CAST_APP_ID` — an APK built without it keeps working
   exactly as before).
2. Start the app, open **Settings → Diagnostics**. Cast now shows *Advertising*
   instead of *Disabled*.
3. On a phone with a Cast sender app (Google Home, YouTube, …), open the
   device list: your television must appear. **If it does not appear, the
   device is not registered in the console** — that is the *Devices* tab from
   step 8, not the fingerprint.
4. Cast something and look at the log:
   ```bash
   adb logcat | grep -i cast
   ```

---

## 5. If it does not work

| Symptom | Cause | What to do |
|---|---|---|
| The television is not in the sender's device list | The device is not registered in the console | Add its serial number in the *Devices* tab |
| The receiver does not start, the log says the app id is unknown | The fingerprint in the console does not match the signing key | Compare the console entry with `scripts/cast-registration-info.sh` |
| The receiver does not start, the log says the app id is unknown | The APK was built **without** the id | Build with `-Pphairplay.castAppId=<APP_ID>` |
| Cast stays *Disabled* in the diagnostics | The id is empty, `00000000` or the placeholder | Build with the id and check `BuildConfig.CAST_APP_ID` |
| The ID stopped working after a new release | The release was signed with a **different key** | The console entry must be updated to the new fingerprint — or, better, keep signing with the same key |
| Nothing works on Fire TV | Fire TV has no Google Play Services | Not fixable — Cast does not exist on Fire TV |

---

## 6. What is still open afterwards

Registering the ID removes the **blocker**, not the work:

* The **media path** of the Cast receiver still has to be validated with real
  senders and real media — the same honest gap as on the other protocols.
* The receiver has to run **on the television**; a phone-only test proves
  nothing about it.
* Cast does not change the **ad-free, telemetry-free, local-only** promise:
  the Cast SDK talks to the local network and to Google's Cast infrastructure,
  and it brings its own dependency with it. That trade-off is documented in
  [`../project/RISKS.md`](../project/RISKS.md) (R-02) and has to be looked at
  once more before a stable release.

---

## 7. The sources

* Google's registration guide: <https://developers.google.com/cast/docs/registration>
* The Android TV receiver: <https://developers.google.com/cast/docs/android_tv_receiver/>
* The console: <https://cast.google.com/publish>
