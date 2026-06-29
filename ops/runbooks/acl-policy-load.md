# Runbook: ACL Config-Policy Load Rejected

**Alert:** `ConfigdAclPolicyLoadFailed` (warn, `increase(configd_acl_policy_load_failed_total[15m]) >= 1`, 5m)
**Severity:** warn (policy updates silently frozen — NOT an outage, but `_acl/` changes are not taking effect)

The config-sourced authorization policy under the reserved `_acl/` key subtree
failed to (re)load. The loader is **fail-closed-to-last-good**: it keeps the
last successfully-loaded policy unchanged — it never deny-alls (lockout) and
never allow-alls (open). So nothing breaks *right now*, but **every `_acl/`
policy update is frozen** until the rejected input is fixed: each rebuild
re-reads the whole `_acl/` subtree and re-rejects it (the parse is
all-or-nothing by design — a partial/truncated policy is more dangerous than a
stale-but-coherent one).

The break-glass `root` principal's authority is its static in-memory grant and
is **un-carveable** by any config policy (it is never a subject of a config
role, and the loader rejects any binding of `root`), so root can always still
write/delete `_acl/` to repair the situation — see
`AclConfigPolicyLoaderTest#rootIsUncarveableByAnyConfigRole`.

## Symptom

- `ConfigdAclPolicyLoadFailed` warns after ≥ 1 rejected load in 15 min.
- `configd_acl_policy_load_failed_total` steps up; `configd_acl_policy_reload_total`
  does **not** advance (no new successful load).
- A SEVERE log line: `ACL config policy REJECTED — keeping last-good policy (no swap): <reason>`
  naming the structural problem (unknown effect/capability, malformed role or
  binding line, an unrecognized `_acl/` key shape, or a reserved name —
  `_acl/roles/admin` / `_acl/bindings/root`).
- A recently-applied `_acl/` change appears to have "not taken effect"
  (the authorization decision is still the previous one).

## Diagnosis

1. **Find the offending key and reason** in the leader/applier logs around the
   alert window — the SEVERE line carries the parse/validation message from
   `PolicySerializer` / `AclConfigPolicyLoader.validateReserved`.
2. **Classify it:**
   - **Malformed shape / grammar** — an unknown `effect` (not `allow`/`deny`),
     an empty/unknown capability, a role line missing fields, or an `_acl/` key
     not matching `roles/<role>` or `bindings/<principal>`.
   - **Reserved name** — a config policy tried to define role `admin` or bind
     principal `root` (both rejected by design; the un-carveable-root guard).
   - **Delivered out-of-band** — write-time validation (Seam 2b) rejects most
     malformed `_acl/` writes pre-commit with a 400, so a *committed* poison key
     most likely arrived via **snapshot install or WAL replay** (a key that
     pre-dates the write-time gate, or was installed wholesale).
3. **Confirm it is persisted, not transient.** A single rebuild can fail on a
   racing read; a *sustained* failure (the `for: 5m`) means a poison key is
   committed in the store.

## Remediation

1. **Repair the policy as `root`** (break-glass; root is un-carveable):
   - If a single key is malformed, **overwrite it** with a corrected value, or
     **`DELETE`** it: `DELETE /v1/config/_acl/<...>` with the root bearer token.
     Removing the poison key lets the next rebuild parse the whole subtree
     cleanly.
   - Verify the fix took: `configd_acl_policy_reload_total` advances and the
     SEVERE line stops on the next `_acl/` apply.
2. **Do NOT disable auth to "get unstuck."** Auth-off does not load `_acl/`
   policy and refuses `_acl/` writes (the bring-up poison footgun is closed);
   it cannot help here and removes all enforcement.
3. **Root cause the source.** If the poison key arrived via snapshot/replay,
   identify the writer/tooling that produced it and add the corrected policy to
   that source so a future restore does not re-introduce it.

## Escalation

If `root` itself cannot reach `_acl/` (the break-glass is somehow not working),
that is a **separate, higher-severity** problem — treat as a control-plane
auth incident and page the on-call security owner; see
[control-plane-down.md](control-plane-down.md).
