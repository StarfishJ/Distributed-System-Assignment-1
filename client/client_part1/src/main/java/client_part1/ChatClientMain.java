package client_part1;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

/**
 * Entry point for Part 1 load-test client.
 *
 * Architecture:
 * - Single dedicated thread (MessageGenerator) generates all messages and places them
 *   in thread-safe BlockingQueues. Sending (worker) threads only take from the queue.
 * - Warmup: per-room queues (20); main: per-worker queues (64), generator shards by
 *   (roomId, userId) so same user in same room always goes to same worker → JOIN before
 *   TEXT/LEAVE, no "user not in room".
 *
 * Warmup: 32 workers × 1000 msgs. Main: 500,000 chat messages total (64 workers).
 */
public class ChatClientMain {

    private static int warmupThreads() { return ClientConfig.getWarmupThreads(); }
    private static int warmupMessagesPerThread() { return ClientConfig.getWarmupMessagesPerThread(); }
    private static int warmupTotal() { return warmupThreads() * warmupMessagesPerThread(); }
    private static int mainThreads() { return ClientConfig.getMainThreads(); }
    private static int mainTotalMessages() { return ClientConfig.getMainTotalMessages(); }
    private static int queueCapacity() { return ClientConfig.getQueueCapacity(); }
    private static int connectionStaggerEvery() { return ClientConfig.getConnectionStaggerEvery(); }
    private static int connectionStaggerMs() { return ClientConfig.getConnectionStaggerMs(); }

    private static List<BlockingQueue<ClientMessage>> createRoomQueues(int capacity) {
        List<BlockingQueue<ClientMessage>> list = new ArrayList<>(MessageGenerator.NUM_ROOMS);
        for (int r = 0; r < MessageGenerator.NUM_ROOMS; r++) {
            list.add(new LinkedBlockingQueue<>(capacity));
        }
        return list;
    }

    private static List<BlockingQueue<ClientMessage>> createWorkerQueues(int numWorkers, int capacity) {
        List<BlockingQueue<ClientMessage>> list = new ArrayList<>(numWorkers);
        for (int i = 0; i < numWorkers; i++) {
            list.add(new LinkedBlockingQueue<>(capacity));
        }
        return list;
    }

    /** Submits workers in batches with delay between batches to avoid connection storm; no Thread.sleep in loop. */
    private static void submitWorkersStaggered(ExecutorService pool, int totalWorkers, IntFunction<Worker> workerForIndex) throws InterruptedException {
        int staggerEvery = connectionStaggerEvery();
        int numBatches = (totalWorkers + staggerEvery - 1) / staggerEvery;
        CountDownLatch lastBatchDone = new CountDownLatch(1);
        ScheduledExecutorService stagger = Executors.newSingleThreadScheduledExecutor();
        for (int batch = 0; batch < numBatches; batch++) {
            final int start = batch * staggerEvery;
            final boolean isLast = (batch == numBatches - 1);
            stagger.schedule(() -> {
                int end = Math.min(start + staggerEvery, totalWorkers);
                for (int i = start; i < end; i++) {
                    pool.submit(workerForIndex.apply(i));
                }
                if (isLast) lastBatchDone.countDown();
            }, (long) batch * connectionStaggerMs(), TimeUnit.MILLISECONDS);
        }
        lastBatchDone.await();
        stagger.shutdown();
        try {
            if (!stagger.awaitTermination(1, TimeUnit.MINUTES)) {
                stagger.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            stagger.shutdownNow();
            throw e;
        }
    }

    /**
     * Waits until completed count reaches totalMessages, or timeout, or no progress for stuckThresholdMs.
     * Prints a message when stuck or timeout. Returns the completed count (success + fail) when the loop exits.
     */
    private static long waitUntilCompletedOrStuck(Metrics metrics, long totalMessages, long timeoutMs, long stuckThresholdMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        long lastCompleted = metrics.getSuccessCount() + metrics.getFailCount();
        long lastProgressMs = System.currentTimeMillis();
        while (lastCompleted < totalMessages && System.currentTimeMillis() < deadline) {
            TimeUnit.MILLISECONDS.sleep(50);
            long completed = metrics.getSuccessCount() + metrics.getFailCount();
            if (completed > lastCompleted) {
                lastCompleted = completed;
                lastProgressMs = System.currentTimeMillis();
            } else if (System.currentTimeMillis() - lastProgressMs > stuckThresholdMs) {
                System.out.println("[Main] No progress for " + (stuckThresholdMs / 1000) + "s at " + lastCompleted + " / " + totalMessages + ", putting poison anyway.");
                break;
            }
        }
        long sentBeforePoison = metrics.getSuccessCount() + metrics.getFailCount();
        if (sentBeforePoison < totalMessages && System.currentTimeMillis() >= deadline) {
            System.out.println("[Main] Timeout waiting for all messages; completed: " + sentBeforePoison + " / " + totalMessages);
        }
        return sentBeforePoison;
    }

    public static void main(String[] args) {
        String urlFromArgs = "http://localhost:8080";
        boolean skipWarmup = false;
        int nonOptArgCount = 0;
        
        for (String arg : args) {
            if ("--no-warmup".equals(arg) || "-n".equals(arg)) {
                skipWarmup = true;
            } else if (!arg.startsWith("-")) {
                if (nonOptArgCount == 0) {
                    urlFromArgs = arg;
                    nonOptArgCount++;
                }
            }
        }
        final String serverUrl = urlFromArgs;

        System.out.println("Part 1 Client — server: " + serverUrl + " (per-worker queues, sharded by room+user, rooms 1–20)");
        if (!skipWarmup) {
            System.out.println("Warmup: " + warmupThreads() + " workers × " + warmupMessagesPerThread() + " msgs (" + warmupTotal() + " total)");
        }
        System.out.println("Main:   " + mainThreads() + " workers, " + mainTotalMessages() + " msgs total");
        System.out.println("---");

        if (!skipWarmup) {
            System.out.println("[Warmup] Starting...");
            // Create one queue per worker thread (per-worker queues with sharding)
            List<BlockingQueue<ClientMessage>> warmupQueues = createWorkerQueues(warmupThreads(), queueCapacity());
            int warmupTotalMessages = warmupThreads() * warmupMessagesPerThread();
            Metrics warmupMetrics = new Metrics();
            Thread generatorWarmup = new Thread(new MessageGenerator(warmupQueues, warmupTotalMessages, true));
            generatorWarmup.start();

            ExecutorService warmupPool = Executors.newFixedThreadPool(warmupThreads());
            warmupMetrics.start();
            try {
                submitWorkersStaggered(warmupPool, warmupThreads(),
                        i -> new Worker(warmupQueues.get(i), serverUrl, warmupMetrics, i, warmupMessagesPerThread()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("Warmup interrupted");
                return;
            }
            warmupPool.shutdown();
            try {
                generatorWarmup.join(60_000);
                warmupPool.awaitTermination(60, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("Warmup interrupted");
                return;
            }
            warmupMetrics.end();
            System.out.println("[Warmup] Done in " + warmupMetrics.getWallTimeMs() + " ms (success=" + warmupMetrics.getSuccessCount() + ", fail=" + warmupMetrics.getFailCount() + ", " + String.format("%.1f", warmupMetrics.getThroughputPerSecond()) + " msg/s)");
            System.out.println("---");
        }

        System.out.println("[Main] Starting (target " + mainTotalMessages() + " messages). Ensure server is running on " + serverUrl + ".");
        List<BlockingQueue<ClientMessage>> mainQueues = createWorkerQueues(mainThreads(), queueCapacity());
        Metrics mainMetrics = new Metrics();
        Thread generatorMain = new Thread(new MessageGenerator(mainQueues, mainTotalMessages(), true));
        generatorMain.start();

        ExecutorService mainPool = Executors.newFixedThreadPool(mainThreads());
        mainMetrics.start();
        try {
            submitWorkersStaggered(mainPool, mainThreads(),
                    i -> new Worker(mainQueues.get(i), serverUrl, mainMetrics, i));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Main phase interrupted");
            return;
        }
        mainPool.shutdown();

        // Progress log every 10s so user can see it's running (ScheduledExecutor avoids Thread.sleep in loop)
        ScheduledExecutorService progressScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "progress");
            t.setDaemon(true);
            return t;
        });
        progressScheduler.scheduleAtFixedRate(() -> {
            if (mainPool.isTerminated()) return;
            long ok = mainMetrics.getSuccessCount();
            long fail = mainMetrics.getFailCount();
            long sent = ok + fail;
            double throughput = mainMetrics.getThroughputPerSecond();
            System.out.println("[Main] progress: " + sent + " / " + mainTotalMessages() + " sent (ok=" + ok + ", fail=" + fail + ") — " + String.format("%.1f", throughput) + " msg/s");
        }, 10, 10, TimeUnit.SECONDS);

        try {
            generatorMain.join(300_000);
            int queueTotal = 0;
            for (BlockingQueue<ClientMessage> q : mainQueues) queueTotal += q.size();
            System.out.println("[Main] Generator joined. Queue total size: " + queueTotal + " (expected ~500K if generator ran).");
            // Wait until at least some messages have been sent (or 30s) so workers are consuming
            long deadline = System.currentTimeMillis() + 30_000;
            while (mainMetrics.getSuccessCount() + mainMetrics.getFailCount() < 100 && System.currentTimeMillis() < deadline) {
                TimeUnit.MILLISECONDS.sleep(500);
            }
            long sentBeforePoison = waitUntilCompletedOrStuck(mainMetrics, mainTotalMessages(), 300_000, 60_000);
            System.out.println("[Main] Putting poison (sent so far: " + sentBeforePoison + ")...");
            for (int i = 0; i < mainThreads(); i++) {
                mainQueues.get(i).put(ClientMessage.POISON);
            }
            System.out.println("[Main] Poison pills added. Waiting for workers to finish...");
            mainPool.awaitTermination(300, TimeUnit.SECONDS);
            progressScheduler.shutdown();
        } catch (InterruptedException e) {
            progressScheduler.shutdown();
            Thread.currentThread().interrupt();
            System.err.println("Main phase interrupted");
            return;
        }
        mainMetrics.end();
        System.out.println("[Main] Finished.");
        System.out.println();
        mainMetrics.printSummary();
    }
}
