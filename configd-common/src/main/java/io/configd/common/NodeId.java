package io.configd.common;

public record NodeId(int id) implements Comparable<NodeId> {

    public static NodeId of(int id) {
        return new NodeId(id);
    }

    @Override
    public int compareTo(NodeId other) {
        return Integer.compare(this.id, other.id);
    }

    @Override
    public String toString() {
        return "Node-" + id;
    }
}
