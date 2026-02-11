# ChatFlow Assignment 1 — Design Document

**Course:** 6650 | **Assignment:** WebSocket Chat Server and Client | **Pages:** 2 max

> **Export to PDF:** Use VS Code extension "Markdown PDF", or [Pandoc](https://pandoc.org/) `pandoc DesignDocument.md -o DesignDocument.pdf`, or copy into Word/Google Docs and save as PDF. Keep within 2 pages.

---
## 1. Architecture

### 1.1 High-Level Architecture

```
                    ┌─────────────────────────────────────────────────────────┐
                    │                    ASSIGNMENT 1 Overview                      │
                    └─────────────────────────────────────────────────────────┘

    ┌──────────────────────────────┐                    ┌──────────────────────────────┐
    │       CLIENT (Java)          │                    │       SERVER (Java)          │
    │  Multithreaded load-test     │                    │  WebSocket + REST            │
    │  simulator                   │                    │  (e.g. EC2 us-west-2)        │
    └──────────────┬───────────────┘                    └──────────────┬───────────────┘
                   │                                                  │
                   │  WebSocket  ws://host:port/chat/{roomId}         │
                   │  ─────────────────────────────────────────────►  │  accept, validate,
                   │  ◄─────────────────────────────────────────────  │  echo / error
                   │              JSON messages                        │
                   │                                                  │
                   │  REST  GET /health  (optional liveness check)    │
                   │  ─────────────────────────────────────────────►  │
                   │  ◄─────────────────────────────────────────────  │  {"status":"UP"}
                   │                                                  │
    ┌──────────────┴───────────────┐                    ┌──────────────┴───────────────┐
    │  • Generator → Queue         │                    │  • /chat/{roomId} WS         │
    │  • Workers → WS connections  │                    │  • MessageValidator          │
    │  • Metrics, CSV, charts      │                    │  • /health REST              │
    └──────────────────────────────┘                    └──────────────────────────────┘
```

**Note:** Client and Server are two separate Java applications. The client connects via **WebSocket** to `/chat/{roomId}` to send chat messages; the server validates, echoes back or returns errors. **REST** `GET /health` is used for health checks. The server can be deployed on AWS EC2 (e.g. us-west-2).

---

### 1.2 System Overview (Detailed Architecture)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                               CLIENT (Java)                                 │
│                                                                             │
│  ┌──────────────────┐      ┌─────────────────────┐      ┌────────────────┐  │
│  │ MessageGenerator │ ───► │ BlockingQueue       │ ◄─── │ Worker Threads │  │
│  │ (1 thread)       │ put  │ <ChatMessage>       │ take │ (N threads)    │  │
│  │ 500K msgs        │      │ thread-safe buffer  │      │ WS connections │  │
│  └──────────────────┘      └─────────────────────┘      └────────┬───────┘  │
│                                                                  │          │
└──────────────────────────────────────────────────────────────────│──────────┘
                                                                   │ WebSocket
                                                                   │ /chat/{roomId}
                                                                   ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                                SERVER (Java)                                │
│                                                                             │
│   ┌─────────────────────────┐    ┌────────────────────┐                     │
│   │ WebSocket Endpoint      │    │ REST GET /health   │                     │
│   │ /chat/{roomId}          │    │ {"status":"UP"}    │                     │
│   └───────────┬─────────────┘    └────────────────────┘                     │
│               │                                                             │
│               ▼                                                             │
│   ┌─────────────────────────┐                                               │
│   │ MessageValidator        │ ──► valid: echo + serverTimestamp + status    │
│   │ (JSON schema checks)    │     invalid: error payload                    │
│   └─────────────────────────┘                                               │
└─────────────────────────────────────────────────────────────────────────────┘
```

**Description:** The client uses a **producer–consumer** model: a single `MessageGenerator` produces all chat messages and enqueues them into a `BlockingQueue`; multiple `Worker` threads consume from the queue, each maintaining a WebSocket connection to the server, and send messages to `/chat/{roomId}`. The server validates incoming JSON, echoes valid messages back to the sender with a server timestamp and status, and returns structured errors for invalid payloads. The `/health` endpoint provides a simple REST check for server liveness.

**Server processing pipeline:** Validation and “act on valid message” are kept separate. The latter is a pipeline: **A1** `validate → respond`; **A2** `validate → enqueue → [consumer] → respond`; **A3** `validate → persist → respond`. This allows adding queues (A2) and persistence (A3) without changing validation logic.

---

## 2. Major Classes and Their Relationships

### 2.1 Server

| Class | Responsibility |
|-------|----------------|
| `ChatServer` | Bootstraps WebSocket server (e.g., Java-WebSocket), registers `/chat/{roomId}` handler, optionally starts HTTP server for `/health`. |
| `ChatWebSocketHandler` | Handles `onOpen`, `onMessage`, `onClose`, `onError`. Parses JSON → `ChatMessage` DTO, delegates validation, then runs “act on valid” pipeline (A1: echo only; A2/A3: enqueue/persist as added). |
| `ChatMessage` | DTO: `userId`, `username`, `message`, `timestamp`, `messageType`. Used for parsing and validation. |
| `MessageValidator` | Validates `userId` (1–100000), `username` (3–20 alphanumeric), `message` (1–500 chars), `timestamp` (ISO-8601), `messageType` (TEXT\|JOIN\|LEAVE). Returns validation result. |
| `HealthHandler` | Serves GET `/health`, returns `{"status":"UP","timestamp":"..."}`. |

```
ChatServer ──► ChatWebSocketHandler
       │              │
       │              ├──► MessageValidator
       │              └──► ChatMessage (DTO)
       │
       └──► HealthHandler  (HTTP /health)
```

### 2.2 Client

| Class | Responsibility |
|-------|----------------|
| `ChatClientMain` | Entry point. Starts `MessageGenerator`, creates `BlockingQueue`, spawns Worker pool. Runs Warmup then Main phase, collects metrics, prints summary and writes CSV. |
| `MessageGenerator` | Single thread. Generates 500K (+ warmup) messages with random `userId`, `username`, `message`, `roomId`, `messageType` (90% TEXT, 5% JOIN, 5% LEAVE), enqueues to `BlockingQueue`. |
| `ChatMessage` / `ClientMessage` | Same structure as server DTO for serialization. |
| `Worker` / `SenderTask` | Runnable. Opens WebSocket to `/chat/{roomId}`, `take` from queue, sends message, waits for echo, records latency. Retries with exponential backoff on failure; tracks successes/failures. |
| `Metrics` | Aggregates successful/failed counts, latencies, connection stats (total, reconnects). Thread-safe counters; optional shared list for per-message latency. |

```
ChatClientMain ──► MessageGenerator (thread)
       │
       ├──► BlockingQueue<ChatMessage>
       │
       ├──► ExecutorService → Worker × N
       │              │
       │              └──► Metrics (shared)
       │
       └──► Metrics → CSV export, stats, charts
```

---

## 3. Threading Model

```
                    ┌─────────────────────────────────────────┐
                    │         MessageGenerator (1 thread)     │
                    │   Generates 500K + warmup msgs → Queue  │
                    └─────────────────┬───────────────────────┘
                                      │ put()
                                      ▼
                    ┌─────────────────────────────────────────┐
                    │      BlockingQueue<ChatMessage>         │
                    └─────────────────┬───────────────────────┘
                                      │ take()
         ┌────────────────────────────┼────────────────────────────┐
         ▼                            ▼                            ▼
  ┌─────────────┐            ┌─────────────┐            ┌─────────────┐
  │ Worker 1    │            │ Worker 2    │    ...     │ Worker N    │
  │ 1 WS conn   │            │ 1 WS conn   │            │ 1 WS conn   │
  │ send + ack  │            │ send + ack  │            │ send + ack  │
  └─────────────┘            └─────────────┘            └─────────────┘
```

- **Warmup:** 32 threads start, each opens one WebSocket, sends 1000 messages, then exits. Warmup duration is measured separately.
- **Main:** After warmup, a new pool of N workers (e.g., 64) is created. Each maintains one WebSocket, fetches messages from the same queue until 500K are sent. Main phase duration and throughput are measured.
- **Generator:** Runs in a single thread; only produces messages and enqueues. No I/O. Ensures workers are never starved (bounded queue or sufficient production rate).
- **Synchronization:** `BlockingQueue` coordinates producers and consumers. `Metrics` uses atomic counters / locks for aggregates. Per-message latencies are collected in a thread-safe structure (e.g., `ConcurrentLinkedQueue` or synchronized list) for Part 2 analysis.

---

## 4. WebSocket Connection Management Strategy

| Aspect | Strategy |
|--------|----------|
| **Creation** | Each Worker creates one WebSocket connection to `ws://host:port/chat/{roomId}` when it starts. `roomId` is derived from the message (1–20) or fixed per worker; connections are reused for many messages. |
| **Reuse** | Same connection is used for all messages sent by that Worker during its lifetime. No per-message connect/disconnect. |
| **Failure & retry** | On send failure or connection drop: close socket, retry up to 5 times with exponential backoff (e.g., 1s, 2s, 4s, 8s, 16s). After 5 failures, record message as failed and continue. |
| **Reconnection** | Reconnect uses the same backoff. Worker re-establishes WebSocket and continues sending from the queue. |
| **Cleanup** | When the Worker finishes (queue drained for its batch or shutdown), it closes its WebSocket connection. |

This keeps connection overhead low and avoids creating thousands of short-lived connections.

---

## 5. Little's Law Calculations and Predictions

**Little's Law:** ( L = λ * W)

- L: average number of “customers” in the system (here: concurrent connections or in-flight requests).
- W: average time per “customer” in the system (e.g., round-trip time per message).
- λ: throughput (messages per second).

**Steps:**

1. **Measure W (single-message RTT)**  
   Use one thread, one connection. Send one message, wait for echo, record elapsed time. Repeat many times (e.g., 100), average → *W*.  
   *Example:* W = 50 ms = 0.05 s. *(Replace with your measured value.)*

2. **Choose L**  
   For Main phase, use *L* = number of concurrent Workers (e.g., 64), each with one connection and one in-flight message.  
   *Example:* L = 64.

3. **Predicted throughput**
   ```
   λ_pred = L / W = 64 / 0.05 = 1280 msg/s
   ```
   *(Use your own L and W in the submitted document.)*

4. **Validation**  
   Run Part 1 client; compute actual λ = (successful messages) / (total wall time). Compare to λ_pred. Differences may come from connection setup, retries, GC, or lock contention; these can be briefly discussed in the report.

---

## 6. Extensibility for A2–A4

- **Validation vs. response decoupled:** `MessageValidator` only checks messages; the handler performs “act on valid” (echo now; later enqueue or persist). A2 (message distribution via queues) and A3 (persistence) can be added as pipeline steps without changing validation.
- **Room-aware design:** `roomId` in endpoint and messages supports A2 routing/partitioning, A3 storage by room, and A4 retrieval APIs (e.g. `GET /rooms/{roomId}/messages`).
- **REST + WebSocket:** `/health` already uses REST. A4 retrieval APIs extend HTTP endpoints; WebSocket remains for real-time chat. No structural change required.

---

## Summary

- **Architecture:** Client producer–consumer over `BlockingQueue`; Server WebSocket echo with validation and REST `/health`.
- **Classes:** Clear split between transport (Handlers, Workers), validation (`MessageValidator`), and data (`ChatMessage`, `Metrics`).
- **Threading:** Single generator, multiple workers, `BlockingQueue` for coordination.
- **Connections:** One WebSocket per Worker, reuse, retry with exponential backoff, then cleanup.
- **Performance:** Little's Law gives λ_pred; Part 1/2 metrics validate and allow analysis.
- **Extensibility:** Pipeline-style “validate → [optional: enqueue] → [optional: persist] → respond” and room-aware, REST+WS layout allow A2–A4 to plug in cleanly.
