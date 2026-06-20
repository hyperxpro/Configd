# Session 7.5 — Infrastructure Validation Campaign — Run Log

> First real-hardware measurement of Configd. Running ON a hand-provisioned AWS EC2
> **m6id.4xlarge SPOT** box in ap-south-1, repo on `/mnt/nvme` (local instance-store NVMe).
> Append-only chronological log; every result is committed + PUSHED as produced (spot reclaim
> destroys the local NVMe with ~2 min notice — §13.8). Started 2026-06-20.

---

## §6.1 — Box spec (recorded once; every number is relative to this)

| Property | Value |
|---|---|
| Instance type | **m6id.4xlarge**, lifecycle = **spot** (IMDS confirmed) |
| Region/AZ | ap-south-1 |
| vCPU | **16** (Intel Xeon Platinum 8375C @ 2.90 GHz; 1 socket × 8 cores × 2 threads — **single-socket, no NUMA**) |
| Memory | **61 GiB** total (~64 GB), 0 swap |
| OS | Ubuntu 26.04 LTS, kernel 7.0.0-1006-aws |
| JDK | OpenJDK **25.0.3** (2026-04-21, Temurin-equivalent Ubuntu build), installed during bring-up |
| Build | Maven via `./mvnw` (project requires `maven.compiler.release=25`) |
| Local NVMe | `/dev/nvme1n1`, 884.8 GB, **Amazon EC2 NVMe Instance Storage**, ext4, mounted `/mnt/nvme` (rw,relatime) |
| EBS root | `/dev/nvme0n1` (50 GB gp3), ext4, mounted `/` — **data/WAL must NOT live here (§13.9)** |

**Single-socket consequence:** M-4 (NUMA / CPU-pinning) is genuinely unprovable on this box — there
is no second socket and no cross-socket memory topology. M-4 stays in the shrunken manifest (signed
geographic/hardware-necessity), NOT manifest-dumping.

## §6.1 — fsync ceiling baseline (the single most important interpretive number — §7.0)

**Operator baseline (given):** ~**8,300 fdatasync IOPS @ ~33µs avg** on `/mnt/nvme`.

**Independent re-measurement on this box** (`fio-3.41`, target `/mnt/nvme`, single-thread, iodepth=1,
`--fdatasync=1` = one barrier per write — the "one fsync per entry" model):

| fio variant | IOPS | fdatasync avg | fdatasync p99 | p99.9 |
|---|---|---|---|---|
| Buffered (`--direct=0`) bs=4k | **14,300** | 67µs | 145µs | 155µs |
| O_DIRECT (`--direct=1`) bs=4k | **6,454** | 112µs | 938µs | 2.9ms |
| etcd-style buffered bs=2300 | **7,184** | 134µs | 947µs | 3.4ms |

Invocation (variant A): `fio --name=fdatasync-baseline --filename=/mnt/nvme/.fio-fsync-test --rw=write
--bs=4k --ioengine=sync --fdatasync=1 --iodepth=1 --numjobs=1 --size=512M --runtime=25 --time_based
--direct=0`.

**Reconciliation & interpretation.** The operator's ~8,300 @ ~33µs sits in the O_DIRECT / larger-block
round-trip regime (1/8,300 ≈ 120µs end-to-end, consistent with my O_DIRECT 6.5k–7.2k band). The
buffered 4k path reaches ~14.3k. So the **single-thread durable-append ceiling is a BAND of
~6.5k–14.3k fdatasync/s** depending on I/O path & block size. For attribution I anchor on the
**operator's ~8,300/s as the conservative reference**, noting buffered headroom to ~14k.

## §7.0 — Commit-fsync MODEL in code (determined BEFORE any throughput number)

**Verdict: Configd fsyncs PER ENTRY. There is NO group commit anywhere.** Evidence:

- `RaftNode.propose(command)` — `RaftNode.java:363-407`: validates → `log.append(entry)` (**:400**) →
  `broadcastAppendEntries()` → returns. One append per client command.
- All proposals are **marshalled onto a single tick executor** — `ConfigdServer.java:510` ("R-01:
  marshal proposals onto the single tick executor so node.propose() …"). Concurrent client writes are
  therefore **serialized**, each doing its own append+fsync — no coalescing of concurrent proposals.
- `RaftLog.append(entry)` — `RaftLog.java:349-358`: `storage.appendToLog(WAL_NAME, serializeEntry(entry))`
  per entry. `appendAll` (`:365-369`) and `appendEntries` (`:386-413`) just **loop `append()`** → N
  fsyncs for N entries.
- `FileStorage.appendToLog` — `FileStorage.java:89-114`: **opens the channel, writes, `channel.force(true)`
  (`:110`), closes** — one fsync (**data + metadata**, heavier than fdatasync) AND one file open/close
  **per entry**.

**Consequence for the headline (the §7.0 trap, pre-registered before measuring):** the leader's local
durable-append rate is bounded by serialized per-entry `force(true)` + open/close. Given the fsync band
(~8.3k/s conservative) and that `force(true)` (metadata) + open/close is *heavier* than the bare
fdatasync fio measured, the un-batched leader is expected to plateau in the **~5k–10k/s** range — right
at the 10k/s target — and will *look* "system-bound" while actually being **fsync-per-op-bound (case a)**.
The mandated fix is **group commit** (batch N pending proposals → one `appendAll` → one fsync, channel
kept open), per §7.1 / §7.0(a). Before/after on this box (Prime Directive §4.4): the per-op baseline is
measured first, then group commit implemented, then re-measured.

## §6.2 — data-dir / WAL runtime path

- `--data-dir` CLI arg → `ServerConfig.java:178` `Path.of(dataDir)`; WAL written as `<dataDir>/<name>.wal`
  by `FileStorage` (`appendToLog` resolves `directory.resolve(logName + ".wal")`).
- Production Compose passes `--data-dir /data` (a mounted volume). **On this box every cluster is
  launched with `--data-dir` under `/mnt/nvme/...`; the runtime path is asserted before any throughput
  claim (§13.9).**
- Signing-key D-1 default: `ConfigdServer.java:210` `dataDir.resolve("signing-key.bin")` (co-located —
  the D-1 P1 to fix; co-location check helper already exists at `:919`, warn-only at `:881`).

---

## Bring-up status
- [x] Box spec recorded
- [x] fsync baseline re-measured + reconciled with operator number
- [x] Commit-fsync model determined in code (per-entry, no group commit)
- [x] Dependency-warming build green (`mvnw -T1C -DskipTests clean install`, 66 s, BUILD SUCCESS)
- [x] gates 1–7 green on this box (fast CI mode; 14:24:57→14:39:05 UTC, ~14 min — see `bring-up-gates.md`)
- [ ] data-dir-on-/mnt/nvme asserted at runtime (at first cluster start — headline)
