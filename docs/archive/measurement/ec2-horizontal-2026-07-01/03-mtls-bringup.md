# Cross-box mTLS bring-up -- the new-risk item, retired

The single-box runs never exercised cross-host TLS (they were loopback). Cross-box mTLS is the
`EdgeTransportSanMismatch` class of risk (see the driver-protocol RFC, section 6): the Raft peer client
enforces HTTPS endpoint identification, so a node dialing a peer verifies the peer's cert SAN against the
name it dialed, across separate machines. This session proves it works, with no cert regeneration.

## How (no cert regen)

The shipped `deploy/compose/secrets/server.pem` already carries
`SAN = dns:cp1, dns:cp2, dns:cp3, dns:localhost, ip:127.0.0.1` (a single shared server identity
`CN=configd-cp` that every CP node presents both as its server cert and as its Raft-peer client cert; the
shared trust store trusts it). So cross-box mTLS needs only:

- `/etc/hosts` on every box mapping cp1/cp2/cp3 to the three private IPs;
- `--peer-addresses 1=cp1:9291,2=cp2:9291,3=cp3:9291` (dial by the SAN name, resolved cross-box);
- the production TLS triple `--tls-cert server.pem --tls-key server-ks.p12 --tls-trust-store
  server-ts.p12` on all three nodes.

The shared Ed25519 signing key (`signing-key.bin`) is distributed identically to all three nodes (each
signs its own fan-out at apply time; a per-node key would break verification at failover).

## Evidence (captured live)

```
--- readiness over mTLS (probed cross-box from the load box) ---
cp1=200  cp2=200  cp3=200   READY_COUNT=3/3

--- per-node startup log (all three identical) ---
  TLS          : enabled
  Shard map    : StaticShardMap[N=3, epoch=0]
  Owner pool   : 3 owner thread(s)
  NettyRaftTransport listening on /[0:0:0:0:0:0:0:0]:9291 (mTLS) [tier=epoll]

--- explicit curl over mTLS (P12 client cert), cross-box from the load box ---
cp1 https /health/ready = 200

--- leaders formed over the mTLS peer channel ---
LEADER_DIST: cp1=2 cp2=1 cp3=0   TOTAL_LED=3   MAX_ELECTIONS=2
```

All three nodes formed a cluster over mutual-TLS Raft peer channels across three separate hosts, elected
leaders for all 3 groups, and served the API over mTLS to a fourth host. The cross-box cert SAN / peer-
auth risk is retired.

(The mTLS cluster came up leadership-maldistributed at 2-1-0; that is the measurement's concern, not the
TLS proof's -- see `05-leadership-placement.md`. The throughput curve then ran plaintext for baseline
comparability, per `00-environment.md`. Full log: `captures/mtls-bringup-proof.txt`.)
