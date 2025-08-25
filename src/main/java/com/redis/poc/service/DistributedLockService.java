package com.redis.poc.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collections;
import java.util.UUID;

@Service
@Slf4j
public class DistributedLockService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final RedisScript<Long> releaseScript;

    public DistributedLockService(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
        // This Lua script ensures atomicity. It checks if the key holds the correct token before deleting it.
        // This is the best practice for preventing a client from deleting a lock it no longer owns.
        String script = "if redis.call('get',KEYS[1]) == ARGV[1] then return redis.call('del',KEYS[1]) else return 0 end";
        this.releaseScript = new DefaultRedisScript<>(script, Long.class);
    }

    /**
     * Attempts to acquire a distributed lock.
     * It uses SETNX for atomicity and sets a TTL to prevent deadlocks.
     * @param lockKey The key for the lock.
     * @param timeout The timeout for the lock.
     * @return A unique lock token if the lock was acquired, null otherwise.
     */
    public String acquireLock(String lockKey, Duration timeout) {
        String token = UUID.randomUUID().toString();
        log.info("Attempting to acquire lock for key: {} with token: {}", lockKey, token);
        Boolean success = redisTemplate.opsForValue().setIfAbsent(lockKey, token, timeout);
        return (success != null && success) ? token : null;
    }

    /**
     * Releases a distributed lock using a Lua script for a safe, atomic check-and-delete operation.
     * @param lockKey The key for the lock.
     * @param token The unique token proving ownership of the lock.
     * @return true if the lock was released by this call, false otherwise.
     */
    public boolean releaseLock(String lockKey, String token) {
        log.info("Attempting to release lock for key: {} with token: {}", lockKey, token);
        Long result = redisTemplate.execute(releaseScript, Collections.singletonList(lockKey), token);
        return result != null && result == 1L;
    }
}
