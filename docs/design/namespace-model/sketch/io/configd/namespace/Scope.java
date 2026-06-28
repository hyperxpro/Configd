package io.configd.namespace;

/**
 * Local stand-in for {@code io.configd.common.ConfigScope} so the sketch compiles standalone (design
 * artifact — not wired). The replication-domain axis: an ORTHOGONAL typed dimension, never a path
 * segment (RFC §2, A2-1). Mirrors the built {@code {GLOBAL, REGIONAL, LOCAL}}.
 */
public enum Scope { GLOBAL, REGIONAL, LOCAL }
