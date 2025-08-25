package com.redis.poc.streams;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.*;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

@Service
@Slf4j
public class StreamConsumer {

    private final RedisTemplate<String, Object> redisTemplate;
    private static final String STREAM_KEY = "orders";
    private static final String GROUP_NAME = "order-processors";
    private static final String CONSUMER_NAME = "consumer-1";
    private static final String DLQ_STREAM_KEY = "orders:dlq";
    private static final String CONSUMER_STATE_KEY = "orders:consumer:state"; // Hash: fields lastId, processedCount
    private static final String PROCESSED_KEY_PREFIX = "orders:processed:"; // Per-message key with TTL for idempotency
    @Value("${redis.stream.orders.idempotency-ttl-seconds:3600}")
    private long idempotencyTtlSeconds;

    private final AtomicInteger lagGauge = new AtomicInteger();
    public StreamConsumer(RedisTemplate<String, Object> redisTemplate, MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;
        meterRegistry.gauge("orders.stream.lag", lagGauge);
    }
    

    /**
     * Initializes the consumer group. If the group already exists, it does nothing.
     */
    @PostConstruct
    private void init() {
        try {
            redisTemplate.opsForStream().createGroup(STREAM_KEY, ReadOffset.from("0-0"), GROUP_NAME);
        } catch (Exception e) {
            log.warn("Consumer group '{}' already exists.", GROUP_NAME);
        }
    }

    /**
     * A scheduled task that polls the Redis Stream for new messages.
     * It reads messages from the stream, processes them, and acknowledges them.
     * If processing fails, it moves the message to a dead-letter queue.
     */
    @Scheduled(fixedRate = 5000) // Poll every 5 seconds
    public void consumeMessages() {
        String lastId = (String) redisTemplate.opsForHash().get(CONSUMER_STATE_KEY, "lastId");
        StreamOffset<String> offset = lastId != null ? StreamOffset.create(STREAM_KEY, ReadOffset.from(lastId)) : StreamOffset.create(STREAM_KEY, ReadOffset.lastConsumed());
    @SuppressWarnings("unchecked")
    List<MapRecord<String, Object, Object>> messages = redisTemplate.opsForStream().read(
        Consumer.from(GROUP_NAME, CONSUMER_NAME),
        StreamReadOptions.empty().count(10).block(Duration.ofSeconds(2)),
        offset
    );

    if (messages != null) {
            for (MapRecord<String, Object, Object> message : messages) {
                try {
                    // Simulate processing
                    log.info("Processing message: {}", message.getValue());
                    // Idempotency: skip if already processed (simple set check)
                    String processedKey = PROCESSED_KEY_PREFIX + message.getId().getValue();
                    Boolean already = redisTemplate.hasKey(processedKey);
                    if (Boolean.TRUE.equals(already)) {
                        log.debug("Skipping already processed message {}", message.getId());
                        redisTemplate.opsForStream().acknowledge(STREAM_KEY, GROUP_NAME, message.getId());
                        continue;
                    }
                    redisTemplate.opsForValue().set(processedKey, "1", java.time.Duration.ofSeconds(idempotencyTtlSeconds));
                    redisTemplate.opsForHash().put(CONSUMER_STATE_KEY, "lastId", message.getId().getValue());
                    redisTemplate.opsForHash().increment(CONSUMER_STATE_KEY, "processedCount", 1);
                    redisTemplate.opsForStream().acknowledge(STREAM_KEY, GROUP_NAME, message.getId());
                } catch (Exception e) {
                    log.error("Error processing message: {}", message.getValue(), e);
                    // Move the message to the dead-letter queue
                    redisTemplate.opsForStream().add(DLQ_STREAM_KEY, message.getValue());
                    // Acknowledge the original message to remove it from the main stream
                    redisTemplate.opsForStream().acknowledge(STREAM_KEY, GROUP_NAME, message.getId());
                }
            }
        }
        try {
            String lastProduced = (String) redisTemplate.opsForValue().get("orders:lastId");
            String lastConsumed = (String) redisTemplate.opsForHash().get(CONSUMER_STATE_KEY, "lastId");
            if (lastProduced != null && lastConsumed != null) {
                lagGauge.set((int)Math.max(0, parseSeq(lastProduced) - parseSeq(lastConsumed)));
            }
        } catch (Exception ignore) {}
    }

    private long parseSeq(String id) { try { return Long.parseLong(id.split("-")[1]); } catch (Exception e) { return 0; } }
}
