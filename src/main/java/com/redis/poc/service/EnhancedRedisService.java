package com.redis.poc.service;

import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.github.resilience4j.timelimiter.annotation.TimeLimiter;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.api.sync.RedisCommands;
import io.micrometer.core.annotation.Timed;
import io.micrometer.tracing.annotation.NewSpan;
import io.micrometer.tracing.annotation.SpanTag;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * An enhanced Redis service that provides resilient, observable, and performant
 * access to a Redis server. This class integrates Resilience4j for fault tolerance,
 * Micrometer for observability (metrics and tracing), and uses Lettuce's asynchronous
 * capabilities for non-blocking operations.
 */
@Service
@Slf4j
public class EnhancedRedisService {

    private final StatefulRedisConnection<String, String> connection;
    private static final String BACKEND = "redis";

    private final Executor redisAsyncExecutor;

    public EnhancedRedisService(StatefulRedisConnection<String, String> connection, Executor redisAsyncExecutor) {
        this.connection = connection;
        this.redisAsyncExecutor = redisAsyncExecutor;
    }

    /**
     * Retrieves a value from Redis, applying a full set of resilience patterns.
     * This method is decorated with annotations for retries, circuit breaking,
     * bulkheading, and time limiting to ensure robust operation under load and
     * during Redis instability. It also includes observability through metrics and tracing.
     * The operation is executed asynchronously on a dedicated thread pool.
     *
     * @param key The key for which to retrieve the value.
     * @return A CompletableFuture containing the value, or null if not found.
     *         The future will complete exceptionally in case of unrecoverable errors.
     */
    @Retry(name = BACKEND)
    @CircuitBreaker(name = BACKEND, fallbackMethod = "fallbackGetValue")
    @Bulkhead(name = BACKEND)
    @TimeLimiter(name = BACKEND)
    @Timed(value = "redis.operations", description = "Time taken for Redis operations")
    @NewSpan("redis-get")
    public CompletableFuture<String> getValueWithFullResilience(@SpanTag("key") String key) {
        return CompletableFuture.supplyAsync(
                () -> {
                    log.debug("Executing Redis GET for key: {}", key);

                    try {
                        // Use synchronous commands here as the entire block is already async.
                        RedisCommands<String, String> commands = connection.sync();
                        String value = commands.get(key);

                        // Add custom metrics/logging for cache hits and misses.
                        if (value != null) {
                            log.info("Cache HIT for key: {}", key);
                        } else {
                            log.info("Cache MISS for key: {}", key);
                        }

                        return value;
                    } catch (Exception e) {
                        log.error("Redis operation failed for key: {}", key, e);
                        // Wrap the exception for better error handling upstream.
                        throw new RedisOperationException("Failed to get value for key: " + key, e);
                    }
                },
                redisAsyncExecutor);
    }

    /**
     * Retrieves multiple values from Redis in a single batch operation using pipelining.
     * Pipelining significantly improves performance by sending multiple commands to Redis
     * at once, reducing network latency. This method is also protected by retry and
     * circuit breaker patterns.
     *
     * @param keys A list of keys to retrieve.
     * @return A CompletableFuture containing a list of values corresponding to the keys.
     *         If a key is not found, the corresponding value in the list will be null.
     */
    @Retry(name = BACKEND)
    @CircuitBreaker(name = BACKEND)
    @Timed(value = "redis.batch.operations")
    @NewSpan("redis-batch-get")
    public CompletableFuture<List<String>> getBatchValues(@SpanTag("keys") List<String> keys) {
        return CompletableFuture.supplyAsync(
                () -> {
                    if (keys.isEmpty()) {
                        return Collections.emptyList();
                    }

                    // Use asynchronous commands for pipelining.
                    RedisAsyncCommands<String, String> async = connection.async();
                    // Disable auto-flushing to manually control when commands are sent.
                    async.setAutoFlushCommands(false); // Enable pipelining

                    // Create a list of futures, one for each GET command.
                    List<RedisFuture<String>> futures =
                            keys.stream().map(async::get).collect(Collectors.toList());

                    // Manually flush the commands, sending them all to Redis in one go.
                    async.flushCommands(); // Execute all commands

                    // Wait for all futures to complete and collect the results.
                    return futures.stream()
                            .map(future -> {
                                try {
                                    // Wait for each future to complete with a timeout.
                                    return future.get(5, TimeUnit.SECONDS);
                                } catch (Exception e) {
                                    log.error("Failed to get value in batch operation", e);
                                    return null; // Return null for failed individual retrievals.
                                }
                            })
                            .collect(Collectors.toList());
                },
                redisAsyncExecutor);
    }

    /**
     * Sets a key-value pair in Redis with validation and an optional Time-To-Live (TTL).
     * This method includes input validation and is protected by resilience patterns.
     *
     * @param key   The key to set. Cannot be null or empty.
     * @param value The value to set. Cannot be null.
     * @param ttl   The optional time-to-live for the key. If null, the key will not expire.
     * @return A CompletableFuture containing true if the set operation was successful, false otherwise.
     */
    @Retry(name = BACKEND)
    @CircuitBreaker(name = BACKEND)
    @Timed(value = "redis.set.operations")
    @NewSpan("redis-set")
    public CompletableFuture<Boolean> setValueWithValidation(
            @SpanTag("key") String key, @SpanTag("value") String value, Duration ttl) {

        return CompletableFuture.supplyAsync(
                () -> {
                    // --- Input Validation ---
                    if (key == null || key.trim().isEmpty()) {
                        throw new IllegalArgumentException("Key cannot be null or empty");
                    }
                    if (value == null) {
                        throw new IllegalArgumentException("Value cannot be null");
                    }
                    if (ttl != null && ttl.isNegative()) {
                        throw new IllegalArgumentException("TTL cannot be negative");
                    }

                    try {
                        RedisCommands<String, String> commands = connection.sync();
                        String result;

                        // Use SETEX if a TTL is provided to set the value and expiration atomically.
                        if (ttl != null) {
                            result = commands.setex(key, ttl.getSeconds(), value);
                        } else {
                            result = commands.set(key, value);
                        }

                        // Redis SET commands return "OK" on success.
                        boolean success = "OK".equals(result);
                        log.debug("Set operation for key '{}' completed with result: {}", key, success);
                        return success;

                    } catch (Exception e) {
                        log.error("Redis SET operation failed for key: {}", key, e);
                        throw new RedisOperationException("Failed to set value for key: " + key, e);
                    }
                },
                redisAsyncExecutor);
    }

    /**
     * A fallback method for the circuit breaker on getValueWithFullResilience.
     * This method is invoked when the circuit is open. It logs a warning and
     * returns a default value (null) to prevent cascading failures.
     *
     * @param key The original key passed to the method.
     * @param ex The exception that caused the fallback.
     * @return A completed CompletableFuture with a null value.
     */
    private CompletableFuture<String> fallbackGetValue(String key, Exception ex) {
        log.warn("Fallback activated for key '{}' due to: {}", key, ex.getMessage());
        // In a real application, this could return a value from a secondary cache,
        // a database, or a sensible default value.
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Custom exception to provide more specific error handling for Redis operations.
     * This helps in distinguishing Redis-related failures from other runtime exceptions.
     */
    public static class RedisOperationException extends RuntimeException {
        public RedisOperationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
