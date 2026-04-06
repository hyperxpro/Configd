# Getting Started

## Prerequisites

- **Java 21+** (Temurin/Corretto/GraalCE recommended)
- **Gradle 8.14+** (wrapper included — no global install needed)

## Build from Source

```bash
git clone <repo-url> && cd configd
./gradlew build
```

This compiles all modules and runs the full test suite. JARs are written to each module's `build/libs/` directory.

To build without tests:

```bash
./gradlew build -x test
```

## Run Tests

```bash
./gradlew test
```

Tests use JUnit 5 and require `--enable-preview` (configured automatically by the build script). The test suite includes:

- Unit tests for each module
- Deterministic Raft cluster simulation (`configd-testkit`)
- End-to-end write → store → delta → edge tests

## Project Structure

```
configd/
├── configd-common/           # Shared types: NodeId, Clock, HybridTimestamp
├── configd-consensus-core/   # Raft consensus: RaftNode, RaftLog, elections
├── configd-config-store/     # MVCC store: VersionedConfigStore, HamtMap
├── configd-edge-cache/       # Edge reads: LocalConfigStore, StalenessTracker
├── configd-transport/        # Transport abstraction (Netty, simulation)
├── configd-testkit/          # Deterministic simulation framework
├── docker/                   # Docker build and runtime images
├── docs/                     # Architecture docs, ADRs, wiki
├── build.gradle.kts          # Root build script
└── settings.gradle.kts       # Module declarations
```

## Using as a Library

Configd is a library — there is no standalone server binary (yet). You embed it in your Java application. See the [Integration Guide](Integration-Guide.md) for details.

Add the modules you need as dependencies. At minimum, most applications need:

```kotlin
// build.gradle.kts
dependencies {
    implementation("io.configd:configd-common:0.1.0-SNAPSHOT")
    implementation("io.configd:configd-config-store:0.1.0-SNAPSHOT")
    implementation("io.configd:configd-edge-cache:0.1.0-SNAPSHOT")
}
```

## Recommended JVM Flags

Configd is designed for Java 21 with low-latency GC. Recommended production flags:

```bash
java --enable-preview \
     -XX:+UseZGC \
     -XX:+ZGenerational \
     -Xms256m -Xmx2g \
     -XX:+AlwaysPreTouch \
     -jar your-app.jar
```

For edge nodes where read latency is critical, pin the Raft I/O thread to a dedicated core and avoid GC pauses on the read path (reads are allocation-free by design).
