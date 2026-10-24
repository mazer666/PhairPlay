# Contributing to PhairPlay

Thank you for your interest in contributing to PhairPlay! This document explains
how to set the project up, which commands have to be green before a pull
request can be merged, and the coding standards the project follows.

Everything in this repository is written in **English** — code, comments, test
names, commit messages and documentation.

---

## 1. Before you start

- **Read the requirements.** [`docs/spec/REQUIREMENTS.md`](spec/REQUIREMENTS.md)
  says what the product must do; [`docs/ARCHITECTURE.md`](ARCHITECTURE.md)
  says how it is built.
- **Find your file first.** [`docs/WHERE_TO_CHANGE.md`](WHERE_TO_CHANGE.md)
  answers "I want to change X — where is that?" in one step.
- **Check the existing issues** — your idea may already be discussed. Open an
  issue before a large feature, so the approach can be agreed *before* the
  work starts.
- **A change of an accepted decision needs a CR**, not a pull request: see
  [`docs/cr/CR_TEMPLATE.md`](cr/CR_TEMPLATE.md).

---

## 2. Setting up the build

### 2.1 What you need

| Tool | Version | Why |
|---|---|---|
| **JDK** | **17** (Temurin) | the Gradle toolchain is pinned to 17 (`jvmToolchain(17)`) |
| **Android SDK** | platform `android-35`, build-tools `35.0.0` | `compileSdk` / `targetSdk` are 35 |
| **Android NDK** | `28.2.13676358` | the FairPlay and ALAC native code is compiled with CMake |
| **CMake** | `3.22.1` | the native build (`app/src/main/cpp/CMakeLists.txt`) |
| **Gradle** | **8.7** | you do **not** install it — the wrapper `./gradlew` downloads exactly that version |

Check the JDK before anything else:

```bash
java -version     # must print 17.x
```

### 2.2 Point the build at your SDK

Create the file `local.properties` in the repository root (it is in
`.gitignore`, so it stays on your machine):

```properties
sdk.dir=/absolute/path/to/your/Android/sdk
```

### 2.3 The first build

```bash
./gradlew :app:assembleGoogletvDebug
```

The APK ends up in `app/build/outputs/apk/googletv/debug/`. The first build
also compiles the native libraries and therefore takes a while.

### 2.4 On a small machine

The build needs memory. If your machine has less than 4 GB of RAM, cap the
daemon and do not keep it alive between the commands:

```bash
./gradlew --no-daemon -Dorg.gradle.jvmargs="-Xmx1024m" :test-runner:test
```

That is exactly the command the project uses in a constrained environment, and
it is the command the reviewer expects to see in a handoff note.

---

## 3. The gate — what has to be green

A pull request is reviewed against these seven commands. Four of them are
repository structure checks, three of them are Gradle tasks.

| # | Gate | Command | Expected result |
|---|---|---|---|
| 1 | File size | `bash scripts/check-file-size.sh` | `OK: file size check passed — 189 file(s) checked, none above the hard limit of 550 lines, 0 warning(s) above the soft limit of 400 lines.` |
| 2 | KDoc headers | `bash scripts/check-kdoc-headers.sh` | `OK: KDoc header check passed — 233 class declaration(s) checked, all of them have a KDoc header.` |
| 3 | Documentation links | `bash scripts/check-docs-links.sh` | `OK: documentation link check passed — <N> internal link(s) resolve, <M> external link(s) skipped.` |
| 4 | Language completeness | `bash scripts/check-i18n-completeness.sh` | `OK: i18n completeness check passed — 80 key(s) in app/src/main/res/values/strings.xml, complete in 2 language(s).` |
| 5 | The JVM protocol suite | `./gradlew :test-runner:test` | `BUILD SUCCESSFUL` — 511 tests, 0 failures, 0 errors, 0 skipped |
| 6 | The Android lint, both flavours | `./gradlew :app:lintGoogletvDebug :app:lintFiretvDebug` | `No issues found.` for **both** variants |
| 7 | The debug APKs, both flavours | `./gradlew :app:assembleGoogletvDebug :app:assembleFiretvDebug` | `BUILD SUCCESSFUL`, two APKs in `app/build/outputs/apk/` |

The counts in the expected results are the **measured** state of the tree
(2026-10-24). They are not fixed values — read the current state in
[`docs/quality/QUALITY.md`](quality/QUALITY.md). The link count is written as
`<N>` on purpose: every document that is added or extended adds links, so
that number grows with every change. What has to stay true is the **exit
code** — every internal link has to resolve.

Every script exits **1** when it finds a violation and prints the file and the
line number, so a red script is a red build. Each script also understands
`--help`:

```bash
bash scripts/check-file-size.sh --help
```

**One detail of the CI:** the KDoc check runs there with `--skip-nested`,
because a class that is nested inside another class is documented by the
header of the class that contains it. The strict run (without the flag) is
green as well — both exit codes are recorded in
[`docs/acceptance/QA-QUALITY-GATE-ACCEPTANCE.md`](acceptance/QA-QUALITY-GATE-ACCEPTANCE.md).

---

## 4. The `test-runner` module — and what it does **not** compile

The JVM suite does **not** run through the Android Gradle plugin. It runs in
its own module, `test-runner/`, which is a plain Kotlin JVM module
(`kotlin-jvm`, `jvmToolchain(17)`).

**Why:** the protocol code should be testable on any machine, without the
Android SDK and without an emulator. The module compiles the production
sources and the test sources into **one** compilation unit, so that `internal`
members are reachable from the tests without a friend-module workaround, and
the `test` task then scans that output for the `@Test` methods.

**What it compiles:**

```
app/src/main/kotlin        the production code
app/src/firetv/kotlin      the Fire TV flavour code
app/src/test/kotlin        the JVM tests
test-runner/src/stubs      three stubs that replace the excluded files
```

**What it does not compile** — the `exclude` block in
`test-runner/build.gradle.kts`, and the reason for every entry:

| Excluded | Why |
|---|---|
| `**/ui/**` | the whole UI layer needs AndroidX, Leanback and the `R` class |
| `**/MainActivity.kt`, `**/PhairPlayApp.kt` | the full Activity lifecycle needs the Android runtime |
| `**/settings/SettingsRepository.kt` | DataStore is an AndroidX library |
| `**/service/PhairPlayService.kt`, `**/service/BootReceiver.kt` | the foreground service needs `NotificationCompat` and `R` |
| `**/service/ServiceNotificationManager.kt`, `**/service/ReceiverSupervisor.kt` | split out of the service: both need the notification manager |
| `**/MainActivityTest.kt` | the Activity test needs a device |
| `**/airplay/VideoSubmissionTest.kt` | it exercises the real `MediaCodec` submission path, not the JVM stub |
| `**/airplay/MdnsServiceTest.kt` | it mocks `NsdManager`, whose static initialiser calls JNI |
| `**/service/ServiceControllerTest.kt` | it triggers `android.os.Build`, which calls JNI |
| `**/util/NetworkUtilsTest.kt` | it mocks `ContentResolver`, which calls JNI through `android.os.Build` |
| `**/airplay/VideoDecoder.kt` | shadowed by `test-runner/src/stubs/VideoDecoder.kt`, which has no `MediaCodec`/`Surface` dependency but exposes the companion members the tests need |

**What that means for you:** the six excluded *test* files are not failures —
they are tests that only run on a device. A green CI run does **not** mean the
UI layer and the mDNS registration are covered. If you touch one of those
areas, say so in the pull request and name the device test that would cover
it.

---

## 5. The coding rules

These rules are enforced in code review and, where possible, by a script. A
pull request that violates them will be asked to fix it before merging.

### RULE 1 — File size: 400 lines soft, 550 lines hard

| Range | Meaning | Consequence |
|---|---|---|
| ≤ 400 lines | the target | nothing to do |
| 401 – 550 lines | over the soft limit | allowed, but a review comment; a new change to the file should reduce it |
| > 550 lines | over the hard limit | **blocks the merge** — the file must be split first |

```kotlin
// BAD: One giant class doing everything
class AirPlayStuff { ... }  // 800 lines

// GOOD: Separate classes with clear responsibilities
class MdnsService { ... }   // registers mDNS
class RtspHandler { ... }   // handles the RTSP protocol
class VideoDecoder { ... }  // decodes H.264
```

**Why the limit is this strict:** every hand-written Kotlin file parses
something that a stranger on the network can send, so a long file is a long
list of places where a length check can be forgotten — and the audience is a
beginner, for whom 400 lines is about one screen of code. The rule is
mechanically checkable, which is what makes it survive a review. The
normative statements are NFR-23 in
[`docs/spec/REQUIREMENTS.md`](spec/REQUIREMENTS.md) and this rule.

The check runs in CI:

```bash
bash scripts/check-file-size.sh            # warnings above 400, error above 550
bash scripts/check-file-size.sh --strict   # a warning above 400 also fails
```

### RULE 2 — KDoc headers

Every **class** must have a KDoc header comment explaining:

- **WHAT** it does (1 sentence)
- **WHY** it exists (1 sentence)
- **HOW** to use it (short example)

```kotlin
/**
 * MdnsService — Advertises PhairPlay as an AirPlay 2 receiver on the local network.
 *
 * WHY: For macOS to show PhairPlay in the AirPlay menu, the device must announce
 * itself using mDNS. Without this, macOS would never know PhairPlay exists.
 *
 * Example:
 *   val mdns = MdnsService(context)
 *   mdns.start()   // device appears in macOS AirPlay menu
 *   mdns.stop()    // device disappears from macOS AirPlay menu
 */
class MdnsService(private val context: Context) {
```

Every **non-trivial method** must have a comment explaining:

- What it does (for callers)
- Why it works the way it does (for future maintainers)
- Any security or performance considerations

**Don't** write obvious comments:

```kotlin
// BAD: obvious
// increment i by 1
i++

// GOOD: explains the "why" behind a protocol detail
// AirPlay uses CTR mode specifically because it supports arbitrary-length
// audio frames without padding, which is critical for real-time streaming
```

The check runs in CI:

```bash
bash scripts/check-kdoc-headers.sh              # every declaration
bash scripts/check-kdoc-headers.sh --skip-nested  # what CI runs
```

### RULE 3 — Tests

Every **public method** must have at least one unit test.
Every **AirPlay protocol step** must have a corresponding test.

Test files must be named `[ClassName]Test.kt` and placed in the matching test directory:

- Unit tests: `app/src/test/kotlin/com/phairplay/`
- Instrumented tests: `app/src/androidTest/kotlin/com/phairplay/`

Tests must be commented like production code, and their **names are
documentation** — a fellow developer should understand what is being tested and
why, without opening the file:

```kotlin
/**
 * Test: RECORD triggers the onStreamingStarted callback.
 *
 * WHY: The UI switch from WaitingScreen to StreamingScreen depends on this
 * callback being called when RECORD is received.
 */
@Test
fun `RECORD triggers onStreamingStarted callback`() {
```

A test that was skipped, disabled or deleted is a gap that has to be written
down — never a green checkmark.

### RULE 4 — Security

- All **network input must be validated** before processing (length checks, format checks)
- Every buffer a peer can influence needs a **byte** budget, not only an item count
- **No hardcoded** IP addresses, port numbers (except standard AirPlay ports), or secrets
- **No unnecessary Android permissions** — use only what is in the manifest, and every
  permission must be traceable to a call site
- **All exceptions must be caught and logged** — never silently swallowed
- **Buffer overflow prevention**: always check array lengths before indexing
- **No network access that leaves the local network** — no telemetry, no analytics,
  no cloud fallback

### RULE 5 — Performance

- **Video decode**: Hardware MediaCodec only — never add a software fallback
- **Thread safety**: Network I/O on IO dispatcher, UI updates on Main dispatcher
- **No blocking calls on Main thread** — use coroutines for all async work
- **No memory leaks**: all resources (MediaCodec, AudioTrack, sockets) must be released in the appropriate lifecycle method
- **A performance claim without a measurement is not a claim.** If you did not
  measure it, write "not measured" and the reason.

### RULE 6 — Build flavours

Changes that affect platform-specific behavior must be implemented in the appropriate flavor directory:

- `app/src/main/`: Shared code
- `app/src/googletv/`: Google TV specific (API 29+ only)
- `app/src/firetv/`: Fire TV specific (API 25+, no Google APIs)

If you use an API that requires API level 26+, wrap it in a version check:

```kotlin
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
    // API 26+ code
}
```

Both flavours are linted and both are built in CI. A change that only compiles
in one of them is not done.

### RULE 7 — Language

Everything in the repository is **English**: code, KDoc, comments, identifiers,
test names, commit messages, documentation and the issue templates. There is no
exception inside the repository — the project does not ship a German or a
French version of the *documentation*; only the *user interface* is
translated (`values-de/`, `values-fr/`).

---

## 6. Commit messages

Use the **Conventional Commits** format:

```
type: short description (max 72 chars)

Longer explanation if needed (optional).
```

Types: `feat`, `fix`, `docs`, `test`, `refactor`, `perf`, `chore`

Examples:

```
feat: add NTP timing synchronization for A/V sync
fix: prevent crash when RTSP ANNOUNCE has empty SDP body
docs: add Fire TV sideloading instructions to README
test: add unit tests for AES-128-CTR decryption
```

The message is **English**, and it says what the change does — not "cleanup"
or "misc". A breaking change is marked with `!` and a `BREAKING CHANGE:`
footer.

---

## 7. Pull request checklist

Before you open the pull request, verify:

- [ ] `./gradlew :test-runner:test` is green — 0 failures, 0 errors, **0 skipped**
- [ ] `./gradlew :app:lintGoogletvDebug :app:lintFiretvDebug` reports `No issues found.` for **both** flavours
- [ ] `./gradlew :app:assembleGoogletvDebug :app:assembleFiretvDebug` builds **both** APKs
- [ ] `bash scripts/check-file-size.sh` exits 0 — no file above 550 lines, ideally none above 400
- [ ] `bash scripts/check-kdoc-headers.sh` exits 0 — every class has a header
- [ ] `bash scripts/check-docs-links.sh` exits 0 — every internal link resolves
- [ ] `bash scripts/check-i18n-completeness.sh` exits 0 — every string key exists in every translation
- [ ] Every public method that changed has a test
- [ ] Every new class has a KDoc header with what / why / how
- [ ] The documentation that the change made stale is updated **in the same pull request**
- [ ] The PR description explains **what** changed, **why**, and how it was verified
- [ ] The PR description names the **known limitations** — anything that is still open is written down as *open, because …*
- [ ] New network input, new permission or new stored value: **yes/no**, with the reason
- [ ] Nothing in the repository is written in a language other than English

A device test that was **not** executed is reported as "not measured" — with
the reason — and never as "passed".

---

## 8. How to add a new protocol or a new receiver

This is the path that keeps the four protocol stacks independent. Follow it in
this order; the order matters.

**1. Get the decision first.** A new protocol is a product decision. If it
changes an accepted requirement, the codec matrix or the scope, write a
**CR** ([`docs/cr/CR_TEMPLATE.md`](cr/CR_TEMPLATE.md)) and let the Product
role accept it. Do not start the code before the decision exists.

**2. Write the ADR.** A new protocol stack needs an
[ADR](adr/ADR_TEMPLATE.md) that answers: why this protocol, why next to the
existing ones, what the boundaries are, and what the alternatives were. The
existing records are in [`docs/decisions/`](decisions/) — ADR-001 (the
multi-protocol model) and ADR-004 (the DLNA roadmap) are the two that matter
most here.

**3. Respect the protocol isolation.** The four stacks —
`com.phairplay.airplay`, `com.phairplay.dlna`, `com.phairplay.miracast` and
the Cast integration in the flavour source sets — stay independent:

- the new code lives in its **own package**, `com.phairplay.<protocol>/`;
- it does **not** import from another protocol package;
- it does **not** change a shared class to make its life easier — a shared
  change needs the Architect role;
- a bug in one protocol may never be the reason another protocol fails;
- the new protocol is **additionally and independently switchable** in the
  settings — it never replaces an existing one.

**4. Own the whole life of the data.** A new protocol brings new network
input, and every byte of it needs the treatment the other stacks get: a
length, a format and a range check before a value is used, a **byte** budget
for every buffer, a timeout on every socket, and a **negative test** that
proves the guard rejects the attack.

**5. Wire it into the service and the UI.** The receiver lifecycle lives in
`com.phairplay.service`, the settings in `com.phairplay.settings`, the status
card on the Home screen in `com.phairplay.ui`. A new protocol gets a settings
switch and a status card that tells the truth about its state — the same words
everywhere.

**6. Update the documentation in the same pull request.** At least:

| Document | What has to change |
|---|---|
| [`docs/ARCHITECTURE.md`](ARCHITECTURE.md) | the component overview and the data flow of the new stack |
| [`docs/WHERE_TO_CHANGE.md`](WHERE_TO_CHANGE.md) | the "I want to change X" entry for the new package |
| [`docs/spec/REQUIREMENTS.md`](spec/REQUIREMENTS.md) | the requirement IDs of the new protocol |
| [`docs/spec/PROJECT_PLAN.md`](spec/PROJECT_PLAN.md) | the phase plan, with the same shape as ADR-004's D1–D4 |
| [`GLOSSARY.md`](../GLOSSARY.md) | the terms a newcomer will meet in the new stack |
| [`docs/quality/QUALITY.md`](quality/QUALITY.md) | if a quality target or a budget moves |
| [`README.md`](../README.md) | the honest limitations — a protocol that was not tested on hardware says so |

**7. Prove it, and say what you could not prove.** The protocol suite has to
cover the new parsers, and the device test plan needs the new cases. Anything
that was not executed on hardware is reported as *not validated* — never as
*working*.

---

## 9. Questions?

Open a GitHub issue, or start a discussion. If you are unsure where a change
belongs, read [`docs/WHERE_TO_CHANGE.md`](WHERE_TO_CHANGE.md) first — it
answers "where is that?" for most questions.
