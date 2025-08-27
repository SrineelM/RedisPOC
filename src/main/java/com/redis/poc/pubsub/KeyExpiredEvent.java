package com.redis.poc.pubsub;

import org.springframework.context.ApplicationEvent;

/**
 * Represents a specific business event: the expiration of a key in Redis.
 *
 * This class follows the Spring Application Event model, which is a best practice for
 * decoupling components. Instead of the raw Redis listener having to know about all the
 * services that care about key expirations, it simply publishes this event.
 * Other components can then listen for this specific, strongly-typed event without
 * needing any knowledge of Redis itself.
 */
public class KeyExpiredEvent extends ApplicationEvent {

    // The Redis key that expired.
    private final String key;

    /**
     * Constructs a new KeyExpiredEvent.
     *
     * @param source The component that published the event (typically the Redis listener).
     * @param key    The name of the key that expired.
     */
    public KeyExpiredEvent(Object source, String key) {
        super(source);
        this.key = key;
    }

    /**
     * Gets the expired key.
     *
     * @return The expired key.
     */
    public String getKey() {
        return key;
    }
}
