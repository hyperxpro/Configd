package io.configd.common;

/**
 * Unique identifier for a node in the Configd cluster.
 * Value type — two NodeIds with the same id are equal.
 */
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
