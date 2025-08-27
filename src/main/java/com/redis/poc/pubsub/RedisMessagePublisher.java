package com.redis.poc.pubsub;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.stereotype.Service;

/**
 * A service that acts as a publisher for Redis Pub/Sub messaging.
 *
 * This class provides a simple, high-level method to send messages to a specific
 * Redis channel (topic). It abstracts away the details of the RedisTemplate and
 * provides a clear, intention-revealing API for publishing.
 */
@Service
@Slf4j
public class RedisMessagePublisher {

    // The RedisTemplate provides the core API for Redis interactions.
    private final RedisTemplate<String, Object> redisTemplate;
    // The ChannelTopic represents the specific Redis channel to which messages will be published.
    private final ChannelTopic topic;

    /**
     * Constructs the publisher with the necessary Redis components.
     *
     * @param redisTemplate The RedisTemplate for sending messages, injected by Spring.
     * @param topic         The specific channel topic to publish to, injected by Spring.
     */
    public RedisMessagePublisher(RedisTemplate<String, Object> redisTemplate, ChannelTopic topic) {
        this.redisTemplate = redisTemplate;
        this.topic = topic;
    }

    /**
     * Publishes a message to the configured Redis channel.
     *
     * @param message The message content to be published.
     */
    public void publish(String message) {
        log.info("Publishing message: '{}' to topic: '{}'", message, topic.getTopic());
        // Use RedisTemplate to convert and send the message to the specified topic.
        redisTemplate.convertAndSend(topic.getTopic(), message);
    }
}
