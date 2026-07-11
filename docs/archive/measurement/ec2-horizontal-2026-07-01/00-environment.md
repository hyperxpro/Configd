# EC2 horizontal-scale measurement -- environment and methodology (2026-07-01)

The multi-machine session that closes the empirical item the single-box run
(`docs/archive/measurement/ec2-2026-06-30/`) left open: does aggregate write throughput scale
horizontally across separate machines when one Raft group's leader sits on each machine? The single-box
run showed durability/soak/DR green but could only reach a ~1100 w/s plateau (3 co-located JVMs sharing
16 cores plus one NVMe plus loopback) -- it could not represent N groups on N separate machines' own
CPU/disk/NIC. This session puts each group's leader on its own box and measures the aggregate.

Measure only, no code change to the system under test (`main` @ HEAD; the branch's server code is
byte-identical to `main`).

## Boxes

| | |
|---|---|
| Instances | 4 x `m6i.xlarge` (4 vCPU / 16 GiB, non-burstable, so no CPU-credit corruption) |
| Roles | `cp1`, `cp2`, `cp3` = consensus nodes (node-id 1/2/3); `load` = dedicated load-driver box |
| Region / AZ | ap-south-1 / ap-south-1b (all 4 in the same AZ -- this measures throughput under consensus, not WAN) |
| AMI | `ami-0f9235932f10668d4` (Amazon Linux 2023, kernel 6.1.175) |
| Pricing | on-demand ~$0.193/hr each (~$0.77/hr for all 4); not spot |
| Root/WAL disk | 30 GiB gp3 (m6i has no instance-store; the WAL/fsync path is gp3, a durable-fsync volume) |
| Data dir | `/data/run/n<id>` on the gp3 root volume |

Why a dedicated 4th load box: to remove the "did the driver starve the consensus threads?" confound
entirely. Validated -- see the driver-headroom check in `02-scaling-curve.md`: the load box ran at 15%
CPU while pushing 1600/s, so the driver was never the bottleneck.

## Toolchain / build

| | |
|---|---|
| JDK | Amazon Corretto **25.0.3** (JDK 25 LTS; matches the dev box) |
| Commit | `68463e5` (branch `ec2-measurement-2026-06-30` head). Server code is byte-identical to `main` @ `ce7d719` (`git diff main..HEAD` over all server/consensus/replication/transport modules is empty -- the branch only adds docs and measurement harnesses) |
| Build | `./mvnw -pl configd-server,configd-testkit -am clean package -DskipTests` on each box |
| Server jar | `configd-server-0.1.0-SNAPSHOT.jar` (shaded) |
| Bench jar | `configd-testkit/target/benchmarks.jar` (`io.configd.bench.ShardAwareWriteDriver`) |
| GC | Generational ZGC (`-XX:+UseZGC`, see ADR-0041) |
| Netty transport | Epoll (auto-default); io_uring is opt-in only (see ADR-0043) |
| Heap | `-Xmx4g -Xms4g` per node |
| Owner pool | `-Dconfigd.raft.ownerPoolSize=N` (= shardCount) -- each group gets its own owner thread |

## Topology -- one leader per machine (the validity requirement)

- N Raft groups via `-Dconfigd.raft.shardCount=N` on all 3 nodes; `StaticShardMap` routes
  `(scope,key) -> shard`. No `--edge-port` (this is the write/consensus plane; the N>1 edge fail-closed
  guard is never tripped).
- One JVM per box. Node k binds Raft `0.0.0.0:9291` and API `0.0.0.0:8281`; peers dial by hostname
  `--peer-addresses 1=cp1:9291,2=cp2:9291,3=cp3:9291` (`/etc/hosts` maps cp1/cp2/cp3 to the 3 private
  IPs). Symmetric default election timeouts (150/300/50 ms); no leader-pinning timeout asymmetry (that
  was tried and rejected in the single-box run because it makes the leader fragile at the knee).
- Validity requirement: for the N=3 headline, each of the 3 boxes must lead exactly one group (1-1-1),
  so writes to different shards commit on different machines' cores in parallel. If leadership piles
  onto one box, the run measures single-box contention in disguise and is invalid. Verified before every
  measured N=3 point -- see `02-scaling-curve.md`.

## mTLS posture

Cross-box mTLS is proven functional end-to-end -- the new risk the single-box runs never exercised
(cross-host cert SAN / peer auth, the `EdgeTransportSanMismatch` class). See `03-mtls-bringup.md`: a
3-node cluster booted with the production TLS triple over cross-box mTLS on both the Raft peer channel
and the API, formed leaders (`NettyRaftTransport listening ... (mTLS) [tier=epoll]`), and served
`/health/ready = 200` over mTLS from a separate host, with no cert regen (the shipped `server.pem`
already carries `dns:cp1,cp2,cp3` SANs, resolved cross-box via `/etc/hosts`).

The throughput/scaling runs themselves are plaintext cross-box, exactly as the single-box methodology
ran them plaintext-loopback: mTLS is a separate, optional crypto-overhead arm and is
scaling-shape-invariant. Running the curve plaintext keeps it directly comparable to the ~800/s and
~1100/s single-box baselines (which are plaintext) and avoids confounding "does it scale" with "mTLS CPU
overhead." The cross-machine replication path is real network in both cases.

## Load generator

`ShardAwareWriteDriver` (the same driver as the single-box N x knee run) run from the dedicated `load`
box. It replicates `StaticShardMap.shardFor` and keeps a per-shard leader pointer learned from
`X-Leader-Hint`, so every PUT lands on the machine that leads that key's shard.
`NODEMAP = 1=http://cp1:8281,2=http://cp2:8281,3=http://cp3:8281`; 512 B values; ~1 M distinct keys
spread over N groups.

- `calibrate-sharded` (closed-loop): C workers each send-as-fast-as-possible; the sustained rate is the
  cluster's true ceiling at that concurrency. This is the primary method (same as the single-box run's
  reported N x knee), and is directly comparable across N.
- `atrate-sharded` (open-loop): fed at a target rate; used for the N=1 single-group knee that anchors to
  the ~800/s single-box framing.
