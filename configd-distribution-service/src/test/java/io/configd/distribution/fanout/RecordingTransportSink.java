package io.configd.distribution.fanout;

import io.configd.distribution.wire.EdgeFrame;
import io.configd.distribution.wire.ErrorCode;

import java.util.ArrayList;
import java.util.List;

final class RecordingTransportSink implements TransportSink {

    private final List<EdgeFrame> sent = new ArrayList<>();
    private boolean closed;
    private ErrorCode closeCode;
    private String closeMessage;

    private int blockNext;

    @Override
    public boolean offer(EdgeFrame frame) {
        if (closed) {
            return false;
        }
        if (blockNext > 0) {
            blockNext--;
            return false;
        }
        sent.add(frame);
        return true;
    }

    @Override
    public void close(ErrorCode code, String message) {
        closed = true;
        closeCode = code;
        closeMessage = message;
    }

    void blockNextOffers(int n) {
        this.blockNext = n;
    }

    List<EdgeFrame> sent() {
        return sent;
    }

    @SuppressWarnings("unchecked")
    <T extends EdgeFrame> List<T> sentOfType(Class<T> type) {
        List<T> out = new ArrayList<>();
        for (EdgeFrame f : sent) {
            if (type.isInstance(f)) {
                out.add((T) f);
            }
        }
        return out;
    }

    boolean closed() {
        return closed;
    }

    ErrorCode closeCode() {
        return closeCode;
    }

    String closeMessage() {
        return closeMessage;
    }

    void clear() {
        sent.clear();
    }
}
