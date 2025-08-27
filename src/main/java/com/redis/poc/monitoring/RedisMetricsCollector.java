package com.redis.poc.monitoring;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import java.util.Properties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

/**
 * A centralized service for collecting and exposing Redis-related metrics using Micrometer.
 * This class provides a single point of contact for all Redis instrumentation, ensuring
 * that metric names and tags are consistent across the application.
 * It tracks cache performance, operational latency, errors, and live Redis server stats.
 */
@Component
@Slf4j
public class RedisMetricsCollector {

    private final RedisTemplate<String, Object> redisTemplate;
    private final MeterRegistry meterRegistry;

    // --- Micrometer Metrics ---
    private Counter cacheHitCounter;
    private Counter cacheMissCounter;
    private Counter redisErrorCounter;
    private Counter redisCommandSuccessCounter;

    // Volatile to ensure visibility across threads for the custom success rate gauge.
    private volatile long totalCommands = 0L;
    private volatile long successfulCommands = 0L;

    public RedisMetricsCollector(RedisTemplate<String, Object> redisTemplate, MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Initializes and registers all the Micrometer metrics upon bean construction.
     */
    @PostConstruct
    public void initMetrics() {
        // Counter for successful cache lookups.
        cacheHitCounter = Counter.builder("redis.cache.hits")
                .description("Number of cache hits")
                .register(meterRegistry);

        // Counter for failed cache lookups.
        cacheMissCounter = Counter.builder("redis.cache.misses")
                .description("Number of cache misses")
                .register(meterRegistry);

        // Counter for any errors that occur during Redis operations.
        redisErrorCounter = Counter.builder("redis.errors")
                .description("Number of Redis errors")
                .tag("type", "operation") // Example of a static tag
                .register(meterRegistry);

        // Counter for successful Redis commands.
        redisCommandSuccessCounter = Counter.builder("redis.command.success")
                .description("Number of successful Redis commands")
                .register(meterRegistry);

        // A gauge to report the current number of active connections.
        // Note: This is a placeholder as the default Lettuce factory doesn't expose this easily.
        Gauge.builder("redis.connections.active", this, RedisMetricsCollector::getActiveConnections)
                .description("Estimated number of active Redis connections")
                .register(meterRegistry);

        // A gauge that fetches and reports the current memory usage from the Redis server.
        Gauge.builder("redis.memory.used", this, RedisMetricsCollector::getUsedMemory)
                .description("Current used memory by Redis in bytes")
                .register(meterRegistry);

        // A custom gauge to calculate and report the command success rate.
        Gauge.builder(
                        "redis.command.success.rate",
                        this,
                        c -> c.totalCommands == 0 ? 1.0 : ((double) c.successfulCommands / c.totalCommands))
                .description("The ratio of successful Redis commands to total commands")
                .register(meterRegistry);
    }

    // --- Public methods to be called by other services ---

    public void recordCacheHit(String key) {
        cacheHitCounter.increment();
        log.debug("Cache HIT recorded for key: {}", key);
    }

    public void recordCacheMiss(String key) {
        cacheMissCounter.increment();
        log.debug("Cache MISS recorded for key: {}", key);
    }

    public void recordError(String operation) {
        redisErrorCounter.increment();
        totalCommands++; // Increment total commands even on failure.
        log.warn("Redis error recorded for operation='{}'", operation);
    }

    public void recordSuccess(String operation) {
        redisCommandSuccessCounter.increment();
        successfulCommands++;
        totalCommands++;
        log.debug("Redis success recorded for operation='{}'", operation);
    }

    /**
     * Starts a timer sample. To be used in a try-with-resources block or paired with recordTimer.
     * @return A Timer.Sample instance that has started timing.
     */
    public Timer.Sample startTimer() {
        return Timer.start(meterRegistry);
    }

    /**
     * Stops the timer sample and records the duration.
     * @param sample The sample from startTimer().
     * @param operation The name of the operation being timed (e.g., "GET", "SET"). Used as a tag.
     */
    public void recordTimer(Timer.Sample sample, String operation) {
        sample.stop(Timer.builder("redis.operation.duration")
                .description("Measures the latency of Redis operations")
                .tag("operation", operation) // Dynamic tag to differentiate operations
                .publishPercentiles(0.5, 0.95, 0.99) // Publish 50th, 95th, and 99th percentiles
                .register(meterRegistry));
    }

    // --- Methods used by Gauges to fetch live data ---

    /**
     * Placeholder method to get active connections. For production, this would require a custom
     * connection factory or using the dedicated Micrometer Lettuce/Jedis instrumentation.
     * @return -1.0 as a placeholder indicating data is not available.
     */
    public Double getActiveConnections() {
        return -1.0; // Placeholder
    }

    /**
     * Fetches the current `used_memory` value from the Redis server's INFO command.
     * @return The used memory in bytes, or -1.0 if the value cannot be fetched.
     */
    public Double getUsedMemory() {
        try {
            // The INFO command can be slow; use with caution or on a background thread.
            Properties info =
                    redisTemplate.getConnectionFactory().getConnection().info("memory");
            if (info != null && info.getProperty("used_memory") != null) {
                return Double.parseDouble(info.getProperty("used_memory"));
            }
        } catch (Exception e) {
            log.warn("Failed to get Redis memory usage for metrics", e);
        }
        return -1.0; // Return a sentinel value on failure.
    }
}
