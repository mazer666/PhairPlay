# Documentation Policy

Documentation in PhairPlay is part of the product. A receiver that people
cannot install, cannot trust or cannot contribute to is not finished, however
good the code is.

This policy defines three rules:

1. the **same-change rule** — a change and its documentation belong together;
2. the **single-source-of-truth rule** — one fact, one place;
3. the **archive rule** — what happens to a document that is replaced.

---

## 1. The same-change rule

> **A code or configuration change is incomplete if it makes any documentation
> stale.**

The documentation update happens in the **same commit / pull request**. Not in
a follow-up, not in a later release, not in the author's head.

### What has to be updated with what

| What the change does | What has to be updated in the same change |
|---|---|
| Changes product behaviour | `docs/spec/REQUIREMENTS.md` (and a CR in `docs/cr/` if the change is material), plus the matching guide under `docs/guides/` if a user can see the difference |
| Adds or removes a package, changes a boundary | `docs/ARCHITECTURE.md`, `docs/WHERE_TO_CHANGE.md`, and an ADR in `docs/decisions/` if the decision is material |
| Changes a default, a timeout, a size budget or a port | the owning class, `docs/quality/QUALITY.md` if it moves a quality target, and `docs/spec/ACCEPTANCE_CRITERIA.md` if it moves a criterion |
| Changes a permission | `app/src/main/AndroidManifest.xml`, `PRIVACY.md` §4, and the permission table in `docs/audit/2026-10-24-security-audit.md` |
| Changes what is stored or what leaves the device | `PRIVACY.md` §2 and §3, and `SECURITY.md` if a guarantee changes |
| Changes the milestone status | `docs/spec/PROJECT_PLAN.md`, `CHANGELOG.md` |
| Changes a test gate, a quality target or the severity scale | `docs/quality/QUALITY.md`, and `docs/spec/ACCEPTANCE_CRITERIA.md` |
| Adds a document | the index in `docs/README.md` |
| Adds a decision | an ADR in `docs/decisions/` (or `docs/adr/` after the migration) |
| Changes a command that a contributor runs | `README.md` and `docs/CONTRIBUTING.md` — same change, always |

### How far "the same change" reaches

The rule covers **every** document, not only the ones you think of as
"documentation":

- the KDoc header of a class is documentation;
- the commit message is documentation;
- the name of a test is documentation;
- the label of a settings switch is documentation.

A pull request that changes behaviour without touching the corresponding
document is **not ready for review**. The reviewer rejects it with the name of
the document that has to change, not with a general remark.

### The one exception

A *pure* refactoring — code that moves without changing behaviour — does not
need a product-document update. It still needs:

- the KDoc to move with the code and to stay true;
- the file-size rule to be respected on the way (that is the point of the
  refactoring);
- **no** entry in the changelog that describes it as a capability.

---

## 2. The single-source-of-truth rule

> **Every number and every rule lives in exactly one file. Every other file
> links to it.**

This is not a style preference. It is what stops the project from ending up
with two contradictory versions of the same fact — which is the single most
common way a documentation set becomes untrustworthy.

### The places that own what

| Fact | Owner | Everything else links here |
|---|---|---|
| Requirement IDs, codec matrix, scope | `docs/spec/REQUIREMENTS.md` | — |
| Milestone status | `docs/spec/PROJECT_PLAN.md` | `README.md` links to it |
| Measurable acceptance criteria, performance budgets | `docs/spec/ACCEPTANCE_CRITERIA.md` | `docs/TESTING.md` links to it |
| Architecture, package boundaries | `docs/ARCHITECTURE.md` | `docs/WHERE_TO_CHANGE.md` links to it |
| The security state of the release, the findings | `docs/audit/2026-10-24-security-audit.md` | `SECURITY.md` is the short user-facing version |
| The user-facing security policy | `SECURITY.md` | — |
| The data-protection statement | `PRIVACY.md` | — |
| The quality model, the severity grades, the current state per dimension | `docs/quality/QUALITY.md` | — |
| The coding rules | `docs/CONTRIBUTING.md` | `AGENTS.md` links to it |
| The file-size rule | NFR-23 in `docs/spec/REQUIREMENTS.md` (the 400-line soft limit) and RULE 1 in `docs/CONTRIBUTING.md` (the merge-blocking rule) | `AGENTS.md` and `docs/quality/QUALITY.md` link to it instead of restating it |
| The document index | `docs/README.md` | — |
| What changed | `CHANGELOG.md` | — |
| Terminology | `GLOSSARY.md` | — |

### What "duplicate" means here

**Duplicating a number** — writing "400 lines" in five files — is a
violation. Each of those five copies becomes a place where the number can go
stale.

**Repeating a value with a link** is fine and is often necessary for
readability:

```markdown
The RTSP message budget is 1 MB (`SECURITY.md` §2).
```

The number may appear in the sentence, but the *normative* statement — the
rule that it is 1 MB and what happens when it is exceeded — lives in exactly
one file. If the number changes, only that one file has to change.

### When two documents seem to need the same fact

Split the difference by question:

- *What does the product do?* → `docs/spec/`
- *How is it built?* → `docs/ARCHITECTURE.md`
- *Is it good enough?* → `docs/quality/QUALITY.md`
- *What do I have to do to change it?* → `docs/CONTRIBUTING.md`,
  `docs/WHERE_TO_CHANGE.md`
- *What may it never do?* → `PRIVACY.md`, `SECURITY.md`
- *What did we decide and why?* → `docs/decisions/`

If a sentence fits into none of these, it probably does not belong in a
document at all.

---

## 3. The archive rule

[`docs/archive/`](archive/) holds the **superseded** documentation. It is a
history, not a second source of truth, and nothing in it is binding.

### When a document moves into the archive

A document moves into `docs/archive/` when **all** of the following are true:

1. It has been **replaced** by a newer version, or the feature/design it
   describes has changed substantially.
2. The replacement is **in place and linked** from `docs/README.md` and from
   the places that used to link to the old document.
3. The document is **no longer normative** — nothing in the current build
   depends on it.
4. The replacement itself is **stable** — it is not a draft that may still be
   rewritten.

### When a document does *not* move

- **A document that is still true.** Fix it; do not archive it.
- **A draft that was never accepted.** Delete it. An archive is a record of
  decisions that were taken, not of drafts.
- **A document that is superseded but still referenced.** Migrate the
  references first, then archive it in the same change.
- **A working document that is still being written.** It stays where it is,
  clearly marked as a draft.

### The naming convention

An archived document keeps its name and gets the date of the **archiving**
change appended:

```text
REQUIREMENTS_v2.2_2026-10-24.md
TECHNICAL_SPEC_v1.2_2026-10-24.md
```

The date is the date the document was archived, not the date it was written —
the written date is already in the document header.

### How an archived document is marked

Every archived document gets this banner directly under its title:

```markdown
> **ARCHIVED — superseded on YYYY-MM-DD.**
> This document is kept for historical reference only. It does **not** describe
> the current build. The current version is <LINK TO THE CURRENT DOCUMENT>.
> Do not follow this document for new work.
```

Three properties of this banner matter:

1. It is **at the top**, so a reader cannot miss it.
2. It names the **current** document, so the reader has somewhere to go.
3. It says what the document is *not* — that it is not the instruction to
   follow.

### Reading an archived document

An archived document explains **why** something was decided. It is the right
place to look when you want to know the history of a decision — which
alternative was rejected and at what cost. It is the wrong place to look for
the current behaviour: for that, read the live document the banner points to.

### The current state of the archive

`docs/archive/` is currently **empty**: no document has been superseded yet
since the repository was set up. `docs/archive/README.md` records the
convention and must be updated in the same change as the first document that
moves in.

---

## 4. House rules for writing

These are not gates; they are what makes a document usable by the person who
reads it six months from now, usually on a phone, usually in a hurry.

- **Write for a beginner.** Explain the term the first time you use it, or
  link to [`GLOSSARY.md`](../GLOSSARY.md).
- **Give the exact command.** "Run the tests" is not an instruction;
  `./gradlew :test-runner:test` is.
- **Never name a file, a path, a link or a command that you have not checked.**
  A dead link costs more trust than a missing sentence.
- **Prefer a table to a paragraph** when the reader is looking for one fact.
- **State the limits.** A document that only describes the happy path is
  misleading. Write down what does not work and why.
- **English.** The whole repository is English, including this policy.
