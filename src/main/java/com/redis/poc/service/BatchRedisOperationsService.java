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

@Service
@Slf4j
public class BatchRedisOperationsService {

    private final StatefulRedisConnection<String, String> connection;
    private final RedisTemplate<String, Object> redisTemplate;
    private final AuditLogger auditLogger;

    private final Executor redisAsyncExecutor;

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
     * Batch GET operations using pipelining for better performance
     */
    @Retry(name = "redis-resilience")
    @Timed(value = "redis.batch.get", description = "Time taken for batch GET operations")
    public CompletableFuture<Map<String, String>> getBatchValues(List<String> keys, String username) {
        if (keys == null || keys.isEmpty()) {
            return CompletableFuture.completedFuture(Map.of());
        }

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

                // Wait for all futures to complete and collect results
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
     * Batch SET operations with TTL using pipelining
     */
    @Retry(name = "redis-resilience")
    @Timed(value = "redis.batch.set", description = "Time taken for batch SET operations")
    public CompletableFuture<Boolean> setBatchValues(Map<String, String> keyValues, 
                                                   Duration ttl, String username) {
        if (keyValues == null || keyValues.isEmpty()) {
            return CompletableFuture.completedFuture(true);
        }

    return CompletableFuture.supplyAsync(() -> {
            long startTime = System.currentTimeMillis();
            
            try {
                RedisAsyncCommands<String, String> async = connection.async();
                
                // Create futures for all SET operations
                List<RedisFuture<String>> futures = keyValues.entrySet().stream()
                        .map(entry -> {
                            if (ttl != null) {
                                return async.setex(entry.getKey(), ttl.getSeconds(), entry.getValue());
                            } else {
                                return async.set(entry.getKey(), entry.getValue());
                            }
                        })
                        .collect(Collectors.toList());

                // Wait for all operations to complete
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
     * Atomic multi-operation using transactions
     */
    @Retry(name = "redis-resilience")
    @Timed(value = "redis.transaction", description = "Time taken for Redis transactions")
    public CompletableFuture<Boolean> executeTransaction(List<RedisOperation> operations, String username) {
    return CompletableFuture.supplyAsync(() -> {
            long startTime = System.currentTimeMillis();
            
            try {
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

    public static class RedisOperation {
        public enum Type { SET, DELETE, EXPIRE }
        
        private Type type;
        private String key;
        private String value;
        private long ttlSeconds;

        // Constructors and getters
        public RedisOperation(Type type, String key) {
            this.type = type;
            this.key = key;
        }

        public RedisOperation(Type type, String key, String value) {
            this.type = type;
            this.key = key;
            this.value = value;
        }

        public RedisOperation(Type type, String key, long ttlSeconds) {
            this.type = type;
            this.key = key;
            this.ttlSeconds = ttlSeconds;
        }

        // Getters
        public Type getType() { return type; }
        public String getKey() { return key; }
        public String getValue() { return value; }
        public long getTtlSeconds() { return ttlSeconds; }
    }
}
