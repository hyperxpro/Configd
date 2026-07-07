# Group B §2.4 — Node-Join Authorization Gate (as-built)

> **Status:** built. Companion to the investigation `investigation/04-node-join-gate.md` (the spec, with the
> full T1–T11 matrix and the etcd/CockroachDB/ZooKeeper grounding). This records what actually shipped and
> the honest correctness boundary for the two dormant modes.
>
> **One-line principle (unchanged across etcd / CockroachDB / ZooKeeper):** *interior (Raft peer) membership
> requires a marker a mere client credential can never present.* Configd already implemented the reserved-
> marker model via `PeerIdentityPolicy` (per-node allow-list, stronger than etcd's single `--peer-cert-allowed-cn`
> or CRDB's single `CN=node`). This gate **strengthens** that; it is not new machinery.

## The three-plane model (ratified)

The Raft **interior** (node ↔ node) is **mTLS-only** (`setNeedClientAuth(true)`). There is no token wire path
to it: the auth arc's `AUTH`/`REFRESH_AUTH` frames live on the **edge/M2M client** plane (`EdgeFrameCodec`),
a different wire on a different transport. The interior `MessageType` enum carries only consensus/gossip
frames — **no** credential-bearing frame exists (guarded by `NodeJoinInteriorBoundaryTest`). Consequently:

- **mTLS is the interior's only live auth mode.**
- **OIDC and HTTP-Basic node markers are dormant, fail-closed — not deferred.** They are *unreachable by
  construction* today: with no interior frame able to carry a bearer/Basic credential, a token *cannot* be
  presented to consensus, which is the correct fail-closed state. They are fully specified below so that if a
  token-bearing interior auth frame is ever added (a named RFC forward extension), the gate slots in at the
  *same* resolve seam with no interior-ingress redesign.

## What shipped (mTLS node-join strengthening)

All four changes compose with the existing Layer-1 (handshake → resolve → pin) and Layer-2 (`senderId` ==
pinned; in-body `leaderId`/`candidateId` == sender) enforcement from the wire-hardening arc — they are the
*same* checks strengthened, not a second gate.

1. **Fail-closed default under authentication.** When authentication is enabled **and** the Raft interior
   uses TLS **and** the peer allow-list is empty, the server **refuses to boot** — a CA-valid client
   certificate could otherwise forge a peer's `senderId` and join consensus. Predicate
   (`PeerIdentityPolicy.requireEnforcedUnderAuth(authEnabled, tlsEnabled)`):

   ```
   REFUSE TO BOOT  iff  authEnabled AND tlsEnabled AND NOT enforced()
   ```

   `authEnabled` = the `configd.auth.*` chain is configured and is not the explicit `none` posture, **or** the
   legacy `--auth-token` is set (`ConfigdServer.isAuthEnabled`). The **auth-disabled** and **plaintext-interior**
   postures keep today's loud one-time warning (dev/test/shared-cert fleets are byte-identical). Fires only in a
   multi-node deployment (peer addresses configured); a single-node deployment has no interior ingress.

2. **Optional separate peer trust anchor** (etcd `--peer-trusted-ca-file` / ZooKeeper `ssl.quorum.trustStore`).
   `configd.raft.peerIdentity.trustStore` (+ optional `…trustStorePassword`) gives the Raft interior a
   **distinct** trust store: the node's own key material with a **different CA**. A client certificate that
   does not chain to the peer CA fails the peer *handshake* structurally — stronger than a CN match on a shared
   CA. Implemented as `TlsManager.peerContext()` (built from the node key + peer trust store); the Raft
   transports use `peerContext()`, the edge/client plane keeps `currentContext()`. Unset ⇒ `peerContext()` **is**
   `currentContext()` (byte-identical to the shared trust store).

3. **Marker modes.** Default `RDN` marker (`CN`, configurable via `configd.raft.peerIdentity.marker`), plus an
   optional **SAN-URI / SPIFFE** marker mode (`configd.raft.peerIdentity.markerType=san-uri`, mirroring etcd
   `--peer-cert-allowed-hostname`): the allow-list keys are matched by **exact string** against the peer cert's
   SAN URI entries. Default stays RDN=CN (byte-identical).

4. **Config via `ConfigSource`.** `PeerIdentityPolicy.fromConfig(ConfigSource)` replaces `fromSystemProperties()`
   (retained as a byte-identical delegate). All node-join keys flow through the Gate-1 config SPI (system
   properties → environment → YAML), lowercase-dotted, fail-closed. The key
   `configd.raft.peerIdentity.allowedNodes` and its `identity=nodeId` semantics are preserved verbatim for
   back-compat.

### Config keys (all under `configd.raft.peerIdentity.`)

| Key | Meaning | Default |
|---|---|---|
| `allowedNodes` | comma list `identity=nodeId` (the reserved-marker allow-list) | unset ⇒ unenforced (warn) |
| `marker` | Subject-DN RDN type carrying the identity (RDN mode) | `CN` |
| `markerType` | `rdn` or `san-uri` | `rdn` |
| `trustStore` | separate PKCS12 peer trust anchor (R3) | unset ⇒ shared client/edge trust store |
| `trustStorePassword` | password for the peer trust store | the Raft store password |

## The node-join marker per auth mode

| Mode | Status | Node marker | Fail-closed rule |
|---|---|---|---|
| **mTLS** | **live** | marker RDN value (default `CN`) or SAN URI ∈ `allowedNodes` → a specific `NodeId`; optionally must also chain to the separate peer CA (R3) | marker absent / unparseable / not in allow-list → `null` → dropped at Layer 1 |
| **OIDC** | **dormant, fail-closed** (forward extension) | a required **node-claim**: `aud` includes `configd-interior` and/or `roles`/scope contains `configd-node`; the IdP MUST NOT grant it to human/client identities | a token lacking the node-claim → at most a client `Principal`, never interior — and today no interior frame carries a token at all |
| **HTTP Basic** | **dormant, fail-closed** (forward extension) | a designated **node principal**: a reserved account (recommend username `node`, CRDB-style), provisioned only to nodes, non-interactive | any other account → client at most; today no interior frame carries Basic credentials |

**The CA/IdP issuance assumption (inherent to every reference system).** The gate checks the marker; the CA/IdP
guarantees the marker is unforgeable. For a **shared CA + allow-list** the operator must not issue a *client*
cert whose marker collides with a node's allow-list entry (etcd's exact caveat). The **separate peer CA** (R3)
eliminates that vector structurally — the assumption reduces to "do not add the peer CA to the client trust
store." For OIDC/Basic (when live) the IdP must not grant the node-claim / `node` account to clients.

## Test matrix (client-cred-never-interior)

| # | Proof | Where |
|---|---|---|
| T1 | client CN not in allow-list → dropped + metric | `RaftPeerIdentityBindingTest` / `NettyRaftPeerIdentityBindingTest` |
| T2 | valid node cert forging another node's `senderId` → Layer-2 drop + metric | both binding tests |
| T3/T4 | forged in-body `leaderId` (single + coalesced) → drop + metric | `HostilePeerInjectionE2ETest` + `RaftTransportAdapter` tests |
| **T5** | **separate peer CA:** client-CA cert with matching `CN=raft-node-1` → **handshake fails** (structural) + a real-node control still delivered | both binding tests (`impostorNotInPeerTrustFailsHandshakeEvenWithMatchingCn`, `realNodeUnderSeparatePeerTrustIsDelivered`) |
| **T6** | **auth + TLS + empty allow-list → BOOT ERROR**; auth-off / plaintext / enumerated allow-list → boots | `PeerIdentityPolicyTest.bootGate*` (predicate) + `NodeJoinBootGateTest` (real `ConfigdServer.start`) |
| T7 | reverse-path forged `senderId` on a connection we dialed → drop | both binding tests |
| **SAN-URI** | SPIFFE-id peer authorized by SAN URI; a cert without the SAN URI rejected | both binding tests (`sanUriMarkerAuthorizesPeerBySpiffeId`, `…RejectsCertWithoutSpiffeId`) |
| **T8 / token-path** | the interior wire admits no credential-bearing frame → no client token reaches consensus; a node cert on the edge is an ordinary `Principal` (interior standing is conferred *only* at the Raft ingress) | `NodeJoinInteriorBoundaryTest` + §"three-plane model" |
| T9/T10 | OIDC node-claim / Basic node-principal fail-closed | dormant (see above); activate with the interior token frame |
| T11 | Netty ↔ JDK accept/reject + metric parity | both binding tests run the same scenarios; `*RaftTransportContractTest` ×3 tiers |

**Byte-identity.** No node-join config + auth-off ⇒ every path is the pre-Group-B path: `fromConfig` on an unset
allow-list is `unenforced()`; `peerContext()` == `currentContext()`; the boot gate returns without throwing; the
RDN resolve path is the same call. Proven by the full existing consensus/transport/failover suites staying green
(`*RaftTransportContractTest` ×3, `RaftTransportMtlsAttackTest`, `TlsManagerTest`, `ConfigdServerTest`).
