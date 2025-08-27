package com.redis.poc.service;

import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.stereotype.Service;

/**
 * A service to demonstrate the direct use of Spring's RedisTemplate.
 * This is the most common, high-level abstraction for interacting with Redis in a Spring application.
 */
@Service
@Slf4j
public class RedisTemplateExampleService {

    // RedisTemplate is thread-safe and can be used by multiple threads concurrently.
    private final RedisTemplate<String, Object> redisTemplate;
    private final ValueOperations<String, Object> valueOps;

    public RedisTemplateExampleService(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
        // Best Practice: Initialize opsForValue() once to avoid repeated method calls.
        this.valueOps = redisTemplate.opsForValue();
    }

    /**
     * Sets a key-value pair with an optional TTL.
     * This demonstrates the simplicity of synchronous write operations.
     * @param key The key to set.
     * @param value The value to store.
     * @param ttl The time-to-live for the key.
     */
    public void setValue(String key, Object value, Duration ttl) {
        log.info("Setting value for key '{}' with TTL {}s", key, ttl.toSeconds());
        valueOps.set(key, value, ttl);
    }

    /**
     * Retrieves a value for a given key.
     * This is a simple, synchronous read operation.
     * @param key The key to retrieve.
     * @return The value, or null if the key does not exist.
     */
    public Object getValue(String key) {
        log.info("Getting value for key '{}'", key);
        return valueOps.get(key);
    }

    /**
     * Deletes a key.
     * @param key The key to delete.
     */
    public void deleteValue(String key) {
        log.info("Deleting key '{}'", key);
        redisTemplate.delete(key);
    }
}
