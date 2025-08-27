package com.redis.poc.service;

import java.time.Duration;
import java.util.Collections;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

/**
 * Service for handling distributed locks using Redis.
 * This service provides a simple implementation of a distributed lock,
 * which is essential for ensuring that only one process can access a
 * specific section of code or a particular resource at a time in a
 * distributed environment.
 */
@Service
@Slf4j
public class DistributedLockService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final RedisScript<Long> releaseScript;

    public DistributedLockService(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
        // This Lua script ensures atomicity for the release operation.
        // It checks if the key (the lock) is held by the expected owner (identified by the token)
        // before deleting it. This is a crucial step to prevent a client from erroneously
        // releasing a lock that it no longer holds (e.g., if the lock expired and was
        // acquired by another client).
        String script =
                "if redis.call('get',KEYS[1]) == ARGV[1] then return redis.call('del',KEYS[1]) else return 0 end";
        this.releaseScript = new DefaultRedisScript<>(script, Long.class);
    }

    /**
     * Attempts to acquire a distributed lock.
     * This method uses the Redis SET command with the NX option (set if not exists)
     * to ensure atomicity. A Time-To-Live (TTL) is also set on the lock to prevent
     * deadlocks in case a client crashes or fails to release the lock.
     *
     * @param lockKey The key representing the lock in Redis.
     * @param timeout The duration for which the lock should be held before it expires.
     * @return A unique string token if the lock was successfully acquired, otherwise null.
     */
    public String acquireLock(String lockKey, Duration timeout) {
        // Generate a unique value for the lock to identify the lock holder.
        String token = UUID.randomUUID().toString();
        log.info("Attempting to acquire lock for key: {} with token: {}", lockKey, token);

        // setIfAbsent is equivalent to the Redis SET key value NX PX timeout command.
        // It sets the key only if it does not already exist.
        Boolean success = redisTemplate.opsForValue().setIfAbsent(lockKey, token, timeout);

        // If success is not null and is true, the lock was acquired.
        return (success != null && success) ? token : null;
    }

    /**
     * Releases a distributed lock using a Lua script for a safe, atomic check-and-delete operation.
     * The script ensures that the lock is only released if it is still held by the client
     * that is attempting to release it.
     *
     * @param lockKey The key for the lock.
     * @param token The unique token proving ownership of the lock, returned by acquireLock.
     * @return true if the lock was released by this call, false otherwise.
     */
    public boolean releaseLock(String lockKey, String token) {
        log.info("Attempting to release lock for key: {} with token: {}", lockKey, token);
        // Execute the Lua script to atomically check the token and delete the key.
        Long result = redisTemplate.execute(releaseScript, Collections.singletonList(lockKey), token);
        // The script returns 1 if the key was deleted, 0 otherwise.
        return result != null && result == 1L;
    }
}
