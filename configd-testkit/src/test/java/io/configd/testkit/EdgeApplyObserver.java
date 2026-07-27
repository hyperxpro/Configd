package io.configd.testkit;

@FunctionalInterface
interface EdgeApplyObserver {

    void onApplied(int edgeId, long seq, long commitTsMillis, long visibleTsMillis);

    EdgeApplyObserver NONE = (edgeId, seq, commitTsMillis, visibleTsMillis) -> { };
}
