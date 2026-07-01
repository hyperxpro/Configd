# ADR-0021: Maven Build System

## Status
Accepted (2026-04-11)

## Context

The original rewrite plan (docs/rewrite-plan.md) specified Gradle with Kotlin DSL as the build system, citing parallel task execution, incremental compilation, build cache, and type-safe configuration as benefits.

During implementation, Maven was chosen instead. This ADR documents the rationale.

## Decision

Use Apache Maven for the build system instead of Gradle.

## Rationale

1. **Ecosystem maturity:** Maven has broader tooling support in CI/CD systems, IDE integrations, and dependency management plugins. The Maven Central publishing workflow is simpler.

2. **Deterministic builds:** Maven's declarative POM model produces more predictable builds. Gradle's imperative build scripts can diverge across environments.

3. **Simpler dependency management:** Maven's `<dependencyManagement>` section provides clear, centralized version pinning without Gradle's platform/BOM complexity.

4. **Team familiarity:** The project's core domain (distributed consensus, formal verification) demands engineering attention. A familiar build system reduces cognitive overhead.

5. **Acceptable trade-offs:** Gradle's incremental compilation and build cache benefits are marginal for a project of this size (10 modules, ~150 source files). Full Maven builds complete in under 30 seconds.

## Consequences

- Incremental build benefits cited in the rewrite plan are not realized
- Build cache for CI is handled at the Maven repository level (`.m2/repository` caching in CI)
- The `settings.gradle.kts` referenced in the rewrite plan does not exist and is not needed
