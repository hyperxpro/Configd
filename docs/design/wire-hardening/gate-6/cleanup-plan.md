# Gate 6 — wire-docs cleanup plan

**Scope.** The wire/protocol docs and this arc's own scaffolding — not the whole 150-doc tree.
Concretely: docs the RFC now supersedes for the wire format; leftover investigation/scaffolding
docs; the arc's own working docs under `docs/design/wire-hardening/`; and any AI-slop-shaped wire
doc. Gate 5 made `docs/rfc/driver-protocol/` the normative wire spec for both planes; this gate
reconciles the surrounding docs against it.

**Headline finding.** The RFC did **not** supersede any deletable standalone wire doc. There is no
old wire-framing / frame-format document to remove: the RFC is the only wire-spec document, and it
*integrated with* the two wire ADRs (0028, 0029) by citing them, not by replacing them. So the
**DELETE set for wire docs is empty**. The one real cleanup was that the ADRs did not point forward
to the RFC and could mislead a reader landing on their stale diagrams — fixed below with additive
pointer notes, not deletion. A separate, out-of-arc question (the three `docs/investigation/` files)
is presented for the operator, since two of the three are referenced by shipped ADRs/assessments.

---

## DELETE

**None proposed in this gate.** Every wire doc examined either is the normative spec, is CI/RFC/
runbook-load-bearing, or is genuine arc reference. Details of why each candidate did **not** qualify:

| Candidate considered | Verdict | Why not deleted / where content lives |
|---|---|---|
| `docs/adr/adr-0029-wire-format-v1.md` | **KEEP (CI-load-bearing)** | The wire-compat CI gate's error message cites "ADR-0029 §8.10" (`.github/workflows/ci.yml:385`, and `:373`). Deleting or renumbering it breaks the gate's operator guidance. It is also referenced by RFC §06, `02-watches.md`, `docs/operations/known-limitations.md`, and four other ADRs. It records the framing *discipline* decision — content with no other home. |
| `docs/adr/adr-0028-snapshot-on-disk-format.md` | **KEEP** | Referenced by RFC §06 (F7-2), `CONTRIBUTING.md`, and two snapshot runbooks (`ops/runbooks/restore-from-snapshot.md`, `snapshot-install.md`). Records the snapshot-body/TLV-trailer decision — no other home. Its stale diagram is handled by the RFC's "code wins" note, now mirrored in the ADR. |
| `docs/architecture/architecture.md` (wire summary) | **KEEP** | High-level orientation only (snapshot cap, frame-queue backpressure, transport selection); points at ADR-0043. Not a byte-spec; does not compete with the RFC. |

No standalone `wire-framing.md` / `frame-format.md` / `wire-format.md` exists outside the RFC and
these ADRs (verified by filename sweep and content grep).

---

## KEEP + RESTRUCTURE (edits made in this gate — safe, additive/corrective)

All of the following are **done** and left uncommitted for the lead.

1. **New `docs/design/wire-hardening/README.md`** — a single index tying the arc's gate docs
   (gate-1 … gate-6) together, and stating plainly at the top that the **normative wire spec is the
   RFC, not this directory** — these docs are the reasoning/threat-model/coverage behind it. Points
   to `rfc/driver-protocol/06-wire-framing.md` (edge §1–§12, Raft §13) and `07-errors.md`.

2. **`docs/adr/adr-0029-wire-format-v1.md`** — added a top-of-doc note: this ADR is the canonical
   origin of the framing *discipline* (and the CI gate cites its §8.10), but its concrete diagram
   predates the Gate-2 `HEADER_SIZE = 26` / reserved-`epoch` addition; the byte-authoritative layout
   is the RFC §06 (edge §1–§12, Raft §13); where they disagree, the code/RFC win. Mirrors the RFC's
   own §06 language exactly, so the two docs now agree about their relationship. No content removed.

3. **`docs/adr/adr-0028-snapshot-on-disk-format.md`** — added the parallel top-of-doc note: the ADR
   records the snapshot-body/trailer *decision*; the byte-authoritative on-wire description is RFC
   §06 (F7-2). No content removed.

4. **Arc working docs `docs/design/wire-hardening/gate-1..gate-5/`** — **KEEP as-is.** These are
   genuine reference (catalog, threat model, attack matrix, prior art, findings register, workstream
   specs, fuzz coverage, e2e coverage, RFC validation), spot-checked and free of process residue /
   overclaim; no humanize edits needed. They are now discoverable via the new README index.

---

## UPDATE (docs map — edit made in this gate)

- **`docs/README.md`** — the "Write a client driver" entry now states that the RFC is **the**
  normative wire specification for **both** planes (edge §1–§12 and Raft §13, plus `07-errors.md`),
  validated byte-for-byte against the codecs and golden fixtures; that ADR-0028/0029 record *why*
  the framing is shaped as it is while the RFC records *what the bytes are*; and that where a diagram
  and the RFC disagree, the RFC (and the code) win. This gives a reader the one-hop answer to "what
  is the authoritative wire spec, and how do the ADRs relate to it."

---

## Out of this arc's scope — flagged for the operator (NOT actioned)

### The three `docs/investigation/` files

These are read-only investigation records from *other* arcs (edge fan-out efficiency, encryption
composition, multi-shard watch), not wire-hardening scaffolding. They are pre-ship working docs that
the shipped features (code + RFC §02 + the relevant ADRs) have since superseded. Total ≈ 583 lines
across 3 files. Disposition is a judgment call for the operator, because two of the three are
**referenced** and the repo's convention is to *archive* superseded evidence under `docs/archive/`
("kept out of the way but not deleted", per `docs/README.md`), not delete it:

| File | Lines | Referenced by | Recommended disposition |
|---|---|---|---|
| `edge-fanout-efficiency-2026-07-02.md` | 205 | `docs/adr/adr-0045-...` (as its backing investigation) | **KEEP or archive-with-ref-update** — it is ADR-0045's evidence; do not delete. |
| `encryption-interaction-2026-07-03.md` | 115 | `docs/readiness/production-standard-gap-assessment-2026-07-03.md` | **Archive** (move to `docs/archive/investigation/`, update the one inbound reference) — superseded by ship. |
| `multi-shard-watch-2026-07-02.md` | 263 | *none found* | **Archive** — superseded by the shipped multi-shard-watch feature (code + RFC §02 + `inv-msw-atomic.md`); no inbound reference, so a move is clean. |

Recommendation: if the operator wants these off the active tree, **move all three to
`docs/archive/investigation/`** and fix the two inbound references (ADR-0045 and the gap assessment)
in the same change — a move, not a delete, preserving the evidence per the repo's own archive rule.
This is deliberately left un-actioned pending the operator's call, since it touches two shipped docs
and lies outside the wire-hardening arc.

### `docs/design/frozen-format-v1-2026-07-03.md` and `anchor-witness-peer-quorum-2026-07-04.md`

**Leave alone — explicitly out of scope.** These are the *active* frozen-format arc's design docs
(a different, in-flight workstream), not wire-hardening scaffolding and not wire-protocol spec docs.
Not stale.

---

## CI / build-script reference cross-check

Every DELETE candidate was grepped against the repo, with attention to CI/gate scripts that read
`docs/` as fixtures:

- **`gates/gate-B.sh`** asserts and greps `docs/architecture/raft-threading-contract.md` — not a
  wire doc; untouched.
- **`.github/workflows/ci.yml`** references ADR-0029 (§8.10) in the wire-compat gate's operator
  error message — **ADR-0029 must not be deleted or renumbered.** Untouched (only an additive note
  added, which does not affect the §8.10 anchor or any grep).
- Other `docs/` references in `gates/` and `ops/` point at session captures, runbooks, and the
  contract-test map — none at any doc proposed for deletion (there are none).

**No delete in this plan touches a CI or build script.** The additive edits (new README, ADR pointer
notes, docs-map line) change no path any script reads and add no grep-visible token a gate keys on.

---

## Summary for the checkpoint

- **DELETE set: 0 files.** The RFC integrated with the wire ADRs (citing them) rather than
  superseding a deletable doc; both ADRs are CI/RFC/runbook-load-bearing. There is no stale
  standalone wire-framing doc to remove.
- **Edits made (safe, uncommitted):** new arc README index; forward-pointer notes on ADR-0028 and
  ADR-0029; a docs-map line naming the RFC as *the* normative wire spec for both planes.
- **Operator's call (not actioned):** archive-move the three `docs/investigation/` files (two are
  referenced → update refs in the same change); these are out-of-arc and superseded, but the repo
  archives rather than deletes evidence.
- **CI risk: none.** No proposed deletion (there are none) and no edit touches a doc a gate/CI script
  reads or a token it keys on.
