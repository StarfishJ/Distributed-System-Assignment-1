# WebSocket Chat Server

Spring Boot WebSocket server: `/chat/{roomId}` + `GET /health`.

## Build & run

```bash
cd server
mvn clean package
mvn spring-boot:run
```

Or run the JAR:

```bash
java -jar target/chat-server-0.0.1-SNAPSHOT.jar
```

Server listens on **8080**.

## Test

- **Health:** `curl http://localhost:8080/health`
- **WebSocket:** `wscat -c ws://localhost:8080/chat/1`

Example valid JSON to send over WebSocket:

```json
{"userId":"123","username":"user123","message":"hello","timestamp":"2025-01-29T12:00:00Z","messageType":"TEXT"}
```

Valid messages are echoed back with `serverTimestamp` and `status: "OK"`. Invalid messages return `status: "ERROR"` and an error `message`.
