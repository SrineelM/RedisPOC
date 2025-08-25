package com.redis.poc.monitoring;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.Properties;

@Component
@Slf4j
public class RedisMetricsCollector {

    private final RedisTemplate<String, Object> redisTemplate;
    private final MeterRegistry meterRegistry;
    
    private Counter cacheHitCounter;
    private Counter cacheMissCounter;
    private Counter redisErrorCounter;
    private Counter redisCommandSuccessCounter;
    private volatile long totalCommands = 0L;
    private volatile long successfulCommands = 0L;
    // Timer removed (was unused). For prod: consider distribution summary for op latency percentiles.

    public RedisMetricsCollector(RedisTemplate<String, Object> redisTemplate,
                                 MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    public void initMetrics() {
        cacheHitCounter = Counter.builder("redis.cache.hits")
                .description("Number of cache hits")
                .register(meterRegistry);
                
        cacheMissCounter = Counter.builder("redis.cache.misses")
                .description("Number of cache misses")
                .register(meterRegistry);
                
        redisErrorCounter = Counter.builder("redis.errors")
                .description("Number of Redis errors")
                .tag("type", "operation")
                .register(meterRegistry);

    redisCommandSuccessCounter = Counter.builder("redis.command.success")
        .description("Number of successful Redis commands")
        .register(meterRegistry);
                
    Timer.builder("redis.operation.duration")
        .description("Redis operation duration")
        .publishPercentiles(0.5, 0.95, 0.99)
        .register(meterRegistry);

        // Register custom gauges for Redis info
        meterRegistry.gauge("redis.connections.active", this, 
            RedisMetricsCollector::getActiveConnections);
            
        meterRegistry.gauge("redis.memory.used", this, 
            RedisMetricsCollector::getUsedMemory);

    // Success rate gauge (ratio) — returns -1 when no commands yet
    meterRegistry.gauge("redis.command.success.rate", this,
        c -> c.totalCommands == 0 ? -1.0 : ((double) c.successfulCommands / (double) c.totalCommands));
    }

    public void recordCacheHit(String key) {
        cacheHitCounter.increment();
        log.debug("Cache HIT recorded for key: {}", key);
    }

    public void recordCacheMiss(String key) {
        cacheMissCounter.increment();
        log.debug("Cache MISS recorded for key: {}", key);
    }

    public void recordError(String operation, Exception error) {
        redisErrorCounter.increment();
        totalCommands++;
        log.error("Redis error in operation='{}' msg={}", operation, error.getMessage());
    }

    public void recordSuccess(String operation) {
        redisCommandSuccessCounter.increment();
        successfulCommands++;
        totalCommands++;
        log.debug("Redis success operation='{}'", operation);
    }

    public Timer.Sample startTimer() {
        return Timer.start(meterRegistry);
    }

    public void recordTimer(Timer.Sample sample, String operation) {
        sample.stop(Timer.builder("redis.operation.duration")
                .tag("operation", operation)
                .register(meterRegistry));
    }

    // Health check methods
    public Double getActiveConnections() {
        // LettuceConnectionFactory doesn't expose pool stats directly without custom wrapper
        return -1.0; // For prod: expose pool via a custom factory or use Micrometer Lettuce metrics
    }

    public Double getUsedMemory() {
        try {
            try (var conn = redisTemplate.getConnectionFactory().getConnection()) { // NOSONAR
                @SuppressWarnings("deprecation")
                Properties info = conn.info("memory");
                String usedMemoryStr = info.getProperty("used_memory");
                return usedMemoryStr != null ? Double.parseDouble(usedMemoryStr) : -1.0;
            }
        } catch (Exception e) {
            log.warn("Failed to get Redis memory usage", e);
            return -1.0;
        }
    }
    // For prod: consider adding an AOP aspect in a separate file to auto-time RedisTemplate operations
}
