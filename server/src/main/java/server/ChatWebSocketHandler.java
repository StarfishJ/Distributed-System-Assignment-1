package server;

import java.io.IOException;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketSession;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;

import reactor.core.publisher.Mono;

/**
 * Reactive WebSocket handler: same business logic as FastChatServer
 * (validate JSON, JOIN/TEXT/LEAVE, echo). WebFlux keeps one thread per connection
 * only when work is done; otherwise non-blocking, so high concurrency with fewer threads.
 */
public class ChatWebSocketHandler implements WebSocketHandler {

    private final ConcurrentHashMap<String, Set<String>> roomToJoinedUsers = new ConcurrentHashMap<>();
    private static final JsonFactory JSON_FACTORY = new JsonFactory();

    private Set<String> joinedUsersForRoom(String roomId) {
        return roomToJoinedUsers.computeIfAbsent(roomId, k -> ConcurrentHashMap.newKeySet());
    }

    /** Extract roomId from path /chat/1 -> "1". */
    private static String roomIdFromPath(String path) {
        String prefix = "/chat/";
        if (path == null || !path.startsWith(prefix)) return null;
        String idStr = path.substring(prefix.length()).split("/")[0].trim();
        try {
            int rid = Integer.parseInt(idStr);
            if (rid >= 1 && rid <= 20) return idStr;
        } catch (NumberFormatException ignored) { }
        return null;
    }

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        String path = session.getHandshakeInfo().getUri().getPath();
        String roomId = roomIdFromPath(path);

        return session.receive()
                .flatMap(msg -> {
                    // 批处理消息：客户端可能一次发送多条消息（\n 分隔）
                    String payload = msg.getPayloadAsText();
                    String serverTimestamp = Instant.now().toString();
                    StringBuilder batch = new StringBuilder();
                    for (String line : payload.split("\n")) {
                        if (line.isEmpty()) continue;
                        String resp = processSingleMessage(roomId, line, serverTimestamp);
                        if (resp != null) {
                            if (batch.length() > 0) batch.append("\n");
                            batch.append(resp);
                        }
                    }
                    if (batch.length() == 0) return Mono.empty();
                    // 非阻塞发送：WebFlux 的 send 已经是非阻塞的
                    return session.send(Mono.just(session.textMessage(batch.toString())));
                }, 512) // 增加 flatMap 并发度：允许同时处理更多消息（默认是 256）
                .then();
    }

    private String processSingleMessage(String roomId, String message, String serverTimestamp) {
        String userId = null, username = null, msgContent = null, timestamp = null, messageType = null;

        try (JsonParser p = JSON_FACTORY.createParser(message)) {
            if (p.nextToken() != JsonToken.START_OBJECT) return buildErrorJson("invalid JSON");
            while (p.nextToken() != JsonToken.END_OBJECT) {
                String fieldName = p.currentName();
                p.nextToken();
                if (fieldName == null) continue;
                switch (fieldName) {
                    case "userId" -> userId = p.getValueAsString();
                    case "username" -> username = p.getValueAsString();
                    case "message" -> msgContent = p.getValueAsString();
                    case "timestamp" -> timestamp = p.getValueAsString();
                    case "messageType" -> messageType = p.getValueAsString();
                    default -> p.skipChildren();
                }
            }
        } catch (IOException e) {
            return buildErrorJson("invalid JSON");
        }

        if (userId == null || userId.isEmpty()) return buildErrorJson("userId missing");
        if (username == null || username.length() < 3) return buildErrorJson("username invalid");
        if (msgContent == null || msgContent.isEmpty()) return buildErrorJson("message missing");
        if (timestamp == null || !isValidTimestampFast(timestamp)) return buildErrorJson("invalid timestamp");
        if (messageType == null) return buildErrorJson("messageType missing");

        Set<String> joined = roomId != null ? joinedUsersForRoom(roomId) : null;
        if ("JOIN".equals(messageType)) {
            if (joined != null) joined.add(userId);
            return buildEchoJson(userId, username, msgContent, timestamp, messageType, roomId, serverTimestamp);
        } else {
            if (roomId == null || joined == null || !joined.contains(userId)) {
                return buildErrorJson("user not in room");
            }
            if ("LEAVE".equals(messageType)) joined.remove(userId);
            return buildEchoJson(userId, username, msgContent, timestamp, messageType, roomId, serverTimestamp);
        }
    }

    private static String buildEchoJson(String userId, String username, String type, String clientTime,
                                        String msgType, String roomId, String serverTimestamp) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\"userId\":\"").append(userId)
                .append("\",\"username\":\"").append(username)
                .append("\",\"message\":\"").append(escapeJson(type))
                .append("\",\"timestamp\":\"").append(clientTime)
                .append("\",\"messageType\":\"").append(msgType)
                .append("\",\"serverTimestamp\":\"").append(serverTimestamp)
                .append("\",\"status\":\"OK\"");
        if (roomId != null) sb.append(",\"roomId\":\"").append(roomId).append("\"");
        sb.append("}");
        return sb.toString();
    }

    private static String buildErrorJson(String message) {
        return "{\"status\":\"ERROR\",\"message\":\"" + escapeJson(message) + "\"}";
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static boolean isValidTimestampFast(String ts) {
        if (ts == null || ts.length() < 19) return false;
        return ts.charAt(4) == '-' && ts.charAt(7) == '-' && ts.charAt(10) == 'T'
                && ts.charAt(13) == ':' && ts.charAt(16) == ':';
    }
}
