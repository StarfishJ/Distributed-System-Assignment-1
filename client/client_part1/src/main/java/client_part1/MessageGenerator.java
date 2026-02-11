package client_part1;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Single dedicated thread that generates all messages and places them in a thread-safe
 * queue/buffer. Sending (worker) threads only take from the queue—they never wait for
 * this generator.
 *
 * 3.1 Message Generation (500,000 total):
 * - userId: random 1–100000, username: "user{userId}", message: random from 50 pre-defined
 * - roomId: random 1–20, messageType: 90% TEXT, 5% JOIN, 5% LEAVE, timestamp: ISO-8601
 *
 * Client enforces JOIN-before-TEXT/LEAVE per room (per-room set of joined userIds) so that
 * server receives valid sequences; server should still validate and reject invalid messages as a safety net.
 */
public class MessageGenerator implements Runnable {

    public static final int NUM_ROOMS = 20;

    private final BlockingQueue<ClientMessage> queue;
    private final List<BlockingQueue<ClientMessage>> queuesByRoom; // null = use single queue or workerQueues
    private final List<BlockingQueue<ClientMessage>> workerQueues; // null = use queue or queuesByRoom
    private final List<List<BlockingQueue<ClientMessage>>> queuesByRoomIndex; // worker i -> room (i%NUM_ROOMS), for sharding by userId
    private final int totalMessages;
    private int[] messagesPerRoom = null; // when non-null, put exactly messagesPerRoom[r] into queue r (warmup, 20 queues)
    private int[] messagesPerWorker = null; // when non-null, put exactly messagesPerWorker[i] into workerQueues[i] (warmup, per-worker)

    /** Per-room set of userIds that have joined and not yet left; ensures JOIN before TEXT/LEAVE for that (user, room). */
    private final List<Set<String>> joinedUsersByRoom;

    private static final String[] MESSAGES = {
            "Hello!", "How are you?", "Good morning", "Good night", "See you later",
            "What's up?", "Nice to meet you", "Thank you", "You're welcome", "No problem",
            "Sounds good", "Let me know", "I agree", "That's right", "Exactly",
            "Awesome!", "Great job", "Well done", "Keep it up", "Good luck",
            "Take care", "Have a good day", "Catch you later", "Talk soon", "Bye",
            "Sure thing", "Got it", "Understood", "Makes sense", "I see",
            "Interesting", "Tell me more", "Really?", "No way", "Wow",
            "Cool", "Nice", "Perfect", "Excellent", "Amazing",
            "Not bad", "Could be better", "Let's do it", "Why not?", "Maybe",
            "I think so", "Probably", "Definitely", "Absolutely", "Of course"
    };

    private static List<Set<String>> createJoinedState() {
        List<Set<String>> list = new ArrayList<>(NUM_ROOMS);
        for (int i = 0; i < NUM_ROOMS; i++) list.add(new HashSet<>());
        return list;
    }

    /** Build index: for each room r, list of worker queues that serve room r (worker i serves room i%NUM_ROOMS). */
    private static List<List<BlockingQueue<ClientMessage>>> buildRoomIndex(List<BlockingQueue<ClientMessage>> allQueues) {
        if (allQueues == null) return null;
        List<List<BlockingQueue<ClientMessage>>> map = new ArrayList<>(NUM_ROOMS);
        for (int i = 0; i < NUM_ROOMS; i++) map.add(new ArrayList<>());
        for (int i = 0; i < allQueues.size(); i++) {
            map.get(i % NUM_ROOMS).add(allQueues.get(i));
        }
        return map;
    }

    /** Single queue: all messages go to one queue (e.g. all workers use same room). */
    public MessageGenerator(BlockingQueue<ClientMessage> queue, int totalMessages) {
        this.queue = queue;
        this.queuesByRoom = null;
        this.workerQueues = null;
        this.queuesByRoomIndex = null;
        this.totalMessages = totalMessages;
        this.joinedUsersByRoom = createJoinedState();
    }

    /** Per-room queues: messages go to queue[roomId-1]. queuesByRoom must have size NUM_ROOMS. */
    public MessageGenerator(List<BlockingQueue<ClientMessage>> queuesByRoom, int totalMessages) {
        this.queue = null;
        this.queuesByRoom = queuesByRoom;
        this.workerQueues = null;
        this.queuesByRoomIndex = null;
        this.totalMessages = totalMessages;
        this.joinedUsersByRoom = createJoinedState();
    }

    /** Per-room queues with exact counts per room: messagesPerRoom[roomIndex] messages go to queue[roomIndex]. For warmup (20 queues). */
    public MessageGenerator(List<BlockingQueue<ClientMessage>> queuesByRoom, int[] messagesPerRoom) {
        this.queue = null;
        this.queuesByRoom = queuesByRoom;
        this.workerQueues = null;
        this.queuesByRoomIndex = null;
        int sum = 0;
        for (int c : messagesPerRoom) sum += c;
        this.totalMessages = sum;
        this.messagesPerRoom = messagesPerRoom;
        this.joinedUsersByRoom = createJoinedState();
    }

    /** Per-worker queues + shard by (roomId, userId): same user in same room always to same worker → JOIN before TEXT, no "user not in room". Main phase. */
    public MessageGenerator(List<BlockingQueue<ClientMessage>> workerQueues, int totalMessages, boolean useSharding) {
        this.queue = null;
        this.queuesByRoom = null;
        this.workerQueues = workerQueues;
        this.queuesByRoomIndex = buildRoomIndex(workerQueues);
        this.totalMessages = totalMessages;
        this.joinedUsersByRoom = createJoinedState();
    }

    /** Per-worker queues with exact counts per worker: messagesPerWorker[i] into workerQueues[i], room (i%20)+1. Warmup. Use perWorkerWarmup=true to disambiguate from per-room (List, int[]). */
    public MessageGenerator(List<BlockingQueue<ClientMessage>> workerQueues, int[] messagesPerWorker, boolean perWorkerWarmup) {
        if (!perWorkerWarmup) throw new IllegalArgumentException("use perWorkerWarmup=true for per-worker warmup");
        this.queue = null;
        this.queuesByRoom = null;
        this.workerQueues = workerQueues;
        this.queuesByRoomIndex = null;
        int sum = 0;
        for (int c : messagesPerWorker) sum += c;
        this.totalMessages = sum;
        this.messagesPerWorker = messagesPerWorker;
        this.joinedUsersByRoom = createJoinedState();
    }

    @Override
    public void run() {
        try {
            if (workerQueues != null) {
                if (messagesPerWorker != null) {
                    System.out.println("[Generator] Started, will put " + totalMessages + " messages into " + workerQueues.size() + " worker queues (warmup).");
                    for (int w = 0; w < workerQueues.size(); w++) {
                        int roomId = (w % NUM_ROOMS) + 1;
                        for (int j = 0; j < messagesPerWorker[w]; j++) {
                            ClientMessage msg = generateMessageForRoom(roomId);
                            workerQueues.get(w).put(msg);
                        }
                    }
                    System.out.println("[Generator] Done, put " + totalMessages + " messages into " + workerQueues.size() + " worker queues.");
                } else {
                    System.out.println("[Generator] Started, will put " + totalMessages + " messages into " + workerQueues.size() + " worker queues (sharded by room+user).");
                    for (int i = 0; i < totalMessages; i++) {
                        ClientMessage msg = generateMessage();
                        int roomIndex = Integer.parseInt(msg.getRoomId()) - 1;
                        List<BlockingQueue<ClientMessage>> roomQueues = queuesByRoomIndex.get(roomIndex);
                        int sub = Math.abs(msg.getUserId().hashCode()) % roomQueues.size();
                        roomQueues.get(sub).put(msg);
                    }
                    System.out.println("[Generator] Done, put " + totalMessages + " messages into " + workerQueues.size() + " worker queues.");
                }
                return;
            }
            if (queuesByRoom != null) {
                System.out.println("[Generator] Started, will put " + totalMessages + " messages into " + NUM_ROOMS + " queues.");
            }
            if (messagesPerRoom != null) {
                for (int roomIndex = 0; roomIndex < messagesPerRoom.length && roomIndex < NUM_ROOMS; roomIndex++) {
                    for (int j = 0; j < messagesPerRoom[roomIndex]; j++) {
                        ClientMessage msg = generateMessageForRoom(roomIndex + 1);
                        queuesByRoom.get(roomIndex).put(msg);
                    }
                }
            } else {
                for (int i = 0; i < totalMessages; i++) {
                    ClientMessage msg = generateMessage();
                    int roomIndex = Integer.parseInt(msg.getRoomId()) - 1;
                    if (queuesByRoom != null) {
                        queuesByRoom.get(roomIndex).put(msg);
                    } else {
                        queue.put(msg);
                    }
                }
            }
            if (queuesByRoom != null) {
                System.out.println("[Generator] Done, put " + totalMessages + " messages into " + NUM_ROOMS + " room queues.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("MessageGenerator interrupted: " + e.getMessage());
        }
    }

    private ClientMessage generateMessage() {
        return generateMessageForRoom(ThreadLocalRandom.current().nextInt(NUM_ROOMS) + 1);
    }

    private ClientMessage generateMessageForRoom(int roomId) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        int roomIndex = roomId - 1;
        Set<String> joined = joinedUsersByRoom.get(roomIndex);

        String userId;
        String messageType;

        if (joined.isEmpty()) {
            userId = String.valueOf(rnd.nextInt(1, 100_001));
            joined.add(userId);
            messageType = "JOIN";
        } else {
            int r = rnd.nextInt(100);
            if (r < 90) {
                messageType = "TEXT";
                userId = pickRandomFromSet(joined);
            } else if (r < 95) {
                messageType = "JOIN";
                String existingInOther = pickUserIdFromOtherRoom(roomIndex);
                userId = (existingInOther != null && rnd.nextBoolean()) ? existingInOther : String.valueOf(rnd.nextInt(1, 100_001));
                joined.add(userId);
            } else {
                messageType = "LEAVE";
                userId = pickRandomFromSet(joined);
                joined.remove(userId);
            }
        }

        ClientMessage msg = new ClientMessage();
        msg.setUserId(userId);
        msg.setUsername("user" + userId);
        msg.setMessage(MESSAGES[rnd.nextInt(MESSAGES.length)]);
        msg.setRoomId(String.valueOf(roomId));
        msg.setMessageType(messageType);
        msg.setTimestamp(Instant.now().toString());
        return msg;
    }

    private String pickUserIdFromOtherRoom(int currentRoomIndex) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        int otherRoom = rnd.nextInt(NUM_ROOMS - 1);
        if (otherRoom >= currentRoomIndex) otherRoom++;
        Set<String> set = joinedUsersByRoom.get(otherRoom);
        if (set.isEmpty()) return null;
        return pickRandomFromSet(set);
    }

    private static String pickRandomFromSet(Set<String> set) {
        int size = set.size();
        if (size == 0) return null;
        int skip = ThreadLocalRandom.current().nextInt(size);
        Iterator<String> it = set.iterator();
        for (int i = 0; i < skip; i++) it.next();
        return it.next();
    }
}
