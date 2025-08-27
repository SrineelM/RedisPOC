package com.redis.poc.controller;

import com.redis.poc.service.RedisTemplateExampleService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;

/**
 * A REST controller demonstrating basic Redis operations via RedisTemplate.
 * Exposes simple endpoints for setting, getting, and deleting key-value pairs.
 */
@RestController
@RequestMapping("/api/template-example")
public class RedisTemplateExampleController {

    private final RedisTemplateExampleService service;

    public RedisTemplateExampleController(RedisTemplateExampleService service) {
        this.service = service;
    }

    /**
     * Sets a string value for a given key in Redis with a fixed Time-to-Live (TTL).
     * @param key The key to set.
     * @param value The string value to store.
     * @return A response entity confirming the operation.
     */
    @PostMapping
    public ResponseEntity<String> setValue(@RequestParam String key, @RequestBody String value) {
        // Set a default TTL of 5 minutes for this example.
        service.setValue(key, value, Duration.ofMinutes(5));
        return ResponseEntity.ok("Value set for key: " + key);
    }

    /**
     * Retrieves the value for a given key from Redis.
     * @param key The key to retrieve.
     * @return A response entity containing the value if found, or a 404 Not Found status.
     */
    @GetMapping
    public ResponseEntity<Object> getValue(@RequestParam String key) {
        Object value = service.getValue(key);
        if (value == null) {
            // Return 404 Not Found if the key doesn't exist (or has expired).
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(value);
    }

    /**
     * Deletes a key-value pair from Redis.
     * @param key The key to delete.
     * @return A response entity with a 204 No Content status indicating successful deletion.
     */
    @DeleteMapping
    public ResponseEntity<Void> deleteValue(@RequestParam String key) {
        service.deleteValue(key);
        return ResponseEntity.noContent().build();
    }
}
