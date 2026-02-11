package client_part1;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Runnable Worker: one WebSocket to /chat/{roomId}, consumes from queue and sends messages.
 *
 * Pipelining: up to PIPELINE_SIZE messages in flight per connection (send without waiting for each echo).
 * Assumes server echoes in FIFO order so we match responses to sends by order.
 *
 * - Retry on timeout: if oldest in-flight exceeds ECHO_TIMEOUT, close, fail all in-flight, reconnect.
 * - Track failed messages via Metrics.recordFail().
 * 
 * FIX: WebSocketClient instances cannot be reused after close - must create new instance for reconnection.
 */
public class Worker implements Runnable {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static volatile boolean loggedFirstError;
    private static volatile boolean loggedFirstClose;

    private final BlockingQueue<ClientMessage> queue;
    private final String serverBaseUrl;
    private final Metrics metrics;
    /**
     * Which room this worker connects to (1–20). Derived from workerId so that:
     * - Worker i connects to room (i % 20) + 1 and only consumes from queue[i % 20].
     * - That queue is filled by MessageGenerator with messages whose message.getRoomId() == this roomId.
     * So the connection room is fixed per worker and matches the messages in its queue.
     */
    private final int roomId;
    /** If > 0, exit after this many successful sends (e.g. for warmup). 0 = run until poison pill. */
    private final int maxMessages;

    public Worker(BlockingQueue<ClientMessage> queue, String serverBaseUrl, Metrics metrics, int workerId) {
        this(queue, serverBaseUrl, metrics, workerId, 0);
    }

    /**
     * @param queue  This worker's room queue (only messages for room (workerId%20)+1).
     * @param workerId  Used to compute roomId = (workerId % NUM_ROOMS) + 1; worker connects to /chat/{roomId}.
     */
    public Worker(BlockingQueue<ClientMessage> queue, String serverBaseUrl, Metrics metrics, int workerId, int maxMessages) {
        this.queue = queue;
        this.serverBaseUrl = toWsUrl(serverBaseUrl);
        this.metrics = metrics;
        this.roomId = (workerId % MessageGenerator.NUM_ROOMS) + 1;
        this.maxMessages = maxMessages <= 0 ? 0 : maxMessages;
    }

    private static String toWsUrl(String url) {
        if (url == null) return "ws://localhost:8080";
        if (url.startsWith("http://")) return "ws://" + url.substring(7);
        if (url.startsWith("https://")) return "wss://" + url.substring(8);
        if (!url.startsWith("ws")) return "ws://" + url;
        return url;
    }

    /** Sleep for the given milliseconds. Returns true if interrupted. */
    private static boolean sleepMs(long ms) {
        try {
            TimeUnit.MILLISECONDS.sleep(ms);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return true;
        }
    }

    /**
     * Creates a new WebSocketClient instance. Must create new instance for each connection
     * because Java-WebSocket clients cannot be reused after close.
     */
    private WebSocketClient createClient(URI uri, Semaphore pipelinePermits,
            ConcurrentLinkedQueue<Long> pendingSendTimes, AtomicInteger successCount,
            AtomicLong lastResponseTimeMs) {
        WebSocketClient client = new WebSocketClient(uri) {
            @Override
            public void onOpen(ServerHandshake handshake) {
                metrics.recordConnection();
            }

            @Override
            public void onMessage(String text) {
                // 服务端可能一次回显多行（\n 分隔），与 Part2 批发送一致
                if (text == null || text.isEmpty()) return;
                String[] lines = text.split("\n");
                for (String line : lines) {
                    if (line.isEmpty()) continue;
                    
                    boolean isOk = line.contains("\"status\":\"OK\"");
                    boolean isError = line.contains("\"status\":\"ERROR\"");
                    
                    if (isError && !loggedFirstError) {
                        loggedFirstError = true;
                        System.err.println("[Worker] First server ERROR response: " + line);
                    }

                    // 每一行响应都尝试匹配一个在途请求，确保 pendingSendTimes 与 permit 不泄漏
                    Long ts = pendingSendTimes.poll();
                    if (ts == null) {
                        // 没有对应的发送记录，可能是服务器额外的广播/日志，直接忽略
                        continue;
                    }

                    pipelinePermits.release();
                    long now = System.currentTimeMillis();
                    lastResponseTimeMs.set(now);
                    
                    if (isOk) {
                        successCount.incrementAndGet();
                        metrics.recordSuccess();
                    } else {
                        metrics.recordFail();
                    }
                }
            }

            @Override
            public void onClose(int code, String reason, boolean remote) {
                if (!loggedFirstClose) {
                    loggedFirstClose = true;
                    System.err.println("[Worker] First connection close: code=" + code + " reason=" + reason + " remote=" + remote);
                }
            }

            @Override
            public void onError(Exception ex) { }
        };
        client.setTcpNoDelay(true);
        return client;
    }

    /**
     * Attempts to connect with exponential backoff.
     * Returns connected client or null if all attempts failed.
     */
    private WebSocketClient connectWithRetry(URI uri, Semaphore pipelinePermits,
            ConcurrentLinkedQueue<Long> pendingSendTimes, AtomicInteger successCount,
            AtomicLong lastResponseTimeMs) {
        int maxRetries = 5;
        for (int attempt = 0; attempt < maxRetries; attempt++) {
            if (Thread.currentThread().isInterrupted()) return null;
            long[] backoff = ClientConfig.getBackoffMs();
            if (attempt > 0) {
                long delay = backoff[Math.min(attempt - 1, backoff.length - 1)];
                if (sleepMs(delay)) return null;
            }
            try {
                WebSocketClient client = createClient(uri, pipelinePermits, pendingSendTimes, successCount, lastResponseTimeMs);
                if (client.connectBlocking(ClientConfig.getConnectTimeoutSec(), TimeUnit.SECONDS)) {
                    lastResponseTimeMs.set(System.currentTimeMillis());
                    return client;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            } catch (Exception e) {
                // Continue retry
            }
        }
        return null;
    }

    @Override
    public void run() {
        Semaphore pipelinePermits = new Semaphore(ClientConfig.getPipelineSize());
        ConcurrentLinkedQueue<Long> pendingSendTimes = new ConcurrentLinkedQueue<>();
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicLong lastResponseTimeMs = new AtomicLong(System.currentTimeMillis());

        URI uri = URI.create(serverBaseUrl + "/chat/" + roomId);
        
        // Use AtomicReference to allow client replacement during reconnection
        AtomicReference<WebSocketClient> clientRef = new AtomicReference<>();
        
        // Initial connection
        WebSocketClient initialClient = connectWithRetry(uri, pipelinePermits, pendingSendTimes, successCount, lastResponseTimeMs);
        if (initialClient == null) {
            System.err.println("[Worker] Failed initial connection to room " + roomId);
            return;
        }
        clientRef.set(initialClient);

        try {
            int consecutiveReconnectFailures = 0;
            List<ClientMessage> batch = new ArrayList<>(ClientConfig.getBatchSize());

            while (true) {
                if (maxMessages > 0 && successCount.get() >= maxMessages) break;
                if (Thread.currentThread().isInterrupted()) break;

                batch.clear();
                ClientMessage first;
                try {
                    first = queue.poll(1, TimeUnit.MILLISECONDS);
                    if (first == null) continue;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (ClientMessage.isPoison(first)) break;
                batch.add(first);
                queue.drainTo(batch, ClientConfig.getBatchSize() - 1);

                WebSocketClient client = clientRef.get();
                StringBuilder batchBuilder = new StringBuilder();

                for (ClientMessage msg : batch) {
                    boolean acquired = false;
                    long acquireLoopStart = System.currentTimeMillis();

                    while (!acquired) {
                        if (Thread.currentThread().isInterrupted()) break;
                        if (maxMessages > 0 && successCount.get() >= maxMessages) break;

                        long acquireTimeout = ClientConfig.getAcquireLoopTimeoutMs();
                        if (System.currentTimeMillis() - acquireLoopStart > acquireTimeout) {
                            System.err.println("[Worker] Stuck in acquire loop for " + (acquireTimeout/1000) + "s, exiting worker for room " + roomId);
                            while (pendingSendTimes.poll() != null) {
                                pipelinePermits.release();
                                metrics.recordFail();
                            }
                            return;
                        }

                        client = clientRef.get(); // Refresh in case of reconnect
                        
                        // SOFT TIMEOUT: Reconnect only if NO response has been received for ECHO_TIMEOUT_MS
                        boolean hasTimeout = !pendingSendTimes.isEmpty() && (System.currentTimeMillis() - lastResponseTimeMs.get() > ClientConfig.getEchoTimeoutMs());

                        if (!client.isOpen() || hasTimeout) {
                            if (client.isOpen()) client.close();
                            while (pendingSendTimes.poll() != null) {
                                pipelinePermits.release();
                                metrics.recordFail();
                            }
                            metrics.recordReconnect();
                            sleepMs(500); // Slightly longer backoff before reconnecting to let server breathe
                            WebSocketClient newClient = connectWithRetry(uri, pipelinePermits, pendingSendTimes, successCount, lastResponseTimeMs);
                            if (newClient == null) {
                                consecutiveReconnectFailures++;
                                if (consecutiveReconnectFailures >= 3) {
                                    System.err.println("[Worker] " + consecutiveReconnectFailures + " consecutive reconnection failures, exiting worker for room " + roomId);
                                    return;
                                }
                                sleepMs(1000);
                                continue;
                            }
                            clientRef.set(newClient);
                            client = newClient;
                            consecutiveReconnectFailures = 0;
                            acquireLoopStart = System.currentTimeMillis();
                            continue;
                        }

                        consecutiveReconnectFailures = 0;
                        try {
                            acquired = pipelinePermits.tryAcquire();
                            if (!acquired) acquired = pipelinePermits.tryAcquire(20, TimeUnit.MILLISECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }

                    if (!acquired) {
                        metrics.recordFail();
                        continue;
                    }

                    try {
                        if (!client.isOpen()) {
                            pipelinePermits.release();
                            metrics.recordFail();
                            continue;
                        }
                        String json = OBJECT_MAPPER.writeValueAsString(msg);
                        if (batchBuilder.length() > 0) batchBuilder.append("\n");
                        batchBuilder.append(json);
                        pendingSendTimes.add(System.currentTimeMillis());
                    } catch (Exception e) {
                        pipelinePermits.release();
                        metrics.recordFail();
                    }
                }

                if (batchBuilder.length() > 0 && clientRef.get().isOpen()) {
                    try {
                        clientRef.get().send(batchBuilder.toString());
                    } catch (Exception ignored) { }
                }
            }

            // Wait for in-flight to drain (with timeout), then fail remaining
            long drainDeadline = System.currentTimeMillis() + ClientConfig.getEchoTimeoutMs();
            while (!pendingSendTimes.isEmpty() && System.currentTimeMillis() < drainDeadline) {
                sleepMs(50);
            }
            while (pendingSendTimes.poll() != null) {
                pipelinePermits.release();
                metrics.recordFail();
            }
        } finally {
            WebSocketClient client = clientRef.get();
            if (client != null && client.isOpen()) {
                client.close();
            }
        }
    }
}
