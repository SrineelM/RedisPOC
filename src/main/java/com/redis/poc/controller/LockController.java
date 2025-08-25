package com.redis.poc.controller;

import com.redis.poc.service.DistributedLockService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;

@RestController
@RequestMapping("/api/lock")
public class LockController {

    private final DistributedLockService lockService;

    public LockController(DistributedLockService lockService) {
        this.lockService = lockService;
    }

    /**
     * Endpoint to acquire a distributed lock.
     * Adopts the best practice of returning a unique token representing lock ownership.
     * @param lockKey The key for the lock to acquire.
     * @return A 200 OK with the lock token if successful, or a 409 Conflict if the lock is already held.
     */
    @PostMapping("/acquire")
    public ResponseEntity<Map<String, String>> acquireLock(@RequestParam String lockKey) {
        String token = lockService.acquireLock(lockKey, Duration.ofSeconds(30));
        if (token != null) {
            return ResponseEntity.ok(Map.of("status", "acquired", "token", token));
        } else {
            // Best practice: Use 409 Conflict to indicate the resource is currently locked.
            return ResponseEntity.status(409).body(Map.of("status", "locked"));
        }
    }

    /**
     * Endpoint to release a distributed lock.
     * Adopts the best practice of requiring the lock token to prevent accidental or malicious releases.
     * @param lockKey The key for the lock to release.
     * @param token The token proving ownership.
     * @return A message indicating the result of the release operation.
     */
    @PostMapping("/release")
    public ResponseEntity<Map<String, Boolean>> releaseLock(@RequestParam String lockKey, @RequestParam String token) {
        boolean released = lockService.releaseLock(lockKey, token);
        return ResponseEntity.ok(Map.of("released", released));
    }
}
