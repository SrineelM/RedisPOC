package com.redis.poc.service;

import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.github.resilience4j.timelimiter.annotation.TimeLimiter;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.api.sync.RedisCommands;
import io.micrometer.core.annotation.Timed;
import io.micrometer.tracing.annotation.NewSpan;
import io.micrometer.tracing.annotation.SpanTag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

@Service
@Slf4j
public class AdvancedLettuceClientService {

    private final StatefulRedisConnection<String, String> connection;
    private static final String RESILIENCE_BACKEND = "redis-resilience";

    public AdvancedLettuceClientService(StatefulRedisConnection<String, String> connection) {
        this.connection = connection;
    }

    /**
     * Demonstrates a Circuit Breaker with retry.
     * If Redis becomes slow or unresponsive, the circuit breaker will "open" after a configured number of failures,
     * preventing further calls and allowing the system to recover. This is critical for preventing cascading failures.
     * The fallback method is called when the circuit is open.
     */
    @Retry(name = RESILIENCE_BACKEND)
    @CircuitBreaker(name = RESILIENCE_BACKEND, fallbackMethod = "fallbackForCircuitBreaker")
    @Timed(value = "redis.operations", description = "Time taken for Redis operations")
    @NewSpan("redis-get-circuit-breaker")
    public String getValueWithCircuitBreaker(@SpanTag("key") String key) {
        log.debug("Executing command with Circuit Breaker for key: {}", key);
        RedisCommands<String, String> syncCommands = connection.sync();
        String value = syncCommands.get(key);
        
        if (value != null) {
            log.debug("Cache HIT for key: {}", key);
        } else {
            log.debug("Cache MISS for key: {}", key);
        }
        
        return value;
    }

    private String fallbackForCircuitBreaker(String key, Throwable t) {
        log.warn("Circuit Breaker fallback activated for key '{}' due to: {}", key, t.getMessage());
        return "Fallback: Default Value";
    }

    /**
     * Demonstrates a Bulkhead with retry.
     * This limits the number of concurrent executions of this method. If the limit is reached,
     * subsequent calls will be rejected immediately. This prevents a single, high-traffic operation
     * from exhausting the connection pool or thread pool.
     */
    @Retry(name = RESILIENCE_BACKEND)
    @Bulkhead(name = RESILIENCE_BACKEND, fallbackMethod = "fallbackForBulkhead")
    @Timed(value = "redis.bulkhead.operations")
    @NewSpan("redis-get-bulkhead")
    public String getValueWithBulkhead(@SpanTag("key") String key) {
        log.debug("Executing command with Bulkhead for key: {}", key);
        RedisCommands<String, String> syncCommands = connection.sync();
        // Simulate latency (local only). For production testing, use chaos tooling / latency injection at network layer.
        try {
            java.util.concurrent.CompletableFuture<Void> delay = new java.util.concurrent.CompletableFuture<>();
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
                    .schedule(() -> delay.complete(null), 1, java.util.concurrent.TimeUnit.SECONDS);
            delay.join();
        } catch (Exception e) {
            log.debug("Latency simulation failed", e);
        }
        return syncCommands.get(key);
    }

    private String fallbackForBulkhead(String key, Throwable t) {
        log.warn("Bulkhead fallback activated for key '{}': {}", key, t.getMessage());
        return "Fallback: Bulkhead limit reached";
    }

    /**
     * Demonstrates TimeLimiter and Backpressure using Lettuce's async API with retry.
     * The method returns a CompletableFuture, making it non-blocking. This naturally handles backpressure
     * as the application isn't waiting on threads. The @TimeLimiter annotation enforces a timeout on the async operation.
     */
    @Retry(name = RESILIENCE_BACKEND)
    @TimeLimiter(name = RESILIENCE_BACKEND)
    @CircuitBreaker(name = RESILIENCE_BACKEND) // Often combined with a circuit breaker
    @Timed(value = "redis.async.operations")
    @NewSpan("redis-get-async")
    public CompletableFuture<String> getValueAsynchronously(@SpanTag("key") String key) {
        log.debug("Executing command asynchronously with TimeLimiter for key: {}", key);
        RedisAsyncCommands<String, String> asyncCommands = connection.async();
        return asyncCommands.get(key).toCompletableFuture()
                .whenComplete((value, throwable) -> {
                    if (throwable != null) {
                        log.error("Async operation failed for key: {}", key, throwable);
                    } else if (value != null) {
                        log.debug("Async cache HIT for key: {}", key);
                    } else {
                        log.debug("Async cache MISS for key: {}", key);
                    }
                });
    }
}
