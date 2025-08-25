package com.redis.poc.pubsub;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Best Practice: A decoupled listener for high-level application events.
 * This component has no knowledge of Redis. It only knows about the business event,
 * `KeyExpiredEvent`, making the system modular and easier to test and maintain.
 */
@Component
@Slf4j
public class ApplicationEventListener {

    @EventListener
    public void handleKeyExpiration(KeyExpiredEvent event) {
        log.info("Application event listener received expiration for key: {}. Performing business logic...", event.getKey());
        // TODO: Add business logic here, e.g., clean up related database entries, notify users, etc.
    }
}
