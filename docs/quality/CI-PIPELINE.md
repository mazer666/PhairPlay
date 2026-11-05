# The CI pipeline of PhairPlay

This document describes what the pipeline checks, what a red gate looks like,
which artefacts a run produces, how long the gates take and how a developer
reproduces them locally.

The pipeline itself lives in `.github/workflows/ci.yml`. The lint workflow in
`.github/workflows/lint.yml` runs Android Lint for both flavours and is
documented at the end of this file.

## The jobs at a glance

| Job | What it checks | Fails when | Artefacts |
|---|---|---|---|
| `quality-gate` | the structure rules of the repository: file size, KDoc headers, documentation links, the completeness of the translations, the privacy policy | a rule of `docs/CONTRIBUTING.md` is broken | none |
| `jvm-tests` | the protocol tests of the JVM test runner | a test fails | `jvm-test-report` |
| `android-build` | Android Lint and the debug APK, for both flavours | Lint reports a finding, or the build fails | `build-reports`, `debug-apk-googletv`, `debug-apk-firetv` |
| `native-libraries` | the ABIs, the ELF alignment and the alignment inside the ZIP of the native libraries | a 64-bit library is not aligned to 16 KB, an ABI does not match `app/build.gradle.kts`, a library is stored in the wrong way | `native-checked-apks` |
| `security-supply-chain` | the secret scan, the SBOM of the declared dependencies and the decision on every dependency | a credential is committed, a dependency is newer than the accepted version, a dependency was never assessed | `sbom-cyclonedx` |
| `build-only` | nothing — it only builds the release artefacts, on a manual run | the build fails | `release-artefacts` |

The job `build-only` runs only for `workflow_dispatch`, and it publishes
nothing. The publication of a release stays an explicit act of the owner; the
pipeline never talks to the Play Console, never creates a tag and never
pushes. The token of the runner needs nothing but `contents: read`.

Every job that is not `build-only` is skipped for a manual run, so that a
manual run builds the artefacts and nothing else.

## What a red gate looks like

Every gate follows the same convention, so that a red run can be read without
knowing the tool behind it:

| Exit code | Meaning |
|---|---|
| 0 | green — everything that could be checked was checked and passed |
| 1 | red — at least one violation was found |
| 2 | a usage error: an unknown argument or a missing value |
| 77 | skipped — the gate could not run, and it says why |

**A skip is never a pass.** Exit 77 means that the gate verified nothing, and
the reason is always printed. The native library gate uses it when there is
no artefact to check; the secret scan when there is nothing to scan; the
dependency gate when there is no SBOM to read.

A red gate prints the file and the line, or the component and the version,
and the name of the rule that was broken. It never prints the secret it
found: the secret scan reports the file, the line and a redacted marker.

### The gates and what they prove

| Script | Proves |
|---|---|
| `scripts/check-native-libs.sh` | that the 64-bit libraries of the artefact are aligned to 16 KB, that the ABIs match the declaration in `app/build.gradle.kts`, and that the libraries sit uncompressed on a 16 KB boundary inside the APK |
| `scripts/check-secrets.sh` | that no private key, no token and no keystore is committed, and that `local.properties` and the keystore patterns are ignored |
| `scripts/build-sbom.sh` | that every declared dependency is in a CycloneDX 1.5 document, with its version, its purl, its usage flag, the tool chain and the commit hash |
| `scripts/check-dependencies.sh` | that every dependency of the SBOM was assessed by the team and is within the version ceiling that the team set |

Each of these gates has a self-test that proves it can turn red:

| Self-test | Cases |
|---|---|
| `scripts/test-check-native-libs.sh` | a wrongly aligned 64-bit library, an ABI that is not declared, a compressed library, a library that is not aligned inside the ZIP, the 32-bit exemption, the skip without an artefact, and the real artefact of the working tree |
| `scripts/test-check-secrets.sh` | a committed private key, a committed token, a credential assigned a literal value, a committed keystore, a committed `local.properties`, a missing ignore rule — and that the secret never reaches the output |
| `scripts/test-build-sbom.sh` | a missing catalog, an empty catalog, the expected number of components, the validity of the JSON, the used and the unused flag |
| `scripts/test-check-dependencies.sh` | a lowered version ceiling, an expired waiver, a dependency that is not in the allowlist, and the `--allow-unassessed` mode |

A gate that cannot turn red proves nothing, which is why the self-tests run
in the same pipeline as the gates.

## The artefacts of a run

| Artefact | Content | Retention |
|---|---|---|
| `jvm-test-report` | the report of the JVM protocol tests | 30 days |
| `build-reports` | the reports of Android Lint and of the build | 30 days |
| `debug-apk-googletv`, `debug-apk-firetv` | the debug APKs of the two flavours | 7 days |
| `native-checked-apks` | the debug APKs that passed the native library gate | 7 days |
| `sbom-cyclonedx` | the CycloneDX 1.5 document of the declared dependencies | 30 days |
| `release-artefacts` | the release APKs and the bundle of a manual run | 30 days |

## How long a run takes

The duration of the pipeline itself is **not measured** — the pipeline runs on
the runner of the team, and the duration of a run is not recorded anywhere
that this repository could read.

The durations of the gates below were measured on the development machine
(the sandbox of the team), with a warm file system cache. They are the time
the gate needs, not the time the job needs; the job also builds the
artefacts, and that is where the minutes go.

| Gate | Measured duration |
|---|---|
| `scripts/check-native-libs.sh` (one debug APK, 25 MB) | 5.4 s (29.6 s with a cold cache) |
| `scripts/test-check-native-libs.sh` | 5.5 s |
| `scripts/check-secrets.sh` (372 versioned files) | 2.5 s |
| `scripts/test-check-secrets.sh` | 3.1 s |
| `scripts/build-sbom.sh` | 0.2 s |
| `scripts/test-build-sbom.sh` | 0.5 s |
| `scripts/check-dependencies.sh` (25 components) | 0.2 s |
| `scripts/test-check-dependencies.sh` | 1.0 s |

The gates need no network, no `jq`, no `xmllint` and no Android SDK: they
run with bash, `awk`, `sed`, `grep` and `unzip`. The one exception is
`zipalign`, which the native library gate uses when the Android build tools
are installed and replaces with its own reader otherwise.

## Reproducing a gate locally

Every gate runs on the machine of the developer, without a network:

```bash
# the structure rules
./scripts/check-file-size.sh --ext .kt --ext .sh
./scripts/check-kdoc-headers.sh --skip-nested
./scripts/check-docs-links.sh
./scripts/check-i18n-completeness.sh
./scripts/check-privacy-policy.sh

# the native libraries of a built artefact
./gradlew :app:assembleGoogletvDebug :app:assembleFiretvDebug
./scripts/check-native-libs.sh \
  --apk app/build/outputs/apk/googletv/debug/app-googletv-debug.apk \
  --apk app/build/outputs/apk/firetv/debug/app-firetv-debug.apk

# the credentials in the repository
./scripts/check-secrets.sh

# the SBOM and the dependencies
./scripts/build-sbom.sh
./scripts/check-dependencies.sh

# the self-tests of the gates
./scripts/test-check-native-libs.sh
./scripts/test-check-secrets.sh
./scripts/test-build-sbom.sh
./scripts/test-check-dependencies.sh
```

The SBOM is written to `dist/sbom/phairplay-<version>-sbom.cdx.json`. The
dependency gate reads it from there and builds it itself when it is missing.

### The paths of the commands

| Command | What it reads | What it writes |
|---|---|---|
| `check-native-libs.sh` | the APK or the AAB, `app/build.gradle.kts` | nothing |
| `check-secrets.sh` | the versioned files, `.gitignore` | nothing |
| `build-sbom.sh` | `gradle/libs.versions.toml`, the build scripts | `dist/sbom/*.cdx.json` |
| `check-dependencies.sh` | the SBOM, the two files in `scripts/allowlists/` | nothing |

### The decisions of the team

Two files decide what the dependency gate accepts. They are maintained by the
team and are reviewed like any other piece of the repository:

* `scripts/allowlists/dependency-allowlist.txt` — the dependencies that were
  assessed, one per line, with the reason as a comment. A dependency that is
  not listed has **not** been assessed: the gate reports it as `UNASSESSED`
  and does not count it as passed. The decision is still open.
* `scripts/allowlists/known-vulnerabilities.txt` — the version ceiling of the
  dependencies that carry a known advisory, with the advisory and the decision
  in the note. A dependency above the ceiling is a `VIOLATION`. A note that
  starts with `WAIVED(<date>)` accepts the version until that date; when the
  date has passed, the waiver is over and the dependency becomes a violation
  again.

The gate never asks a server what is known about a version. The knowledge
lives in the repository, where it can be reviewed, and the gate runs offline.

## The lint workflow

`.github/workflows/lint.yml` runs Android Lint for **both** flavours
(`:app:lintGoogletvDebug` and `:app:lintFiretvDebug`) with `fail-fast:
false`, so that a finding in one flavour does not hide the result of the
other. The job verifies that a report was produced at all: a lint run
without a report must not look like a clean run. The report is uploaded for
30 days.

## The rules of the gate scripts

* Bash 3.2 compatible, because the owner runs them on a Mac: no `mapfile`,
  no `readarray`, no `${var,,}`, no associative array, no `jq`, no `xmllint`,
  no `nc`.
* POSIX tools only: `awk`, `sed`, `grep`, `unzip`, `od`, `dd`, `tail`,
  `head`, `mktemp`.
* A file of `scripts/` stays below 400 lines, like every other source file of
  the project.
* The header of every script says what it checks and why, in the same style
  as the other scripts of the repository.
