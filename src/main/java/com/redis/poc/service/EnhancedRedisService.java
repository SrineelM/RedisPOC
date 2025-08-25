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

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import io.lettuce.core.RedisFuture;

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
     * Complete resilience pattern with retry, circuit breaker, bulkhead, and observability
     */
    @Retry(name = BACKEND)
    @CircuitBreaker(name = BACKEND, fallbackMethod = "fallbackGetValue")
    @Bulkhead(name = BACKEND)
    @TimeLimiter(name = BACKEND)
    @Timed(value = "redis.operations", description = "Time taken for Redis operations")
    @NewSpan("redis-get")
    public CompletableFuture<String> getValueWithFullResilience(@SpanTag("key") String key) {
        return CompletableFuture.supplyAsync(() -> {
            log.debug("Executing Redis GET for key: {}", key);
            
            try {
                RedisCommands<String, String> commands = connection.sync();
                String value = commands.get(key);
                
                // Add custom metrics
                if (value != null) {
                    log.info("Cache HIT for key: {}", key);
                } else {
                    log.info("Cache MISS for key: {}", key);
                }
                
                return value;
            } catch (Exception e) {
                log.error("Redis operation failed for key: {}", key, e);
                throw new RedisOperationException("Failed to get value for key: " + key, e);
            }
    }, redisAsyncExecutor);
    }

    /**
     * Batch operations with pipeline for better performance
     */
    @Retry(name = BACKEND)
    @CircuitBreaker(name = BACKEND)
    @Timed(value = "redis.batch.operations")
    @NewSpan("redis-batch-get")
    public CompletableFuture<List<String>> getBatchValues(@SpanTag("keys") List<String> keys) {
        return CompletableFuture.supplyAsync(() -> {
            if (keys.isEmpty()) {
                return Collections.emptyList();
            }

            RedisAsyncCommands<String, String> async = connection.async();
            async.setAutoFlushCommands(false); // Enable pipelining

            List<RedisFuture<String>> futures = keys.stream()
                    .map(async::get)
                    .collect(Collectors.toList());

            async.flushCommands(); // Execute all commands

            return futures.stream()
                    .map(future -> {
                        try {
                            return future.get(5, TimeUnit.SECONDS);
                        } catch (Exception e) {
                            log.error("Failed to get value in batch operation", e);
                            return null;
                        }
                    })
                    .collect(Collectors.toList());
    }, redisAsyncExecutor);
    }

    /**
     * Set with proper error handling and validation
     */
    @Retry(name = BACKEND)
    @CircuitBreaker(name = BACKEND)
    @Timed(value = "redis.set.operations")
    @NewSpan("redis-set")
    public CompletableFuture<Boolean> setValueWithValidation(
            @SpanTag("key") String key, 
            @SpanTag("value") String value, 
            Duration ttl) {
        
    return CompletableFuture.supplyAsync(() -> {
            // Input validation
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
                
                if (ttl != null) {
                    result = commands.setex(key, ttl.getSeconds(), value);
                } else {
                    result = commands.set(key, value);
                }
                
                boolean success = "OK".equals(result);
                log.debug("Set operation for key '{}' completed with result: {}", key, success);
                return success;
                
            } catch (Exception e) {
                log.error("Redis SET operation failed for key: {}", key, e);
                throw new RedisOperationException("Failed to set value for key: " + key, e);
            }
    }, redisAsyncExecutor);
    }

    // Fallback methods with proper logging and monitoring
    private CompletableFuture<String> fallbackGetValue(String key, Exception ex) {
        log.warn("Fallback activated for key '{}' due to: {}", key, ex.getMessage());
        // Could return from secondary cache, database, or default value
        return CompletableFuture.completedFuture(null);
    }

    // Custom exception for better error handling
    public static class RedisOperationException extends RuntimeException {
        public RedisOperationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
