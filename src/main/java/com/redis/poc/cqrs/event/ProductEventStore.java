package com.redis.poc.cqrs.event;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import org.springframework.data.redis.connection.stream.MapRecord;

@Component
public class ProductEventStore {
    private final RedisTemplate<String, Object> redisTemplate;
    private static final String EVENT_STREAM_KEY = "product:events";

    public ProductEventStore(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    // Legacy list-based event append (for backward compatibility)
    public void appendEvent(ProductEvent event) {
        redisTemplate.opsForList().rightPush(EVENT_STREAM_KEY, event);
    }

    public List<Object> getAllEvents() {
        return redisTemplate.opsForList().range(EVENT_STREAM_KEY, 0, -1);
    }

    // --- Redis Streams based event sourcing ---
    public String appendEventToStream(ProductEvent event) {
        java.util.Map<String, Object> fields = new java.util.HashMap<>();
        fields.put("type", event.getType().name());
        fields.put("productId", event.getProductId());
        fields.put("payload", event.getPayload());
        fields.put("timestamp", event.getTimestamp().toString());
        fields.put("version", event.getVersion());
        var recordId = redisTemplate.opsForStream().add(EVENT_STREAM_KEY + ":stream", fields);
        return recordId != null ? recordId.getValue() : null; // For prod: handle null and retry
    }

    public java.util.List<java.util.Map<String, Object>> readEventsFromStream(String from, String to) {
        // For prod: use XREAD with count and last id tracking; here we use simple XRANGE via opsForStream().range
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

    // For prod: Use consumer groups for scalable consumption, handle event replay and idempotency

    // Snapshot logic (local, Redis-centric)
    public void saveSnapshot(String aggregateId, Object product) {
        redisTemplate.opsForHash().put("product:snapshots", aggregateId, product);
    }

    public Object loadSnapshot(String aggregateId) {
        return redisTemplate.opsForHash().get("product:snapshots", aggregateId);
    }

    // For prod: Use distributed event store (Kafka/EventStoreDB/Redis Streams) and store snapshots in a scalable store
}
