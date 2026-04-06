package io.configd.common;

import java.util.concurrent.ConcurrentHashMap;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

/**
 * Runtime support for @Buggify fault injection.
 * In simulation mode, each injection point is enabled/disabled once per run
 * (determined by seed). Enabled points fire with their configured probability.
 */
public final class BuggifyRuntime {

    private static volatile boolean simulationMode = false;
    private static RandomGenerator random = RandomGeneratorFactory.of("L64X128MixRandom").create(0L);
    private static final ConcurrentHashMap<String, Boolean> enabledPoints = new ConcurrentHashMap<>();

    private BuggifyRuntime() {}

    public static void enableSimulationMode(long seed) {
        simulationMode = true;
        random = RandomGeneratorFactory.of("L64X128MixRandom").create(seed);
        enabledPoints.clear();
    }

    public static void disableSimulationMode() {
        simulationMode = false;
        enabledPoints.clear();
    }

    /**
     * Check if a buggify point should fire.
     * Returns false immediately in production mode (zero overhead).
     */
    public static boolean shouldFire(String pointId, double probability) {
        if (!simulationMode) return false;
        
        boolean enabled = enabledPoints.computeIfAbsent(pointId, 
            k -> random.nextDouble() < 0.5); // 50% chance of being enabled for this run
        
        if (!enabled) return false;
        return random.nextDouble() < probability;
    }

    /** Convenience: check with default probability (25%). */
    public static boolean shouldFire(String pointId) {
        return shouldFire(pointId, 0.25);
    }

    public static boolean isSimulationMode() {
        return simulationMode;
    }
}
