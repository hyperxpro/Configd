package io.configd.api;

import io.configd.api.ConfigWriteService.ProposeCommitResult;
import io.configd.api.ConfigWriteService.WriteResult;
import io.configd.common.ConfigScope;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The write service is commit-confirmed. These tests assert the taxonomy: a
 * successful write returns {@link WriteResult.Committed} carrying the
 * applied-mutation sequence (NOT a free-running local proposalId), and the
 * failure variants Lost / Indeterminate are distinguishable from both success
 * and from NotLeader / Overloaded.
 */
class ConfigWriteServiceTest {

    private static ConfigWriteService.RaftProposer proposerReturning(ProposeCommitResult outcome) {
        return (scope, keys, cmd) -> outcome;
    }

    @Test
    void putCommittedByLeaderCarriesSeq() {
        ConfigWriteService service = new ConfigWriteService(
                proposerReturning(new ProposeCommitResult.Committed(42L)), null, null);

        var result = service.put("my.key", new byte[]{1, 2, 3}, ConfigScope.GLOBAL);
        assertInstanceOf(WriteResult.Committed.class, result);
        assertEquals(42L, ((WriteResult.Committed) result).seq(),
                "Committed must carry the applied-mutation sequence S (contract §6 cursor)");
    }

    @Test
    void putNotLeaderIsRejectedPreAppend() {
        ConfigWriteService service = new ConfigWriteService(
                proposerReturning(new ProposeCommitResult.NotLeader()), null, null);

        var result = service.put("my.key", new byte[]{1}, ConfigScope.GLOBAL);
        assertInstanceOf(WriteResult.NotLeader.class, result);
    }

    @Test
    void putLostAfterAppendIsDistinctFromNotLeaderAndSuccess() {
        ConfigWriteService service = new ConfigWriteService(
                proposerReturning(new ProposeCommitResult.Lost()), null, null);

        var result = service.put("my.key", new byte[]{1}, ConfigScope.GLOBAL);
        assertInstanceOf(WriteResult.Lost.class, result,
                "post-append definite loss must be a distinct, retryable variant");
        assertFalse(result instanceof WriteResult.Committed);
        assertFalse(result instanceof WriteResult.NotLeader);
    }

    @Test
    void putIndeterminateIsDistinctFromSuccessAndDefiniteFailure() {
        ConfigWriteService service = new ConfigWriteService(
                proposerReturning(new ProposeCommitResult.Indeterminate()), null, null);

        var result = service.put("my.key", new byte[]{1}, ConfigScope.GLOBAL);
        assertInstanceOf(WriteResult.Indeterminate.class, result,
                "deadline-expiry-with-unknown-outcome must be distinguishable from success and definite failure");
        assertFalse(result instanceof WriteResult.Committed);
        assertFalse(result instanceof WriteResult.Lost);
        assertFalse(result instanceof WriteResult.NotLeader);
    }

    @Test
    void putWithValidationFailure() {
        ConfigWriteService service = new ConfigWriteService(
                proposerReturning(new ProposeCommitResult.Committed(1L)),
                (key, value) -> "value too large",
                null);

        var result = service.put("my.key", new byte[]{1}, ConfigScope.GLOBAL);
        assertInstanceOf(WriteResult.ValidationFailed.class, result);
        assertEquals("value too large", ((WriteResult.ValidationFailed) result).reason());
    }

    @Test
    void putAtExactlyTheValueCapPassesTheApiGate() {
        // The built-in API-layer value-size gate rejects ABOVE 1 MiB, not AT it: a value at exactly
        // the cap proceeds past the gate to the proposer and commits.
        ConfigWriteService service = new ConfigWriteService(
                proposerReturning(new ProposeCommitResult.Committed(9L)), null, null);

        var result = service.put("my.key", new byte[1_048_576], ConfigScope.GLOBAL);
        assertInstanceOf(WriteResult.Committed.class, result,
                "a value at exactly the 1 MiB cap is accepted by the API gate");
        assertEquals(9L, ((WriteResult.Committed) result).seq());
    }

    @Test
    void putOneByteOverTheValueCapIsRejectedByTheRealApiGate() {
        // Un-stubbed: no fabricating validator (validator == null). The value-size check built into
        // ConfigWriteService.put is what must reject an oversized value - exercised here for real,
        // one byte over the cap. (putWithValidationFailure above stubs a validator that fires on a
        // 1-byte value, which never reaches this gate.)
        ConfigWriteService service = new ConfigWriteService(
                proposerReturning(new ProposeCommitResult.Committed(1L)), null, null);

        var result = service.put("my.key", new byte[1_048_577], ConfigScope.GLOBAL);
        assertInstanceOf(WriteResult.ValidationFailed.class, result);
        assertEquals("value size exceeds maximum of 1048576 bytes (1 MB)",
                ((WriteResult.ValidationFailed) result).reason(),
                "the real API-layer value-size gate rejects one byte over the cap");
    }

    @Test
    void putWithRateLimiting() {
        RateLimiter limiter = new RateLimiter(
                io.configd.common.Clock.system(), 0.001, 0.001);
        limiter.tryAcquire();

        ConfigWriteService service = new ConfigWriteService(
                proposerReturning(new ProposeCommitResult.Committed(1L)), null, limiter);

        var result = service.put("key", new byte[]{1}, ConfigScope.GLOBAL);
        assertInstanceOf(WriteResult.Overloaded.class, result);
    }

    @Test
    void overloadedFromProposerMapsToOverloaded() {
        ConfigWriteService service = new ConfigWriteService(
                proposerReturning(new ProposeCommitResult.Overloaded()), null, null);

        var result = service.put("key", new byte[]{1}, ConfigScope.GLOBAL);
        assertInstanceOf(WriteResult.Overloaded.class, result);
    }

    @Test
    void blankKeyRejected() {
        ConfigWriteService service = new ConfigWriteService(
                proposerReturning(new ProposeCommitResult.Committed(1L)), null, null);

        var result = service.put("  ", new byte[]{1}, ConfigScope.GLOBAL);
        assertInstanceOf(WriteResult.ValidationFailed.class, result);
    }

    @Test
    void deleteCommittedCarriesSeq() {
        ConfigWriteService service = new ConfigWriteService(
                proposerReturning(new ProposeCommitResult.Committed(7L)), null, null);

        var result = service.delete("my.key", ConfigScope.REGIONAL);
        assertInstanceOf(WriteResult.Committed.class, result);
        assertEquals(7L, ((WriteResult.Committed) result).seq());
    }

    @Test
    void notLeaderIncludesLeaderHint() {
        io.configd.common.NodeId leaderNode = io.configd.common.NodeId.of(5);
        ConfigWriteService service = new ConfigWriteService(
                proposerReturning(new ProposeCommitResult.NotLeader()), null, null, (scope, key) -> leaderNode);

        var result = service.put("key", new byte[]{1}, ConfigScope.GLOBAL);
        assertInstanceOf(WriteResult.NotLeader.class, result);
        assertEquals(leaderNode, ((WriteResult.NotLeader) result).leaderId());
    }

    @Test
    void lostIncludesLeaderHintWhenKnown() {
        io.configd.common.NodeId leaderNode = io.configd.common.NodeId.of(2);
        ConfigWriteService service = new ConfigWriteService(
                proposerReturning(new ProposeCommitResult.Lost()), null, null, (scope, key) -> leaderNode);

        var result = service.put("key", new byte[]{1}, ConfigScope.GLOBAL);
        assertInstanceOf(WriteResult.Lost.class, result);
        assertEquals(leaderNode, ((WriteResult.Lost) result).leaderHint());
    }

    @Test
    void notLeaderWithNullHintWhenNoSupplier() {
        ConfigWriteService service = new ConfigWriteService(
                proposerReturning(new ProposeCommitResult.NotLeader()), null, null);

        var result = service.put("key", new byte[]{1}, ConfigScope.GLOBAL);
        assertInstanceOf(WriteResult.NotLeader.class, result);
        assertNull(((WriteResult.NotLeader) result).leaderId());
    }

    @Test
    void deleteNotLeaderIncludesLeaderHint() {
        io.configd.common.NodeId leaderNode = io.configd.common.NodeId.of(3);
        ConfigWriteService service = new ConfigWriteService(
                proposerReturning(new ProposeCommitResult.NotLeader()), null, null, (scope, key) -> leaderNode);

        var result = service.delete("key", ConfigScope.GLOBAL);
        assertInstanceOf(WriteResult.NotLeader.class, result);
        assertEquals(leaderNode, ((WriteResult.NotLeader) result).leaderId());
    }
}
