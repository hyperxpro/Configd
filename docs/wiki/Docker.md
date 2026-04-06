# Docker

Configd provides two Dockerfiles in the `docker/` directory:

| File | Purpose | Base Image |
|------|---------|------------|
| `Dockerfile.build` | CI/CD — build and test in a hermetic container | `eclipse-temurin:21-jdk-noble` |
| `Dockerfile.runtime` | Distroless runtime base for service modules | `gcr.io/distroless/java21-debian12:nonroot` |

## Why Two Images?

Configd is currently a **library** — there is no standalone server binary. The build image compiles and tests the library. The runtime image packages the JARs into a minimal distroless container that downstream services can extend when a main class is added.

## Build Image

Builds all modules and runs the test suite.

```bash
# Build the image
docker build -f docker/Dockerfile.build -t configd-build .

# Run build + tests
docker run --rm configd-build

# Run build without tests
docker run --rm configd-build ./gradlew build -x test --no-daemon

# Run only a specific module's tests
docker run --rm configd-build ./gradlew :configd-consensus-core:test --no-daemon
```

### Extracting JARs

```bash
docker create --name configd-out configd-build ./gradlew build -x test --no-daemon
docker start -a configd-out
docker cp configd-out:/workspace/configd-common/build/libs/ ./out/common/
docker cp configd-out:/workspace/configd-consensus-core/build/libs/ ./out/consensus/
docker cp configd-out:/workspace/configd-config-store/build/libs/ ./out/store/
docker cp configd-out:/workspace/configd-edge-cache/build/libs/ ./out/edge/
docker cp configd-out:/workspace/configd-transport/build/libs/ ./out/transport/
docker rm configd-out
```

## Runtime Image (Distroless)

Multi-stage build that compiles Configd, then copies the JARs into a distroless Java 21 container.

```bash
docker build -f docker/Dockerfile.runtime -t configd-runtime .
```

### What Is Distroless?

[Distroless images](https://github.com/GoogleContainerTools/distroless) contain only the application and its runtime dependencies — no shell, no package manager, no OS utilities. This minimizes the attack surface and image size.

The Configd runtime image uses `gcr.io/distroless/java21-debian12:nonroot`, which:

- Runs as a non-root user (UID 65534)
- Contains only the JRE 21 and required system libraries
- Has no shell (`/bin/sh` does not exist)

### Extending for a Service Module

When the control plane API or another service module is implemented, extend the runtime image:

```dockerfile
FROM configd-runtime

COPY my-service/build/libs/my-service.jar /app/my-service.jar

ENTRYPOINT ["java", \
    "--enable-preview", \
    "-XX:+UseZGC", \
    "-XX:+ZGenerational", \
    "-cp", "/app/libs/*:/app/my-service.jar", \
    "io.configd.controlplane.Main"]
```

### Inspecting the Image

Since distroless has no shell, use `docker export` or a debug variant:

```bash
# List files in the image
docker create --name tmp configd-runtime true
docker export tmp | tar tf - | grep /app/
docker rm tmp

# Use the debug variant for troubleshooting (has busybox shell)
# Replace :nonroot with :debug-nonroot in Dockerfile.runtime, rebuild,
# then: docker run --rm -it configd-runtime-debug sh
```

## Docker Compose (CI Example)

```yaml
services:
  build:
    build:
      context: .
      dockerfile: docker/Dockerfile.build
    volumes:
      - gradle-cache:/root/.gradle
    command: ["./gradlew", "build", "--no-daemon"]

volumes:
  gradle-cache:
```

## Layer Caching

Both Dockerfiles are structured for optimal layer caching:

1. **Gradle wrapper** — changes very rarely
2. **Build scripts** (`build.gradle.kts`, `settings.gradle.kts`) — changes sometimes; triggers dependency re-download
3. **Source code** — changes frequently; only recompiles

This means dependency downloads are cached across builds as long as your build scripts don't change.
