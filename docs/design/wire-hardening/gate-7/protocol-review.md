# Gate 7 — Protocol/spec lane: independent RFC⇄code re-validation + coverage completeness

**Scope.** Final-pass, READ-ONLY re-validation of the wire-hardening arc's protocol surface: (1) does the
Gate-5 RFC still match the codec source byte-for-byte across both planes; (2) is a *safe* conformant client
buildable from the RFC alone (safe against a hostile server); (3) is every frame type + every reject path
actually covered by a fuzz OR integration OR E2E test. Independent of Gate 5's own `rfc-validation.md`.

**Sources re-read against code (not trusted from Gate 5):** `EdgeFrameCodec.java`, `EdgeFrame.java`,
`WatchCursor.java`, `ErrorCode.java`, `FrameType.java`, `CommandCodec.java`, `EdgeSnapshotCodec.java`
(edge); `FrameCodec.java`, `RaftMessageCodec.java`, `RaftWireProtocol.java`, `MessageType.java`,
`PeerIdentityPolicy.java` (Raft); the RFC `06-wire-framing.md` (incl. new §13), `07-errors.md`,
`02-watches.md`, `04-data-plane.md`; the gate-1 catalogs, gate-3 fuzz-coverage, gate-4 e2e-coverage.

**Verdict.** The RFC is materially accurate and a safe conformant client IS buildable from it. All seven
Gate-5 drift fixes (D1–D7) are correct against current code. I found **one Gate-5-missed drift** (NOTIFY
decode cap — the RFC documents pre-WH-14 behaviour), **one omitted bound** (`MAX_PREFIXES` on a
client-emitted frame), and **two low doc-accuracy nits** (stale §3 line citations; one coverage-doc
misstatement). None is a wire-format defect; the byte layouts and value bounds all match. Coverage is
complete: every frame and every reject path (WH-01…15) has a fuzz, integration, or E2E test.

---

## Ranked findings

### F1 — rfc-drift, MEDIUM — NOTIFY decode DOES enforce the 256 KiB cap; RFC F6-3 says it does not (Gate-5 missed the WH-14 fix)
- **RFC location:** `06-wire-framing.md` **F6-3** (the `NOTIFY` intro, ~line 298–299): *"the **decoder**
  enforces `count ≤ 64` but **not** the 256 KiB cap — a driver **MUST** bound a received `NOTIFY` by the
  **2 MiB frame cap** (F3-2), not assume 256 KiB"*.
- **Code truth:** `EdgeFrameCodec.decodeNotify` `:782–787` — `payloadBytes > MAX_NOTIFY_BATCH_BYTES`
  (256 KiB, `:120`) throws `FRAME_TOO_LARGE`, **before** the `count ≤ 64` check `:788–791`. Both the
  element cap AND the 256 KiB payload cap are enforced on decode.
- **Why this is real drift:** this is exactly the **WH-14** hardening (findings-register `:120–122`, a
  Gate-2 fix; gate-3 fuzz-coverage sweeps the "NOTIFY byte-cap (WH-14 `MAX_NOTIFY_BATCH_BYTES`)"; gate-4
  lists WH-14 covered). The RFC — written in Gate 5, *after* WH-14 landed — reproduced the stale
  **pre-Gate-2 gate-1 G2** description instead of the post-fix behaviour. Gate-5's own validation table
  even marks F6-3 "✓" while its prose contradicts the code it cites.
- **Client impact:** a *receiving* driver built to the RFC (allowing up to 2 MiB) is still safe (the real
  server never emits >256 KiB), so this is not a hostile-server safety hole. But it is a false normative
  statement: a strict validator / second-implementation built from the RFC would be non-conformant, and
  the "not assume 256 KiB" instruction is simply wrong.
- **Fix:** rewrite F6-3 to state the decoder enforces **both** `count ≤ 64` **and** encoded
  `payload ≤ 256 KiB (MAX_NOTIFY_BATCH_BYTES)`, rejecting the latter as **`FRAME_TOO_LARGE`**; drop the
  "not assume 256 KiB / bound by 2 MiB" clause. Add the `NOTIFY > 256 KiB ⇒ FRAME_TOO_LARGE` reject to the
  §4 reject taxonomy / §07 mapping.

### F2 — rfc-gap, LOW — SUBSCRIBE `MAX_PREFIXES = 4096` cap is undocumented (a bound the server enforces on a client-emitted frame)
- **Code truth:** `EdgeFrameCodec.decodeSubscribe` `:720–723` rejects `prefixCount > MAX_PREFIXES` (4096,
  constant `:129`) as `FRAME_CORRUPT`, in addition to the `prefixCount * 4 > remaining` tight bound
  `:717`.
- **RFC location:** **F6-1** (SUBSCRIBE layout, ~line 241–261) and the **F10-2** caps table (~line 560–567)
  omit it. `SUBSCRIBE` is a genuinely client→server frame, so a driver author encoding from the RFC alone
  cannot learn that a SUBSCRIBE with >4096 prefixes is rejected. (Contrast: the 1024-byte watch-target cap
  IS in F10-2.)
- **Client impact:** low — 4096 is far above any realistic prefix set — but it is a real omitted
  server-enforced bound on a driver-produced frame, exactly the "RFC omits a bound the code enforces"
  class the review targets.
- **Fix:** add `MAX_PREFIXES = 4096` to the F6-1 `prefixCount` annotation and/or the F10-2 caps table.

### F3 — rfc-drift, LOW — stale `file:line` citations in §3 (F3-1 / F3-2)
- **RFC location:** **F3-1** cites `EdgeFrameCodec.decode` **:557–640**; actual decode is **:597–683**.
  **F3-2** cites `decodeCursor :808–811` (actual bound `:914`), `decodeNotify :693/:709` (actual
  `:788`/`:805`), `decodeSubscribe :667–669` (actual `:717–719`).
- **Assessment:** the *described* decode order and bound-then-allocate discipline are **correct**; only the
  line pointers are stale (~40–110 lines low — pre-dating a code shift). Notably the **gate-5 validation
  table** citations ARE current (`decodeSubscribe :729–733`, `encodeSubscribeInto :306–327`,
  `encodeCursorInto :445–453`, `decodeCursor :903–926`), so the drift is confined to the §3 prose. This
  undermines the RFC's "every check tied to a cited file:line" claim for an auditor who follows them.
- **Fix:** refresh the F3-1 / F3-2 inline line numbers to the current source.

### F4 — coverage-doc nit, LOW — gate-3 fuzz-coverage misstates the SNAPSHOT_CHUNK decode cap
- **Doc location:** `gate-3/fuzz-coverage.md` (~line 97–99): *"The `SNAPSHOT_CHUNK` **decode** cap is
  bounded by `MAX_EDGE_FRAME_SIZE`"*.
- **Code truth:** `EdgeFrameCodec.decodeSnapshotChunk` `:859` rejects `len > MAX_SNAPSHOT_CHUNK_BYTES`
  (**1 MiB**, `:114`) as `FRAME_TOO_LARGE` — a dedicated cap tighter than the 2 MiB frame cap. RFC **F6-5**
  is correct ("≤ 1 MiB = MAX_SNAPSHOT_CHUNK_BYTES"); only the coverage doc's prose is wrong.
- **Assessment:** coverage is not reduced — the 1 MiB check is on the arbitrary-byte oracle path — the
  statement just understates the enforced strictness.
- **Fix:** correct the sentence to `MAX_SNAPSHOT_CHUNK_BYTES` (1 MiB).

---

## What was independently verified CORRECT

### Gate-5 drift fixes (D1–D7) — all confirmed against current code
- **D1** SUBSCRIBE `topologyEpoch` — `encodeSubscribeInto` `:318` writes it between prefixes and
  `resumeCursor` on all versions; `decodeSubscribe` `:729–733` rejects `0`. Golden hex in F6-1 matches. ✓
- **D2** cursor `topologyEpoch` prefix + 12-byte floor — `encodeCursorInto` `:445–453`, `decodeCursor`
  `:903–926` (floor `:905`, epoch≠0 `:909`, `count*12 ≤ remaining` `:914`). ✓
- **D3/D7** ErrorCode = **12** values incl. `STALE_TOPOLOGY(12)` — `ErrorCode.java` `:14–107`;
  `07-errors.md` retitled "the 12", codes 1..12, row 12 present. ✓
- **D4** version set `{0x01,0x02,0x03}` — `decode` `:634–635`; 07-errors E3-1 row 1 fixed. ✓
- **D5** first-frame deadline (F10-1d) — `firstFrameDeadlineMs` default 10 000 ms, disarmed after first
  routed frame (mirrors Raft `inboundReadTimeoutMs`). ✓
- **D6** Raft `HEADER_SIZE = 26` + reserved-`epoch` MBZ — `FrameCodec` `:76`, `:314–323`. ✓

### High-risk frames — byte layout + every bound match the codec (file:line)
- **Edge envelope F2-1:** HEADER 6 / TRAILER 4 / min 10 / MAX 2 MiB / CRC32C over `[0,L-4)`
  (`:101–114`, `:604–627`). Decode order F3-1 = len-bounds → `L==data` → **CRC before ver/type** → version
  (+pin) → type → payload → strict-end (`:597–683`). ✓
- **SUBSCRIBE / SUBSCRIBE_OK (+`0x03` trailing bytes), NOTIFY layout, SNAPSHOT_BEGIN/CHUNK/END, CURSOR_ACK,
  HEARTBEAT, ERROR_CLOSE** — all layouts + bounds match (`:708–879`). `failoverResumeCursor ≥ -1` sentinel
  = `EdgeFrame.Subscribe` ctor `:118`. ✓
- **Cursor F8**, **WATCH_CREATE/CREATED/EVENT/PROGRESS** incl. the `val_len == -1` DELETE sentinel — match
  (`:903–1018`); §02 §5.4 documents the sentinel + kind coupling. ✓
- **F7-1 CommandCodec** PUT/DELETE/BATCH, `u16` keyLen, `i32` valueLen ≤ 1 MiB (`MAX_VALUE_SIZE`),
  count ≤ 10000 (`MAX_BATCH_COUNT`), blank-key reject — match. ✓
- **Raft §13:** envelope (`HEADER 26`, ver `0x02`, `gid u32`, `term u64`, `epoch u64 MBZ`, MAX 16 MiB),
  decode order (`FrameCodec.decode :263–330`), sender-id prefix (`SENDER_ID_SIZE = 4`), `MessageType`
  0x01–0x13 with 0x08–0x0E dormant, **AppendEntries** (`:388–436`), **InstallSnapshot** (`:514–562`,
  offset≥0, 4 MiB/blob, optional configData, strict-end), **InstallSnapshotResponse** (optional-trailing
  `nextExpectedOffset`), **CoalescedHeartbeat** (record 40 B, sentinel gid/term 0, dup-gid reject,
  strict-end), **Witness** (29-byte body, exact field order, `from` = transport prefix). Caps confirmed:
  10000 / 1 MiB / 4 MiB / 1024. Timeouts: connect 1 s / handshake 2 s / read-idle 15 s / maxInbound 1024 /
  outbound 1024. ✓

### Safe-client-buildability — buildable, with the F1/F2 caveats
- Every server→client hostile vector is bounded by a documented rule: hostile snapshot stream (F6-4 +
  "reassembly REQUIRED" verify-chunkCount/totalBytes-or-discard + F3-2 bound-before-alloc ceiling), hostile
  NOTIFY (count ≤ 64 + F3-2), hostile cursor in WATCH_PROGRESS/CANCELED (F8 floor + ascending gid + count
  bound), untrusted `ERROR_CLOSE.message` (F6-9 sanitize). Version pin + fail-closed forward-compat (F4 /
  F11) + skip-unknown only in the snapshot TLV trailer are specified. The only omissions are F1 (a
  misstated NOTIFY cap — safe-lenient for a receiver) and F2 (an undocumented encoder-side cap).

### Coverage completeness — complete
- **Every edge frame (18):** fuzz `F`+`P` in gate-3 `EdgeFrameCodec`/`EdgeSnapshotCodec` tables + INT in
  gate-4. **Every Raft decode surface (12):** gate-3 `RaftMessageCodec` table `F` + gate-4 RW/INT. Dormant
  0x08–0x0E have no codec (nothing to fuzz) and their WH-10 drop is proven E2E
  (`HostilePeerInjectionE2ETest`).
- **Every reject path WH-01…15:** present in gate-4's per-reject-path table with a named test. WH-04 does
  not exist as a standalone finding (folded into WH-06 per the register's execution plan); WH-16 is a
  document-and-defer aggregate-ceiling item (F10-2a / F13-8), not a reject path. No frame and no reject
  path is testless.

---

## Recommendation
Land the four doc fixes (F1 medium, F2–F4 low) — all are docs-only, no code change, golden fixtures
untouched. F1 is the one worth doing before shipping the RFC as the conformance authority, since it is a
false normative statement about an enforced cap. After that the protocol surface is release-grade: both
planes are specified field-by-field with offsets/widths/endianness/caps tied to live codec checks, and the
coverage is complete.
