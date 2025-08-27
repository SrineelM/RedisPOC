package com.redis.poc.streams;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A robust service that consumes and processes events from a Redis Stream using a consumer group.
 *
 * <p><b>Core Concepts Implemented:</b></p>
 * <ul>
 *   <li><b>Consumer Groups:</b> Allows multiple instances of this service to work together,
 *       load-balancing message processing and providing fault tolerance. Redis ensures each
 *       message is delivered to only one consumer in the group.</li>
 *   <li><b>Message Acknowledgement (XACK):</b> Explicitly confirms that a message has been
 *       successfully processed, removing it from the Pending Entries List (PEL) of the consumer group.</li>
 *   <li><b>Atomic Idempotency:</b> Prevents the same message from being processed more than once,
 *       even if it's redelivered after a crash. This is achieved using an atomic `SETNX` operation.</li>
 *   <li><b>Dead-Letter Queue (DLQ):</b> Isolates messages that fail processing, preventing them
 *       from blocking the stream (a "poison pill" scenario). Failed messages can be analyzed later.</li>
 *   <li><b>Lag Monitoring:</b> Uses Micrometer to expose a metric (`orders.stream.lag`) that
 *       tracks the number of unprocessed messages for the consumer group, which is critical for
 *       monitoring consumer health and performance.</li>
 * </ul>
 */
@Service
@Slf4j
public class StreamConsumer {

    // --- Redis Keys and Configuration ---

    /** The primary Redis Stream key for order events. */
    private static final String STREAM_KEY = "orders";

    /** The name of the consumer group. All consumers in this group collaborate to process the stream. */
    private static final String GROUP_NAME = "order-processors";

    /** A unique name for this specific consumer instance within the group. */
    private static final String CONSUMER_NAME = "consumer-1"; // In a real-world app, this could be dynamic (e.g., pod name).

    /** The stream key for the Dead-Letter Queue, where failed messages are sent. */
    private static final String DLQ_STREAM_KEY = "orders:dlq";

    /** A prefix for creating unique keys in Redis to track processed message IDs for idempotency. */
    private static final String PROCESSED_KEY_PREFIX = "orders:processed:";

    /**
     * Time-to-live (TTL) for idempotency keys. This determines how long Redis remembers a processed
     * message ID. It should be longer than the maximum possible time for a message to be redelivered.
     */
    @Value("${redis.stream.orders.idempotency-ttl-seconds:3600}")
    private long idempotencyTtlSeconds;

    // --- Dependencies ---

    private final RedisTemplate<String, Object> redisTemplate;
    private final AtomicLong lagGauge;

    /**
     * Constructs a new StreamConsumer.
     *
     * @param redisTemplate The Spring RedisTemplate for interacting with Redis.
     * @param meterRegistry The Micrometer registry for creating and managing metrics.
     */
    public StreamConsumer(RedisTemplate<String, Object> redisTemplate, MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;
        // Initialize a Micrometer gauge to monitor the consumer group's lag.
        // The gauge will report the value held by the AtomicLong.
        this.lagGauge = meterRegistry.gauge("orders.stream.lag", new AtomicLong(0));
    }

    /**
     * Initializes the consumer by creating the consumer group on the stream.
     * This method is run once after the bean is constructed, thanks to @PostConstruct.
     * It's designed to be safe on application restarts; if the group already exists,
     * the Redis command will fail, and the exception is caught and logged.
     */
    @PostConstruct
    private void init() {
        try {
            // Create the consumer group. ReadOffset.from("0-0") means the group will be able
            // to read the entire history of the stream if it's created for the first time.
            // If the stream doesn't exist, Spring Data Redis will create it automatically.
            redisTemplate.opsForStream().createGroup(STREAM_KEY, ReadOffset.from("0-0"), GROUP_NAME);
            log.info("Consumer group '{}' created for stream '{}'.", GROUP_NAME, STREAM_KEY);
        } catch (RedisSystemException e) {
            // This is expected if the group already exists, which is normal on a restart.
            log.warn("Consumer group '{}' already exists, which is expected. Details: {}", GROUP_NAME, e.getMessage());
        }
    }

    /**
     * A scheduled task that periodically polls the Redis Stream for new messages.
     * This is the core processing loop of the consumer.
     */
    @Scheduled(fixedRate = 5000) // Polls every 5 seconds.
    public void consumeMessages() {
        try {
            // Read messages from the stream for our specific consumer group and name.
            // `ReadOffset.lastConsumed()` is a special offset ('>') that tells Redis to send
            // only new messages that have not yet been delivered to any consumer in this group.
            // This is the standard way to consume a stream with a group.
            @SuppressWarnings("unchecked")
            List<MapRecord<String, Object, Object>> messages = redisTemplate.opsForStream().read(
                    Consumer.from(GROUP_NAME, CONSUMER_NAME),
                    StreamReadOptions.empty()
                            .count(10) // Read up to 10 messages at a time.
                            .block(Duration.ofSeconds(2)), // Block for 2s if no messages, to avoid busy-polling.
                    StreamOffset.create(STREAM_KEY, ReadOffset.lastConsumed())
            );

            if (messages != null && !messages.isEmpty()) {
                log.debug("Received {} new messages from stream '{}'.", messages.size(), STREAM_KEY);
                for (MapRecord<String, Object, Object> message : messages) {
                    processMessage(message);
                }
            }
        } catch (Exception e) {
            // Catching exceptions at this top level prevents the scheduler from stopping.
            log.error("An unexpected error occurred during the message consumption cycle.", e);
        }

        // After processing a batch, update the lag metric.
        updateLagMetric();
    }

    /**
     * Processes a single message from the stream.
     * This method contains the logic for idempotency, processing, and error handling (DLQ).
     *
     * @param message The message record to process.
     */
    private void processMessage(MapRecord<String, Object, Object> message) {
        String messageId = message.getId().getValue();
        String processedKey = PROCESSED_KEY_PREFIX + messageId;

        try {
            // --- Idempotency Check (Atomic) ---
            // Use `setIfAbsent` which maps to the atomic `SETNX` command in Redis.
            // It sets the key only if it does not already exist and returns true if it was set.
            // This prevents a race condition where two consumers could process the same message
            // if it was redelivered after a failure.
            Boolean wasSet = redisTemplate.opsForValue().setIfAbsent(processedKey, "1", idempotencyTtlSeconds, TimeUnit.SECONDS);

            if (Boolean.FALSE.equals(wasSet)) {
                // If the key was already present, `setIfAbsent` returns false.
                // This means the message has already been processed successfully.
                log.warn("Skipping already processed message ID: {}. Acknowledging to be safe.", messageId);
                // We still acknowledge it to ensure it's removed from this consumer's pending list
                // in case this is a redelivery after a crash post-processing but pre-acknowledgement.
                acknowledgeMessage(message.getId());
                return;
            }

            // --- Business Logic ---
            // This is where the actual work of processing the order would happen.
            // For this example, we just log the message content.
            log.info("Processing message ID: {}. Payload: {}", messageId, message.getValue());
            // Simulate some work.
            // Thread.sleep(100);

            // --- Acknowledgment ---
            // Acknowledge the message to tell Redis it has been successfully processed.
            // This removes it from the Pending Entries List (PEL).
            acknowledgeMessage(message.getId());
            log.debug("Successfully processed and acknowledged message ID: {}", messageId);

        } catch (Exception e) {
            log.error("Error processing message ID: {}. Moving to Dead-Letter Queue.", messageId, e);

            // --- Dead-Letter Queue Logic ---
            // If any exception occurs, move the problematic message to the DLQ for later inspection.
            // We add the original message ID and the error message for better context.
            redisTemplate.opsForStream().add(MapRecord.create(DLQ_STREAM_KEY, Map.of(
                    "original_message_id", messageId,
                    "error", e.getMessage(),
                    "payload", message.getValue().toString() // Store original payload
            )));

            // --- CRITICAL STEP ---
            // Acknowledge the original message even after failure. This is vital.
            // It removes the "poison pill" message from the main stream's pending list,
            // preventing it from being endlessly redelivered and blocking all further processing.
            acknowledgeMessage(message.getId());
        }
    }

    /**
     * Acknowledges a message in the stream for the consumer group.
     *
     * @param id The RecordId of the message to acknowledge.
     */
    private void acknowledgeMessage(RecordId id) {
        redisTemplate.opsForStream().acknowledge(STREAM_KEY, GROUP_NAME, id);
    }

    /**
     * Calculates the consumer group's lag and updates the Micrometer gauge.
     * Lag is the number of messages in the stream that have not yet been acknowledged by the group.
     * This is a more robust way to measure lag than comparing custom-stored IDs.
     */
    private void updateLagMetric() {
        try {
            // XPENDING command provides a summary of pending messages for a group.
            // The `getTotalPendingMessages()` gives us the total number of messages
            // that have been delivered but not yet acknowledged. This is the "lag".
            PendingMessagesSummary summary = redisTemplate.opsForStream().pending(STREAM_KEY, GROUP_NAME);
            if (summary != null) {
                lagGauge.set(summary.getTotalPendingMessages());
                log.trace("Updated lag for group '{}' to: {}", GROUP_NAME, summary.getTotalPendingMessages());
            }
        } catch (Exception e) {
            log.warn("Could not update lag metric for group '{}'.", GROUP_NAME, e);
        }
    }
}