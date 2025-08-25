package com.redis.poc.pubsub;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

/**
 * Best Practice: A dedicated listener for Redis keyspace notifications.
 * Its sole responsibility is to catch the low-level Redis message and publish a
 * clean, high-level Spring ApplicationEvent. This decouples the Redis infrastructure
 * from the business logic that needs to react to the event.
 */
@Component
@Slf4j
public class KeyExpiredListener implements MessageListener {

    private final ApplicationEventPublisher publisher;

    public KeyExpiredListener(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String expiredKey = new String(message.getBody());
        log.info("Redis key expired: {}. Publishing KeyExpiredEvent.", expiredKey);
        // Publish the clean, decoupled application event
        publisher.publishEvent(new KeyExpiredEvent(this, expiredKey));
    }
}
