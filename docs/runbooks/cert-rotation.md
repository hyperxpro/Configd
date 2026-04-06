# Runbook: Certificate Rotation Procedure

## Detection
- Alert: `configd_transport_tls_cert_expiry_days < 30` on any node.
- Dashboard: "TLS Health" panel shows certificate expiry countdown per node.
- Symptom: upcoming TLS certificate expiration will cause `ConnectionManager` to reject new connections; existing connections may fail mid-session.
- Metric: `configd_transport_tls_handshake_failures_total` increasing as peers reject expired certs.

## Diagnosis
1. Check current certificate expiry across all nodes:
   ```bash
   curl -s http://<node>:8080/metrics | grep configd_transport_tls_cert_expiry
   ```
2. Verify `TlsConfig` on each node points to the correct keystore and truststore paths.
3. Confirm the CA certificate in the truststore is valid and matches the issuer of node certificates.
4. Check `TlsManager` logs for any pending rotation state or errors from previous rotation attempts.

## Mitigation
1. If certificates have ALREADY expired:
   - Raft consensus continues on existing connections (TCP keepalive), but no new connections can be established.
   - `ConnectionManager` will fail to reconnect partitioned peers.
   - Prioritize rotation on the leader node first to maintain write availability.
2. If expiry is imminent (< 7 days), begin emergency rotation immediately.

## Recovery
Perform a rolling certificate rotation (zero-downtime):

1. **Generate new certificates** signed by the same CA (or a new CA if rotating the CA):
   ```bash
   keytool -genkeypair -alias configd-node-<id> -keyalg EC -groupname secp256r1 \
     -keystore /etc/configd/tls/keystore.p12 -storetype PKCS12
   ```

2. **Update truststore** on ALL nodes first (add new CA cert alongside old):
   ```bash
   keytool -importcert -alias configd-ca-new -file ca-new.pem \
     -keystore /etc/configd/tls/truststore.p12 -storetype PKCS12
   ```

3. **Rotate node certificates one at a time**, starting with followers:
   ```bash
   # On each node:
   cp new-keystore.p12 /etc/configd/tls/keystore.p12
   curl -X POST http://<node>:8080/admin/tls-reload
   ```
   `TlsManager` reloads the keystore without restarting the JVM.

4. Wait for `ConnectionManager` to re-establish connections with the new cert.

5. After ALL nodes have new certs, remove old CA from truststores:
   ```bash
   keytool -delete -alias configd-ca-old -keystore /etc/configd/tls/truststore.p12
   ```

## Verification
- `configd_transport_tls_cert_expiry_days` reports the new expiry on all nodes.
- `configd_transport_tls_handshake_failures_total` is 0 (no new failures).
- `configd_transport_connections_active` matches expected peer count on each node.
- `RaftNode` leader can send heartbeats to all peers (check `configd_raft_heartbeat_sent_total`).
- Edge nodes via `EdgeConfigClient` reconnect with new TLS context.

## Prevention
- Automate certificate rotation with a cert-manager or ACME-based renewal 60 days before expiry.
- Set alerts at 90, 60, and 30 days before expiry with escalating severity.
- Use short-lived certificates (90 days) with automated renewal to reduce rotation risk.
- Test rotation procedure quarterly in staging using the `TlsConfig` integration tests.
- Store certificate metadata in `VersionedConfigStore` for centralized expiry tracking.
