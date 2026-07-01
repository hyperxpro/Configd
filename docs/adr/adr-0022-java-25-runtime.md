# ADR-0022: Java 25 Runtime

## Status
Accepted (2026-04-11)

## Context

The original spec (PROMPT.md Section 3) and ADR-0009 specify "Java 21+ LTS" as the runtime. The implementation uses Java 25 (Amazon Corretto 25.0.2), which is a non-LTS release.

## Decision

Target Java 25 as the runtime, with a planned migration to Java 29 LTS (expected 2027-09) when available.

## Rationale

1. **Sealed class maturity:** Java 25 provides stable sealed classes and pattern matching (finalized in JDK 21) with performance improvements in the JIT compiler for sealed type hierarchies. The Raft `RaftMessage` sealed interface benefits from devirtualization optimizations added in JDK 23+.

2. **ZGC improvements:** Java 25 includes generational ZGC improvements that reduce tail latency for the edge read path. Sub-millisecond GC pauses are critical for the p999 < 5ms edge read target.

3. **Preview features:** The `--enable-preview` flag is used for select language features. These are isolated and can be migrated when they stabilize.

4. **Corretto support:** Amazon Corretto provides quarterly security patches for non-LTS releases, with a support window through the next release cycle.

## Migration Plan

1. Track Java 29 LTS availability (expected September 2027)
2. Evaluate preview feature stabilization in each interim release
3. Migrate to Java 29 LTS within 3 months of its GA release
4. Until then, Amazon Corretto 25 provides security patch coverage

## Consequences

- No long-term vendor support guarantee beyond the next release cycle
- Must track and migrate preview features as they stabilize or change
- CI must test against the specific JDK version (pinned in CI config)
