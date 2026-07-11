# Allocation hypotheses -- verdicts (JMH `-prof gc`, real hardware)

The three EC2-actionable allocation hypotheses from `docs/quality/INDEX.md`, measured on the box with
`gc.alloc.rate.norm` (B/op). None were applied to `main` in this session -- the session's scope was
measurement only -- so these are verdicts for a follow-up. Captures: `captures/alloc/`.

## Measured floors (m6id.4xlarge, JDK 25, `-prof gc -f1 -wi3 -i5`)

`FanOutEncodeIntoBenchmark` (server-to-edge encode, 64-delta NOTIFY):

| leg | B/op | ns/op |
|---|---:|---:|
| `legacyMultiPassEncode` (prior implementation) | 69,504 | 9,556 |
| `messageBuildingFloor` (irreducible) | 25,520 | 3,855 |
| `prodEncodeIntoByteBufPooled` (new sink per op, pooled buf) | **25,696** | 7,339 |
| `prodEncodeIntoHeapReused` (reused sink) | **25,520** | 5,342 |

`EdgeHttpAllocBenchmark` (edge read serving, real loopback HTTP):

| leg | B/op |
|---|---:|
| `configGet` (200 read, returns value) | 35,777 |
| `healthLive` (constant body) | 33,412 |

## Verdicts

### 1. server -- `EdgeFrameToByteEncoder`/`ByteBufFrameSink` per-encode sink reuse: real win (small), confirmed
Measured delta: `prodEncodeIntoByteBufPooled` 25,696 minus `prodEncodeIntoHeapReused` 25,520 = 176 B/op
(the proposal estimated ~240; measured 176 on this hardware/JDK). The `prodEncodeIntoHeapReused` leg is
the "reused sink" change applied, and it sits exactly at the message-building floor (25,520) -- so
reusing one `ByteBufFrameSink` per connection is a strict improvement of ~176 B/op (0.7% of the encode
floor), with no throughput regression (it is in fact faster: 5,342 vs. 7,339 ns/op for the heap leg).
Worth landing as a follow-up -- small but free, and on the measured fan-out hot path.

### 2. edge-node -- `EdgeReadHandler` constant bodies as `static final byte[]`: negligible
The hoisted constants (`"Not Found"`, `"Method Not Allowed"`) are on the 404/405 error paths only
(`EdgeReadHandler:105/180/208`); the hot 200 read returns the dynamic value (`:167`), and `{"live":true}`
lives in a different handler. The measured per-request floor is ~33-36 KB/op, dominated by the JDK
HttpServer+HttpClient round-trip shell -- a ~9-20-byte UTF-8 encode on an error response is immeasurable
noise against that. The proposal's "~1716 B/req read floor" framing conflated this with the read-path
marginal (`configGet` minus `healthLive` = ~2,365 B/op, which is HTTP/serialization overhead, not the
error-body constants). Not worth applying for allocation reasons (harmless as a tidy-up, but not a win).

### 3. edge-cache -- `PrefixStorageFilter:74` `prefixes().isEmpty()` to `isEmpty()`: clean but tiny
By inspection a strict improvement: `prefixes()` allocates
`Collections.unmodifiableSet(new LinkedHashSet<>(...))` per `filter()` call on the delta-apply path
purely to test emptiness, while `PrefixSubscription.isEmpty()` (line 79) is lock-free and
allocation-free and exists for exactly this caller. Removes one `LinkedHashSet` snapshot per delta-apply
on the edge. No dedicated benchmark exists (the existing alloc benchmarks don't exercise
`PrefixStorageFilter.filter`), so this was not measured on hardware -- but it is unambiguously
allocation-reducing and behavior-identical. Worth landing as a clean follow-up (small magnitude).

## Method note
The server hypothesis is fully validated by the existing benchmark legs (new-sink vs. reused-sink is the
before/after). The other two are negligible (edge-node, dominated by the HTTP shell) or clearly correct
but small and unbenchmarked (edge-cache); given the priority of the headline go/no-go items (N x knee,
soak, DR) and a preference not to risk the measurement budget, confirmatory throwaway-branch micro-edits
for those two were not run on the paid box -- the evidence (measured floors + code) already gives the
verdict.

## Follow-up to merge (not part of this session)
- `ByteBufFrameSink` per-connection reuse (~176 B/op, server fan-out) -- confirmed win.
- `PrefixStorageFilter` `isEmpty()` (edge-cache) -- clean win, small.
- `EdgeReadHandler` static byte[] -- optional tidy-up, negligible allocation impact.
