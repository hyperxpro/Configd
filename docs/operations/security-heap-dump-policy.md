# Heap Dump Security Policy

## Risk

All config values are stored as raw `byte[]` in memory within:
- `VersionedValue.value` (HAMT leaf nodes)
- `LogEntry.command` (Raft log entries containing serialized PUT commands)
- `ConfigSnapshot.data` (full HAMT tree)
- Snapshot transfer buffers

A JVM heap dump (triggered by OOM, `-XX:+HeapDumpOnOutOfMemoryError`, or `jcmd`) captures all config values in plaintext. Since Configd stores operational configuration that may include sensitive values, heap dumps must be treated as sensitive artifacts.

## Mitigations

### Operational Controls (required)

1. **Restrict heap dump access:** Heap dump files must be stored in access-controlled directories. Only SRE personnel with production access may read heap dump files.

2. **Disable automatic heap dumps in production:** Do not use `-XX:+HeapDumpOnOutOfMemoryError` in production JVM flags unless actively debugging. Use `-XX:+ExitOnOutOfMemoryError` instead to fail fast without writing a dump.

3. **Secure `jcmd` access:** Restrict access to the `jcmd` tool and the JVM's diagnostic port. Container security policies should prevent arbitrary command execution.

4. **Encrypt heap dumps at rest:** If heap dumps are collected for diagnostics, encrypt them with a key managed by the security team. Delete dumps after analysis.

### Future Engineering Controls (planned)

1. **Envelope encryption for sensitive values:** Values marked as sensitive will be stored encrypted in the HAMT and Raft log, decrypted only at read time with a key held in a KMS or hardware security module.

2. **Secret value type:** A `SecretValue` wrapper that is redacted in `toString()`, debug output, and diagnostic tools.

3. **Off-heap storage:** For large values, consider Agrona direct buffers that are not captured in standard heap dumps.

## Non-Goals

Configd is not a secrets manager (PROMPT.md Section 0.2). Dedicated secrets management should use purpose-built systems (HashiCorp Vault, AWS Secrets Manager, etc.). This policy addresses incidental exposure of operational config that may contain sensitive data.
