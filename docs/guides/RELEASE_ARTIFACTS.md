# The release artefacts — what they are and where they live

**Read this before you publish anything.**

PhairPlay builds a release in two separate acts:

| Act | What it does | Command | Result |
|---|---|---|---|
| **1 — Build** | compiles, signs, verifies and describes the artefacts | `./scripts/release.sh <version> --artifacts-only` | a directory `dist/release/<version>/` with every artefact and its proof |
| **2 — Publish** | creates the tag and uploads the artefacts to GitHub | `./scripts/release.sh <version>` | a public release on GitHub |

**Act 2 needs an explicit authorisation.** Act 1 does not: it writes to the
local disk and touches nothing that anybody else can see. That separation is
deliberate — a release that cannot be taken back is a decision, not a side
effect of a build.

---

## 1. What the build act produces

```
dist/release/v1.0.0-alpha.8/
├── PhairPlay-1.0.0-alpha.8-GoogleTV.apk    the signed Google TV app
├── PhairPlay-1.0.0-alpha.8-FireTV.apk      the signed Fire TV app
├── PhairPlay-1.0.0-alpha.8-GoogleTV.aab    the signed bundle for the Play Store
├── PhairPlay-1.0.0-alpha.8-source.tar.gz    the source of exactly that commit
├── release-manifest.json                    the machine-readable description
├── SHA256SUMS                               the checksum of every file above
├── BUILD-PROVENANCE.txt                     which commit, which toolchain
└── VERIFICATION.md                          what was checked — and what was not
```

### `release-manifest.json`

The machine-readable answer to "which file is this?". It names the version, the
version code, the commit, the branch, the toolchain, the flavours, the test
counts of the run that produced the artefacts, the size and the SHA-256 of every
artefact, the signer of each APK and the AAB — and it states explicitly that
the artefacts are **not published** and that **0 of 16 device test cases** were
executed.

### `SHA256SUMS`

The checksum of every file in the directory, in the format
`sha256sum -c SHA256SUMS` understands. To check an artefact somebody sent you:

```bash
cd dist/release/v1.0.0-alpha.8
sha256sum -c SHA256SUMS
```

### `BUILD-PROVENANCE.txt`

The commit, the branch, whether the tree was clean, the Gradle, JDK and SDK
versions, the compile/target/min SDK levels, the build invocation and the
timestamp. This is the answer to "how was this built, from what?".

### `VERIFICATION.md`

What was checked automatically (with the measured result) **and what was not**
— the device test cases, the Play review, the Android TV quality review, the
Cast receiver. An unexecuted check is never a pass; this file says so.

---

## 2. Retention and retrieval

| Where | How long | How to retrieve |
|---|---|---|
| `dist/release/<version>/` on the build machine | until the owner deletes it — the directory is **not** in the repository (`.gitignore` contains `/dist/`) | `ls dist/release/` — the version directories are named after the version |
| The GitHub release | as long as the release exists | the release page of the repository, with the assets attached |
| The CI artefacts | 30 days (see `.github/workflows/ci.yml`) | the run of the workflow in the Actions tab |

**A release directory that was verified once stays verifiable**: the
`SHA256SUMS` and the manifest describe the files as they were on that day. If
a file in the directory is changed afterwards, the checksum no longer matches —
that is the point of the file.

---

## 3. Signing status

The APKs and the bundle are signed with the release keystore that lives
**outside** the repository (`~/.config/phairplay/phairplay-release.jks`). The
keystore, its password and the GitHub token never enter the repository and are
never part of an artefact.

The certificate fingerprint (SHA-1) of that key:

```
F4:BF:7B:F0:E1:40:1D:0C:33:EE:85:C2:51:FF:24:12:1B:D5:7C:E4
```

`scripts/cast-registration-info.sh` prints it and compares it with the guide —
a mismatch is reported, not hidden.

**An alpha build signed with the alpha key is not the production build.** For
the stable release the owner decides whether the alpha key *becomes* the
production key (then its password belongs in a password safe) or whether a
separate production key is created.

---

## 4. What the build act does **not** do

* It creates **no tag** and **no GitHub release** — that is the publish act.
* It does **not** upload anything anywhere.
* It does **not** run the 16 device test cases: that needs real hardware.
* It does **not** run the Google Play review or the Android TV quality review.

The build act proves that the artefacts are *consistent and reproducible*. It
does not prove that they work on a television — that is what the device test
plan is for.
