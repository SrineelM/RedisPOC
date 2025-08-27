package com.redis.poc.pubsub;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * A high-level, decoupled listener for application-specific events.
 *
 * This component demonstrates a key design principle: it has no knowledge of Redis or any other
 * low-level infrastructure. It operates purely on the level of Spring's Application Events.
 * By listening for the strongly-typed {@link KeyExpiredEvent}, it can execute business logic
 * in response to a key expiration without being coupled to the source of that event.
 * This makes the system more modular, easier to test, and easier to maintain.
 */
@Component
@Slf4j
public class ApplicationEventListener {

    /**
     * Handles the {@link KeyExpiredEvent} published when a Redis key expires.
     *
     * The {@code @EventListener} annotation automatically registers this method to be invoked
     * whenever a {@code KeyExpiredEvent} is published to the Spring ApplicationContext.
     *
     * This enhanced version demonstrates how to handle different types of expired keys
     * by inspecting the key's prefix, allowing for different business logic per key type.
     *
     * @param event The event containing the expired key.
     */
    @EventListener
    public void handleKeyExpiration(KeyExpiredEvent event) {
        String key = event.getKey();
        log.info("Application event listener received expiration for key: '{}'. Processing...", key);

        // --- Enhanced Logic: Handle events based on key patterns ---
        if (key == null) {
            return; // Or handle as an error
        }

        // Example 1: A revoked JWT ID has been cleaned up from the denylist.
        if (key.startsWith("jti:")) {
            String jti = key.substring(4);
            log.info("[Business Logic] Revoked JWT with JTI '{}' has been cleared from the Redis denylist.", jti);
            // No further action is typically needed here.

            // Example 2: A user's session has expired.
        } else if (key.startsWith("session:")) {
            String sessionId = key.substring(8);
            log.warn("[Business Logic] User session '{}' expired. Triggering cleanup or notification.", sessionId);
            // TODO: Add logic to clean up related database entries, notify a user, or log for analytics.

            // Example 3: A rate limit bucket has expired.
        } else if (key.startsWith("rl:")) {
            log.debug("[Business Logic] Rate limit bucket '{}' expired and was cleaned up by Redis.", key);
            // This is usually informational and requires no action.

        } else {
            // Default case for any other keys.
            log.info("[Business Logic] An unhandled key '{}' expired. No specific action configured.", key);
        }
    }
}
