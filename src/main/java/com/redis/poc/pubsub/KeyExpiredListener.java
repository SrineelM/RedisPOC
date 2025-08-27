package com.redis.poc.pubsub;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

/**
 * A Redis MessageListener that specializes in handling keyspace notifications, specifically for expired keys.
 *
 * This class acts as a bridge between the low-level Redis pub/sub system and the high-level Spring Application Event model.
 * Its sole responsibility is to listen for raw messages from Redis on the key expiration channel,
 * extract the expired key, and then publish a clean, strongly-typed {@link KeyExpiredEvent}.
 * This decouples the Redis infrastructure concerns from the application's business logic.
 *
 * To receive events, Redis must be configured to enable keyspace notifications for expired events (e.g., `notify-keyspace-events Ex`).
 */
@Component
@Slf4j
public class KeyExpiredListener implements MessageListener {

    // The ApplicationEventPublisher is a Spring infrastructure component used to publish events to the application
    // context.
    private final ApplicationEventPublisher publisher;

    /**
     * Constructs the listener with the required ApplicationEventPublisher.
     *
     * @param publisher The Spring ApplicationEventPublisher, injected by the container.
     */
    public KeyExpiredListener(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    /**
     * Callback method executed when a message is received from the configured Redis channel.
     *
     * @param message The raw Redis message, containing the expired key in its body.
     * @param pattern The pattern that matched the channel (e.g., "__keyevent@*__:expired").
     */
    @Override
    public void onMessage(Message message, byte[] pattern) {
        String expiredKey = new String(message.getBody());
        log.info("Redis key expired: '{}'. Publishing KeyExpiredEvent.", expiredKey);

        // Publish the clean, decoupled application event. Any component in the application
        // can now listen for KeyExpiredEvent without needing to know about Redis.
        publisher.publishEvent(new KeyExpiredEvent(this, expiredKey));
    }
}
