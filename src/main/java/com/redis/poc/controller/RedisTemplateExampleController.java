package com.redis.poc.controller;

import com.redis.poc.service.RedisTemplateExampleService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;

@RestController
@RequestMapping("/api/template-example")
public class RedisTemplateExampleController {

    private final RedisTemplateExampleService service;

    public RedisTemplateExampleController(RedisTemplateExampleService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<String> setValue(@RequestParam String key, @RequestBody String value) {
        // Set a default TTL of 5 minutes for this example
        service.setValue(key, value, Duration.ofMinutes(5));
        return ResponseEntity.ok("Value set for key: " + key);
    }

    @GetMapping
    public ResponseEntity<Object> getValue(@RequestParam String key) {
        Object value = service.getValue(key);
        if (value == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(value);
    }

    @DeleteMapping
    public ResponseEntity<Void> deleteValue(@RequestParam String key) {
        service.deleteValue(key);
        return ResponseEntity.noContent().build();
    }
}
