package com.redis.poc.pubsub;

import org.springframework.context.ApplicationEvent;

/**
 * Best Practice: A custom, strongly-typed application event.
 * This is published when a Redis key expiration is detected. It decouples the
 * listener that receives the raw Redis message from the components that need to
 * react to the business event of a key expiring.
 */
public class KeyExpiredEvent extends ApplicationEvent {

    private final String key;

    public KeyExpiredEvent(Object source, String key) {
        super(source);
        this.key = key;
    }

    public String getKey() {
        return key;
    }
}
