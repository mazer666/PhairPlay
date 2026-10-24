# CR-XXXX — <Title>

> Copy this file, rename it to `CR-XXXX-<short-slug>.md` and fill it in.
>
> **When a CR is needed**
>
> A CR is the way to propose a change to something that has already been
> decided: a requirement, a codec, a budget, a severity grade, an architecture
> decision, or a rule in [`../CONTRIBUTING.md`](../CONTRIBUTING.md).
>
> **A CR is not needed** for a bug fix, for a refactoring, for a new test, or
> for a documentation correction that does not change a rule. Those are pull
> requests.
>
> **A CR is always needed** before the implementation starts. Implementing
> first and writing the CR afterwards turns the CR into a rubber stamp.

---

**Status:** proposed | approved | rejected | implemented
**Date:** YYYY-MM-DD
**Proposer role:** <!-- the role that asks for the change -->
**Reviewer roles:** <!-- from the review matrix in ../../AGENTS.md -->
**Related requirement IDs:** <!-- FR-xx / NFR-xx, or "none" -->
**Related ADRs:** <!-- ADR-00x, or "none" -->
**Related CRs:** <!-- CR-XXXX, or "none" -->

---

## Problem

What is wrong today — with the requirement, the rule, the budget or the
decision?

Describe the situation, not the solution. Include the evidence: the failing
test, the user complaint, the measurement, the finding ID.

---

## Requested change

What exactly should be different afterwards?

Write it as a rule, so that it can be checked later. "The file-size limit
becomes 500 lines soft and 600 lines hard, measured with
`wc -l` over `app/src/main/kotlin`" is checkable. "The files get smaller" is
not.

If the change adds or relaxes a number, give the new number **and** say where
it becomes the single source of truth.

---

## Reason

Why is this the right change?

- What does it buy — for the user, for the project, for a contributor?
- What does it cost?
- What happens if we do nothing?

---

## Impacted requirements

| ID | Current wording (abridged) | After the change |
|---|---|---|
| | | |

If no requirement changes, say so explicitly: *"No requirement changes; this CR
changes a process rule."*

---

## Impacted architecture

Which packages, boundaries or decisions are affected?

- **Packages touched:** <!-- com.phairplay.airplay, com.phairplay.dlna … -->
- **Boundaries:** <!-- does the change cross a package boundary? -->
- **Dependencies:** <!-- does it add a dependency? -->
- **Protocol isolation:** <!-- does one protocol start to depend on another? -->
- **ADRs to write or supersede:**

---

## Impacted platforms

| Platform | Affected? | Notes |
|---|---|---|
| Google TV (Android 10+) | | |
| Fire TV (Android 7.1+) | | |
| macOS sender | | |
| iOS / iPadOS sender | | |
| Windows sender | | |
| Other UPnP controller | | |

---

## Milestone impact

- **Milestone:** <!-- Mxx -->
- **Does this move a milestone status?** yes / no — if yes, which one and to what
- **Does this change the scope of a milestone?** yes / no
- **Does it add work to a milestone that is already closed?** yes / no

---

## Acceptance and test changes

| What | How it is verified | Pass condition |
|---|---|---|
| | | |

Every criterion needs a **How to Verify** and a **Pass Condition**. "Works
well" is not a criterion. "Tested manually on the TV" is only allowed
together with the exact script.

Which existing tests have to change, and why?

---

## Risks

| Risk | How likely | What we do about it |
|---|---|---|
| | | |

Include the risk to the **privacy promise** and to the **security
guarantees** explicitly, even when the answer is "none".

---

## Decision

**approved** / **rejected** / **approved with conditions** / **deferred**

**Decided by:** <!-- the role -->
**Date:**

**Conditions:**
-

**Follow-up:**
-
