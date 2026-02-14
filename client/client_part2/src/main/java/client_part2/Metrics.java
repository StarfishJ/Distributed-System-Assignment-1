package client_part2;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
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
    
    // Note: Per-message metrics are now written directly to CSV file asynchronously
    // No need to store MessageMetric objects in memory, reducing GC pressure
    
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
    
    // Asynchronous CSV writer: writes to file in background thread to avoid blocking
    private static volatile BufferedWriter csvWriter;
    private static volatile ExecutorService csvWriterExecutor;
    private static final AtomicBoolean csvWriterInitialized = new AtomicBoolean(false);
    private static final ConcurrentLinkedQueue<String> csvWriteQueue = new ConcurrentLinkedQueue<>();
    private static final int CSV_BATCH_SIZE = 1000; // Batch size for flushing
    
    /**
     * Initialize asynchronous CSV writer (called once at start).
     */
    public static void initializeCsvWriter() {
        if (csvWriterInitialized.compareAndSet(false, true)) {
            try {
                java.io.File resultsDir = new java.io.File("results");
                if (!resultsDir.exists()) resultsDir.mkdirs();
                csvWriter = new BufferedWriter(new FileWriter("results/per_message_metrics.csv", false), 64 * 1024); // 64KB buffer
                csvWriter.write("timestamp,messageType,latency,statusCode,roomId\n");
                
                // Start background thread for async writes
                csvWriterExecutor = Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "csv-writer");
                    t.setDaemon(true);
                    return t;
                });
                
                csvWriterExecutor.submit(() -> {
                    List<String> batch = new ArrayList<>(CSV_BATCH_SIZE);
                    boolean shutdown = false;
                    while (!shutdown) {
                        try {
                            // Collect batch from queue
                            String line;
                            while ((line = csvWriteQueue.poll()) != null && batch.size() < CSV_BATCH_SIZE) {
                                batch.add(line);
                            }
                            
                            if (!batch.isEmpty()) {
                                // Write batch
                                for (String l : batch) {
                                    csvWriter.write(l);
                                    csvWriter.write("\n");
                                }
                                csvWriter.flush();
                                batch.clear();
                            } else if (csvWriterExecutor.isShutdown()) {
                                // Executor shutdown and queue empty, exit
                                shutdown = true;
                            } else {
                                Thread.sleep(10); // Small sleep when queue is empty
                            }
                        } catch (IOException e) {
                            System.err.println("[Metrics] CSV write error: " + e.getMessage());
                            break;
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            // Continue to flush remaining data before exiting
                            shutdown = true;
                        }
                    }
                    // Final flush of any remaining batch
                    try {
                        if (!batch.isEmpty() && csvWriter != null) {
                            for (String l : batch) {
                                csvWriter.write(l);
                                csvWriter.write("\n");
                            }
                            csvWriter.flush();
                        }
                    } catch (IOException e) {
                        System.err.println("[Metrics] Final CSV flush error: " + e.getMessage());
                    }
                    return null;
                });
            } catch (IOException e) {
                System.err.println("[Metrics] Failed to initialize CSV writer: " + e.getMessage());
            }
        }
    }
    
    /**
     * Shutdown CSV writer and flush remaining data.
     * Blocks until all queued data is written to file.
     */
    public static void shutdownCsvWriter() {
        if (csvWriterExecutor != null && csvWriter != null) {
            try {
                // Wait for queue to drain (with timeout)
                int waitCount = 0;
                while (!csvWriteQueue.isEmpty() && waitCount < 100) {
                    Thread.sleep(100);
                    waitCount++;
                }
                
                // Shutdown executor (interrupts writer thread)
                csvWriterExecutor.shutdown();
                
                // Wait a bit more for final flush
                Thread.sleep(200);
                
                // Close writer
                csvWriter.close();
                System.out.println("[Metrics] CSV writer shutdown complete. Queue size: " + csvWriteQueue.size());
            } catch (IOException | InterruptedException e) {
                System.err.println("[Metrics] Error shutting down CSV writer: " + e.getMessage());
            }
        }
    }
    
    /**
     * Record detailed per-message metrics for CSV export (asynchronous write).
     * Writes to file in background thread without blocking main execution.
     * This avoids memory allocation for MessageMetric objects and reduces GC pressure.
     * @param timestamp Timestamp when acknowledgment received (milliseconds since epoch)
     * @param messageType Message type (JOIN, TEXT, LEAVE)
     * @param latency Latency in milliseconds
     * @param statusCode Status code ("OK" or "ERROR")
     * @param roomId Room ID (1-20)
     */
    public void recordMessageMetric(long timestamp, String messageType, long latency, String statusCode, int roomId) {
        if (csvWriter == null) {
            initializeCsvWriter();
            if (csvWriter == null) return; // Failed to initialize, skip recording
        }
        
        // Format CSV line as String and add to queue (non-blocking, no object allocation)
        // This is much faster than creating MessageMetric objects
        String line = timestamp + "," + 
                     (messageType != null ? messageType : "UNKNOWN") + "," +
                     latency + "," +
                     (statusCode != null ? statusCode : "UNKNOWN") + "," +
                     roomId;
        csvWriteQueue.offer(line); // Non-blocking add to queue, background thread will write it
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
