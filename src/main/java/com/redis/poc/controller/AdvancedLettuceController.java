package com.redis.poc.controller;

import com.redis.poc.service.AdvancedLettuceClientService;
import com.redis.poc.validation.ValidRedisKey;
import io.lettuce.core.api.StatefulRedisConnection;
import io.micrometer.core.annotation.Timed;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/advanced-lettuce")
@Validated
@Slf4j
public class AdvancedLettuceController {

    private final AdvancedLettuceClientService advancedLettuceClientService;
    private final StatefulRedisConnection<String, String> connection;

    public AdvancedLettuceController(AdvancedLettuceClientService advancedLettuceClientService, StatefulRedisConnection<String, String> connection) {
        this.advancedLettuceClientService = advancedLettuceClientService;
        this.connection = connection;
    }

    // Helper endpoint to set a value for testing
    @GetMapping("/set")
    @Timed(value = "redis.controller.set", description = "Time taken for Redis SET operations")
    public String setValue(
            @RequestParam @ValidRedisKey @NotBlank String key, 
            @RequestParam @NotBlank @Size(max = 1024, message = "Value size cannot exceed 1024 characters") String value,
            Authentication authentication) {
        
        log.info("User {} setting value for key: {}", authentication.getName(), key);
        connection.sync().set(key, value);
        return "Value set for key: " + key;
    }

    @GetMapping("/circuit-breaker")
    @Timed(value = "redis.controller.circuit.breaker", description = "Time taken for circuit breaker operations")
    public String testCircuitBreaker(
            @RequestParam @ValidRedisKey @NotBlank String key,
            Authentication authentication) {
        
        log.info("User {} testing circuit breaker for key: {}", authentication.getName(), key);
        return advancedLettuceClientService.getValueWithCircuitBreaker(key);
    }

    @GetMapping("/bulkhead")
    @Timed(value = "redis.controller.bulkhead", description = "Time taken for bulkhead operations")
    public String testBulkhead(
            @RequestParam @ValidRedisKey @NotBlank String key,
            Authentication authentication) {
        
        log.info("User {} testing bulkhead for key: {}", authentication.getName(), key);
        return advancedLettuceClientService.getValueWithBulkhead(key);
    }

    @GetMapping("/time-limiter")
    @Timed(value = "redis.controller.time.limiter", description = "Time taken for time limiter operations")
    public CompletableFuture<String> testTimeLimiter(
            @RequestParam @ValidRedisKey @NotBlank String key,
            Authentication authentication) {
        
        log.info("User {} testing time limiter for key: {}", authentication.getName(), key);
        return advancedLettuceClientService.getValueAsynchronously(key);
    }
}
