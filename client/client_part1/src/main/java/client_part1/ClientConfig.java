package client_part1;

import java.io.InputStream;
import java.util.Properties;

/**
 * 从 client.properties 读取可调参数，未配置项使用默认值。
 * 修改 src/main/resources/client.properties 即可调参，无需改代码。
 */
public final class ClientConfig {

    private static final Properties PROPS = load();

    private static Properties load() {
        Properties p = new Properties();
        try (InputStream in = ClientConfig.class.getResourceAsStream("/client.properties")) {
            if (in != null) p.load(in);
        } catch (Exception ignored) { }
        return p;
    }

    private static int getInt(String key, int defaultValue) {
        String v = PROPS.getProperty(key);
        if (v == null || v.isBlank()) return defaultValue;
        try { return Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return defaultValue; }
    }

    private static long getLong(String key, long defaultValue) {
        String v = PROPS.getProperty(key);
        if (v == null || v.isBlank()) return defaultValue;
        try { return Long.parseLong(v.trim()); } catch (NumberFormatException e) { return defaultValue; }
    }

    private static long[] getLongArray(String key, long[] defaultValue) {
        String v = PROPS.getProperty(key);
        if (v == null || v.isBlank()) return defaultValue;
        String[] parts = v.split(",");
        long[] out = new long[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try { out[i] = Long.parseLong(parts[i].trim()); } catch (NumberFormatException e) { return defaultValue; }
        }
        return out;
    }

    // ----- Main 使用的参数 -----
    public static int getWarmupThreads() { return getInt("warmup.threads", 32); }
    public static int getWarmupMessagesPerThread() { return getInt("warmup.messagesPerThread", 1000); }
    public static int getMainThreads() { return getInt("main.threads", 64); }
    public static int getMainTotalMessages() { return getInt("main.totalMessages", 500_000); }
    public static int getQueueCapacity() { return getInt("queue.capacity", 50_000); }
    public static int getConnectionStaggerEvery() { return getInt("connection.staggerEvery", 8); }
    public static int getConnectionStaggerMs() { return getInt("connection.staggerMs", 80); }

    // ----- Worker 使用的参数（与 Part2 一致以提升吞吐） -----
    public static int getPipelineSize() { return getInt("worker.pipelineSize", 2000); }
    public static int getBatchSize() { return getInt("worker.batchSize", 250); }
    public static long getEchoTimeoutMs() { return getLong("worker.echoTimeoutMs", 15_000); }
    public static long getAcquireLoopTimeoutMs() { return getLong("worker.acquireLoopTimeoutMs", 30_000); }
    public static int getConnectTimeoutSec() { return getInt("worker.connectTimeoutSec", 10); }
    public static long[] getBackoffMs() { return getLongArray("worker.backoffMs", new long[] { 500, 1000, 2000, 4000, 8000 }); }
}
