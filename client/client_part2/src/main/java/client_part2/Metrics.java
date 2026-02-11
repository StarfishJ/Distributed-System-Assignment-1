package client_part2;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.LongAdder;

/**
 * Thread-safe metrics for Part 2 (performance analysis).
 * Optimized with LongAdder to reduce contention in hot paths.
 */
public class Metrics {

    private final LongAdder successCount = new LongAdder();
    private final LongAdder failCount = new LongAdder();
    private final LongAdder businessErrorCount = new LongAdder();
    private final LongAdder connectionCount = new LongAdder();
    private final LongAdder connectionFailureCount = new LongAdder();
    private final LongAdder reconnectCount = new LongAdder();
    
    private final ConcurrentLinkedQueue<Long> latenciesMs = new ConcurrentLinkedQueue<>();
    
    // Use arrays instead of Map for fixed-size room IDs (1-20) to avoid hash lookups
    private final LongAdder[] successByRoom = new LongAdder[MessageGenerator.NUM_ROOMS + 1];
    private final Map<String, LongAdder> successByMessageType = new ConcurrentHashMap<>();

    private volatile long startTimeMs;
    private volatile long endTimeMs;

    public Metrics() {
        for (int i = 0; i < successByRoom.length; i++) {
            successByRoom[i] = new LongAdder();
        }
        successByMessageType.put("TEXT", new LongAdder());
        successByMessageType.put("JOIN", new LongAdder());
        successByMessageType.put("LEAVE", new LongAdder());
        successByMessageType.put("UNKNOWN", new LongAdder());
    }

    public void start() {
        startTimeMs = System.currentTimeMillis();
    }

    public void end() {
        endTimeMs = System.currentTimeMillis();
    }

    public void recordSuccess() {
        successCount.increment();
    }

    public void recordBusinessError() {
        businessErrorCount.increment();
    }

    private static final ThreadLocal<Integer> SAMPLING_COUNTER = ThreadLocal.withInitial(() -> 0);

    public void recordSuccessWithDetails(int roomId, String messageType, long latencyMs) {
        successCount.increment();
        
        // Zero-contention sampling (1 in 1000) to minimize GC and queue contention
        int count = SAMPLING_COUNTER.get() + 1;
        SAMPLING_COUNTER.set(count);
        if (count % 1000 == 0) {
            latenciesMs.add(latencyMs);
        }
        
        if (roomId >= 1 && roomId < successByRoom.length) {
            successByRoom[roomId].increment();
        }
        String type = messageType != null && !messageType.isEmpty() ? messageType : "UNKNOWN";
        LongAdder typeCounter = successByMessageType.get(type);
        if (typeCounter != null) {
            typeCounter.increment();
        } else {
            successByMessageType.computeIfAbsent(type, k -> new LongAdder()).increment();
        }
    }

    public void recordFail() {
        failCount.increment();
    }

    public void recordConnection() {
        connectionCount.increment();
    }

    public void recordReconnect() {
        reconnectCount.increment();
    }

    public void recordConnectionFailure() {
        connectionFailureCount.increment();
    }

    public void recordLatencyMs(long latencyMs) {
        int count = SAMPLING_COUNTER.get() + 1;
        SAMPLING_COUNTER.set(count);
        if (count % 1000 == 0) {
            latenciesMs.add(latencyMs);
        }
    }

    public long getSuccessCount() { return successCount.sum(); }
    public long getFailCount() { return failCount.sum(); }
    public long getBusinessErrorCount() { return businessErrorCount.sum(); }
    public long getConnectionCount() { return connectionCount.sum(); }
    public long getConnectionFailureCount() { return connectionFailureCount.sum(); }
    public long getReconnectCount() { return reconnectCount.sum(); }

    public long getWallTimeMs() {
        if (endTimeMs == 0) return System.currentTimeMillis() - startTimeMs;
        return endTimeMs - startTimeMs;
    }

    public double getWallTimeSeconds() {
        return getWallTimeMs() / 1000.0;
    }

    public double getThroughputPerSecond() {
        long total = successCount.sum() + failCount.sum();
        if (total == 0) return 0;
        long wallMs = getWallTimeMs();
        if (wallMs <= 0) return 0;
        return total * 1000.0 / wallMs;
    }

    public void printSummary() {
        long success = successCount.sum();
        long fail = failCount.sum();
        long bizErr = businessErrorCount.sum();
        long total = success + fail;
        long wallMs = getWallTimeMs();
        double wallSec = getWallTimeSeconds();
        double throughput = total > 0 && wallMs > 0 ? total * 1000.0 / wallMs : 0;

        System.out.println("========== Part 2 Metrics (Performance Analysis) ==========");
        System.out.println("Successful messages (responses): " + success);
        System.out.println("Business errors (status=ERROR):  " + bizErr);
        System.out.println("Failed messages (no response):   " + fail);
        System.out.println("Total runtime (ms):  " + wallMs);
        System.out.println("Throughput (msg/s):  " + String.format("%.2f", throughput));
        System.out.println("Total connections:   " + connectionCount.sum());
        System.out.println("Connection failures: " + connectionFailureCount.sum());
        System.out.println("Reconnections:       " + reconnectCount.sum());
        System.out.println();

        // Response time statistics (successful messages only)
        List<Long> sorted = new ArrayList<>(latenciesMs);
        if (!sorted.isEmpty()) {
            Collections.sort(sorted);
            int n = sorted.size();

            double sum = 0;
            for (long v : sorted) sum += v;
            double mean = sum / n;
            Long midLo = (n > 1) ? sorted.get(n / 2 - 1) : null;
            Long midHi = sorted.get(n / 2);
            double median = (n % 2 == 1)
                    ? (midHi != null ? midHi : 0L)
                    : ((midLo != null ? midLo : 0L) + (midHi != null ? midHi : 0L)) / 2.0;
            long p95 = sorted.get((int) Math.min(n - 1, Math.round((n - 1) * 0.95)));
            long p99 = sorted.get((int) Math.min(n - 1, Math.round((n - 1) * 0.99)));
            long minLat = sorted.get(0);
            long maxLat = sorted.get(n - 1);

            System.out.println("--- Response Time (successful messages) ---");
            System.out.println("Mean (ms):           " + String.format("%.2f", mean));
            System.out.println("Median (ms):         " + String.format("%.2f", median));
            System.out.println("95th percentile (ms): " + p95);
            System.out.println("99th percentile (ms): " + p99);
            System.out.println("Min (ms):            " + minLat);
            System.out.println("Max (ms):            " + maxLat);
            System.out.println();
        }

        // Throughput per room
        if (wallSec > 0) {
            System.out.println("--- Throughput per room (msg/s) ---");
            for (int r = 1; r < successByRoom.length; r++) {
                long count = successByRoom[r].sum();
                if (count > 0) {
                    double roomThroughput = count / wallSec;
                    System.out.println("  Room " + String.format("%2d", r) + ": " + String.format("%.2f", roomThroughput) + " msg/s (" + count + " msgs)");
                }
            }
            System.out.println();
        }

        // Message type distribution
        if (!successByMessageType.isEmpty()) {
            System.out.println("--- Message Type Distribution ---");
            long totalTyped = 0;
            for (LongAdder v : successByMessageType.values()) totalTyped += v.sum();
            List<Map.Entry<String, LongAdder>> entries = new ArrayList<>(successByMessageType.entrySet());
            entries.sort((a, b) -> Long.compare(b.getValue().sum(), a.getValue().sum()));
            for (Map.Entry<String, LongAdder> e : entries) {
                long c = e.getValue().sum();
                double pct = totalTyped > 0 ? 100.0 * c / totalTyped : 0;
                System.out.println("  " + e.getKey() + ": " + c + " (" + String.format("%.2f", pct) + "%)");
            }
        }

        if (success == 0 && total == 0 && connectionCount.sum() > 0) {
            System.out.println("(Hint: 0 sent with connections → check server is running and WebSocket /chat/{1..20} echoes JSON with status:\"OK\")");
        }
        System.out.println("============================================================");
    }
}
