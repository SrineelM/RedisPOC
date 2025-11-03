package com.redis.poc.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for HybridCacheService demonstrating L1 (Caffeine) and L2 (Redis) caching.
 *
 * <p><b>Test Scenarios:</b></p>
 * <ul>
 *   <li><b>Cache Miss:</b> Data not in L1 or L2, fetch from source</li>
 *   <li><b>L1 Hit:</b> Data in Caffeine cache (sub-microsecond access)</li>
 *   <li><b>L2 Hit:</b> Data not in L1 but in Redis (1-2ms access)</li>
 *   <li><b>Cache Population:</b> L1 miss populates from L2, L2 miss populates from source</li>
 *   <li><b>Cache Eviction:</b> Invalidation propagates through both layers</li>
 *   <li><b>Statistics:</b> Hit/miss ratios tracked correctly</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("HybridCacheService Unit Tests")
class HybridCacheServiceTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    @InjectMocks
    private HybridCacheService hybridCacheService;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    // ========================================================================
    // CACHE MISS SCENARIOS
    // ========================================================================

    @Test
    @DisplayName("Cache Miss - should fetch from source when not in L1 or L2")
    void givenCacheMiss_whenGet_thenFetchFromSource() {
        // Given: Not in L1 cache (Caffeine) or L2 cache (Redis)
        String key = "product:1";
        when(valueOperations.get(key)).thenReturn(null);

        // When: Getting value with loader function
        String result = hybridCacheService.get(key, k -> "Product from DB");

        // Then: Loader function called, value cached in both layers
        assertThat(result).isEqualTo("Product from DB");

        // Verify: Redis SET operation called to populate L2
        verify(valueOperations, times(1)).set(eq(key), eq("Product from DB"), anyLong(), eq(TimeUnit.MINUTES));
    }

    // ========================================================================
    // CACHE HIT SCENARIOS
    // ========================================================================

    @Test
    @DisplayName("L1 Hit - should return from Caffeine without Redis access")
    void givenL1Hit_whenGetMultipleTimes_thenNoRedisAccess() {
        // Given: Value cached in L1 after first access
        String key = "product:2";
        when(valueOperations.get(key)).thenReturn("Product from Redis");

        // When: First access (populates L1 from L2)
        String firstAccess = hybridCacheService.get(key, k -> "Product from DB");

        // When: Second access (L1 hit)
        String secondAccess = hybridCacheService.get(key, k -> "Product from DB");

        // Then: Both return same value
        assertThat(firstAccess).isEqualTo("Product from Redis");
        assertThat(secondAccess).isEqualTo("Product from Redis");

        // Verify: Redis accessed only once (first access), second is L1 hit
        verify(valueOperations, times(1)).get(key);
    }

    @Test
    @DisplayName("L2 Hit - should return from Redis when L1 misses")
    void givenL2Hit_whenL1Evicted_thenFetchFromRedis() {
        // Given: Value in Redis but not in L1 (L1 was evicted)
        String key = "product:3";
        when(valueOperations.get(key)).thenReturn("Product from Redis");

        // When: Getting value (L1 miss, L2 hit)
        String result = hybridCacheService.get(key, k -> "Product from DB");

        // Then: Value returned from Redis, loader not called
        assertThat(result).isEqualTo("Product from Redis");

        // Verify: Redis GET called, no SET (already in Redis)
        verify(valueOperations, times(1)).get(key);
        verify(valueOperations, never()).set(anyString(), any(), anyLong(), any());
    }

    // ========================================================================
    // CACHE INVALIDATION
    // ========================================================================

    @Test
    @DisplayName("Invalidation - should evict from both L1 and L2")
    void givenCachedValue_whenInvalidated_thenRemovedFromBothLayers() {
        // Given: Value cached in both layers
        String key = "product:4";
        when(valueOperations.get(key)).thenReturn("Cached Product");
        hybridCacheService.get(key, k -> "Should not be called");

        // When: Invalidating cache
        hybridCacheService.evict(key);

        // Then: L2 (Redis) DELETE called
        verify(redisTemplate, times(1)).delete(key);

        // When: Accessing again after eviction
        reset(valueOperations);
        when(valueOperations.get(key)).thenReturn(null);
        String afterEviction = hybridCacheService.get(key, k -> "Fresh from DB");

        // Then: Loader called (cache miss)
        assertThat(afterEviction).isEqualTo("Fresh from DB");
    }

    @Test
    @DisplayName("Clear All - should clear both L1 and L2 caches")
    void givenMultipleCachedValues_whenClearAll_thenAllRemoved() {
        // Given: Multiple values cached
        String key1 = "product:5";
        String key2 = "product:6";

        when(valueOperations.get(key1)).thenReturn("Product 5");
        when(valueOperations.get(key2)).thenReturn("Product 6");

        hybridCacheService.get(key1, k -> "Should not be called");
        hybridCacheService.get(key2, k -> "Should not be called");

        // When: Clearing all caches
        hybridCacheService.clearAll();

        // Then: Statistics reset
        HybridCacheService.CacheStatistics stats = hybridCacheService.getStatistics();
        assertThat(stats.getTotalRequests()).isEqualTo(0);
        assertThat(stats.getL1Hits()).isEqualTo(0);
        assertThat(stats.getL2Hits()).isEqualTo(0);
    }

    // ========================================================================
    // STATISTICS TRACKING
    // ========================================================================

    @Test
    @DisplayName("Statistics - should track L1 and L2 hits correctly")
    void givenMultipleAccesses_whenTracked_thenStatisticsCorrect() {
        // Given: Various cache access patterns
        String key1 = "product:7";
        String key2 = "product:8";

        when(valueOperations.get(key1)).thenReturn("Product 7");
        when(valueOperations.get(key2)).thenReturn(null);

        // When: First access key1 (L2 hit)
        hybridCacheService.get(key1, k -> "Should not be called");

        // When: Second access key1 (L1 hit)
        hybridCacheService.get(key1, k -> "Should not be called");

        // When: Access key2 (cache miss)
        hybridCacheService.get(key2, k -> "Product from DB");

        // Then: Statistics tracked correctly
        HybridCacheService.CacheStatistics stats = hybridCacheService.getStatistics();

        assertThat(stats.getTotalRequests()).isEqualTo(3);
        assertThat(stats.getL1Hits()).isEqualTo(1); // Second access to key1
        assertThat(stats.getL2Hits()).isEqualTo(1); // First access to key1
        assertThat(stats.getMisses()).isEqualTo(1); // Access to key2

        // Hit ratio should be (L1 + L2) / Total = 2/3 = 66.67%
        assertThat(stats.getHitRatio()).isGreaterThan(0.65);
        assertThat(stats.getHitRatio()).isLessThan(0.68);
    }

    // ========================================================================
    // CONCURRENCY TESTS
    // ========================================================================

    @Test
    @DisplayName("Concurrency - should handle concurrent access safely")
    void givenConcurrentAccess_whenMultipleThreads_thenThreadSafe() throws InterruptedException {
        // Given: Shared key accessed by multiple threads
        String key = "concurrent:product:9";
        when(valueOperations.get(key)).thenReturn("Cached Product");

        // When: Multiple threads access concurrently
        Thread[] threads = new Thread[10];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < 100; j++) {
                    hybridCacheService.get(key, k -> "Should be cached");
                }
            });
            threads[i].start();
        }

        // Wait for all threads
        for (Thread thread : threads) {
            thread.join();
        }

        // Then: No concurrency exceptions, statistics consistent
        HybridCacheService.CacheStatistics stats = hybridCacheService.getStatistics();
        assertThat(stats.getTotalRequests()).isEqualTo(1000); // 10 threads * 100 iterations
    }

    // ========================================================================
    // NULL VALUE HANDLING
    // ========================================================================

    @Test
    @DisplayName("Null Handling - should handle null values gracefully")
    void givenNullValue_whenCached_thenHandledGracefully() {
        // Given: Loader returns null
        String key = "product:null";
        when(valueOperations.get(key)).thenReturn(null);

        // When: Getting with loader that returns null
        String result = hybridCacheService.get(key, k -> null);

        // Then: Null returned, not cached (avoid caching null)
        assertThat(result).isNull();

        // Verify: Redis SET not called for null values
        verify(valueOperations, never()).set(anyString(), isNull(), anyLong(), any());
    }
}
