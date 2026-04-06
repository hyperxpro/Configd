# Changelog

All notable changes to Configd are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

The on-disk wire format and snapshot format follow the
[ADR-0010](docs/decisions/adr-0010-netty-grpc-transport.md) framing
contract. Per §8.10 of the gap-closure rules, any wire-incompatible
change MUST land in two consecutive minor releases (deprecation in
`N`, removal no earlier than `N+2`) before the older format is
removed.

## [Unreleased]

### Added

### Changed

### Deprecated

### Removed

### Fixed

### Security

## [0.1.0] - TBD

GA target. Tracked in `docs/progress.md` and `docs/ga-review.md`. The
release is cut per `ops/runbooks/release.md` once every Phase-11 gate
is GREEN. Until the tag is pushed and the release workflow
(`.github/workflows/release.yml`) emits a verified Cosign-signed
image with SLSA provenance, this entry remains a placeholder.
