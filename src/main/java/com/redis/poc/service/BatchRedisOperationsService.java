package com.redis.poc.service;

import com.redis.poc.audit.AuditLogger;
import io.github.resilience4j.retry.annotation.Retry;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.micrometer.core.annotation.Timed;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Service for executing Redis commands in batches to optimize performance.
 * It uses pipelining for mass get/set operations and transactions for atomic multi-key updates.
 * All operations are executed asynchronously on a dedicated thread pool to be non-blocking.
 */
@Service
@Slf4j
public class BatchRedisOperationsService {

    private final StatefulRedisConnection<String, String> connection;
    private final RedisTemplate<String, Object> redisTemplate;
    private final AuditLogger auditLogger;

    /** A dedicated executor for running asynchronous Redis operations off the main request threads. */
    private final Executor redisAsyncExecutor;

    /**
     * Constructs the service with its dependencies.
     *
     * @param connection A stateful Lettuce connection, ideal for direct async/pipelined commands.
     * @param redisTemplate A Spring RedisTemplate, ideal for transactions and higher-level abstractions.
     * @param auditLogger A custom service for logging audit and performance events.
     * @param redisAsyncExecutor A dedicated thread pool for async operations.
     */
    public BatchRedisOperationsService(StatefulRedisConnection<String, String> connection,
                                       RedisTemplate<String, Object> redisTemplate,
                                       AuditLogger auditLogger,
                                       Executor redisAsyncExecutor) {
        this.connection = connection;
        this.redisTemplate = redisTemplate;
        this.auditLogger = auditLogger;
        this.redisAsyncExecutor = redisAsyncExecutor;
    }

    /**
     * Retrieves multiple values from Redis using pipelining.
     * Pipelining sends all GET commands in one network request, reducing round-trip latency.
     *
     * @param keys List of keys to retrieve.
     * @param username User performing the operation, for auditing.
     * @return A CompletableFuture containing a map of keys to their found values.
     */
    @Retry(name = "redis-resilience")
    @Timed(value = "redis.batch.get", description = "Time taken for batch GET operations")
    public CompletableFuture<Map<String, String>> getBatchValues(List<String> keys, String username) {
        if (keys == null || keys.isEmpty()) {
            return CompletableFuture.completedFuture(Map.of());
        }

        // Run the entire operation asynchronously on a dedicated executor.
        return CompletableFuture.supplyAsync(() -> {
            long startTime = System.currentTimeMillis();

            try {
                RedisAsyncCommands<String, String> async = connection.async();

                // Create futures for all GET operations
                Map<String, RedisFuture<String>> futures = keys.stream()
                        .collect(Collectors.toMap(
                                key -> key,
                                async::get
                        ));

                // Now, wait for all futures to complete and collect the results.
                Map<String, String> results = futures.entrySet().stream()
                        .collect(Collectors.toMap(
                                Map.Entry::getKey,
                                entry -> {
                                    try {
                                        return entry.getValue().get(5, TimeUnit.SECONDS);
                                    } catch (Exception e) {
                                        log.error("Failed to get value for key: {}", entry.getKey(), e);
                                        return null;
                                    }
                                }
                        ));

                long duration = System.currentTimeMillis() - startTime;
                auditLogger.logPerformanceEvent("BATCH_GET", duration, true);
                auditLogger.logOperation(username, "BATCH_GET", "keys:" + keys.size(), true,
                        "Retrieved " + results.size() + " values");

                return results;

            } catch (Exception e) {
                long duration = System.currentTimeMillis() - startTime;
                auditLogger.logPerformanceEvent("BATCH_GET", duration, false);
                auditLogger.logOperation(username, "BATCH_GET", "keys:" + keys.size(), false, e.getMessage());
                log.error("Batch GET operation failed", e);
                throw new RuntimeException("Batch GET operation failed", e);
            }
        }, redisAsyncExecutor);
    }

    /**
     * Sets multiple key-value pairs in Redis using pipelining.
     *
     * @param keyValues Map of keys and values to set.
     * @param ttl Optional time-to-live for the keys.
     * @param username User performing the operation, for auditing.
     * @return A CompletableFuture that completes with true if all operations succeeded.
     */
    @Retry(name = "redis-resilience")
    @Timed(value = "redis.batch.set", description = "Time taken for batch SET operations")
    public CompletableFuture<Boolean> setBatchValues(Map<String, String> keyValues,
                                                     Duration ttl, String username) {
        // Return early if there's nothing to do.
        if (keyValues == null || keyValues.isEmpty()) {
            return CompletableFuture.completedFuture(true);
        }

        return CompletableFuture.supplyAsync(() -> {
            long startTime = System.currentTimeMillis();

            try {
                RedisAsyncCommands<String, String> async = connection.async();

                // Fire off all SET commands. Use SETEX if a TTL is provided.
                List<RedisFuture<String>> futures = keyValues.entrySet().stream()
                        .map(entry -> {
                            if (ttl != null) {
                                return async.setex(entry.getKey(), ttl.getSeconds(), entry.getValue());
                            } else {
                                return async.set(entry.getKey(), entry.getValue());
                            }
                        })
                        .collect(Collectors.toList());

                // Wait for all operations to complete and verify they all returned "OK".
                boolean allSuccessful = futures.stream()
                        .allMatch(future -> {
                            try {
                                String result = future.get(5, TimeUnit.SECONDS);
                                return "OK".equals(result);
                            } catch (Exception e) {
                                log.error("SET operation failed in batch", e);
                                return false;
                            }
                        });

                long duration = System.currentTimeMillis() - startTime;
                auditLogger.logPerformanceEvent("BATCH_SET", duration, allSuccessful);
                auditLogger.logOperation(username, "BATCH_SET", "keys:" + keyValues.size(), allSuccessful,
                        "Set " + keyValues.size() + " values with TTL: " + ttl);

                return allSuccessful;

            } catch (Exception e) {
                long duration = System.currentTimeMillis() - startTime;
                auditLogger.logPerformanceEvent("BATCH_SET", duration, false);
                auditLogger.logOperation(username, "BATCH_SET", "keys:" + keyValues.size(), false, e.getMessage());
                log.error("Batch SET operation failed", e);
                throw new RuntimeException("Batch SET operation failed", e);
            }
        }, redisAsyncExecutor);
    }

    /**
     * Executes a series of operations as a single atomic transaction using MULTI/EXEC.
     * This guarantees that either all commands are executed or none are.
     *
     * @param operations A list of operations (SET, DELETE, EXPIRE) to execute.
     * @param username User performing the operation, for auditing.
     * @return A CompletableFuture that completes with true if the transaction was successful.
     */
    @Retry(name = "redis-resilience")
    @Timed(value = "redis.transaction", description = "Time taken for Redis transactions")
    public CompletableFuture<Boolean> executeTransaction(List<RedisOperation> operations, String username) {
        return CompletableFuture.supplyAsync(() -> {
            long startTime = System.currentTimeMillis();

            try {
                // Use RedisTemplate's execute callback, the standard Spring way to handle transactions.
                List<Object> results = redisTemplate.execute((org.springframework.data.redis.core.RedisCallback<List<Object>>) connection -> {
                    connection.multi();

                    for (RedisOperation operation : operations) {
                        switch (operation.getType()) {
                            case SET:
                                connection.stringCommands().set(
                                        operation.getKey().getBytes(),
                                        operation.getValue().getBytes()
                                );
                                break;
                            case DELETE:
                                connection.keyCommands().del(operation.getKey().getBytes());
                                break;
                            case EXPIRE:
                                connection.keyCommands().expire(
                                        operation.getKey().getBytes(),
                                        operation.getTtlSeconds()
                                );
                                break;
                        }
                    }

                    return connection.exec();
                });

                // EXEC returns null if the transaction was aborted (e.g., by WATCH).
                boolean success = results != null && !results.isEmpty();
                long duration = System.currentTimeMillis() - startTime;

                auditLogger.logPerformanceEvent("TRANSACTION", duration, success);
                auditLogger.logOperation(username, "TRANSACTION", "ops:" + operations.size(), success,
                        "Executed " + operations.size() + " operations");

                return success;

            } catch (Exception e) {
                long duration = System.currentTimeMillis() - startTime;
                auditLogger.logPerformanceEvent("TRANSACTION", duration, false);
                auditLogger.logOperation(username, "TRANSACTION", "ops:" + operations.size(), false, e.getMessage());
                log.error("Transaction failed", e);
                throw new RuntimeException("Transaction failed", e);
            }
        }, redisAsyncExecutor);
    }

    /**
     * A simple DTO to represent a single command within a transaction.
     * This makes the `executeTransaction` method's signature clean and type-safe.
     */
    public static class RedisOperation {
        public enum Type { SET, DELETE, EXPIRE }

        private final Type type;
        private final String key;
        private final String value;
        private final long ttlSeconds;

        // Constructors and getters
        public RedisOperation(Type type, String key, String value, long ttlSeconds) {
            this.type = type;
            this.key = key;
            this.value = value;
            this.ttlSeconds = ttlSeconds;
        }

        // Constructor for SET
        public RedisOperation(Type type, String key, String value) {
            this.type = type;
            this.key = key;
            this.value = value;
            this.ttlSeconds = 0;
        }



        // Getters
        public Type getType() { return type; }
        public String getKey() { return key; }
        public String getValue() { return value; }
        public long getTtlSeconds() { return ttlSeconds; }
    }
}
