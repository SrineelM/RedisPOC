package com.redis.poc.config;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.*;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.HashMap;
import java.util.Map;

/**
 * Kafka Configuration for Event-Driven Architecture in Higher Environments.
 *
 * <p><b>Purpose:</b></p>
 * <ul>
 *   <li>Integrate Kafka for distributed event streaming (prod/staging only)</li>
 *   <li>Implement Change Data Capture (CDC) pattern with Redis</li>
 *   <li>Enable decoupled, scalable microservice communication</li>
 *   <li>Support event sourcing and CQRS patterns</li>
 * </ul>
 *
 * <p><b>Architecture Pattern: Redis + Kafka Hybrid</b></p>
 * <pre>
 * Application Layer:
 *   └── Write to Redis (primary data store, low-latency)
 *       └── Publish event to Kafka (async, for other services)
 *           └── Consumers: Analytics, Notifications, Audit, External Systems
 *
 * Benefits:
 *   - Redis: Sub-millisecond reads/writes, perfect for real-time operations
 *   - Kafka: Durable event log, perfect for async processing and integration
 *   - Best of both worlds: Speed + Reliability
 * </pre>
 *
 * <p><b>Use Cases:</b></p>
 * <ul>
 *   <li><b>Cache Invalidation:</b> Publish cache-clear events when data changes</li>
 *   <li><b>CDC (Change Data Capture):</b> Stream all Redis writes to Kafka for audit</li>
 *   <li><b>Event Sourcing:</b> Store events in Kafka, materialize state in Redis</li>
 *   <li><b>Distributed Coordination:</b> Kafka for commands, Redis for state</li>
 *   <li><b>Analytics Pipeline:</b> Stream Redis events to data warehouse</li>
 * </ul>
 *
 * <p><b>When to Use Kafka vs Redis Pub/Sub:</b></p>
 * <table border="1">
 *   <tr><th>Feature</th><th>Kafka</th><th>Redis Pub/Sub</th></tr>
 *   <tr><td>Persistence</td><td>✓ Durable (configurable retention)</td><td>✗ Fire-and-forget</td></tr>
 *   <tr><td>Message Ordering</td><td>✓ Guaranteed per partition</td><td>~ Best effort</td></tr>
 *   <tr><td>Replay Events</td><td>✓ Yes (from any offset)</td><td>✗ No</td></tr>
 *   <tr><td>Consumer Groups</td><td>✓ Yes (load balancing)</td><td>✗ No</td></tr>
 *   <tr><td>Throughput</td><td>Very High (millions/sec)</td><td>High (hundreds of thousands/sec)</td></tr>
 *   <tr><td>Latency</td><td>Low (2-10ms)</td><td>Very Low (<1ms)</td></tr>
 *   <tr><td>Use Case</td><td>Event streaming, CDC, analytics</td><td>Real-time notifications, cache invalidation</td></tr>
 * </table>
 *
 * <p><b>Configuration Profiles:</b></p>
 * <ul>
 *   <li><b>local:</b> Kafka DISABLED (to save resources on 8GB laptops)</li>
 *   <li><b>dev:</b> Kafka DISABLED (optional: enable for integration testing)</li>
 *   <li><b>prod:</b> Kafka ENABLED (full event streaming pipeline)</li>
 * </ul>
 *
 * <p><b>Environment Variables (Production):</b></p>
 * <pre>
 * KAFKA_BOOTSTRAP_SERVERS=kafka1:9092,kafka2:9092,kafka3:9092
 * KAFKA_CONSUMER_GROUP_ID=redis-poc-consumers
 * KAFKA_TOPIC_PRODUCT_EVENTS=product-events
 * KAFKA_TOPIC_CACHE_INVALIDATION=cache-invalidation
 * </pre>
 *
 * <p><b>Resource Requirements:</b></p>
 * <ul>
 *   <li>Kafka Broker: 2GB RAM minimum, 4GB+ recommended</li>
 *   <li>Application overhead: +200MB for Kafka producer/consumer</li>
 *   <li>Total with Redis + PostgreSQL + Kafka: 8GB+ recommended</li>
 * </ul>
 *
 * @see <a href="https://kafka.apache.org/documentation/">Kafka Documentation</a>
 */
@Configuration
@EnableKafka
@Profile("prod") // Only enabled in production; disable in local/dev to save resources
@Slf4j
public class KafkaConfig {

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Value("${spring.kafka.consumer.group-id:redis-poc-consumers}")
    private String consumerGroupId;

    // ========================================================================
    // PRODUCER CONFIGURATION (for publishing events from Redis to Kafka)
    // ========================================================================

    /**
     * Kafka Producer Configuration with optimized settings for Redis events.
     *
     * <p><b>Key Settings:</b></p>
     * <ul>
     *   <li><b>acks=all:</b> Wait for all replicas to acknowledge (strongest durability)</li>
     *   <li><b>retries=3:</b> Retry failed sends up to 3 times</li>
     *   <li><b>compression=snappy:</b> Reduce network bandwidth (good balance of speed/compression)</li>
     *   <li><b>idempotence=true:</b> Prevent duplicate messages</li>
     *   <li><b>batch.size=16384:</b> Batch messages for higher throughput</li>
     * </ul>
     */
    @Bean
    public ProducerFactory<String, Object> producerFactory() {
        Map<String, Object> config = new HashMap<>();
        
        // Bootstrap servers (Kafka cluster)
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        
        // Serializers (convert Java objects to bytes for Kafka)
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        
        // Reliability settings (ensure messages are not lost)
        config.put(ProducerConfig.ACKS_CONFIG, "all"); // Wait for all replicas (strongest guarantee)
        config.put(ProducerConfig.RETRIES_CONFIG, 3); // Retry failed sends
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true); // Prevent duplicates
        
        // Performance settings (balance throughput vs latency)
        config.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "snappy"); // Compress messages
        config.put(ProducerConfig.BATCH_SIZE_CONFIG, 16384); // Batch size in bytes
        config.put(ProducerConfig.LINGER_MS_CONFIG, 10); // Wait 10ms to batch more messages
        config.put(ProducerConfig.BUFFER_MEMORY_CONFIG, 33554432); // 32MB buffer
        
        // Timeout settings
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 5000); // Max time to block on send()
        config.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 30000); // 30 seconds
        
        log.info("Kafka Producer configured: servers={}, acks=all, compression=snappy", bootstrapServers);
        return new DefaultKafkaProducerFactory<>(config);
    }

    /**
     * KafkaTemplate for sending messages to Kafka topics.
     * This is the main API for publishing events from Redis operations.
     */
    @Bean
    public KafkaTemplate<String, Object> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }

    // ========================================================================
    // CONSUMER CONFIGURATION (for consuming events from Kafka)
    // ========================================================================

    /**
     * Kafka Consumer Configuration with optimized settings for processing events.
     *
     * <p><b>Key Settings:</b></p>
     * <ul>
     *   <li><b>group.id:</b> Consumer group for load balancing</li>
     *   <li><b>auto-offset-reset=earliest:</b> Start from beginning if no offset stored</li>
     *   <li><b>enable-auto-commit=false:</b> Manual commit for better control</li>
     *   <li><b>max-poll-records=100:</b> Process 100 records per poll</li>
     * </ul>
     */
    @Bean
    public ConsumerFactory<String, Object> consumerFactory() {
        Map<String, Object> config = new HashMap<>();
        
        // Bootstrap servers
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        
        // Consumer group (enables load balancing across multiple consumers)
        config.put(ConsumerConfig.GROUP_ID_CONFIG, consumerGroupId);
        
        // Deserializers (convert bytes from Kafka back to Java objects)
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        config.put(JsonDeserializer.TRUSTED_PACKAGES, "com.redis.poc.*"); // Security: only trust our packages
        
        // Offset management (where to start consuming)
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"); // Start from beginning
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false); // Manual commit for reliability
        
        // Performance settings
        config.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 100); // Batch size
        config.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 300000); // 5 minutes
        config.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 30000); // 30 seconds
        config.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 10000); // 10 seconds
        
        log.info("Kafka Consumer configured: servers={}, group={}, auto-commit=false", bootstrapServers, consumerGroupId);
        return new DefaultKafkaConsumerFactory<>(config);
    }

    /**
     * Kafka Listener Container Factory for @KafkaListener annotations.
     * Enables concurrent message processing with multiple threads.
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> kafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, Object> factory = 
            new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory());
        factory.setConcurrency(3); // 3 consumer threads for parallel processing
        factory.getContainerProperties().setPollTimeout(3000); // 3 seconds poll timeout
        
        log.info("Kafka Listener Container Factory configured: concurrency=3");
        return factory;
    }

    // ========================================================================
    // TOPIC CONFIGURATION (auto-create topics on startup)
    // ========================================================================

    /**
     * Product Events Topic - for streaming all product CRUD operations.
     * Use case: Event sourcing, audit log, analytics pipeline.
     */
    @Bean
    public NewTopic productEventsTopic() {
        return TopicBuilder.name("product-events")
                .partitions(3) // 3 partitions for parallel processing
                .replicas(3) // 3 replicas for fault tolerance
                .config("retention.ms", "604800000") // 7 days retention
                .config("compression.type", "snappy") // Enable compression
                .build();
    }

    /**
     * Cache Invalidation Topic - for distributed cache synchronization.
     * Use case: When one instance updates Redis, notify all other instances to invalidate cache.
     */
    @Bean
    public NewTopic cacheInvalidationTopic() {
        return TopicBuilder.name("cache-invalidation")
                .partitions(1) // Single partition (order matters for cache invalidation)
                .replicas(3) // 3 replicas for fault tolerance
                .config("retention.ms", "3600000") // 1 hour retention (short-lived events)
                .config("compression.type", "snappy")
                .build();
    }

    /**
     * Redis CDC Topic - for Change Data Capture (stream all Redis writes).
     * Use case: Sync Redis to data warehouse, external systems, or secondary databases.
     */
    @Bean
    public NewTopic redisCdcTopic() {
        return TopicBuilder.name("redis-cdc")
                .partitions(5) // 5 partitions for high throughput
                .replicas(3) // 3 replicas
                .config("retention.ms", "2592000000") // 30 days retention
                .config("compression.type", "lz4") // LZ4 for better compression ratio
                .build();
    }
}
