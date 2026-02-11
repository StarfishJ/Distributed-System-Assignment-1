# CS6650 Assignment 1 — Design Document: WebSocket Chat Server and Client 
## 1. Architecture
```
┌──────────────────────────────┐                    ┌──────────────────────────────┐
│       CLIENT (Java)          │                    │       SERVER (Java)          │
│  Multithreaded load-test     │                    │  Spring Boot WebFlux         │
│  simulator                   │                    │  WebSocket + REST (EC2)      │
└──────────────┬───────────────┘                    └──────────────┬───────────────┘
               │                                                  │
               │  WebSocket  ws://host:port/chat/{roomId}         │
               │  ─────────────────────────────────────────────►  │  validate, echo
               │  ◄─────────────────────────────────────────────  │  / error
               │              JSON messages                       │
               │                                                  │
               │  REST  GET /health                               │
               │  ─────────────────────────────────────────────►  │  {"status":"UP"}
               │                                                  │
┌──────────────┴───────────────┐                   ┌──────────────┴───────────────┐
│ MessageGenerator → Queue     │                   │ ChatWebSocketHandler         │
│ Workers → WS connections     │                   │ HealthController             │
│ Metrics collection           │                   │ Room state management        │
└──────────────────────────────┘                   └──────────────────────────────┘
```
**Client:** Producer-consumer model with `MessageGenerator` (1 thread) producing messages into per-worker `BlockingQueue`s, sharded by `(roomId, userId)` hash. Multiple `Worker` threads consume from queues, each maintaining one WebSocket connection with pipelining (up to `pipelineSize` in-flight messages) and batching.

**Server:** Spring Boot WebFlux reactive server. `ChatWebSocketHandler` processes WebSocket messages, validates JOIN/TEXT/LEAVE logic, maintains room membership state, and echoes valid messages. `HealthController` serves REST `/health` endpoint.
## 2. Major Classes and Relationships
### Server
| Class | Responsibility |
|-------|----------------|
| `ChatApplication` | Spring Boot entry point |
| `WebSocketConfig` | Routes `/chat/{roomId}` to `ChatWebSocketHandler` |
| `ChatWebSocketHandler` | Reactive WebSocket handler: parses JSON, validates JOIN/TEXT/LEAVE, maintains room state, echoes messages |
| `NettyConfig` | Configures Netty worker threads (`-Dnetty.worker.threads=32`) |
| `HealthController` | REST `/health` endpoint |

### Client
| Class | Responsibility |
|-------|----------------|
| `ChatClientMain` | Entry point: creates queues, starts generator, spawns worker pool, collects metrics |
| `MessageGenerator` | Generates messages with state-aware logic (JOIN before TEXT/LEAVE), shards by `(roomId, userId)` to per-worker queues |
| `Worker` | Maintains WebSocket connection, implements pipelining/batching, handles reconnection with message re-queuing |
| `Metrics` | Thread-safe aggregation: success/fail counts, latencies (P95/P99 in Part 2), per-room throughput |
| `ClientConfig` | Loads `client.properties`: pipeline size, batch size, timeouts, worker counts |

**Relationships:**
ChatApplication → WebSocketConfig → ChatWebSocketHandler → Room state
ChatClientMain → MessageGenerator → Per-worker Queue → Worker × N → WebSocket → Metrics

## 3. Threading Model
MessageGenerator (1 thread) → Per-worker BlockingQueue → Worker × N (each: 1 WS conn)
- **Warmup:** 32-64 threads, each sends configurable messages, then exits
- **Main:** 64-128 workers, each maintains one WebSocket, consumes from dedicated queue (sharded by `roomId+userId`)
- **Generator:** Single thread, state-aware message generation, shards to queues
- **Synchronization:** `BlockingQueue` for producer-consumer coordination; `AtomicInteger/Long`, `ConcurrentLinkedQueue` for thread-safe metrics
- **Pipelining:** Up to `pipelineSize` (400-2000) in-flight messages per worker using `Semaphore`; batching (50-250 msgs/frame)
## 4. WebSocket Connection Management

| Aspect | Strategy |
|--------|----------|
| **Creation** | One WebSocket per Worker to `ws://host:port/chat/{roomId}` (`roomId = workerId % 20 + 1`). Retry with exponential backoff (up to 10 attempts). |
| **Reuse** | Same connection for all messages. Batching (multiple msgs/frame) and pipelining (up to `pipelineSize` in-flight). |
| **Failure & Retry** | On drop/timeout: close socket, re-queue pending messages to front (preserving order), retry connection. After max failures, worker exits. |
| **Cleanup** | Wait for in-flight messages to drain, then close connection. |

## 5. Little's Law Calculations
**Little's Law:** L = λ × W, where L = concurrent connections, W = avg RTT, λ = throughput

**Actual Test Results (EC2 deployment, Part 2):**
- **Configuration:** 64 workers, pipelineSize=2000, batchSize=250
- **Measured W (Mean RTT):** 101.19 ms = 0.10119 s
- **Measured W (Median RTT):** 101.00 ms = 0.101 s (very close to mean, indicating stable performance)
- **Actual Throughput λ:** 83,570 msg/s
- **Total Runtime:** 5,983 ms for 500,000 messages
- **P95 latency:** 121 ms, **P99 latency:** 127 ms

**Calculation using Mean RTT:**
1. **L (Concurrent connections):** 64 workers
2. **W (RTT):** 0.10119 s (mean latency)
3. **Naive Prediction (without pipelining):    λ_pred_naive = L / W = 64 / 0.10119 = 632.5 msg/s
4. **Actual vs Naive Prediction:**
   - **Actual:** 83,570 msg/s
   - **Naive prediction:** 632.5 msg/s
   - **Ratio:** 132.1× higher than naive prediction