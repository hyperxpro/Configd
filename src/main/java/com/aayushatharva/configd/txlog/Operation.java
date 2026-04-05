package com.aayushatharva.configd.txlog;

/** Transaction log operation types. */
public enum Operation {
    SET((byte) 1),
    DELETE((byte) 2);

    private final byte code;

    Operation(byte code) {
        this.code = code;
    }

    public byte code() {
        return code;
    }

    public static Operation fromCode(byte code) {
        return switch (code) {
            case 1 -> SET;
            case 2 -> DELETE;
            default -> throw new IllegalArgumentException("Unknown operation code: " + code);
        };
    }
}
