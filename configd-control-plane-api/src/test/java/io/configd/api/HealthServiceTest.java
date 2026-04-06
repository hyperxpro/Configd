package io.configd.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HealthServiceTest {

    @Test
    void livenessAlwaysHealthy() {
        HealthService service = new HealthService();
        var status = service.liveness();
        assertTrue(status.healthy());
    }

    @Test
    void readinessWithNoChecks() {
        HealthService service = new HealthService();
        var status = service.readiness();
        assertTrue(status.healthy());
    }

    @Test
    void readinessWithPassingChecks() {
        HealthService service = new HealthService();
        service.registerReadinessCheck(() -> HealthService.CheckResult.healthy("raft"));
        service.registerReadinessCheck(() -> HealthService.CheckResult.healthy("store"));

        var status = service.readiness();
        assertTrue(status.healthy());
        assertEquals(2, status.checks().size());
    }

    @Test
    void readinessUnhealthyWhenCheckFails() {
        HealthService service = new HealthService();
        service.registerReadinessCheck(() -> HealthService.CheckResult.healthy("raft"));
        service.registerReadinessCheck(
                () -> HealthService.CheckResult.unhealthy("store", "version lag > 5000"));

        var status = service.readiness();
        assertFalse(status.healthy());
        assertTrue(status.checks().stream().anyMatch(c -> !c.healthy()));
    }

    @Test
    void detailedEqualsReadiness() {
        HealthService service = new HealthService();
        service.registerReadinessCheck(() -> HealthService.CheckResult.healthy("test"));

        assertEquals(service.readiness().healthy(), service.detailed().healthy());
    }
}
