package io.configd.jdkvsnetty;

import io.configd.transport.MessageType;

/**
 * An immutable consensus-frame message handed to {@link NettyConsensusFrameEncoder} in the
 * idiomatic Netty path. One instance is reused across all sends in the benchmark (the payload is
 * constant), so it adds no per-message allocation of its own - the only per-message work is the
 * encode inside the pipeline.
 */
record FrameMsg(MessageType type, int groupId, long term, byte[] payload, int senderId) {
}
