# Archive

This folder holds the **superseded documentation** of PhairPlay. It is a
history, not a second source of truth: nothing in it is binding, and every
document in it carries a banner that says so and names the document that
replaced it.

The rules are in [`../DOCUMENTATION_POLICY.md`](../DOCUMENTATION_POLICY.md) §3.

---

## Why a document is here

A document moves here when **all** of the following are true:

1. It has been **replaced** by a newer version, or the feature it describes
   has changed substantially.
2. The replacement is **in place and linked** from [`../README.md`](../README.md)
   and from the places that used to link to the old document.
3. The document is **no longer normative** — nothing in the current build
   depends on it.
4. The replacement itself is **stable** — it is not a draft that may still be
   rewritten.

A document that is merely **outdated** is corrected, not archived. A draft
that was never accepted is deleted, not archived — an archive records decisions
that were taken.

## Naming convention

An archived document keeps its name and gets the date of the **archiving**
change appended:

```text
2026-10-24-security-audit_2026-11-13.md
```

The date is the date the document was archived, not the date it was written —
the written date is already in the document header. A document that carries a
version in its name keeps it:

```text
PROJECT_PLAN-v2.4_2026-11-13.md
```

## The banner

Every archived document carries this banner directly under its title:

```markdown
> **ARCHIVED — superseded on YYYY-MM-DD.**
> This document is kept for historical reference only. It does **not** describe
> the current build. The current version is <LINK TO THE CURRENT DOCUMENT>.
> Do not follow this document for new work.
```

The **content** of an archived document is not changed. The **relative links**
inside it are adjusted so that they keep pointing at files that exist — a link
that leads nowhere costs more trust than a missing sentence.

---

## What is in here

| Archived document | Archived on | Replaced by |
|---|---|---|
| [`2026-06-08-airplay2-mirroring-handshake_2026-11-13.md`](2026-06-08-airplay2-mirroring-handshake_2026-11-13.md) | 2026-11-13 | [`../spec/TECHNICAL_SPEC.md`](../spec/TECHNICAL_SPEC.md), [`../ARCHITECTURE.md`](../ARCHITECTURE.md) |
| [`2026-10-24-pr19-pr17-issue18-review_2026-11-13.md`](2026-10-24-pr19-pr17-issue18-review_2026-11-13.md) | 2026-11-13 | [`../spec/REQUIREMENTS.md`](../spec/REQUIREMENTS.md), [`../spec/ACCEPTANCE_CRITERIA.md`](../spec/ACCEPTANCE_CRITERIA.md) |
| [`2026-10-24-security-audit_2026-11-13.md`](2026-10-24-security-audit_2026-11-13.md) | 2026-11-13 | [`../security/FINDINGS.md`](../security/FINDINGS.md) |
| [`qa-quality-gate-negative-test_2026-11-13.md`](qa-quality-gate-negative-test_2026-11-13.md) | 2026-11-13 | the self-tests of the gates in `scripts/` |
| [`M06-ACCEPTANCE_2026-11-13.md`](M06-ACCEPTANCE_2026-11-13.md) | 2026-11-13 | [`../spec/ACCEPTANCE_CRITERIA.md`](../spec/ACCEPTANCE_CRITERIA.md) (v3.0) |
| [`M07-ACCEPTANCE_2026-11-13.md`](M07-ACCEPTANCE_2026-11-13.md) | 2026-11-13 | [`../spec/ACCEPTANCE_CRITERIA.md`](../spec/ACCEPTANCE_CRITERIA.md) (v3.0) |
| [`M08-ACCEPTANCE_2026-11-13.md`](M08-ACCEPTANCE_2026-11-13.md) | 2026-11-13 | [`../spec/ACCEPTANCE_CRITERIA.md`](../spec/ACCEPTANCE_CRITERIA.md) (v3.0) |
| [`M09-ACCEPTANCE_2026-11-13.md`](M09-ACCEPTANCE_2026-11-13.md) | 2026-11-13 | [`../spec/ACCEPTANCE_CRITERIA.md`](../spec/ACCEPTANCE_CRITERIA.md) (v3.0) |
| [`M10-ACCEPTANCE_2026-11-13.md`](M10-ACCEPTANCE_2026-11-13.md) | 2026-11-13 | [`../spec/ACCEPTANCE_CRITERIA.md`](../spec/ACCEPTANCE_CRITERIA.md) (v3.0) |
| [`M11-ACCEPTANCE_2026-11-13.md`](M11-ACCEPTANCE_2026-11-13.md) | 2026-11-13 | [`../spec/ACCEPTANCE_CRITERIA.md`](../spec/ACCEPTANCE_CRITERIA.md) (v3.0) |
| [`M12-ACCEPTANCE_2026-11-13.md`](M12-ACCEPTANCE_2026-11-13.md) | 2026-11-13 | [`../spec/ACCEPTANCE_CRITERIA.md`](../spec/ACCEPTANCE_CRITERIA.md) (v3.0) | [`../spec/ACCEPTANCE_CRITERIA.md`](../spec/ACCEPTANCE_CRITERIA.md) (v3.0) |
| [`QA-DEVICE-TEST-ACCEPTANCE_2026-11-13.md`](QA-DEVICE-TEST-ACCEPTANCE_2026-11-13.md) | 2026-11-13 | [`../guides/DEVICE_TEST_PLAN.md`](../guides/DEVICE_TEST_PLAN.md) |
| [`QA-I18N-ACCEPTANCE_2026-11-13.md`](QA-I18N-ACCEPTANCE_2026-11-13.md) | 2026-11-13 | `scripts/check-i18n-completeness.sh`, [`../quality/QUALITY.md`](../quality/QUALITY.md) |
| [`QA-QUALITY-GATE-ACCEPTANCE_2026-11-13.md`](QA-QUALITY-GATE-ACCEPTANCE_2026-11-13.md) | 2026-11-13 | [`../quality/CI-PIPELINE.md`](../quality/CI-PIPELINE.md) |
| [`PROJECT_PLAN-v2.4_2026-11-13.md`](PROJECT_PLAN-v2.4_2026-11-13.md) | 2026-11-13 | [`../spec/PROJECT_PLAN.md`](../spec/PROJECT_PLAN.md) (v2.5) |
| [`REQUIREMENTS-v3.0_2026-11-13.md`](REQUIREMENTS-v3.0_2026-11-13.md) | 2026-11-13 | [`../spec/REQUIREMENTS.md`](../spec/REQUIREMENTS.md) (v4.0) |
| [`ACCEPTANCE_CRITERIA-legacy-rounds_2026-11-13.md`](ACCEPTANCE_CRITERIA-legacy-rounds_2026-11-13.md) | 2026-11-13 | [`../spec/ACCEPTANCE_CRITERIA.md`](../spec/ACCEPTANCE_CRITERIA.md) (v3.0) |

### Why the milestone records were archived

The records of M6 … M12 were written against the requirements of the rounds of
2026-10-24. The requirement baseline is now **v4.0** with eight attributes per
requirement, and the acceptance criteria are **v3.0** with a contradiction
register. The milestone records keep the history — *what was checked then, and
on which tree* — but they are no longer the place where a criterion is looked
up: that is [`../spec/ACCEPTANCE_CRITERIA.md`](../spec/ACCEPTANCE_CRITERIA.md),
and the chain to the tests is generated into
[`../spec/TRACEABILITY.md`](../spec/TRACEABILITY.md).

## Reading an archived document

An archived document explains **why** something was decided. It is the right
place to look when you want to know the history of a decision. It is the wrong
place to look for the current behaviour: for that, read the live document the
banner points to.
