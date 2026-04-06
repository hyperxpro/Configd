package io.configd.api;

import io.configd.common.ConfigScope;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ConfigWriteServiceTest {

    @Test
    void putAcceptedByLeader() {
        ConfigWriteService service = new ConfigWriteService(
                (scope, cmd) -> true, null, null);

        var result = service.put("my.key", new byte[]{1, 2, 3}, ConfigScope.GLOBAL);
        assertInstanceOf(ConfigWriteService.WriteResult.Accepted.class, result);
    }

    @Test
    void putRejectedByNonLeader() {
        ConfigWriteService service = new ConfigWriteService(
                (scope, cmd) -> false, null, null);

        var result = service.put("my.key", new byte[]{1}, ConfigScope.GLOBAL);
        assertInstanceOf(ConfigWriteService.WriteResult.NotLeader.class, result);
    }

    @Test
    void putWithValidationFailure() {
        ConfigWriteService service = new ConfigWriteService(
                (scope, cmd) -> true,
                (key, value) -> "value too large",
                null);

        var result = service.put("my.key", new byte[]{1}, ConfigScope.GLOBAL);
        assertInstanceOf(ConfigWriteService.WriteResult.ValidationFailed.class, result);
        assertEquals("value too large",
                ((ConfigWriteService.WriteResult.ValidationFailed) result).reason());
    }

    @Test
    void putWithRateLimiting() {
        RateLimiter limiter = new RateLimiter(
                io.configd.common.Clock.system(), 0.001, 0.001);
        // Exhaust the bucket
        limiter.tryAcquire();

        ConfigWriteService service = new ConfigWriteService(
                (scope, cmd) -> true, null, limiter);

        var result = service.put("key", new byte[]{1}, ConfigScope.GLOBAL);
        assertInstanceOf(ConfigWriteService.WriteResult.Overloaded.class, result);
    }

    @Test
    void blankKeyRejected() {
        ConfigWriteService service = new ConfigWriteService(
                (scope, cmd) -> true, null, null);

        var result = service.put("  ", new byte[]{1}, ConfigScope.GLOBAL);
        assertInstanceOf(ConfigWriteService.WriteResult.ValidationFailed.class, result);
    }

    @Test
    void deleteAccepted() {
        ConfigWriteService service = new ConfigWriteService(
                (scope, cmd) -> true, null, null);

        var result = service.delete("my.key", ConfigScope.REGIONAL);
        assertInstanceOf(ConfigWriteService.WriteResult.Accepted.class, result);
    }

    @Test
    void proposalIdsIncrement() {
        ConfigWriteService service = new ConfigWriteService(
                (scope, cmd) -> true, null, null);

        var r1 = (ConfigWriteService.WriteResult.Accepted) service.put("a", new byte[]{1}, ConfigScope.GLOBAL);
        var r2 = (ConfigWriteService.WriteResult.Accepted) service.put("b", new byte[]{2}, ConfigScope.GLOBAL);
        assertTrue(r2.proposalId() > r1.proposalId());
    }

    @Test
    void notLeaderIncludesLeaderHint() {
        io.configd.common.NodeId leaderNode = io.configd.common.NodeId.of(5);
        ConfigWriteService service = new ConfigWriteService(
                (scope, cmd) -> false, null, null, () -> leaderNode);

        var result = service.put("key", new byte[]{1}, ConfigScope.GLOBAL);
        assertInstanceOf(ConfigWriteService.WriteResult.NotLeader.class, result);
        assertEquals(leaderNode,
                ((ConfigWriteService.WriteResult.NotLeader) result).leaderId());
    }

    @Test
    void notLeaderWithNullHintWhenNoSupplier() {
        ConfigWriteService service = new ConfigWriteService(
                (scope, cmd) -> false, null, null);

        var result = service.put("key", new byte[]{1}, ConfigScope.GLOBAL);
        assertInstanceOf(ConfigWriteService.WriteResult.NotLeader.class, result);
        assertNull(((ConfigWriteService.WriteResult.NotLeader) result).leaderId());
    }

    @Test
    void deleteNotLeaderIncludesLeaderHint() {
        io.configd.common.NodeId leaderNode = io.configd.common.NodeId.of(3);
        ConfigWriteService service = new ConfigWriteService(
                (scope, cmd) -> false, null, null, () -> leaderNode);

        var result = service.delete("key", ConfigScope.GLOBAL);
        assertInstanceOf(ConfigWriteService.WriteResult.NotLeader.class, result);
        assertEquals(leaderNode,
                ((ConfigWriteService.WriteResult.NotLeader) result).leaderId());
    }
}
