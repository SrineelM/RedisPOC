package com.redis.poc.pubsub;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A subscriber service that listens for messages on a specific Redis Pub/Sub channel.
 *
 * This class implements the {@link MessageListener} interface from Spring Data Redis.
 * It is registered to a specific topic and will be invoked whenever a message is published to that topic.
 * For simplicity, it stores received messages in an in-memory list.
 */
@Service
@Slf4j
public class RedisMessageSubscriber implements MessageListener {

    // A thread-safe list to store messages received from the Redis channel.
    private final List<String> messageList = Collections.synchronizedList(new ArrayList<>());

    /**
     * This callback method is executed whenever a message is received from the subscribed Redis channel.
     *
     * @param message The raw Redis message, which includes the message body.
     * @param pattern The pattern that matched the channel (if any).
     */
    @Override
    public void onMessage(Message message, byte[] pattern) {
        String receivedMessage = new String(message.getBody());
        log.info("Received message from topic: '{}'", receivedMessage);
        messageList.add(receivedMessage);
    }

    /**
     * Retrieves a copy of the list of messages received so far.
     *
     * @return A list of received messages.
     */
    public List<String> getMessageList() {
        // Return a copy to prevent external modification of the internal list.
        return new ArrayList<>(messageList);
    }
}
