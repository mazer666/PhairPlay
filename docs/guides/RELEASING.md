# Cutting a release

This guide is for the **owner** of the project and for anyone who wants to
publish a build. You do not need to be an Android developer for it — you need
a JDK, the Android SDK and `curl`.

The release **plan** — when an alpha, a beta and a stable release are built and
which conditions trigger them — lives in
[`../spec/PROJECT_PLAN.md`](../spec/PROJECT_PLAN.md#release-schedule). This
guide is the *how*.

---

## 1. What the script does for you

`scripts/release.sh` performs the whole release in one run and refuses to
publish anything it has not checked itself:

```
quality gate  →  version  →  build  →  verify  →  tag & push  →  publish
```

| Step | What happens | When it stops the release |
|---|---|---|
| **Quality gate** | The four `scripts/check-*.sh` run: file size, KDoc headers, documentation links, language completeness | Any check that is not green |
| **Tests** | `./gradlew :test-runner:test` | A failing test (`--skip-tests` skips this, and the release notes then say so) |
| **Version** | `versionCode` and `versionName` in `app/build.gradle.kts` are set to the version of the tag and committed | A version that does not follow the scheme |
| **Build** | Both flavours are built — one Gradle call each, capped heap, no daemon | A build error |
| **Verify** | `aapt2 dump badging` must show the version of the tag, `apksigner verify` must accept the signature | A version mismatch or an unsigned APK |
| **Tag & push** | The commit and the tag go to the remote | A push that fails |
| **Publish** | The release and the two APKs are published through the GitHub REST API | A missing token or an upload error |

**A release is never published from an unchecked commit.**

---

## 2. One-time setup

```bash
cd PhairPlay
./scripts/release.sh --setup
```

The script asks for four things:

| Question | What it is | Notes |
|---|---|---|
| Key alias | The name of the key inside the keystore | `phairplay` is a good default |
| Keystore password | The password of the keystore | Store it in a password manager — it cannot be recovered |
| Key password | The password of the key itself | Press Enter to use the keystore password |
| GitHub token | A personal access token with the scope `repo` | Create one at <https://github.com/settings/tokens> |

The script writes **two files outside the repository**:

```
~/.config/phairplay/phairplay-release.jks   the signing key
~/.config/phairplay/release.env             the passwords and the token
```

> **These two files never belong into the repository.** Not in a commit, not in
> a backup that goes online, not in a chat. The keystore is the **identity** of
> the project: every future version has to be signed with the same key, because
> Android refuses to update an app that was signed with a different key.
> Back the keystore up **offline** — a lost key means a new key, and a new key
> means every user has to uninstall before they can update.

---

## 3. Cutting a release

```bash
cd PhairPlay
./scripts/release.sh v1.0.0-alpha.3
```

The version has to follow the scheme of the release plan:

| Version | Meaning | Published as |
|---|---|---|
| `v1.0.0-alpha.N` | An alpha: code complete, **not validated on a television** | *prerelease* |
| `v1.0.0-beta.N` | A beta: validated on real hardware | *prerelease* |
| `v1.0.0` | The stable release | *latest* |

Useful variants:

```bash
./scripts/release.sh --help                       # the short usage
./scripts/release.sh v1.0.0-alpha.3 --skip-tests   # without the JVM test suite
```

`--skip-tests` is an escape hatch for a release that must go out now. The
generated release notes then state that the test suite was not run — which is
exactly what has to be visible.

The script publishes the assets under these names:

```
PhairPlay-<version>-GoogleTV.apk
PhairPlay-<version>-FireTV.apk
```

---

## 4. Checking the result

After the script finishes, check these four things — they are the difference
between "the script said it worked" and "the release is good":

1. **The release page** shows both assets and the expected version.
2. **The version inside the APK** matches the tag:
   ```bash
   aapt2 dump badging PhairPlay-1.0.0-alpha.3-GoogleTV.apk | head -1
   # package: name='com.phairplay.googletv' versionCode='6' versionName='1.0.0-alpha.3-googletv' …
   ```
3. **The signature** is the same one as in the previous release:
   ```bash
   apksigner verify --print-certs PhairPlay-1.0.0-alpha.3-GoogleTV.apk | head -2
   ```
   The SHA-256 digest must be identical to the digest of the previous release —
   otherwise the users cannot update in place.
4. **The changelog** has an entry for the version. The script warns if it does
   not.

---

## 5. When something goes wrong

| Symptom | Cause | What to do |
|---|---|---|
| `Quality gate failed` | One of the four checks is red | **Do not publish.** Fix the cause first — the message names the file and the line. |
| `The version ... does not follow the release scheme` | The tag is not `v<major>.<minor>.<patch>[-<stage>.<n>]` | Use the correct scheme, for example `v1.0.0-alpha.4`. |
| `The version inside the APK does not match the tag` | The build used a stale `app/build.gradle.kts` | The script bumps the file itself; if this appears, the working tree was changed after the bump. Start again. |
| `The APK is not signed` | `KEYSTORE_PATH` does not point at the keystore, or the password is wrong | Check `~/.config/phairplay/release.env`. The file must exist and be readable. |
| `401 Unauthorized` | The GitHub token expired or has the wrong scope | Create a new token with the scope `repo` and update `GITHUB_TOKEN` in `release.env`. |
| `404` during the asset upload | The upload went to the wrong host | The script uses `uploads.github.com` for assets — an upload against `api.github.com` answers 404. |
| `The tag exists already` | A previous run got as far as the tag | If the tag points at the current commit, the script rebuilds the release. If it points elsewhere, delete it: `git tag -d <tag> && git push origin :refs/tags/<tag>` |
| The build dies with `Gradle build daemon disappeared` | The machine ran out of memory during the release build (R8 is hungry) | Close other programs and run the script again. The script already caps the heap and builds one flavour at a time. |
| `git push` fails | No write access, or the branch is protected | Check the remote: `git remote -v`. The script pushes to the branch the release plan names. |

**A failed release is not a problem — an unchecked release is.** If the script
stops, nothing was published. Fix the cause and run it again.

---

## 6. Where the files are

| What | Where | Belongs into the repository? |
|---|---|---|
| The release script | `scripts/release.sh` and `scripts/release/` | **yes** |
| The signing key | `~/.config/phairplay/phairplay-release.jks` | **no** |
| Passwords and token | `~/.config/phairplay/release.env` | **no** |
| The release plan | `docs/spec/PROJECT_PLAN.md` | **yes** |
| The device test plan | `docs/guides/DEVICE_TEST_PLAN.md` | **yes** |
| The network check | `docs/guides/NETWORK_CHECK.md` | **yes** |

---

## 7. Before a stable release

An alpha can be cut with the script alone. A **stable** release needs more,
because it is the version that other people install:

1. **All 15 device test cases executed** on real hardware
   ([`DEVICE_TEST_PLAN.md`](DEVICE_TEST_PLAN.md)).
2. **The performance numbers measured**, not estimated: RAM, CPU load, frame
   rate, latency (`docs/quality/QUALITY.md`).
3. **The release checklist** in
   [`../project/RELEASE_READINESS.md`](../project/RELEASE_READINESS.md) complete.
4. **The production key.** An alpha is signed with the alpha key; the stable
   release must be signed with the key that the installed app expects — either
   the same alpha key (then it *is* the production key) or a new one, which
   means every user has to uninstall first.
5. **The changelog entry** written for a user, not for a colleague.
