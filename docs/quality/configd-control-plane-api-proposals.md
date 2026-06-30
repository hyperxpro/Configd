# configd-control-plane-api — §2 NO-TOUCH proposals (REVIEW ONLY, not applied)

Conservative idiomatic-Java quality pass (branch `quality/cpapi`). These observations are in the
§2 no-touch zone (ACL/auth/routing/audit/rate-limit decision & identity code, byte-identity-pinned),
so they are **proposed, not applied**. Each is behavior-preserving *if* applied carefully; a
divergence-analyst / security-reviewer must gate any of them. Ordered high→low value.

Nothing here changes an authorization, routing, rate-limit, or audit-record outcome, nor any
serialized/wire byte. They are redundant-work / readability nits only.

---

## P1 — AuditLog: `canonicalBytes` is computed twice per `record()` (redundant CPU + alloc)

`AuditLog.record()` computes the canonical block once for the chain MAC (line ~199), then calls
`encode(rec)` which recomputes the *identical* canonical block (line ~387) to frame it for disk.
So every audited control-plane mutation re-encodes its 5 fields twice.

- **Proposal:** compute `canonical` once in `record()` and thread it into `encode` (e.g.
  `encode(Record, byte[] canonical)`), keeping the public `encode(Record)` if any other caller needs it.
- **Byte-identity argument:** `canonicalBytes` is a pure function of `(ts, actor, action, target,
  outcome)`; the value passed in equals what `encode` would recompute. The framed bytes, the CRC, and
  the chain MAC are unchanged. Re-check: the canonical passed to `encode` is derived from the same
  `Record` fields, with no reordering.
- **Caveat:** §2 (security audit record / byte layout). Pinned by `AuditLogTest`. Verify that test plus
  `verifyPersisted` round-trip stays green.

## P2 — AuditLog: `decode()` re-encodes every field via `utf8Len` to advance `pos`

In `decode()`, after `readField()` returns each string the cursor is advanced with
`pos += 4 + utf8Len(field)`, where `utf8Len(s) = s.getBytes(UTF_8).length` — i.e. each field is
UTF-8-re-encoded purely to recover a length that was *already* read as the 4-byte frame prefix. Four
re-encodes (+ four throwaway `byte[]`) per decoded record.

- **Proposal:** read the i32 length prefix directly (`int len = readInt(frame, pos); String s =
  new String(frame, pos+4, len, UTF_8); pos += 4 + len;`), removing `utf8Len` entirely (its only 4
  call sites are here).
- **Byte-identity argument:** the stored prefix *is* the UTF-8 byte length, so `len == utf8Len(s)` for
  valid round-tripped UTF-8; cursor advancement and the under/overrun check are identical. The decoded
  `Record` is bit-for-bit the same. Re-check: keep the existing `len < 0` / bounds guard semantics that
  `readField` enforces.
- **Caveat:** §2 (audit decode path; `verifyPersisted`). On the read/verify path only (cold), but it is
  the integrity-verification path — gate with `AuditLogTest`.

## P3 — RateLimiter: `maxScaled` recomputed every CAS iteration (hot admission loop)

`tryAcquire(int)` recomputes `long maxScaled = (long)(maxPermits * SCALE)` on every spin of the
lock-free CAS loop, though `maxPermits` and `SCALE` are both final. The same value is already computed
once at construction for `storedPermitsScaled`'s initial value.

- **Proposal:** precompute `private final long maxPermitsScaled = (long)(burstPermits * SCALE);` in the
  ctor and use it in the loop.
- **Byte-identity argument:** identical deterministic arithmetic (`final double * final long`, same
  cast), so every admission decision is unchanged.
- **Caveat:** §2 + measured admission decision path (`RateLimiterTest`). Per the brief, a hot-path
  micro-opt that needs a benchmark to confirm it helps is propose-only. The decision result is provably
  identical; only the per-iteration work changes. Validate with `RateLimiterTest` and a JMH spot-check
  before landing — the EC2 measurement runs on this path.

## P4 — ConfigWriteService: inline fully-qualified names instead of imports (readability)

The file imports `java.util.List`/`Objects` but then uses fully-qualified
`java.util.function.Supplier`, `java.util.concurrent.ConcurrentHashMap`, and
`java.nio.charset.StandardCharsets.UTF_8` inline (fields ~169–171, ctor ~204–206, encode/put ~241/351).

- **Proposal:** hoist these to `import` statements for consistency with the file's own style.
- **Byte-identity argument:** import vs FQN is identical bytecode — purely lexical.
- **Caveat:** §2 (routing, byte-identical at N=1). No logic touched, but the file is no-touch; left for
  the reviewer. Lowest priority.

## P5 — AclService: wildcard `import java.util.*;` (readability)

`AclService` uses a wildcard `import java.util.*;` whereas the rest of the module uses explicit imports.

- **Proposal:** replace with the explicit imports actually used (`ArrayList, Collection, Collections,
  EnumSet, HashSet, List, Map, Objects, Set`).
- **Byte-identity argument:** identical bytecode; lexical only.
- **Caveat:** §2 (ACL decision core, byte-identity proven by `AclServiceByteIdentityDifferentialTest`).
  Even a no-op import change here invites a security re-gate; lowest priority, listed only for completeness.

---

### Reviewed and deliberately found nothing to change (no proposal)

- **AclService** `isAllowed` / `coversTarget` / `effectiveRules` / `accumulateOwnGrants` — the inline
  role-union (vs the extracted `unionRoleNames`) is a *deliberate, documented* hot-path allocation
  choice; `coversTarget`'s no-early-return is documented "clarity over micro-opt for a security crux."
  Correct as-is.
- **ConfigReadService**, **AuthInterceptor**, **ReplayGuard**, **Policy/PolicyRule/Role/ConfigPolicy**,
  **PolicySerializer**, **PolicyParseException** — clean, idiomatic, immutable, defensively copied.
  `PolicySerializer.split("\n",-1)` / `split(",",-1)` hit the JDK single-non-meta-char fast path (no
  `Pattern` compile), so no nit there.
