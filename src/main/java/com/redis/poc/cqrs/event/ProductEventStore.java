package com.redis.poc.cqrs.event;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import org.springframework.data.redis.connection.stream.MapRecord;

/**
 * Manages the persistence of product events and snapshots using Redis.
 * This class demonstrates two strategies for event storage:
 * 1. A simple approach using Redis Lists (legacy).
 * 2. A more robust approach using Redis Streams.
 * It also handles saving and loading aggregate snapshots to optimize state reconstruction.
 */
@Component
public class ProductEventStore {
    private final RedisTemplate<String, Object> redisTemplate;
    private static final String EVENT_STREAM_KEY = "product:events";

    public ProductEventStore(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Appends an event to a Redis List. Legacy method for basic event sourcing.
     * @param event The product event to append.
     */
    public void appendEvent(ProductEvent event) {
        redisTemplate.opsForList().rightPush(EVENT_STREAM_KEY, event);
    }

    /**
     * Retrieves all events from the Redis List.
     * Note: This is inefficient for large event stores and is provided for the legacy implementation.
     * @return A list of all product events.
     */
    public List<Object> getAllEvents() {
        return redisTemplate.opsForList().range(EVENT_STREAM_KEY, 0, -1);
    }

    // --- Redis Streams based event sourcing ---

    /**
     * Appends a product event to a Redis Stream, which is the preferred method for event sourcing.
     * @param event The product event to append.
     * @return The unique ID of the event record in the stream.
     */
    public String appendEventToStream(ProductEvent event) {
        java.util.Map<String, Object> fields = new java.util.HashMap<>();
        fields.put("type", event.getType().name());
        fields.put("productId", event.getProductId());
        fields.put("payload", event.getPayload());
        fields.put("timestamp", event.getTimestamp().toString());
        fields.put("version", event.getVersion());
        // For production: The stream key could be per-aggregate, e.g., "product:events:stream:" + event.getProductId()
        var recordId = redisTemplate.opsForStream().add(EVENT_STREAM_KEY + ":stream", fields);
        // For production: Handle null recordId and implement a retry mechanism.
        return recordId != null ? recordId.getValue() : null;
    }

    /**
     * Reads a range of events from the Redis Stream.
     * @param from The starting stream ID (e.g., "0-0" for the beginning).
     * @param to The ending stream ID (e.g., "+" for the latest).
     * @return A list of event data maps.
     */
    public java.util.List<java.util.Map<String, Object>> readEventsFromStream(String from, String to) {
        // For production: Use XREADGROUP with consumer groups for scalable, resilient consumption.
        // The current XRANGE approach is simpler for demonstration.
        List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream()
        .range(EVENT_STREAM_KEY + ":stream", org.springframework.data.domain.Range.closed(from, to));
        java.util.List<java.util.Map<String, Object>> events = new java.util.ArrayList<>();
        if (records != null) {
            for (MapRecord<String, Object, Object> record : records) {
                java.util.Map<Object, Object> raw = record.getValue();
                java.util.Map<String, Object> converted = new java.util.HashMap<>();
                for (var entry : raw.entrySet()) {
                    converted.put(String.valueOf(entry.getKey()), entry.getValue());
                }
                events.add(converted);
            }
        }
        return events;
    }

    /**
     * Saves a snapshot of an aggregate's state to a Redis Hash.
     * @param aggregateId The unique ID of the aggregate (e.g., product ID).
     * @param product The snapshot object to save.
     */
    public void saveSnapshot(String aggregateId, Object product) {
        // For production: Consider a more scalable snapshot store and versioning snapshots.
        redisTemplate.opsForHash().put("product:snapshots", aggregateId, product);
    }

    /**
     * Loads the latest snapshot for a given aggregate from the Redis Hash.
     * @param aggregateId The unique ID of the aggregate.
     * @return The snapshot object, or null if not found.
     */
    public Object loadSnapshot(String aggregateId) {
        return redisTemplate.opsForHash().get("product:snapshots", aggregateId);
    }

}
