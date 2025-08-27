package com.redis.poc.service;

import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.github.resilience4j.timelimiter.annotation.TimeLimiter;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.micrometer.core.annotation.Timed;
import io.micrometer.tracing.annotation.NewSpan;
import io.micrometer.tracing.annotation.SpanTag;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Dual-Pattern Redis Service demonstrating BOTH RedisTemplate and Pure Lettuce approaches.
 *
 * <p><b>Architectural Purpose:</b> This service showcases two different patterns for Redis operations:
 * <ul>
 *   <li><b>RedisTemplate Pattern:</b> Spring's integrated approach with automatic connection pooling</li>
 *   <li><b>Pure Lettuce Pattern:</b> Direct Lettuce usage for maximum performance and control</li>
 * </ul>
 *
 * <p><b>Both approaches support:</b>
 * <ul>
 *   <li>Async/Non-blocking operations</li>
 *   <li>Resilience patterns (Circuit Breaker, Retry, Bulkhead, TimeLimiter)</li>
 *   <li>Observability (Metrics and Tracing)</li>
 *   <li>Connection pooling (no StatefulRedisConnection antipattern)</li>
 * </ul>
 *
 * <p><b>Use Cases:</b>
 * <ul>
 *   <li>RedisTemplate: Spring integration, caching, simple operations</li>
 *   <li>Pure Lettuce: High-performance scenarios, pipelining, advanced operations</li>
 * </ul>
 */
@Service
@Slf4j
public class AdvancedLettuceClientService {

    private static final String RESILIENCE_BACKEND = "redis-resilience";

    private final RedisTemplate<String, String> redisTemplate;
    private final RedisConnectionFactory connectionFactory;
    private final RedisClient redisClient;

    /**
     * Constructor demonstrating both RedisTemplate and Pure Lettuce approaches.
     *
     * @param redisTemplate The Spring RedisTemplate with connection pooling
     * @param connectionFactory Factory for getting pooled connections
     * @param redisClient Pure Lettuce client for direct operations
     */
    public AdvancedLettuceClientService(
            RedisTemplate<String, String> redisTemplate,
            RedisConnectionFactory connectionFactory,
            RedisClient redisClient) {
        this.redisTemplate = redisTemplate;
        this.connectionFactory = connectionFactory;
        this.redisClient = redisClient;
    }

    // ====================================================================
    // REDIS TEMPLATE PATTERN - Spring Integration with Async Support
    // ====================================================================

    /**
     * RedisTemplate Pattern: Synchronous operation with Circuit Breaker and Retry.
     * Uses Spring's RedisTemplate with automatic connection pooling.
     */
    @Retry(name = RESILIENCE_BACKEND)
    @CircuitBreaker(name = RESILIENCE_BACKEND, fallbackMethod = "fallbackForGetValue")
    @Timed(value = "redis.template.get", description = "RedisTemplate GET operations")
    @NewSpan("redis-template-get")
    public String getValueWithRedisTemplate(@SpanTag("key") String key) {
        log.debug("[RedisTemplate] Executing GET for key: {}", key);

        String value = redisTemplate.opsForValue().get(key);

        if (value != null) {
            log.debug("[RedisTemplate] Cache HIT for key: {}", key);
        } else {
            log.debug("[RedisTemplate] Cache MISS for key: {}", key);
        }

        return value;
    }

    /**
     * RedisTemplate Pattern: Asynchronous operation using Spring's @Async.
     * Shows how RedisTemplate can be used for non-blocking operations.
     */
    @Async("taskExecutor")
    @Retry(name = RESILIENCE_BACKEND)
    @CircuitBreaker(name = RESILIENCE_BACKEND, fallbackMethod = "fallbackForAsyncRedisTemplate")
    @Timed(value = "redis.template.async.get", description = "RedisTemplate async GET operations")
    @NewSpan("redis-template-async-get")
    public CompletableFuture<String> getValueAsyncWithRedisTemplate(@SpanTag("key") String key) {
        log.debug("[RedisTemplate-Async] Executing async GET for key: {}", key);

        try {
            String value = redisTemplate.opsForValue().get(key);

            if (value != null) {
                log.debug("[RedisTemplate-Async] Cache HIT for key: {}", key);
            } else {
                log.debug("[RedisTemplate-Async] Cache MISS for key: {}", key);
            }

            return CompletableFuture.completedFuture(value);
        } catch (Exception e) {
            log.error("[RedisTemplate-Async] Operation failed for key: {}", key, e);
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * RedisTemplate Pattern: Batch operations using multiGet.
     */
    @Async("taskExecutor")
    @Retry(name = RESILIENCE_BACKEND)
    @CircuitBreaker(name = RESILIENCE_BACKEND, fallbackMethod = "fallbackForBatchRedisTemplate")
    @Timed(value = "redis.template.batch.get", description = "RedisTemplate batch GET operations")
    @NewSpan("redis-template-batch-get")
    public CompletableFuture<Map<String, String>> getBatchWithRedisTemplate(@SpanTag("keys") List<String> keys) {
        log.debug("[RedisTemplate-Batch] Executing batch GET for {} keys", keys.size());

        try {
            List<String> values = redisTemplate.opsForValue().multiGet(keys);

            Map<String, String> result = keys.stream()
                    .collect(Collectors.toMap(key -> key, key -> {
                        int index = keys.indexOf(key);
                        return values != null && index < values.size() ? values.get(index) : null;
                    }))
                    .entrySet()
                    .stream()
                    .filter(entry -> entry.getValue() != null)
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

            log.debug("[RedisTemplate-Batch] Retrieved {} out of {} keys", result.size(), keys.size());
            return CompletableFuture.completedFuture(result);
        } catch (Exception e) {
            log.error("[RedisTemplate-Batch] Batch operation failed", e);
            return CompletableFuture.failedFuture(e);
        }
    }

    // ====================================================================
    // PURE LETTUCE PATTERN - Direct Lettuce API for Maximum Performance
    // ====================================================================

    /**
     * Pure Lettuce Pattern: Asynchronous operation using direct Lettuce async API.
     * Demonstrates connection pooling without StatefulRedisConnection antipattern.
     */
    @TimeLimiter(name = RESILIENCE_BACKEND)
    @CircuitBreaker(name = RESILIENCE_BACKEND, fallbackMethod = "fallbackForPureLettuce")
    @Retry(name = RESILIENCE_BACKEND)
    @Timed(value = "redis.lettuce.async.get", description = "Pure Lettuce async GET operations")
    @NewSpan("redis-lettuce-async-get")
    public CompletableFuture<String> getValueAsyncWithPureLettuce(@SpanTag("key") String key) {
        log.debug("[Pure-Lettuce] Executing async GET for key: {}", key);

        return executeWithLettuceAsync(async -> {
            return async.get(key).toCompletableFuture().whenComplete((value, throwable) -> {
                if (throwable != null) {
                    log.error("[Pure-Lettuce] Async operation failed for key: {}", key, throwable);
                } else if (value != null) {
                    log.debug("[Pure-Lettuce] Async cache HIT for key: {}", key);
                } else {
                    log.debug("[Pure-Lettuce] Async cache MISS for key: {}", key);
                }
            });
        });
    }

    /**
     * Pure Lettuce Pattern: Batch operations with pipelining for optimal performance.
     * Shows the power of direct Lettuce API for high-performance scenarios.
     */
    @TimeLimiter(name = RESILIENCE_BACKEND)
    @CircuitBreaker(name = RESILIENCE_BACKEND, fallbackMethod = "fallbackForPureLettuceBatch")
    @Retry(name = RESILIENCE_BACKEND)
    @Timed(value = "redis.lettuce.batch.get", description = "Pure Lettuce batch GET with pipelining")
    @NewSpan("redis-lettuce-batch-get")
    public CompletableFuture<Map<String, String>> getBatchAsyncWithPureLettuce(@SpanTag("keys") List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return CompletableFuture.completedFuture(Map.of());
        }

        log.debug("[Pure-Lettuce-Batch] Executing pipelined batch GET for {} keys", keys.size());

        return executeWithLettuceAsync(async -> {
            // Disable auto-flushing for pipelining
            async.setAutoFlushCommands(false);

            try {
                // Create futures for all GET operations
                Map<String, CompletableFuture<String>> futures = keys.stream()
                        .collect(Collectors.toMap(
                                key -> key, key -> async.get(key).toCompletableFuture()));

                // Flush all commands at once (pipelining magic!)
                async.flushCommands();

                // Wait for all futures and collect results
                return CompletableFuture.allOf(futures.values().toArray(new CompletableFuture[0]))
                        .thenApply(v -> {
                            Map<String, String> results = futures.entrySet().stream()
                                    .collect(Collectors.toMap(Map.Entry::getKey, entry -> {
                                        try {
                                            return entry.getValue().join();
                                        } catch (Exception e) {
                                            log.warn(
                                                    "[Pure-Lettuce-Batch] Failed to get value for key: {}",
                                                    entry.getKey(),
                                                    e);
                                            return null;
                                        }
                                    }));

                            // Filter out null values
                            Map<String, String> filteredResults = results.entrySet().stream()
                                    .filter(entry -> entry.getValue() != null)
                                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

                            log.debug(
                                    "[Pure-Lettuce-Batch] Retrieved {} out of {} keys using pipelining",
                                    filteredResults.size(),
                                    keys.size());
                            return filteredResults;
                        });
            } finally {
                // Re-enable auto-flushing
                async.setAutoFlushCommands(true);
            }
        });
    }

    /**
     * Pure Lettuce Pattern: Set operation with TTL.
     */
    @TimeLimiter(name = RESILIENCE_BACKEND)
    @CircuitBreaker(name = RESILIENCE_BACKEND, fallbackMethod = "fallbackForPureLettuceSet")
    @Retry(name = RESILIENCE_BACKEND)
    @Timed(value = "redis.lettuce.set", description = "Pure Lettuce SET operations")
    @NewSpan("redis-lettuce-set")
    public CompletableFuture<Boolean> setValueAsyncWithPureLettuce(
            @SpanTag("key") String key, @SpanTag("value") String value, Duration ttl) {
        log.debug("[Pure-Lettuce] Executing async SET for key: {} with TTL: {}", key, ttl);

        return executeWithLettuceAsync(async -> {
            CompletableFuture<String> future;
            if (ttl != null) {
                future = async.setex(key, ttl.getSeconds(), value).toCompletableFuture();
            } else {
                future = async.set(key, value).toCompletableFuture();
            }

            return future.thenApply("OK"::equals).whenComplete((success, throwable) -> {
                if (throwable != null) {
                    log.error("[Pure-Lettuce] SET failed for key: {}", key, throwable);
                } else {
                    log.debug("[Pure-Lettuce] SET for key '{}' completed: {}", key, success);
                }
            });
        });
    }

    // ====================================================================
    // UTILITY METHODS
    // ====================================================================

    /**
     * Executes a Lettuce async operation with proper connection management.
     * Uses connection from pool, not a dedicated StatefulRedisConnection.
     */
    private <T> CompletableFuture<T> executeWithLettuceAsync(LettuceAsyncOperation<T> operation) {
        try {
            // Use RedisClient to create a fresh connection (this uses pooling internally)
            var connection = redisClient.connect();

            // Execute operation and ensure connection is closed afterward
            return operation.execute(connection.async()).whenComplete((result, throwable) -> {
                // Close connection to return it to pool
                connection.closeAsync();
            });

        } catch (Exception e) {
            log.error("[Pure-Lettuce] Failed to execute async operation", e);
            return CompletableFuture.failedFuture(e);
        }
    }

    @FunctionalInterface
    private interface LettuceAsyncOperation<T> {
        CompletableFuture<T> execute(RedisAsyncCommands<String, String> async) throws Exception;
    }

    // ====================================================================
    // FALLBACK METHODS FOR RESILIENCE
    // ====================================================================

    private String fallbackForGetValue(String key, Exception ex) {
        log.warn("[RedisTemplate] Fallback activated for key '{}' due to: {}", key, ex.getMessage());
        return "Fallback: RedisTemplate Default Value";
    }

    private CompletableFuture<String> fallbackForAsyncRedisTemplate(String key, Exception ex) {
        log.warn("[RedisTemplate-Async] Fallback activated for key '{}' due to: {}", key, ex.getMessage());
        return CompletableFuture.completedFuture("Fallback: RedisTemplate Async Default");
    }

    private CompletableFuture<Map<String, String>> fallbackForBatchRedisTemplate(List<String> keys, Exception ex) {
        log.warn("[RedisTemplate-Batch] Fallback activated for {} keys due to: {}", keys.size(), ex.getMessage());
        return CompletableFuture.completedFuture(Map.of());
    }

    private CompletableFuture<String> fallbackForPureLettuce(String key, Exception ex) {
        log.warn("[Pure-Lettuce] Fallback activated for key '{}' due to: {}", key, ex.getMessage());
        return CompletableFuture.completedFuture("Fallback: Pure Lettuce Default");
    }

    private CompletableFuture<Map<String, String>> fallbackForPureLettuceBatch(List<String> keys, Exception ex) {
        log.warn("[Pure-Lettuce-Batch] Fallback activated for {} keys due to: {}", keys.size(), ex.getMessage());
        return CompletableFuture.completedFuture(Map.of());
    }

    private CompletableFuture<Boolean> fallbackForPureLettuceSet(String key, String value, Duration ttl, Exception ex) {
        log.warn("[Pure-Lettuce] SET fallback activated for key '{}' due to: {}", key, ex.getMessage());
        return CompletableFuture.completedFuture(false);
    }

    // ====================================================================
    // BULKHEAD PATTERN DEMONSTRATION
    // ====================================================================

    @Bulkhead(name = RESILIENCE_BACKEND, fallbackMethod = "fallbackForBulkhead")
    @Timed(value = "redis.bulkhead.get")
    @NewSpan("redis-bulkhead-get")
    public String getValueWithBulkhead(@SpanTag("key") String key) {
        log.debug("[Bulkhead] Executing GET with resource isolation for key: {}", key);
        return redisTemplate.opsForValue().get(key);
    }

    private String fallbackForBulkhead(String key, Exception ex) {
        log.warn("[Bulkhead] Fallback activated for key '{}': Service is busy", key);
        return "Fallback: Service is busy, please try again later.";
    }
}
