# Client Part 1 - Basic Load Testing Client

Basic load testing client for the WebSocket chat server. Measures throughput, success/failure rates, and connection statistics.

## Features

- **Warmup Phase:** Pre-warms server connections before main test
- **Main Phase:** Sends configurable number of messages with multiple worker threads
- **Per-worker Queues:** Messages sharded by `(roomId, userId)` for ordered delivery
- **Pipelining:** Supports up to `pipelineSize` in-flight messages per connection
- **Batching:** Sends multiple messages per WebSocket frame
- **Metrics:** Tracks success/fail counts, throughput, connection stats

## Build

```bash
cd client/client_part1
mvn clean compile
```

Or package as JAR:

```bash
mvn clean package
```

## Run

### Basic Usage

```bash
mvn exec:java -Dexec.args="http://your-server:8080"
```

### With Custom Message Count

```bash
mvn exec:java -Dexec.args="http://your-server:8080 100000"
```

### Skip Warmup

```bash
mvn exec:java -Dexec.args="http://your-server:8080 --no-warmup"
```

### Run from JAR

```bash
java -cp target/classes:target/dependency/* client_part1.ChatClientMain http://your-server:8080
```

## Configuration

Edit `src/main/resources/client.properties` to customize:

```properties
# Warmup phase
warmup.threads=32
warmup.messagesPerThread=1000

# Main phase
main.threads=64
main.totalMessages=500000

# Queue and connection
queue.capacity=50000
connection.staggerEvery=8
connection.staggerMs=80

# Worker parameters
worker.pipelineSize=400      # In-flight messages per connection
worker.batchSize=50          # Messages per WebSocket frame
worker.echoTimeoutMs=15000
worker.acquireLoopTimeoutMs=30000
worker.connectTimeoutSec=10
worker.backoffMs=500,1000,2000,4000,8000
```

**Note:** Changes to `client.properties` require recompilation (`mvn clean compile`) to take effect.

## Architecture

- **MessageGenerator:** Single thread generates messages with state-aware logic (JOIN before TEXT/LEAVE), shards by `(roomId, userId)` hash to per-worker queues
- **Workers:** Each worker maintains one WebSocket connection, consumes from its dedicated queue
- **Pipelining:** Up to `pipelineSize` messages can be in-flight per connection without waiting for each echo
- **Batching:** Messages are batched and sent in single WebSocket frames (separated by `\n`)

## Output

The client prints:
- Warmup phase metrics (duration, success/fail counts, throughput)
- Main phase progress updates (every 10 seconds)
- Final summary with:
  - Successful messages
  - Failed messages
  - Total runtime
  - Throughput (msg/s)
  - Connection statistics

## Example Output

```
Part 1 Client — server: http://3.95.208.36:8080 (per-worker queues, sharded by room+user, rooms 1–20)
Warmup: 32 workers × 1000 msgs (32000 total)
Main:   64 workers, 500000 msgs total
---
[Warmup] Starting...
[Warmup] Done in 60579 ms (success=28794, fail=186, 478.4 msg/s)
---
[Main] Starting (target 500000 messages)...
[Main] progress: 491971 / 500000 sent (ok=491970, fail=1) — 43789.1 msg/s
[Main] Finished.

========== Part 1 Metrics ==========
Successful messages: 500000
Failed messages (after 5 retries): 0
Total runtime (ms):  5983
Throughput (msg/s):   83570.12
Total connections:   64
Reconnections:       0
=====================================
```

## Troubleshooting

- **Connection errors:** Check server is running and accessible
- **High failure rate:** Check network latency, increase `worker.echoTimeoutMs`
- **Low throughput:** Increase `worker.pipelineSize` and `worker.batchSize`
- **IDE errors:** See `README_IDE.md` for IDE-specific troubleshooting

## Requirements

- Java 17+
- Maven 3.6+
- Server running on specified URL (default: `http://localhost:8080`)
