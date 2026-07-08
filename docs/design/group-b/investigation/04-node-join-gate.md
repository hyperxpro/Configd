# Group B §2.4 — The Node-Join Authorization Gate (investigation)

> **Status:** investigation / recommendation only — no production code changed. Read-only study for the
> Group B auth arc. Grounds the gate in Configd's *actual* peer-identity code (the wire-hardening arc, PR #67)
> and in the node-vs-client separation of etcd, CockroachDB, and ZooKeeper.
>
> **Scope of the gate.** "Node-join" here means: *admission to the trusted interior* — the Raft consensus
> plane (and any inter-node replication that rides it). It is the authorization boundary that decides whether a
> connection is treated as a **peer** (may drive AppendEntries/RequestVote/InstallSnapshot into a `RaftNode`)
> or is at most a **client** (may, subject to authz, read/watch/write through the front doors). The gate is
> *layered on top of* the four client-auth modes (Gates 2–6); it is not one of them.

---

## 1. Executive summary + recommendation on every fork

**The headline principle (identical across all three reference systems).** *Interior membership requires a
marker a mere client credential can never present.* Every mature Raft/quorum system separates the
**node/peer/quorum trust domain** from the **client trust domain**, by one (usually both) of:

- **A separate trust anchor** for inter-node traffic — etcd `--peer-trusted-ca-file` / `--peer-cert-file`,
  ZooKeeper `ssl.quorum.keyStore`/`ssl.quorum.trustStore`. A client certificate physically cannot chain to
  the peer CA, so it can never be a peer, full stop.
- **A reserved marker on a (possibly shared) CA** — etcd `--peer-cert-allowed-cn` (*"node can only join with
  matching common name even with shared CAs"*), CockroachDB `CN=node` (client certs are `CN=<user>`). A client
  certificate lacks the reserved marker, so it is rejected at the peer ingress even though it is CA-valid.

**Configd already implements the second model, and more granularly than either reference.**
`PeerIdentityPolicy` (built in the wire-hardening arc) maps a per-node marker RDN value (default `CN`) → a
specific `NodeId` via an explicit allow-list; a cert whose marker is absent or not in the allow-list resolves
to `null` = *not an authorized peer* and its connection is dropped at the Raft ingress. This is strictly
stronger than etcd's single allowed-CN and CRDB's single `CN=node`: Configd pins **which** node, not merely
*"a node."* **The mTLS node-join gate is therefore not new machinery — it is `PeerIdentityPolicy` made
fail-closed-by-default under Group B, plus an optional separate peer trust anchor for defense-in-depth.**

**Recommendations on every fork:**

| # | Fork | Recommendation |
|---|---|---|
| R1 | **Is the interior mTLS-only, or can OIDC/Basic authenticate a peer?** | **mTLS-only for v1.** The Raft transport is binary mTLS (`setNeedClientAuth(true)`); a bearer/Basic credential has *no wire path* to it. OIDC/Basic node-markers are **designed-in but dormant**, activating only if/when a token-bearing interior auth frame is added (RFC §03 AU3-3, a named forward extension). They MUST fail closed today (there is no interior ingress that accepts them). |
| R2 | **mTLS marker: reserved `CN=node`, or per-node allow-list?** | **Keep Configd's per-node allow-list** (`identity=NodeId`) as the primary marker — it is stronger than `CN=node`. Additionally **reserve** the bare `CN=node` convention as the *documented default marker name* for operators who want etcd/CRDB parity, but require it to still be enumerated in the allow-list (so `CN=node` alone never resolves a specific `NodeId`). |
| R3 | **Separate peer CA (etcd/ZK model) or shared CA + marker (etcd allowed-cn / CRDB)?** | **Support both; recommend separate peer CA as the strong default for Group B.** Shared-CA + allow-list is the *minimum* (already built) and rests on a CA-issuance assumption (§4.4). A separate peer trust anchor makes a client cert *structurally* unable to be a peer. Expose it as an operator choice (OA-NJ-1). |
| R4 | **Default posture when auth is enabled but no allow-list is configured?** | **Fail closed.** Today the empty allow-list is CA-chain-only with a one-time warning (byte-identity for legacy shared-cert fleets). Group B is strict-by-default: **when auth is enabled and the Raft transport has TLS, an empty peer allow-list MUST be a boot error, not a warning** (§4.3). |
| R5 | **OIDC node-marker shape** | A **required node-claim**: a dedicated audience (`aud` includes `configd-interior`) *and/or* a role/scope claim (e.g. `roles` contains `configd-node`). Fail-closed: a token lacking it is at most a client. The IdP MUST NOT grant it to human/client identities (§4.4). |
| R6 | **HTTP Basic node-marker shape** | A **designated node principal** — a reserved account (recommend the username `node`, mirroring CRDB's reserved `node` user), provisioned only to nodes and unusable as an interactive client login. Fail-closed: every other account is a client at most. |
| R7 | **Enforcement point** | **The Raft interior ingress, in both transports** — the *existing* `PeerIdentityPolicy.resolve(...)` call on handshake completion. The node marker is the **same check strengthened**, not a second check. The edge plane (`FanOutConnectionDriver`) is client-only and must never route into the interior — its role in the gate is purely defensive (§3.3, §4.2). |

---

## 2. Reference-system findings (primary sources, verbatim where load-bearing)

### 2.1 etcd — peer TLS is a separate trust domain; `--peer-cert-allowed-cn` gates membership

Source: **etcd Transport security model**, <https://etcd.io/docs/v3.5/op-guide/security/>.

etcd splits transport security into two independent flag families — **client-to-server** and
**peer (server-to-server)** — each with its own cert, key, CA, and client-cert-auth toggle:

- Client plane: `--cert-file`, `--key-file`, `--trusted-ca-file`, `--client-cert-auth`
  ("*When this is set etcd will check all incoming HTTPS requests for a client certificate signed by the
  trusted CA … the certificate provides credentials for the user name given by the Common Name field.*")
- Peer plane: `--peer-cert-file`, `--peer-key-file`, `--peer-trusted-ca-file`, `--peer-client-cert-auth`.

Because the peer plane has its own `--peer-trusted-ca-file`, an operator can issue peer certs from a
**different CA** than client certs — a client cert cannot chain to the peer CA and thus can never be a peer.

On top of (or instead of) a separate CA, etcd added a **reserved-marker** gate for shared-CA deployments:

> *"v3.3.0 adds etcd `--peer-cert-allowed-cn` flag to support CN(Common Name)-based auth for inter-peer
> connections."*

> *"When [the] flag is specified, node can only join with matching common name even with shared CAs. The
> match is an exact string comparison against the certificate's Common Name (CN) field — no wildcards or
> prefix matching is supported."*

The motivating scenario is exactly Group B's: *"Maintaining different CAs for each component provides tighter
access control … but often tedious"* — so `--peer-cert-allowed-cn` lets a **shared CA** still gate membership
by a reserved CN. There is also `--peer-cert-allowed-hostname` (matched via Go's
`x509.Certificate.VerifyHostname()`, which permits wildcards) for SAN/hostname-based peer gating.

**Extracted principle:** peer admission is *not* "any CA-valid cert"; it is "a cert from the peer trust anchor
**and/or** bearing the reserved peer CN," checked by **exact string comparison**. A client cert — even one
signed by the same CA — is rejected at the peer ingress because its CN is not the allowed peer CN.

### 2.2 CockroachDB — the `node` certificate vs `client.<user>` certificates; `node` is a reserved user

Source: **`cockroach cert`**, <https://www.cockroachlabs.com/docs/stable/cockroach-cert>; **Authorization**,
<https://www.cockroachlabs.com/docs/stable/security-reference/authorization>.

CockroachDB uses one CA but two **disjoint CN namespaces**:

> *"`node.crt` … must be signed by `ca.crt` and must have **`CN=node`** and the list of IP addresses and DNS
> names listed in Subject Alternative Name field."*

> *"`client.<user>.crt` … must be signed by `ca.crt`. Also, `client.<username>.crt` must have **`CN=<user>`**
> (e.g., `CN=marc` for `client.marc.crt`)."*

The `node` identity is a **reserved, special interior user**:

> *"**node** — Used for all internode communications and for executing internal SQL operations that are run as
> part of regular node background processes. **The node user does not appear when listing a cluster's users.**"*

So `CN=node` is the interior marker: it authenticates **all inter-node RPC**, and a client — whose cert is
`CN=<username>` — can never present it. A human named `node` cannot be provisioned as an ordinary SQL user
without colliding with the reserved identity, which the operator (holding the CA) simply does not do.

**Extracted principle:** identical to etcd's allowed-CN — a single reserved CN (`node`) is the interior
marker on a shared CA; the client CN namespace (`<user>`) is disjoint from it by CA-issuance discipline.

### 2.3 ZooKeeper — separate quorum keystore/truststore from the client keystore/truststore

Source: **ZooKeeper Administrator's Guide**, <https://zookeeper.apache.org/doc/current/zookeeperAdmin.html>.

ZooKeeper's inter-server ("quorum") TLS is configured by a **separate** key/trust material from the client
TLS, and is enabled by its own switch:

> *"**`sslQuorum`** (`zookeeper.sslQuorum`) — New in 3.5.5: Enables encrypted quorum communication. Default is
> false."*

- Quorum plane: `ssl.quorum.keyStore.location`/`.password`, `ssl.quorum.trustStore.location`/`.password`,
  `ssl.quorum.hostnameVerification`, `zookeeper.ssl.quorum.context.supplier.class`.
- Client plane: `ssl.keyStore.*`, `ssl.trustStore.*`, `ssl.hostnameVerification`, `ssl.authProvider`.

The two planes have **independent trust stores**, so the set of certificates trusted to join the quorum is
distinct from the set trusted to connect as a client. This is the "separate trust domain" model in its purest
form: quorum membership is decided by the quorum truststore, which a client cert need never be in.

**Extracted principle:** membership is a property of a *distinct trust anchor* (the quorum truststore), not of
"is this cert CA-valid for clients." A client-plane cert is invisible to the quorum plane.

### 2.4 Synthesis — the one principle, two mechanisms

| System | Separate trust anchor for peers | Reserved marker on shared CA | Marker check |
|---|---|---|---|
| **etcd** | `--peer-trusted-ca-file` / `--peer-cert-file` | `--peer-cert-allowed-cn` (+ `-allowed-hostname`) | exact-string CN compare |
| **CockroachDB** | (one CA) | `CN=node` (clients are `CN=<user>`); `node` = reserved user | exact CN |
| **ZooKeeper** | `ssl.quorum.trustStore` vs `ssl.trustStore` | (trust-store separation *is* the marker) | truststore membership |

**The invariant every one of them enforces:** *a connection is admitted to the interior only if it presents
something a client credential structurally cannot* — membership in a peer trust anchor, or a reserved
CN/marker the CA/IdP never issues to clients. Verifier/handshake success alone (a CA-valid cert) is **never**
sufficient for interior admission.

---

## 3. Configd seam grounding (file:line)

The wire-hardening arc (PR #67) already built the peer-identity foundation. This gate composes with it.

### 3.1 `PeerIdentityPolicy` — the built marker resolver

`configd-transport/src/main/java/io/configd/transport/PeerIdentityPolicy.java`

- The model (`:21-34`, `:38`): a peer cert carries its per-node identity in a configurable **marker RDN**
  (default `CN`, `MARKER_PROP = configd.raft.peerIdentity.marker`, `:41`, `:50`). `resolve(subjectDn)`
  (`:146-155`) extracts the marker and maps it to the `NodeId` the node is authorized to present via
  `allowedNodes` (`:53`); an absent marker, an unparseable DN, or a value **not in the allow-list** resolves
  to `null` = *not an authorized peer* (`:151-154`, `:167`).
- `ALLOWED_NODES_PROP = configd.raft.peerIdentity.allowedNodes` (`:48`) — comma-separated `identity=NodeId`.
  An unset/blank spec → `unenforced()` (`:62-64`, `:88-90`); a **non-blank spec yielding zero entries fails
  closed** (`:118-123`); a duplicate identity or malformed pair throws at boot (`:113-116`, `:99-112`).
- `enforced()` = non-empty allow-list (`:127-130`). The Javadoc explicitly grounds the design in etcd
  `--peer-cert-allowed-cn`, CockroachDB `CN=node`, and ZooKeeper quorum-cert verification (`:15-19`).

**This class *is* Configd's node marker for mTLS.** The allow-list is the reserved-marker set; membership in
it is the "something a client can never present" of §2.4 — provided the CA does not issue a client cert whose
marker collides (§4.4).

### 3.2 The Raft interior ingress — the single enforcement point (both transports)

The gate fires exactly where `PeerIdentityPolicy.resolve(...)` is called on handshake completion, before any
frame is dispatched into a `RaftNode`.

**Netty transport** — `configd-netty/src/main/java/io/configd/netty/NettyRaftTransport.java`
- **Layer 1** (`:469-484`, in `userEventTriggered`): on a *successful* mTLS handshake under an enforced
  allow-list, `peerIdentityPolicy.resolve(resolveCertIdentity(ctx))` (`:477`) resolves the peer cert identity;
  `null` → `onPeerIdentityRejected()` + `ctx.close()` (`:478-482`); otherwise the `NodeId` is **pinned** on
  the channel (`PEER_IDENTITY` attr, `:483`). `resolveCertIdentity` reads
  `ssl.engine().getSession().getPeerPrincipal().getName()` and fails closed to `null` (`:494-503`).
- **Layer 2** (`:445-458`, `channelRead0`): every frame's self-declared `senderId` must equal the pinned
  `NodeId`; mismatch or missing pin → reject + close (`:450-456`).
- Fail-closed startup: `start()` refuses to bind when `enforced() && tlsManager == null` (`:208`); a one-time
  loud warning when TLS is on but the allow-list is unset (`:256-260`).

**JDK transport** — `configd-transport/src/main/java/io/configd/transport/TcpRaftTransport.java`
- **Layer 1** (`:495-532`, `handleInboundConnection`): for a *server-accepted* socket it forces the handshake
  (bounded by `HANDSHAKE_TIMEOUT_MS`, `:508-510`) and `peerIdentityPolicy.resolve(resolveCertIdentity(ssl))`
  (`:520`); `null` → reject (`:521-527`). For the **outbound-reverse** path (a peer replying on a connection
  *we* dialed) it pins the hostname-verified `dialTarget` directly (`:529-531`) — closing the reverse-path
  bypass.
- **Layer 2** (`:537-555`): `senderId` must equal the pinned identity; a missing pin under `enforced()` is
  also a DENY (`:546-547`) — `enforced()` implies mTLS since `start()` refuses plaintext (`:265`).

**In-body binding** — `configd-server/src/main/java/io/configd/server/RaftTransportAdapter.java`
- Single message (`:239-254`): a request's in-body `leaderId`/`candidateId` must equal the transport-
  authenticated sender; mismatch → drop + `onPeerIdentityRejected()` (`:246-253`).
- Coalesced heartbeat (`:218-235`): every bundled entry's `leaderId` must equal the sender; the **whole frame
  is scanned before any dispatch** so a forgery late in the map cannot slip earlier entries through
  (`:226-235`). Gated on `enforceIdentity` (the same policy, via `tcpTransport.peerIdentityEnforced()`).

**Wiring** — `configd-server/src/main/java/io/configd/server/ConfigdServer.java:442-446`:
`PeerIdentityPolicy.fromSystemProperties()` is built once and passed to `NettyRaftTransport`, alongside a
`ServerRaftTransportMetrics` sink so all rejections increment `configd_raft_peer_identity_mismatch`
(`ServerRaftTransportMetrics.java:16`, `RaftTransportMetrics.java:20`). Fail-closed: TLS-on-CLI but no
`TlsManager` on the transport is a boot error (`ConfigdServer.java:449-453`).

**The interior ingress is complete and singular.** The Raft transport is the *only* channel that reaches a
`RaftNode`: InstallSnapshot rides the same transport (`RaftMessageCodec.java:38-40`, decoded through the same
handler); config/membership changes ride the Raft **log** (applied by the state machine, not an external
socket); and the membership-change RPCs are *intentionally unexposed* on the admin surface
(`DriverLeadershipAdmin.java:120-138` — `addNode`/`removeNode` throw "unexposed"). So there is **one gate to
strengthen**, replicated across the two transports.

### 3.3 The edge plane — client-only; the gate's defensive composition point

The edge fan-out (watch) plane authenticates clients by mTLS and binds the cert DN as authoritative, but it
**never routes into the Raft interior** — it serves replicated reads/watches only.

- `configd-server/src/main/java/io/configd/server/fanout/NettyFanOutServer.java`: `setNeedClientAuth(true)`
  (`:300`); on handshake success `resolveCertIdentity(ctx)` (`:402`, `:417-427`) → `startSession(ctx,
  identity)`; a "successful" handshake with no verifiable peer cert is rejected (`:403-405`).
- `configd-distribution-service/src/main/java/io/configd/distribution/fanout/FanOutConnectionDriver.java`:
  `bindIdentity` (`:524-532`) makes the verified cert DN authoritative and the wire `edgeId` advisory;
  `admitWatchConnection` (`:480-495`) uses the identity only for slow-consumer admission (`:481`); watch
  authorization is a separate `READ ∧ WATCH` check at subscribe (`handleWatchCreate`, `:557-587`).

**Composition consequence:** the edge is where a client *with a node cert* would land if it dialed the wrong
plane. That is harmless by construction — the edge grants only watch-authz-gated reads, never interior
privileges — but the gate design must state it explicitly (§4.2): **the node marker confers interior standing
*only* at the Raft ingress; on the edge it is just another `Principal`.**

---

## 4. Recommended gate design

### 4.1 The marker per auth mode (+ fail-closed default)

| Mode | Node marker | Default | Fail-closed rule |
|---|---|---|---|
| **mTLS** (interior's only live auth) | Peer cert identity RDN (default `CN`) value ∈ `configd.raft.peerIdentity.allowedNodes` → a specific `NodeId`. Optionally, the cert must also chain to a **separate peer trust anchor** (R3). | Reserved marker name `CN` (per-node values); `CN=node` reserved as the etcd/CRDB-parity convention but still must be enumerated. | Marker absent / unparseable / not in allow-list → `resolve()` = `null` → **not admitted to the interior** (drop at Layer 1). Already built. |
| **OIDC** (dormant; forward extension) | A **required node-claim**: `aud` includes `configd-interior` and/or `roles`/scope contains `configd-node`. | A dedicated claim the IdP grants only to node service accounts. | Token lacking the node-claim → at most a client `Principal`; **never** admitted to the interior. |
| **HTTP Basic** (dormant; forward extension) | A **designated node principal** — a reserved account (recommend username `node`). | The `node` account, provisioned only to nodes, non-interactive. | Any other account → client at most; only the `node` account is a peer. |

**Why OIDC/Basic are dormant, not deferred (honest scoping).** The Raft interior transport is binary mTLS with
`setNeedClientAuth(true)`; a bearer/Basic credential has *no wire frame* that reaches it. So today those
markers are unreachable by construction — which is the correct fail-closed state (a token *cannot* join). They
become live only when a token-bearing interior auth frame is added (RFC §03 AU3-3, a named forward extension);
the marker definitions above are designed so that gate slots in with no interior-ingress redesign.

### 4.2 Enforcement point + composition with `PeerIdentityPolicy`

- **One enforcement point, two transports:** the Raft interior ingress — `NettyRaftTransport.java:476-483`
  and `TcpRaftTransport.java:498-532`. This is the *existing* `PeerIdentityPolicy.resolve(...)` site.
- **The node marker is the same check strengthened, not an additional check.** For mTLS, allow-list
  membership already *is* the marker. Group B strengthens it three ways: (1) **fail-closed default** under auth
  (R4/§4.3); (2) an **optional separate peer trust anchor** (R3) so a client cert is structurally non-peer;
  (3) **mode-awareness** so the OIDC node-claim / Basic node-principal plug into the *same* resolve seam when a
  token-bearing interior frame exists — `resolve(credential) → NodeId | null`, generalizing today's
  `resolve(subjectDn)`.
- **Edge composition (defensive):** the edge (`FanOutConnectionDriver`) must never treat the node marker as an
  interior grant. Recommendation: leave the edge unchanged; interior standing is conferred *only* at the Raft
  ingress. Document that a node cert on the edge is an ordinary watch-authz `Principal`.

### 4.3 The fail-closed rule (the Group B change)

Today: empty allow-list ⇒ `unenforced()` ⇒ CA-chain-only + a one-time warning (byte-identity for legacy
shared-cert fleets — `PeerIdentityPolicy.java:27-34`, `NettyRaftTransport.java:256-260`).

Group B (strict-by-default): **when auth is enabled and the Raft transport has TLS, an empty peer allow-list
MUST be a boot error, not a warning.** Rationale: the wire-hardening warning explicitly states the residual
risk — *"a cert-valid peer can forge another node's senderId"* (`NettyRaftTransport.java:259-260`). Under an
auth arc whose charter is "prove a client cred cannot reach the interior," CA-chain-only interior admission is
exactly the hole the gate must close. Keep the unenforced path only for explicitly auth-disabled deployments
(the loud-warning escape, parallel to the control-plane `--auth-token`-unset banner).

### 4.4 The CA/IdP assumption the operator must satisfy

The gate's soundness rests on an issuance assumption the operator (who controls the CA/IdP) must uphold — this
is inherent to *every* reference system, not a Configd weakness:

- **mTLS, shared-CA + allow-list (minimum posture):** the CA **MUST NOT** issue a *client* certificate whose
  marker RDN value collides with a node's allow-list entry. This is exactly etcd's caveat: *"node can only
  join with matching common name even with shared CAs"* — the operator keeps the peer-CN namespace disjoint
  from the client-CN namespace (CRDB does this by reserving `CN=node`). Exact-string comparison (no
  wildcard/prefix) is required and is what `LdapName`/`equalsIgnoreCase` gives (`PeerIdentityPolicy.java:172`).
- **mTLS, separate peer CA (recommended strong posture, R3):** issue node certs from a **distinct peer CA**
  the client trust anchor does not include (etcd `--peer-trusted-ca-file`, ZK `ssl.quorum.trustStore`). Then a
  client cert *structurally* cannot be a peer regardless of its CN — the assumption reduces to "do not add the
  peer CA to the client truststore," which is far easier to hold than per-cert CN discipline.
- **OIDC:** the IdP **MUST NOT** grant the node-claim (`aud=configd-interior` / role `configd-node`) to any
  human or client identity. Node service accounts get it; nobody else does.
- **HTTP Basic:** the `node` account credential is provisioned **only** to nodes and is non-interactive.

**The gate checks the marker; the CA/IdP guarantees the marker is unforgeable.** An attacker cannot mint a
CA-signed cert with an arbitrary CN, or an IdP-signed token with an arbitrary claim — so the only residual
risk is *operator issuance error*, which the separate-peer-CA posture (R3) eliminates structurally.

### 4.5 Threat model — attack vectors and the gate's answer

| Vector | Reaches the interior? | Why |
|---|---|---|
| Client cert with a **forged** `CN=node`/allow-list CN | **No** | The cert must be CA-signed; an attacker cannot self-mint a trusted cert. Handshake fails → never reaches `resolve()`. |
| Client cert, CA-valid, CN **collides** with a node's allow-list value (shared CA) | **No, if the operator holds §4.4** | The CA-issuance assumption. Eliminated structurally by a separate peer CA (R3). This is the one vector that depends on operator discipline — call it out in ops docs. |
| Client OIDC token bearing the node-claim | **No** | (a) The interior transport is mTLS-only; a token has no wire path there (dormant). (b) When live, the IdP must not grant the node-claim to clients (§4.4). |
| Client Basic account named `node` | **No** | Same: mTLS-only interior today; the `node` account is node-only when live. |
| Client **replaying** a captured peer frame (`senderId`/`leaderId`) | **No** | Layer 2 binding: `senderId` must equal the *pinned cert identity* of the connection it arrives on (`NettyRaftTransport.java:450-456`), and in-body `leaderId`/`candidateId` must equal the sender (`RaftTransportAdapter.java:246-253`). A replay on a client's own mTLS session carries the client's pinned identity, not the peer's → dropped. mTLS confidentiality/integrity blocks off-path injection into an established peer session. |
| A **real node** cert used to dial the **edge** plane as a client | **No interior grant** | The edge never routes to a `RaftNode`; the node identity is an ordinary watch-authz `Principal` there (§3.3, §4.2). |
| Auth **disabled** (allow-list empty, warning path) | **Yes — by explicit operator choice** | This is the residual the fail-closed default (R4/§4.3) closes for auth-enabled deployments. |

**What the gate must check (summary):** at the Raft ingress, before a connection's frames are dispatched into
a `RaftNode`, the connection's *verified* credential must carry the node marker (mTLS: marker RDN value ∈
allow-list → `NodeId`, optionally chained to the peer CA; OIDC: node-claim present; Basic: `node` account).
Missing marker ⇒ the connection is **not** a peer — at most a client.

---

## 5. Test matrix — proving a client credential never reaches the interior

Each row asserts the *negative*: a real, valid **client** credential presenting for node-join is rejected at
the Raft ingress and its frames never reach a `RaftNode`. Existing coverage to extend:
`RaftPeerIdentityBindingTest`, `NettyRaftPeerIdentityBindingTest`, `HostilePeerInjectionE2ETest`,
`PeerIdentityPolicyTest`.

| # | Setup | Action | Expected | Asserts |
|---|---|---|---|---|
| T1 | Enforced allow-list `{node-1=1,node-2=2}` | Client cert `CN=alice` (CA-valid) dials the Raft port | Handshake completes; `resolve` = `null` → connection dropped at Layer 1; `configd_raft_peer_identity_mismatch` +1; **no** frame dispatched | Client CN not in allow-list ⇒ never a peer. |
| T2 | Enforced allow-list | Client cert `CN=node-1` **but** identity maps to a *different* NodeId in frames (`senderId=2`) | Pinned=1; Layer 2 drops every `senderId=2` frame + metric | senderId≠pinned ⇒ dropped (impersonation of another node). |
| T3 | Enforced allow-list | Peer `CN=node-1` (`senderId=1`) but in-body `leaderId=2` | In-body binding drops the frame (`RaftTransportAdapter`) + metric | Forged in-body routing id rejected even when senderId matches. |
| T4 | Enforced allow-list, coalesced heartbeat | Bundle where one entry's `leaderId`≠sender | **Whole frame** dropped before any dispatch + metric | Late-entry forgery cannot slip earlier entries through. |
| T5 | **Separate peer CA** (R3) | Client cert from the **client** CA, `CN=node-1` (marker matches!) | Handshake **fails** (cert not trusted by peer anchor) → never reaches `resolve()` | Separate trust anchor beats even a CN match — structural. |
| T6 | Auth enabled, TLS on, **empty** allow-list | Boot the server | **Boot error** (R4/§4.3), not a warning | Strict-by-default; no CA-chain-only interior under auth. |
| T7 | Reverse path | We dial `node-2`; the far end (a hostile accept) replies with `senderId=1` | Pinned = dialTarget (2); reply `senderId=1` dropped | Reverse-path binding (`TcpRaftTransport.java:529-531`). |
| T8 | Edge plane | Real **node** cert `CN=node-1` opens an edge (watch) connection | Admitted as an ordinary `Principal`; watch-authz applies; **no** interior/Raft effect | Node marker confers interior standing *only* at the Raft ingress. |
| T9 | (Forward-ext, when live) OIDC | Client token lacking the node-claim presents at a token-bearing interior frame | Rejected → at most a client `Principal` | OIDC node-claim fail-closed. |
| T10 | (Forward-ext, when live) Basic | Non-`node` account presents for node-join | Rejected | Designated node principal fail-closed. |
| T11 | Metric parity | Any of T1–T4, both transports | Both Netty and JDK transports produce identical accept/reject + identical `configd_raft_peer_identity_mismatch` deltas | Transport-tier parity (the wire-hardening parity discipline). |

T9/T10 are written now against the *dormant* markers so that activating the forward extension does not ship
an untested interior door.

---

## 6. Open questions for the operator (decisions)

- **OA-NJ-1 (R3) — separate peer CA vs shared-CA + allow-list.** Recommend supporting an **optional separate
  peer trust anchor** for the Raft transport (etcd `--peer-trusted-ca-file` / ZK `ssl.quorum.trustStore`
  parity) as the strong default, with the per-node allow-list as the always-on minimum. Needs a config seam on
  the Raft `TlsManager` (a distinct peer keystore/truststore). **Decision:** ship the separate-CA option in
  Group B, or document the shared-CA CN-discipline assumption and defer the separate anchor?
- **OA-NJ-2 (R4) — fail-closed default timing.** Flipping empty-allow-list-under-auth from warning to boot
  error changes startup behavior for any deployment that enabled TLS on the Raft plane without an allow-list.
  Confirm this is acceptable for the auth-enabled path (it should be — it is the whole point of the arc), and
  that the auth-*disabled* escape keeps the loud-warning open gate.
- **OA-NJ-3 (R2) — reserved `CN=node` convention.** Do operators want the etcd/CRDB-style single reserved
  `CN=node` as a documented default, or is the per-node CN allow-list the only sanctioned shape? (The
  allow-list can express both; this is a docs/ergonomics call.)
- **OA-NJ-4 (R5/R6) — when to make OIDC/Basic node-markers live.** These require the token-bearing interior
  auth frame (RFC §03 AU3-3). Confirm they stay **dormant + fail-closed** for v1 (recommended) and are not
  built until that frame is designed — building the claim/account check without the frame would be dead code
  with a false sense of coverage.
- **OA-NJ-5 — marker RDN choice.** Default `CN`. Some PKI conventions carry node identity in a SAN URI
  (SPIFFE) or an OU. Confirm `CN` default with the `configd.raft.peerIdentity.marker` override is sufficient,
  or whether a SAN-URI marker mode is wanted (parallels the auth-SPI's SPIFFE note).
