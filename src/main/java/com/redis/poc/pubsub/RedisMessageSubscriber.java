package com.redis.poc.pubsub;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
public class RedisMessageSubscriber implements MessageListener {

    private final List<String> messageList = new ArrayList<>();

    /**
     * This method is called when a message is received from the Redis channel.
     * It logs the received message and adds it to a list for later retrieval.
     */
    @Override
    public void onMessage(Message message, byte[] pattern) {
        String receivedMessage = new String(message.getBody());
        log.info("Received message: {}", receivedMessage);
        messageList.add(receivedMessage);
    }

    public List<String> getMessageList() {
        return messageList;
    }
}
