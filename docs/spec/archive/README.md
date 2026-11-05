# Archive — superseded specifications

This folder holds the **previous versions of the documents in `docs/spec/`**. A
document is moved here when it is replaced by a newer version of the same
document — not when a topic is closed.

**Current version of every document:**

| Document | Current | Archived |
|---|---|---|
| [`../REQUIREMENTS.md`](../REQUIREMENTS.md) | v4.0 (2026-11-05) | [`REQUIREMENTS-v3.0.md`](REQUIREMENTS-v3.0.md) |
| [`../REQUIREMENTS_MATRIX.md`](../REQUIREMENTS_MATRIX.md) | v4.0 (2026-11-05) | — (new in v4.0) |
| [`../PROJECT_PLAN.md`](../PROJECT_PLAN.md) | v2.5 (2026-11-05) | [`PROJECT_PLAN-v2.4.md`](PROJECT_PLAN-v2.4.md) |
| [`../ACCEPTANCE_CRITERIA.md`](../ACCEPTANCE_CRITERIA.md) | v3.0 (2026-11-05) | — (kept in the archive of the top-level `docs/archive/` folder) |
| [`../TECHNICAL_SPEC.md`](../TECHNICAL_SPEC.md) | unchanged | — |

## Why a version is kept

* **Reproducibility.** An acceptance record or a review comment may refer to a
  requirement by its identifier. The archived text is the only way to find out
  what that identifier meant at the time.
* **Audit trail.** A requirement that was changed has a reason. The archive shows
  the requirement before the change, so the reason can be read in both directions.
* **Recovery.** A retired idea sometimes turns out to be right again.

## Rules for an archived file

1. **The body is not edited.** The archived version is a snapshot. The only
   addition is the header at the top, which names the successor, the date of the
   replacement and the reasons.
2. **The file name carries the version.** `REQUIREMENTS-v3.0.md`, not
   `REQUIREMENTS-old.md` — a reader must be able to tell which version it is
   without opening it.
3. **The successor links back.** The current document names the archived version
   in its change log, so that neither direction of the reference is broken.

## Naming convention

```
DOCUMENT-v<major>.<minor>.md
```

`PROJECT_PLAN-v2.4.md` is the version of the project plan before the release
readiness section was added.
