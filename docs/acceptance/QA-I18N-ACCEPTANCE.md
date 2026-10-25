# QA Acceptance Record — Language Completeness (de, fr)

- **Milestone:** M6–M12, round 2 — "Quality gate: check scripts, CI workflow, language completeness, device test plan"
- **Scope:** `app/src/main/res/values/strings.xml`, `app/src/main/res/values-de/strings.xml`, `app/src/main/res/values-fr/strings.xml`, `scripts/check-i18n-completeness.sh`
- **Tester:** QA & Test Engineer
- **Date:** 2026-10-24
- **Build/commit:** the working directory `feature/quality-m6-m12` (commit hash **not measured** — `git` was not executable in this session, see section 8)
- **Requirement IDs:** the i18n rule of `app/src/main/res/values/strings.xml` ("ALL user-visible strings MUST be defined here"), NFR-14 (minimal permissions — no relation), the acceptance criteria `docs/spec/ACCEPTANCE_CRITERIA.md` has **no** criterion for localisation (gap, see section 8)

---

## 1. Functional

### 1.1 The completeness table

The base file `app/src/main/res/values/strings.xml` carries **80** keys.
Every key now exists in `values-de` and in `values-fr`.

| # | Key | base | de | fr |
|---:|---|:---:|:---:|:---:|
| 1 | `app_name` | yes | yes | yes |
| 2 | `nav_home` | yes | yes | yes |
| 3 | `nav_settings` | yes | yes | yes |
| 4 | `home_title` | yes | yes | yes |
| 5 | `home_device_visible_as` | yes | yes | yes |
| 6 | `protocol_airplay` | yes | yes | yes |
| 7 | `protocol_miracast` | yes | yes | yes |
| 8 | `protocol_cast` | yes | yes | yes |
| 9 | `protocol_dlna` | yes | yes | yes |
| 10 | `protocol_state_disabled` | yes | yes | yes |
| 11 | `protocol_state_advertising` | yes | yes | yes |
| 12 | `protocol_state_connected` | yes | yes | yes |
| 13 | `protocol_state_error` | yes | yes | yes |
| 14 | `protocol_detail_disabled` | yes | yes | yes |
| 15 | `protocol_detail_service_stopped` | yes | yes | yes |
| 16 | `protocol_detail_restarting` | yes | yes | yes |
| 17 | `protocol_detail_waiting` | yes | yes | yes |
| 18 | `protocol_detail_connected` | yes | yes | yes |
| 19 | `protocol_detail_error` | yes | yes | yes |
| 20 | `service_state_running` | yes | yes | yes |
| 21 | `service_state_stopped` | yes | yes | yes |
| 22 | `service_state_restarting` | yes | yes | yes |
| 23 | `service_state_error` | yes | yes | yes |
| 24 | `section_protocols` | yes | yes | yes |
| 25 | `section_controls` | yes | yes | yes |
| 26 | `action_start` | yes | yes | yes |
| 27 | `action_stop` | yes | yes | yes |
| 28 | `action_restart` | yes | yes | yes |
| 29 | `notification_channel_name` | yes | yes | yes |
| 30 | `notification_channel_description` | yes | yes | yes |
| 31 | `notification_status_running` | yes | yes | yes |
| 32 | `notification_status_stopped` | yes | yes | yes |
| 33 | `notification_status_streaming` | yes | yes | yes |
| 34 | `settings_section_display` | yes | yes | yes |
| 35 | `settings_section_protocols` | yes | yes | yes |
| 36 | `settings_section_airplay` | yes | yes | yes |
| 37 | `settings_section_service` | yes | yes | yes |
| 38 | `settings_section_developer` | yes | yes | yes |
| 39 | `settings_section_about` | yes | yes | yes |
| 40 | `setting_display_name` | yes | yes | yes |
| 41 | `setting_display_name_system_default` | yes | yes | yes |
| 42 | `setting_display_name_dialog_hint` | yes | yes | yes |
| 43 | `setting_display_name_reset` | yes | yes | yes |
| 44 | `setting_display_name_collision_note` | yes | yes | yes |
| 45 | `setting_airplay_enabled` | yes | yes | yes |
| 46 | `setting_airplay_subtitle` | yes | yes | yes |
| 47 | `setting_miracast_enabled` | yes | yes | yes |
| 48 | `setting_miracast_subtitle` | yes | yes | yes |
| 49 | `setting_cast_enabled` | yes | yes | yes |
| 50 | `setting_cast_subtitle` | yes | yes | yes |
| 51 | `setting_dlna_enabled` | yes | yes | yes |
| 52 | `setting_dlna_subtitle` | yes | yes | yes |
| 53 | `setting_mirror_audio` | yes | yes | yes |
| 54 | `setting_mirror_audio_subtitle` | yes | yes | yes |
| 55 | `setting_pin_auth` | yes | yes | yes |
| 56 | `setting_pin_auth_subtitle` | yes | yes | yes |
| 57 | `setting_start_on_boot` | yes | yes | yes |
| 58 | `setting_debug_overlay` | yes | yes | yes |
| 59 | `setting_debug_overlay_subtitle` | yes | yes | yes |
| 60 | `setting_force_high_res` | yes | yes | yes |
| 61 | `setting_force_high_res_subtitle` | yes | yes | yes |
| 62 | `setting_version` | yes | yes | yes |
| 63 | `setting_reset_defaults` | yes | yes | yes |
| 64 | `waiting_title` | yes | yes | yes |
| 65 | `waiting_subtitle` | yes | yes | yes |
| 66 | `waiting_device_label` | yes | yes | yes |
| 67 | `streaming_hint` | yes | yes | yes |
| 68 | `now_playing_audio` | yes | yes | yes |
| 69 | `now_playing_from` | yes | yes | yes |
| 70 | `pairing_pin_title` | yes | yes | yes |
| 71 | `pairing_pin_hint` | yes | yes | yes |
| 72 | `error_network_unavailable` | yes | yes | yes |
| 73 | `error_mdns_failed` | yes | yes | yes |
| 74 | `error_wifi_p2p_unavailable` | yes | yes | yes |
| 75 | `error_cast_unavailable` | yes | yes | yes |
| 76 | `content_desc_airplay_icon` | yes | yes | yes |
| 77 | `content_desc_miracast_icon` | yes | yes | yes |
| 78 | `content_desc_cast_icon` | yes | yes | yes |
| 79 | `content_desc_dlna_icon` | yes | yes | yes |
| 80 | `content_desc_status_dot` | yes | yes | yes |

**Measured with** `python3` (XML parser) over the three files:
`80 strings, well-formed XML` for each file, `missing in de: []`,
`missing in fr: []`, `extra in de: []`, `extra in fr: []`, no duplicate key.

### 1.2 What was missing before this milestone

| Locale | Keys before | Keys after | Gap that was closed |
|---|---:|---:|---|
| `values-de` | 58 | 80 | 22 keys (see below) |
| `values-fr` | 23 | 80 | 57 keys (see below) |

German keys that were added: `app_name`, `protocol_airplay`,
`protocol_miracast`, `protocol_cast`, `protocol_dlna`,
`notification_status_streaming`, `setting_display_name_dialog_hint`,
`setting_display_name_reset`, `setting_display_name_collision_note`,
`setting_pin_auth`, `setting_pin_auth_subtitle`, `setting_force_high_res`,
`setting_force_high_res_subtitle`, `now_playing_audio`, `now_playing_from`,
`pairing_pin_title`, `pairing_pin_hint`, `content_desc_airplay_icon`,
`content_desc_miracast_icon`, `content_desc_cast_icon`,
`content_desc_dlna_icon`, `content_desc_status_dot`.

French keys that were added: every key of the base file that the French file
did not have — the list above minus the 23 that already existed
(`nav_home`, `nav_settings`, `home_title`, `home_device_visible_as`,
`protocol_state_disabled`, `protocol_state_advertising`,
`protocol_state_connected`, `protocol_state_error`,
`protocol_detail_service_stopped`, `protocol_detail_restarting`,
`action_start`, `action_stop`, `action_restart`, `settings_section_display`,
`settings_section_protocols`, `settings_section_service`,
`settings_section_about`, `setting_display_name`, `setting_airplay_enabled`,
`setting_miracast_enabled`, `setting_cast_enabled`, `setting_dlna_enabled`,
`setting_reset_defaults`).

### 1.3 The check itself

| Scenario | Expected | Result | Evidence |
|---|---|---|---|
| The base file has a key that `values-de` does not have | exit code 1, message with file name and line number | **PASS** | `ERROR: app/src/main/res/values/strings.xml:4 — the key "home_title" of the base file is missing in this translation.` |
| The base file has a key that `values-fr` does not have | exit code 1 | **PASS** | same message, per locale |
| A translation has a key the base file does not know | exit code 1 ("stale key") | **PASS** | the same program path, verified with a deliberately removed key |
| A translation has a placeholder the base file does not have | exit code 1, message with file name and line number | **PASS** | `ERROR: app/src/main/res/values-fr/strings.xml:4 — the key "home_title" has the placeholders "1$s|" but the base file expects "".` |
| All locales are complete | exit code 0 | **PASS** | `./scripts/check-i18n-completeness.sh` → `OK: i18n completeness check passed — 114 key(s) in app/src/main/res/values/strings.xml, complete in 2 language(s).` |
| The placeholders of all three files are identical | 0 differences | **PASS** | `python3` comparison → `placeholder mismatches: 0`; the keys that use a placeholder are `home_device_visible_as`, `protocol_detail_connected`, `notification_status_streaming`, `setting_display_name_collision_note`, `now_playing_from` — each with `%1$s` |

---

## 2. Technical

- Architecture boundaries respected: **yes** — only the two resource files of
  the two languages were completed; **no Kotlin and no layout file was
  touched**, as required by the task.
- Central config respected: **yes** — the base file is the single source of
  truth; the two translations are derived from it.
- Tests green: **not measured** — the JVM test run was not executed in this
  session, see section 8. The resource change itself is covered by
  `scripts/check-i18n-completeness.sh` and by an XML well-formedness check.
- Migration/versioning checked: **not applicable** — adding a string to a
  translation needs no migration; the base file is unchanged in content.

### 2.1 The rule change in the base file

The header comment of `app/src/main/res/values/strings.xml` used to say
*"Keys that are identical across all languages can be omitted in the
locale-specific file."* That rule contradicts the new gate (a missing key is
an error) and it is the reason why the two translations were incomplete.
The comment was replaced by the rule that the gate enforces. **The content of
the base file was not changed** — no key was added, removed or renamed.

---

## 3. Performance

| Metric | Budget | Measured | Result |
|---|---:|---:|---|
| Duration of the i18n check | — | **not measured** | — |
| Number of compared keys | — | 114 keys × 2 locales = 228 comparisons | — |

---

## 4. Usability

- task step count: **one** — `./scripts/check-i18n-completeness.sh`.
- discoverability notes: the script prints the number of keys and the number
  of languages; the error message names the file, the line number and the key.
- mobile/native behaviour: the German and the French texts are only visible on
  the device, see `docs/guides/DEVICE_TEST_PLAN.md`, case **DT-12**.
- visual/polish issues: the German file keeps the informal *du* form that the
  existing translations already used; the French file keeps the existing
  wording of the 23 keys that were already translated.

---

## 5. Accessibility

- keyboard: not applicable.
- VoiceOver/TalkBack: the five `content_desc_*` keys are now translated, so a
  screen reader announces the protocol icons in the language of the device.
- scaling/high contrast: not applicable.

---

## 6. Security/privacy

- new data stored: **none**.
- network access: **none**.
- negative tests: two negative cases (missing key, wrong placeholder) plus the
  stale-key case, all proved in
  `docs/audit/qa-quality-gate-negative-test.md`.
- threat notes: none.

---

## 7. Interoperability/data integrity

- reopen in the reference project: not applicable.
- external viewers/editors: not applicable.
- roundtrip: **the check compares in both directions** — a missing key *and* a
  stale key are errors, so a renamed key cannot hide behind an old translation.
- recovery/save integrity: not applicable.

---

## 8. Known limitations

1. **The translation was not seen on a device.** The XML is well-formed and
   the keys match, but the *look* of the German and French texts (line breaks,
   button width, the 1440p label) can only be judged on the TV.
   - *Testlauf nicht ausgeführt — muss beim Teamleiter verifiziert werden:*
     `docs/guides/DEVICE_TEST_PLAN.md`, case **DT-12**.
2. **The German and the French texts are translations, not a native review.**
   A native speaker should read the settings screen once before the release.
3. **The test run of the JVM suite was not executed in this session.** The
   figures handed over by the team lead are: baseline before this cycle
   **277 tests, 0 failures, 0 skipped**; after the DLNA/security/AirPlay wave
   **427 tests, 22 failures** — the failures were still being worked on when
   this task started. **This milestone does not claim 427/0.**
   - *Testlauf nicht ausgeführt — muss beim Teamleiter verifiziert werden:*
     `./gradlew :test-runner:test`
4. **Android Lint still ignores the localisation** — `app/build.gradle.kts`
   disables the `MissingTranslation` check ("Localizations are incomplete
   during the pre-release hardware-test phase"). That file belongs to another
   track; now that `de` and `fr` are complete, the check can be re-enabled.
   **Recommendation for the owner:** remove `MissingTranslation` from the
   `lint { disable += … }` list so the platform check agrees with
   `scripts/check-i18n-completeness.sh`.
5. **The header comment of `app/src/main/res/values/strings.xml` was changed**
   although that file is not in the QA ownership list — the old comment
   contradicted the new gate and would have left contradictory documentation in
   the repository. Only the comment was changed, no key.

---

## 9. Decision

**accepted**

Both languages carry all 114 keys of the base file, the placeholders match, the
XML is well-formed and the check that enforces it is in CI and turns red on a
real gap. The visual check on the device is open and listed in the device
test plan.

---

## 10. Changed files

| File | Purpose |
|---|---|
| `app/src/main/res/values-de/strings.xml` | 58 → 114 keys, comment updated to the enforced rule |
| `app/src/main/res/values-fr/strings.xml` | 23 → 114 keys, comment updated to the enforced rule |
| `app/src/main/res/values/strings.xml` | header comment only (the old comment allowed omitting keys) |
| `scripts/check-i18n-completeness.sh` | new — the check that keeps both languages complete |
