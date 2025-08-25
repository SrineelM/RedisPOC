package com.redis.poc.pubsub;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class RedisMessagePublisher {

    private final RedisTemplate<String, Object> redisTemplate;
    private final ChannelTopic topic;

    public RedisMessagePublisher(RedisTemplate<String, Object> redisTemplate, ChannelTopic topic) {
        this.redisTemplate = redisTemplate;
        this.topic = topic;
    }

    /**
     * Publishes a message to the configured Redis channel.
     * @param message The message to be published.
     */
    public void publish(String message) {
        log.info("Publishing message: {}", message);
        redisTemplate.convertAndSend(topic.getTopic(), message);
    }
}
