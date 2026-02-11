package client_part1;

public class ClientMessage {

    /** Sentinel for queue shutdown; do not send to server. BlockingQueue does not allow null. */
    public static final ClientMessage POISON = new ClientMessage();

    public static boolean isPoison(ClientMessage msg) {
        return msg == POISON;
    }

    private String userId;
    private String username;
    private String message;
    private String timestamp;
    private String messageType;
    private String roomId;

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getTimestamp() { return timestamp; }
    public void setTimestamp(String timestamp) { this.timestamp = timestamp; }

    public String getMessageType() { return messageType; }
    public void setMessageType(String messageType) { this.messageType = messageType; }

    public String getRoomId() { return roomId; }
    public void setRoomId(String roomId) { this.roomId = roomId; }

    @Override
    public String toString() {
        return "ClientMessage{" +
                "userId='" + userId + '\'' +
                ", username='" + username + '\'' +
                ", message='" + message + '\'' +
                ", timestamp='" + timestamp + '\'' +
                ", messageType='" + messageType + '\'' +
                ", roomId='" + roomId + '\'' +
                '}';
    }

}
