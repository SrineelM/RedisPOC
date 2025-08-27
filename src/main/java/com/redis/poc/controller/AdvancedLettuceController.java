package com.redis.poc.controller;

import com.redis.poc.service.AdvancedLettuceClientService;
import com.redis.poc.validation.ValidRedisKey;
import io.micrometer.core.annotation.Timed;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * REST controller demonstrating BOTH RedisTemplate and Pure Lettuce approaches.
 *
 * This controller showcases:
 * - RedisTemplate pattern for Spring integration
 * - Pure Lettuce pattern for maximum performance
 * - Both patterns support async/non-blocking operations
 * - Resilience patterns integration
 * - Performance comparison capabilities
 */
@RestController
@RequestMapping("/api/advanced-lettuce")
@Validated
@Slf4j
public class AdvancedLettuceController {

    private final AdvancedLettuceClientService advancedLettuceClientService;
    private final RedisTemplate<String, String> redisTemplate;

    /**
     * Constructor with RedisTemplate for basic operations.
     * No StatefulRedisConnection - uses proper connection pooling.
     */
    public AdvancedLettuceController(AdvancedLettuceClientService advancedLettuceClientService,
                                   RedisTemplate<String, String> redisTemplate) {
        this.advancedLettuceClientService = advancedLettuceClientService;
        this.redisTemplate = redisTemplate;
    }

    // ====================================================================
    // SETUP ENDPOINTS - For testing both patterns
    // ====================================================================

    @PostMapping("/set")
    @Timed(value = "redis.controller.set", description = "Time taken for Redis SET operations")
    public String setValue(
            @RequestParam @ValidRedisKey @NotBlank String key, 
            @RequestParam @NotBlank @Size(max = 1024, message = "Value size cannot exceed 1024 characters") String value,
            @RequestParam(required = false) Long ttlSeconds,
            Authentication authentication) {
        
        log.info("User {} setting value for key: {}", authentication.getName(), key);

        if (ttlSeconds != null && ttlSeconds > 0) {
            redisTemplate.opsForValue().set(key, value, Duration.ofSeconds(ttlSeconds));
        } else {
            redisTemplate.opsForValue().set(key, value);
        }

        return "Value set for key: " + key + (ttlSeconds != null ? " with TTL: " + ttlSeconds + "s" : "");
    }

    // ====================================================================
    // REDISTEMPLATE PATTERN ENDPOINTS
    // ====================================================================

    @GetMapping("/template/get")
    @Timed(value = "redis.controller.template.get", description = "RedisTemplate GET operations")
    public String getWithRedisTemplate(
            @RequestParam @ValidRedisKey @NotBlank String key,
            Authentication authentication) {

        log.info("User {} testing RedisTemplate GET for key: {}", authentication.getName(), key);
        return advancedLettuceClientService.getValueWithRedisTemplate(key);
    }

    @GetMapping("/template/get-async")
    @Timed(value = "redis.controller.template.async", description = "RedisTemplate async GET operations")
    public CompletableFuture<String> getAsyncWithRedisTemplate(
            @RequestParam @ValidRedisKey @NotBlank String key,
            Authentication authentication) {

        log.info("User {} testing RedisTemplate async GET for key: {}", authentication.getName(), key);
        return advancedLettuceClientService.getValueAsyncWithRedisTemplate(key);
    }

    @PostMapping("/template/batch-get")
    @Timed(value = "redis.controller.template.batch", description = "RedisTemplate batch operations")
    public CompletableFuture<Map<String, String>> getBatchWithRedisTemplate(
            @RequestBody List<String> keys,
            Authentication authentication) {

        log.info("User {} testing RedisTemplate batch GET for {} keys", authentication.getName(), keys.size());
        return advancedLettuceClientService.getBatchWithRedisTemplate(keys);
    }

    // ====================================================================
    // PURE LETTUCE PATTERN ENDPOINTS
    // ====================================================================

    @GetMapping("/lettuce/get-async")
    @Timed(value = "redis.controller.lettuce.async", description = "Pure Lettuce async GET operations")
    public CompletableFuture<String> getAsyncWithPureLettuce(
            @RequestParam @ValidRedisKey @NotBlank String key,
            Authentication authentication) {

        log.info("User {} testing Pure Lettuce async GET for key: {}", authentication.getName(), key);
        return advancedLettuceClientService.getValueAsyncWithPureLettuce(key);
    }

    @PostMapping("/lettuce/batch-get")
    @Timed(value = "redis.controller.lettuce.batch", description = "Pure Lettuce batch operations with pipelining")
    public CompletableFuture<Map<String, String>> getBatchWithPureLettuce(
            @RequestBody List<String> keys,
            Authentication authentication) {

        log.info("User {} testing Pure Lettuce pipelined batch GET for {} keys", authentication.getName(), keys.size());
        return advancedLettuceClientService.getBatchAsyncWithPureLettuce(keys);
    }

    @PostMapping("/lettuce/set-async")
    @Timed(value = "redis.controller.lettuce.set", description = "Pure Lettuce async SET operations")
    public CompletableFuture<Boolean> setAsyncWithPureLettuce(
            @RequestParam @ValidRedisKey @NotBlank String key,
            @RequestParam @NotBlank @Size(max = 1024) String value,
            @RequestParam(required = false) Long ttlSeconds,
            Authentication authentication) {

        log.info("User {} testing Pure Lettuce async SET for key: {}", authentication.getName(), key);
        Duration ttl = ttlSeconds != null ? Duration.ofSeconds(ttlSeconds) : null;
        return advancedLettuceClientService.setValueAsyncWithPureLettuce(key, value, ttl);
    }

    // ====================================================================
    // RESILIENCE PATTERN DEMONSTRATIONS
    // ====================================================================

    @GetMapping("/circuit-breaker")
    @Timed(value = "redis.controller.circuit.breaker", description = "Circuit breaker pattern demonstration")
    public String testCircuitBreaker(
            @RequestParam @ValidRedisKey @NotBlank String key,
            Authentication authentication) {
        
        log.info("User {} testing circuit breaker for key: {}", authentication.getName(), key);
        return advancedLettuceClientService.getValueWithRedisTemplate(key);
    }

    @GetMapping("/bulkhead")
    @Timed(value = "redis.controller.bulkhead", description = "Bulkhead pattern demonstration")
    public String testBulkhead(
            @RequestParam @ValidRedisKey @NotBlank String key,
            Authentication authentication) {
        
        log.info("User {} testing bulkhead for key: {}", authentication.getName(), key);
        return advancedLettuceClientService.getValueWithBulkhead(key);
    }

    @GetMapping("/time-limiter")
    @Timed(value = "redis.controller.time.limiter", description = "Time limiter pattern demonstration")
    public CompletableFuture<String> testTimeLimiter(
            @RequestParam @ValidRedisKey @NotBlank String key,
            Authentication authentication) {
        
        log.info("User {} testing time limiter for key: {}", authentication.getName(), key);
        return advancedLettuceClientService.getValueAsyncWithPureLettuce(key);
    }

    // ====================================================================
    // PERFORMANCE COMPARISON ENDPOINTS
    // ====================================================================

    @PostMapping("/compare/performance")
    @Timed(value = "redis.controller.performance.comparison", description = "Performance comparison between patterns")
    public CompletableFuture<Map<String, Object>> comparePerformance(
            @RequestBody List<String> keys,
            Authentication authentication) {

        log.info("User {} comparing performance between RedisTemplate and Pure Lettuce for {} keys",
                authentication.getName(), keys.size());

        long startTime = System.currentTimeMillis();

        // Test both patterns and compare performance
        CompletableFuture<Map<String, String>> redisTemplateResult =
            advancedLettuceClientService.getBatchWithRedisTemplate(keys);

        CompletableFuture<Map<String, String>> pureLettuceResult =
            advancedLettuceClientService.getBatchAsyncWithPureLettuce(keys);

        return CompletableFuture.allOf(redisTemplateResult, pureLettuceResult)
                .handle((v, throwable) -> {
                    if (throwable != null) {
                        log.error("Performance comparison failed", throwable);
                        return Map.<String, Object>of(
                            "error", "Performance comparison failed: " + throwable.getMessage(),
                            "keysRequested", keys.size(),
                            "redisTemplateResults", 0,
                            "pureLettuceResults", 0,
                            "message", "Error occurred"
                        );
                    } else {
                        long endTime = System.currentTimeMillis();
                        return Map.<String, Object>of(
                            "totalTime", endTime - startTime,
                            "keysRequested", keys.size(),
                            "redisTemplateResults", redisTemplateResult.join().size(),
                            "pureLettuceResults", pureLettuceResult.join().size(),
                            "message", "Both patterns completed successfully"
                        );
                    }
                });
    }

    // ====================================================================
    // INFORMATION ENDPOINTS
    // ====================================================================

    @GetMapping("/patterns/info")
    public Map<String, Object> getPatternsInfo() {
        return Map.of(
            "redisTemplate", Map.of(
                "description", "Spring's integrated Redis client with automatic connection pooling",
                "useCase", "Spring applications, caching, simple operations",
                "asyncSupport", "Via @Async annotation",
                "connectionManagement", "Automatic pooling"
            ),
            "pureLettuce", Map.of(
                "description", "Direct Lettuce API usage for maximum performance",
                "useCase", "High-performance scenarios, pipelining, advanced operations",
                "asyncSupport", "Native CompletableFuture support",
                "connectionManagement", "Manual with pooling (no StatefulRedisConnection antipattern)"
            ),
            "resilience", Map.of(
                "patterns", List.of("Circuit Breaker", "Retry", "Bulkhead", "Time Limiter"),
                "observability", List.of("Metrics", "Tracing", "Logging")
            )
        );
    }
}
