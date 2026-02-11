package client_part1;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe metrics for Part 1 (basic load testing): success/fail counts, wall time,
 * throughput, connection stats. Failed messages are those that failed after 5 send retries with exponential backoff.
 */
public class Metrics {

    private final AtomicLong successCount = new AtomicLong(0);
    /** Messages that failed after 5 retries (tracked for reporting). */
    private final AtomicLong failCount = new AtomicLong(0);
    private final AtomicLong connectionCount = new AtomicLong(0);
    private final AtomicLong reconnectCount = new AtomicLong(0);

    // volatile is used to ensure that the metrics are updated atomically
    private volatile long startTimeMs;
    private volatile long endTimeMs;

    // start is used to start the metrics
    public void start() {
        startTimeMs = System.currentTimeMillis();
    }

    // end is used to end the metrics
    public void end() {
        endTimeMs = System.currentTimeMillis();
    }

    // recordSuccess is used to record a successful message
    public void recordSuccess() {
        successCount.incrementAndGet();
    }

    // recordFail is used to record a failed message
    public void recordFail() {
        failCount.incrementAndGet();
    }

    // recordConnection is used to record a connection
    public void recordConnection() {
        connectionCount.incrementAndGet();
    }

    // recordReconnect is used to record a reconnect
    public void recordReconnect() {
        reconnectCount.incrementAndGet();
    }

    // getSuccessCount is used to get the successful message count
    public long getSuccessCount() { return successCount.get(); }
    public long getFailCount() { return failCount.get(); }
    public long getConnectionCount() { return connectionCount.get(); }
    public long getReconnectCount() { return reconnectCount.get(); }

    // getWallTimeMs is used to get the wall time in milliseconds
    public long getWallTimeMs() {
        if (endTimeMs == 0) return System.currentTimeMillis() - startTimeMs;
        return endTimeMs - startTimeMs;
    }

    // getThroughputPerSecond is used to get the throughput per second
    public double getThroughputPerSecond() {
        long total = successCount.get() + failCount.get();
        if (total == 0) return 0;
        long wallMs = getWallTimeMs();
        if (wallMs <= 0) return 0;
        return total * 1000.0 / wallMs;
    }

    // printSummary is used to print the summary of the metrics
    public void printSummary() {
        long success = successCount.get();
        long fail = failCount.get();
        long total = success + fail;
        long wallMs = getWallTimeMs();
        double throughput = total > 0 && wallMs > 0 ? total * 1000.0 / wallMs : 0;

        System.out.println("========== Part 1 Metrics ==========");
        System.out.println("Successful messages: " + success);
        System.out.println("Failed messages (after 5 retries): " + fail);
        System.out.println("Total runtime (ms):  " + wallMs);
        System.out.println("Throughput (msg/s):   " + String.format("%.2f", throughput));
        System.out.println("Total connections:    " + connectionCount.get());
        System.out.println("Reconnections:        " + reconnectCount.get());
        if (success == 0 && total == 0 && connectionCount.get() > 0) {
            System.out.println("(Hint: 0 sent with connections → check server is running and WebSocket /chat/{1..20} echoes JSON with status:\"OK\")");
        }
        System.out.println("=====================================");
    }
}
