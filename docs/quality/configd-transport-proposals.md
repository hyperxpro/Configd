# configd-transport — §2 NO-TOUCH review proposals

These are idiomatic/GC observations on the **review-only (§2)** files of `configd-transport`
(wire codecs, wire constants, TLS, decoded-wire value types). They are recorded here and
**NOT applied** because each sits on a measured/proven/byte-identical surface. Anything here needs a
divergence-analyst + (for the perf item) a benchmark sign-off before it is touched.

The EC2-measurement byte-identity surface is the **encode** path (pinned by
`wirecompat/WireCompatGoldenBytesTest`, which only encodes). Note that the one substantive item
below is on the **decode** path, which the golden test does NOT cover — extra reason to gate it.

---

## P1 (substantive, GC) — `FrameCodec.decode`: read the CRC trailer with an absolute get

`FrameCodec.java`, `decode(byte[])`, ~line 298:

```java
int trailer = ByteBuffer.wrap(data, crcOffset, TRAILER_SIZE).getInt();
```

`ByteBuffer.wrap(...)` allocates a fresh `HeapByteBuffer` wrapper object purely to read 4 bytes.
Since a big-endian `buf = ByteBuffer.wrap(data)` is already in hand, the same four bytes can be read
with an **absolute** get that allocates nothing and does not disturb the relative read position:

```java
int trailer = buf.getInt(crcOffset);
```

- **Byte-identical:** absolute `getInt(crcOffset)` reads `data[crcOffset..crcOffset+3]` big-endian —
  exactly what the `wrap(data, crcOffset, 4).getInt()` reads. It does not advance `buf`'s position, so
  the subsequent relative reads (`buf.get()` version, type, groupId, term, reserved-epoch, payload)
  are unaffected.
- **Why propose, not apply:** `FrameCodec` is a §2 wire codec on the measured read path; the class
  javadoc asserts a decode allocation budget ("exactly one `byte[]` for the payload and one `Frame`").
  This change *reduces* allocation (removes one wrapper), but moving a measured floor — even
  downward — must be confirmed by the allocation/divergence oracle, not assumed.
- **Doc nit (same spot):** the javadoc's "does not allocate beyond it" is already imprecise — `decode`
  also allocates the two `ByteBuffer` wrappers (the main `wrap` at the top and this trailer `wrap`)
  and the per-call `CRC32C`. If P1 lands, tighten the doc to match; if not, the doc could note the
  wrappers.

## P2 (cosmetic) — collapse redundant fully-qualified names to the file's own import style

Pure readability; zero behavioral effect. Left for the same maintainer who owns these §2 files.

- `BatchEncoder.java` ~line 101: `var ready = new java.util.HashSet<NodeId>();` → add
  `import java.util.HashSet;` and use `new HashSet<NodeId>()` (the rest of the file imports its
  collections).
- `TlsConfig.java`: the record components and factories use `java.util.List` / `java.util.List.of(...)`
  fully qualified → `import java.util.List;` and use `List` / `List.of(...)`. (Touching `TlsConfig`
  at all is extra-sensitive — it carries cipher/protocol selection — so this stays a proposal even
  though it is only a spelling change.)

---

## Reviewed and deliberately left with NO proposal

- `RaftWireProtocol.java` — the single-allocation `encodeWire` (manual big-endian sender-id write +
  `System.arraycopy`) is the DR-N17 measured path; idiomatic as-is.
- `MessageType.java` — `BY_CODE` lookup table + bounds-checked `fromCode`; codes are on the wire.
- `InboundMessage.java`, `FrameCodec.Frame` — clean validating records.
- `TlsManager.java` — already uses try-with-resources for both keystore/truststore streams; volatile
  context publication is correct.
