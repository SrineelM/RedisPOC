package com.redis.poc.controller;

import com.redis.poc.service.DistributedLockService;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for managing distributed locks via a Redis-backed service.
 * This controller exposes endpoints to acquire and release locks, enabling coordination
 * between different services or instances in a distributed environment.
 */
@RestController
@RequestMapping("/api/lock")
public class LockController {

    private final DistributedLockService lockService;

    public LockController(DistributedLockService lockService) {
        this.lockService = lockService;
    }

    /**
     * Endpoint to acquire a distributed lock for a given key.
     * This is a critical operation for ensuring mutual exclusion in a distributed system.
     * Best Practice: On successful acquisition, it returns a unique token representing lock ownership.
     * This token is required to release the lock, preventing accidental releases by other processes.
     * @param lockKey The unique key identifying the resource to be locked.
     * @return A 200 OK with the lock token if successful, or a 409 Conflict if the lock is already held.
     */
    @PostMapping("/acquire")
    public ResponseEntity<Map<String, String>> acquireLock(@RequestParam String lockKey) {
        // Attempt to acquire the lock with a 30-second lease time.
        String token = lockService.acquireLock(lockKey, Duration.ofSeconds(30));
        if (token != null) {
            // Lock acquired successfully, return the token to the client.
            return ResponseEntity.ok(Map.of("status", "acquired", "token", token));
        } else {
            // Best practice: Use 409 Conflict to indicate the resource is currently locked by another process.
            return ResponseEntity.status(409).body(Map.of("status", "locked"));
        }
    }

    /**
     * Endpoint to release a previously acquired distributed lock.
     * Best Practice: Requires the client to provide the unique token obtained during lock acquisition.
     * This is a crucial safety mechanism to ensure that only the rightful owner of a lock can release it.
     * @param lockKey The key for the lock to release.
     * @param token The unique token proving ownership of the lock.
     * @return A ResponseEntity indicating whether the release was successful.
     */
    @PostMapping("/release")
    public ResponseEntity<Map<String, Boolean>> releaseLock(@RequestParam String lockKey, @RequestParam String token) {
        boolean released = lockService.releaseLock(lockKey, token);
        return ResponseEntity.ok(Map.of("released", released));
    }
}
