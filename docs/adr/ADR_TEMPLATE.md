# ADR-XXXX — <Title>

> Copy this file, rename it to `ADR-XXXX-<short-slug>.md` and fill it in.
>
> **Where the record goes:** the existing records live in
> [`../decisions/`](../decisions/). New records use the same folder until the
> migration described in the handover note is done; the numbering continues
> after ADR-004. Do not renumber the existing records.
>
> **Rules**
>
> - An ADR records a decision that was **taken**. It is written afterwards,
>   not before.
> - An ADR is **never edited**. A decision that changes is a *new* ADR that
>   supersedes the old one, and the old one gets its status changed to
>   `superseded by ADR-YYYY`.
> - If the decision changes a requirement, a **CR** is needed as well — see
>   [`../cr/CR_TEMPLATE.md`](../cr/CR_TEMPLATE.md).
> - The alternatives that were rejected are the most valuable part. Do not
>   leave this section empty.

---

**Status:** proposed | accepted | superseded by ADR-YYYY
**Date:** YYYY-MM-DD
**Owner role:** <!-- Architect, Product, Security … -->
**Reviewer roles:** <!-- the review matrix in ../../AGENTS.md decides who -->
**Related requirement IDs:** <!-- FR-xx / NFR-xx, or "none" -->
**Related ADRs:** <!-- ADR-00x, or "none" -->

---

## Context

What technical problem, constraint or force makes a decision necessary?

Write this so that somebody who arrives in two years understands the
situation. Include what made the obvious solution impossible, and which
constraints are real (a protocol requirement, a platform limitation, a
privacy promise) and which are merely preferences.

For PhairPlay, the constraints that come up most often are:

- the product promise: **local network only, no ads, no analytics, no
  telemetry** (NFR-11);
- the hardware: the television decodes with `MediaCodec`, **no software
  fallback** (FR-33);
- the protocol: the sender decides the parameters, so **every byte from the
  network is untrusted input** (NFR-12);
- the platform: some APIs (for example `WifiP2pManager.setWfdInfo()`) are
  hidden system APIs that a sideloaded app cannot use.

---

## Decision

What exactly was decided?

State it so that a reader can tell, from this section alone, what the code
must look like tomorrow. Name the packages, the classes and the commands —
not "a service" but "a foreground service in `com.phairplay.service` that …".

If the decision has parts, number them. If the decision has an explicit
non-goal, write it down here: *"This decision does not …"*.

---

## Consequences

### Positive

-

### Negative / trade-offs

-

### What this makes impossible or harder

-

---

## Alternatives considered

For each alternative: what it was, why it was not chosen, and what it would
have cost.

| Alternative | Why it was not chosen | What it would have cost |
|---|---|---|
| | | |

---

## Verification

How do we know the decision was implemented and works?

| What | How it is checked | Where the evidence lives |
|---|---|---|
| <!-- e.g. "the new package is independent of the AirPlay stack" --> | <!-- e.g. a unit test, a review check --> | <!-- the test name, the file --> |

---

## Follow-up

- [ ] Open points, with the reason they are still open.
- [ ] The work that has to happen before this decision is complete.
