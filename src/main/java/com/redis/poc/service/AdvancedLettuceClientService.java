package com.redis.poc.service;

import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.github.resilience4j.timelimiter.annotation.TimeLimiter;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.micrometer.core.annotation.Timed;
import io.micrometer.tracing.annotation.NewSpan;
import io.micrometer.tracing.annotation.SpanTag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

/**
 * A service layer that acts as a robust, observable, and resilient client for Redis.
 *
 * <p><b>Architectural Role:</b> This class encapsulates all direct interactions with Redis,
 * abstracting away the complexities of the underlying client and adding critical production-ready features.
 * Other parts of the application should use this service instead of interacting with RedisTemplate or
 * a Redis connection directly.
 *
 * <p><b>Key Features Implemented:</b></p>
 * <ul>
 *   <li><b>Resilience Patterns (Resilience4j):</b>
 *     <ul>
 *       <li>{@link CircuitBreaker}: Prevents cascading failures when Redis is slow or down.</li>
 *       <li>{@link Retry}: Automatically retries failed operations, handling transient network issues.</li>
 *       <li>{@link Bulkhead}: Limits concurrent calls to Redis, preventing resource exhaustion.</li>
 *       <li>{@link TimeLimiter}: Enforces timeouts on asynchronous operations.</li>
 *     </ul>
 *   </li>
 *   <li><b>Observability (Micrometer):</b>
 *     <ul>
 *       <li>{@link Timed}: Measures the latency of Redis operations.</li>
 *       <li>{@link NewSpan}: Creates new spans for distributed tracing, providing visibility into request flows.</li>
 *     </ul>
 *   </li>
 *   <li><b>Best Practices:</b>
 *     <ul>
 *       <li>Uses {@link RedisTemplate} for most operations, which leverages a connection pool for high-concurrency, stateless commands (e.g., GET, SET).</li>
 *       <li>Demonstrates {@link StatefulRedisConnection} for asynchronous commands where direct access to the Lettuce API is desired.</li>
 *     </ul>
 *   </li>
 * </ul>
 */
@Service
@Slf4j
public class AdvancedLettuceClientService {

    /**
     * A constant name for the Resilience4j backend configuration.
     * This allows all resilience annotations in this class to share the same configuration
     * defined in `application.yml`, promoting consistency.
     */
    private static final String RESILIENCE_BACKEND = "redis-resilience";

    private final RedisTemplate<String, String> redisTemplate;
    private final StatefulRedisConnection<String, String> statefulConnection;

    /**
     * Constructs the service with necessary Redis clients.
     *
     * @param redisTemplate The primary, pooled client for stateless Redis operations.
     * @param statefulConnection A dedicated, single connection for stateful or async-specific scenarios.
     */
    public AdvancedLettuceClientService(RedisTemplate<String, String> redisTemplate, StatefulRedisConnection<String, String> statefulConnection) {
        this.redisTemplate = redisTemplate;
        this.statefulConnection = statefulConnection;
    }

    /**
     * Retrieves a value from Redis, protected by a Circuit Breaker and Retry mechanism.
     * This is the most common and robust pattern for reading data that might not be essential.
     *
     * <p><b>Pattern:</b> If Redis becomes slow or unresponsive, the {@link Retry} will attempt the operation
     * a few times. If failures continue, the {@link CircuitBreaker} will "open," causing subsequent calls
     * to fail fast and execute the fallback method immediately. This protects the application from waiting
     * on a failing dependency.
     *
     * @param key The key to retrieve.
     * @return The value from Redis, or a default fallback value if the circuit is open or an unrecoverable error occurs.
     */
    @Retry(name = RESILIENCE_BACKEND)
    @CircuitBreaker(name = RESILIENCE_BACKEND, fallbackMethod = "fallbackForGetValue")
    @Timed(value = "redis.operations.get", description = "Time taken for Redis GET operations")
    @NewSpan("redis-get-with-circuit-breaker")
    public String getValueWithCircuitBreaker(@SpanTag("key") String key) {
        log.debug("Executing GET command with RedisTemplate for key: {}", key);
        // Use RedisTemplate, which gets a connection from the pool.
        String value = redisTemplate.opsForValue().get(key);

        if (value != null) {
            log.debug("Cache HIT for key: {}", key);
        } else {
            log.debug("Cache MISS for key: {}", key);
        }

        return value;
    }

    /**
     * A fallback method for {@link #getValueWithCircuitBreaker(String)}.
     * This method is invoked by Resilience4j when the circuit breaker is open or a non-retriable exception occurs.
     * It ensures the application can continue to function gracefully, albeit with potentially degraded data.
     *
     * @param key The original key that was requested.
     * @param t The exception that triggered the fallback.
     * @return A default, safe value.
     */
    private String fallbackForGetValue(String key, Throwable t) {
        log.warn("Circuit Breaker fallback activated for key '{}' due to: {}", key, t.getMessage());
        // In a real application, this could return a default object, an empty Optional, or null.
        return "Fallback: Default Value";
    }

    /**
     * Retrieves a value from Redis, protected by a Bulkhead.
     *
     * <p><b>Pattern:</b> The {@link Bulkhead} limits the number of concurrent executions of this method.
     * If the limit is reached, subsequent calls are rejected immediately and execute the fallback. This is a
     * crucial pattern to prevent a high-traffic operation from overwhelming Redis or exhausting the
     * application's connection or thread pools.
     *
     * @param key The key to retrieve.
     * @return The value from Redis, or a fallback value if the bulkhead is full.
     */
    @Bulkhead(name = RESILIENCE_BACKEND, fallbackMethod = "fallbackForBulkhead")
    @Timed(value = "redis.operations.get.bulkhead")
    @NewSpan("redis-get-with-bulkhead")
    public String getValueWithBulkhead(@SpanTag("key") String key) {
        log.debug("Executing GET command with Bulkhead for key: {}", key);
        return redisTemplate.opsForValue().get(key);
    }

    /**
     * A fallback method for {@link #getValueWithBulkhead(String)}.
     * Invoked when the bulkhead rejects a call due to reaching its concurrency limit.
     *
     * @param key The original key that was requested.
     * @param t The BulkheadFullException that triggered the fallback.
     * @return A specific message indicating the reason for the fallback.
     */
    private String fallbackForBulkhead(String key, Throwable t) {
        log.warn("Bulkhead fallback activated for key '{}': {}", key, t.getMessage());
        return "Fallback: Service is busy, please try again later.";
    }

    /**
     * Retrieves a value asynchronously, protected by a TimeLimiter and Circuit Breaker.
     * This demonstrates using the raw Lettuce async API for maximum performance and non-blocking I/O.
     *
     * <p><b>Pattern:</b> The method returns a {@link CompletableFuture}, allowing the calling thread to proceed
     * without waiting. The {@link TimeLimiter} enforces a strict timeout on the future's completion.
     * This is often combined with a {@link CircuitBreaker} to provide a complete non-blocking resilience strategy.
     *
     * @param key The key to retrieve asynchronously.
     * @return A CompletableFuture that will eventually contain the value or an exception.
     */
    @TimeLimiter(name = RESILIENCE_BACKEND)
    @CircuitBreaker(name = RESILIENCE_BACKEND) // Often combined for full resilience
    @Retry(name = RESILIENCE_BACKEND)
    @Timed(value = "redis.operations.get.async")
    @NewSpan("redis-get-asynchronously")
    public CompletableFuture<String> getValueAsynchronously(@SpanTag("key") String key) {
        log.debug("Executing async GET command with TimeLimiter for key: {}", key);
        // Here, we use the stateful connection's async commands to get a CompletableFuture.
        RedisAsyncCommands<String, String> asyncCommands = statefulConnection.async();
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
