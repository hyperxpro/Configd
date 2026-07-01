package io.configd.observability;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.ThreadMXBean;
import java.util.List;
import java.util.Objects;

/**
 * Binds a minimal set of JVM and process runtime gauges into a {@link MetricsRegistry}
 * so the operability "runtime" dashboard and the leak alerts (heap, FD, thread growth)
 * query real emitted series.
 *
 * <p>Each gauge reads straight off a platform MXBean at scrape time - no background
 * thread. Bound by both the control-plane server and the edge node so each process's
 * {@code /metrics} carries its own runtime view.
 *
 * <p>Series (all gauges, dot-names sanitized to underscores at scrape):
 * {@code jvm_heap_used_bytes}, {@code jvm_heap_max_bytes}, {@code jvm_threads_current},
 * {@code process_open_fds}, {@code jvm_gc_collection_millis} (cumulative collection time
 * summed across collectors), {@code jvm_gc_collections} (cumulative collection count).
 */
public final class JvmMetrics {

    private JvmMetrics() {}

    public static final String NAME_HEAP_USED = "jvm.heap.used.bytes";
    public static final String NAME_HEAP_MAX = "jvm.heap.max.bytes";
    public static final String NAME_THREADS = "jvm.threads.current";
    public static final String NAME_OPEN_FDS = "process.open.fds";
    public static final String NAME_GC_MILLIS = "jvm.gc.collection.millis";
    public static final String NAME_GC_COUNT = "jvm.gc.collections";

    /** Registers the runtime gauges in {@code registry} (idempotent - gauge re-registration is a no-op). */
    public static void bind(MetricsRegistry registry) {
        Objects.requireNonNull(registry, "registry must not be null");
        MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        List<GarbageCollectorMXBean> gcs = ManagementFactory.getGarbageCollectorMXBeans();

        registry.gauge(NAME_HEAP_USED, () -> mem.getHeapMemoryUsage().getUsed());
        registry.gauge(NAME_HEAP_MAX, () -> {
            long max = mem.getHeapMemoryUsage().getMax();
            return max < 0 ? 0L : max; // -1 == undefined
        });
        registry.gauge(NAME_THREADS, threads::getThreadCount);
        registry.gauge(NAME_OPEN_FDS, () -> openFds(os));
        registry.gauge(NAME_GC_MILLIS, () -> sumGc(gcs, true));
        registry.gauge(NAME_GC_COUNT, () -> sumGc(gcs, false));
    }

    private static long sumGc(List<GarbageCollectorMXBean> gcs, boolean time) {
        long total = 0;
        for (GarbageCollectorMXBean gc : gcs) {
            long v = time ? gc.getCollectionTime() : gc.getCollectionCount();
            if (v > 0) total += v;
        }
        return total;
    }

    private static long openFds(OperatingSystemMXBean os) {
        if (os instanceof com.sun.management.UnixOperatingSystemMXBean unix) {
            return unix.getOpenFileDescriptorCount();
        }
        return -1L; // not available on this platform
    }
}
