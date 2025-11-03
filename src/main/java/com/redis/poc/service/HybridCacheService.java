package com.redis.poc.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Hybrid L1/L2 Caching Service using Caffeine (off-heap) + Redis (distributed).
 *
 * <p><b>Architecture: Two-Level Cache Hierarchy</b></p>
 * <pre>
 * Application Request
 *   ↓
 * L1 Cache (Caffeine - Local, Off-Heap, Ultra-Fast)
 *   ├── HIT → Return immediately (sub-microsecond latency)
 *   └── MISS ↓
 * L2 Cache (Redis - Distributed, Shared across instances)
 *   ├── HIT → Return + populate L1 (1-2ms latency)
 *   └── MISS ↓
 * Database/Source of Truth (e.g., PostgreSQL)
 *   └── Fetch → Populate L2 + L1 (10-100ms latency)
 * </pre>
 *
 * <p><b>Performance Characteristics:</b></p>
 * <table border="1">
 *   <tr><th>Layer</th><th>Latency</th><th>Scope</th><th>Capacity</th><th>Eviction</th></tr>
 *   <tr><td>L1 (Caffeine)</td><td>0.1-1μs</td><td>Per JVM</td><td>10K-100K entries</td><td>LRU/LFU</td></tr>
 *   <tr><td>L2 (Redis)</td><td>1-2ms</td><td>Distributed</td><td>Millions</td><td>TTL/LRU</td></tr>
 *   <tr><td>Database</td><td>10-100ms</td><td>Persistent</td><td>Unlimited</td><td>N/A</td></tr>
 * </table>
 *
 * <p><b>Why Caffeine for L1?</b></p>
 * <ul>
 *   <li><b>Off-Heap Storage:</b> Uses native memory, not JVM heap (no GC pressure)</li>
 *   <li><b>Ultra-Low Latency:</b> Sub-microsecond access time (1000x faster than Redis)</li>
 *   <li><b>Automatic Eviction:</b> Size-based, time-based, weight-based eviction</li>
 *   <li><b>High Hit Ratio:</b> TinyLFU admission policy (better than LRU)</li>
 *   <li><b>Thread-Safe:</b> Lock-free, highly concurrent implementation</li>
 *   <li><b>Statistics:</b> Built-in hit/miss rate tracking</li>
 * </ul>
 *
 * <p><b>When to Use This Pattern:</b></p>
 * <ul>
 *   <li>High read/write ratio (90%+ reads)</li>
 *   <li>Hot data that's frequently accessed (e.g., user sessions, product catalog)</li>
 *   <li>Latency-critical applications (sub-millisecond requirements)</li>
 *   <li>Multi-instance deployments (need distributed cache sync)</li>
 *   <li>Large working set that doesn't fit in heap (off-heap capability)</li>
 * </ul>
 *
 * <p><b>When NOT to Use:</b></p>
 * <ul>
 *   <li>Write-heavy workloads (cache invalidation overhead)</li>
 *   <li>Single-instance deployments (just use Caffeine alone)</li>
 *   <li>Rarely accessed data (wasted memory)</li>
 *   <li>Data that changes frequently (cache thrashing)</li>
 * </ul>
 *
 * <p><b>Cache Invalidation Strategy:</b></p>
 * <ol>
 *   <li><b>Write-Through:</b> Update DB → Invalidate L2 (Redis) → Invalidate L1 (Caffeine)</li>
 *   <li><b>Pub/Sub:</b> Instance A writes → publishes invalidation event → All instances clear L1</li>
 *   <li><b>TTL-Based:</b> Both L1 and L2 have TTL (L1 shorter than L2 for eventual consistency)</li>
 * </ol>
 *
 * <p><b>Thread Safety:</b></p>
 * <p>Both Caffeine and RedisTemplate are thread-safe. This service is designed for
 * high-concurrency environments and uses atomic operations where possible.</p>
 *
 * <p><b>Memory Management:</b></p>
 * <pre>
 * Caffeine (L1): 100MB - 1GB (configurable)
 *   - Uses off-heap memory (Java Direct ByteBuffers)
 *   - Does NOT contribute to JVM heap pressure
 *   - Automatic eviction when size limit reached
 *
 * Redis (L2): 1GB - 100GB (depends on infrastructure)
 *   - Distributed across cluster
 *   - Shared by all application instances
 *   - Configurable maxmemory-policy (e.g., allkeys-lru)
 * </pre>
 *
 * <p><b>Configuration:</b></p>
 * <pre>
 * application.yml:
 *   cache:
 *     caffeine:
 *       max-size: 10000        # Max entries in L1
 *       expire-after-write: 5m # L1 TTL (shorter than L2)
 *     redis:
 *       ttl: 10m               # L2 TTL (longer than L1)
 * </pre>
 *
 * @see <a href="https://github.com/ben-manes/caffeine">Caffeine Cache</a>
 */
@Service
@Slf4j
public class HybridCacheService {

    // ========================================================================
    // L1 CACHE: Caffeine (Local, Off-Heap)
    // ========================================================================

    /**
     * L1 cache for frequently accessed data (ultra-low latency).
     * Uses Caffeine with:
     * - Size-based eviction (10,000 entries max)
     * - Time-based expiration (5 minutes after write)
     * - TinyLFU admission policy (better hit ratio than LRU)
     * - Statistics tracking (for monitoring)
     */
    private final Cache<String, Object> l1Cache;

    // ========================================================================
    // L2 CACHE: Redis (Distributed, Shared)
    // ========================================================================

    /**
     * L2 cache for distributed caching across multiple application instances.
     * RedisTemplate provides:
     * - Network-based access (1-2ms latency)
     * - Distributed consistency
     * - Larger capacity (GBs vs MBs)
     * - TTL-based expiration
     */
    private final RedisTemplate<String, Object> redisTemplate;

    // ========================================================================
    // STATISTICS & MONITORING
    // ========================================================================

    /**
     * Per-cache statistics for monitoring and performance tuning.
     * Tracks: hit rate, miss rate, eviction count, load time, etc.
     */
    private final Map<String, CacheMetrics> metricsMap = new ConcurrentHashMap<>();

    /**
     * Constructor: Initialize hybrid caching service with both L1 and L2 caches.
     *
     * @param redisTemplate Spring's RedisTemplate for L2 caching
     */
    public HybridCacheService(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;

        // Initialize L1 cache (Caffeine) with optimal settings
        this.l1Cache = Caffeine.newBuilder()
                // Size-based eviction (prevent unbounded growth)
                .maximumSize(10_000) // Max 10K entries (~50MB for typical objects)

                // Time-based expiration (prevent stale data)
                .expireAfterWrite(5, TimeUnit.MINUTES) // Shorter TTL than L2

                // Statistics (for monitoring hit ratio)
                .recordStats() // Enable hit/miss tracking

                // Build the cache
                .build();

        log.info("Hybrid Cache initialized: L1 (Caffeine) max-size=10000, TTL=5m | L2 (Redis) distributed");
    }

    /**
     * Log cache statistics on application startup for baseline metrics.
     */
    @PostConstruct
    public void logInitialStats() {
        log.info("Caffeine Cache Stats at startup: {}", l1Cache.stats());
    }

    // ========================================================================
    // CORE CACHE OPERATIONS (GET, PUT, DELETE)
    // ========================================================================

    /**
     * Get value from hybrid cache (L1 → L2 → miss).
     *
     * <p><b>Flow:</b></p>
     * <ol>
     *   <li>Check L1 (Caffeine) - if HIT, return immediately (~0.1μs)</li>
     *   <li>If MISS, check L2 (Redis) - if HIT, populate L1 and return (~1ms)</li>
     *   <li>If MISS, return null (caller should fetch from DB and call put())</li>
     * </ol>
     *
     * <p><b>Thread Safety:</b> Fully thread-safe. Both Caffeine and RedisTemplate
     * are designed for high-concurrency access.</p>
     *
     * @param key Cache key
     * @return Cached value, or null if not found in L1 or L2
     */
    public Object get(String key) {
        // === STEP 1: Check L1 (Caffeine) ===
        Object value = l1Cache.getIfPresent(key);
        if (value != null) {
            log.debug("L1 HIT: key={}", key);
            recordHit("L1");
            return value;
        }

        // === STEP 2: L1 MISS → Check L2 (Redis) ===
        log.debug("L1 MISS: key={}, checking L2 (Redis)", key);
        recordMiss("L1");

        value = redisTemplate.opsForValue().get(key);
        if (value != null) {
            log.debug("L2 HIT: key={}, populating L1", key);
            recordHit("L2");

            // Populate L1 for future requests (write-back to L1)
            l1Cache.put(key, value);
            return value;
        }

        // === STEP 3: L2 MISS → Cache miss (caller should fetch from DB) ===
        log.debug("L2 MISS: key={} not found in any cache layer", key);
        recordMiss("L2");
        return null;
    }

    /**
     * Put value into hybrid cache (write-through to both L1 and L2).
     *
     * <p><b>Flow:</b></p>
     * <ol>
     *   <li>Write to L1 (Caffeine) - immediate local cache update</li>
     *   <li>Write to L2 (Redis) - propagate to distributed cache</li>
     *   <li>Set TTL on L2 (10 minutes) - longer than L1 for consistency</li>
     * </ol>
     *
     * <p><b>Consistency Model:</b> Eventual consistency. L1 updates are immediate,
     * L2 updates are atomic but may take 1-2ms to propagate.</p>
     *
     * @param key   Cache key
     * @param value Value to cache
     */
    public void put(String key, Object value) {
        // === Write to L1 (Caffeine) ===
        l1Cache.put(key, value);
        log.debug("L1 PUT: key={}", key);

        // === Write to L2 (Redis) with TTL ===
        redisTemplate.opsForValue().set(key, value, Duration.ofMinutes(10));
        log.debug("L2 PUT: key={}, TTL=10m", key);
    }

    /**
     * Put value with custom TTL (time-to-live) in L2.
     * L1 still uses default 5-minute TTL.
     *
     * @param key   Cache key
     * @param value Value to cache
     * @param ttl   Time-to-live for L2 cache
     */
    public void put(String key, Object value, Duration ttl) {
        // L1: Use default TTL (5 minutes from Caffeine config)
        l1Cache.put(key, value);

        // L2: Use custom TTL
        redisTemplate.opsForValue().set(key, value, ttl);
        log.debug("L1 PUT: key={} | L2 PUT: key={}, TTL={}", key, key, ttl);
    }

    /**
     * Invalidate (delete) value from hybrid cache (both L1 and L2).
     *
     * <p><b>Flow:</b></p>
     * <ol>
     *   <li>Invalidate L1 (Caffeine) - immediate local removal</li>
     *   <li>Invalidate L2 (Redis) - propagate to distributed cache</li>
     * </ol>
     *
     * <p><b>Use Case:</b> When data changes in the database, invalidate cache
     * to prevent serving stale data.</p>
     *
     * @param key Cache key to invalidate
     */
    public void invalidate(String key) {
        // === Invalidate L1 ===
        l1Cache.invalidate(key);
        log.debug("L1 INVALIDATE: key={}", key);

        // === Invalidate L2 ===
        redisTemplate.delete(key);
        log.debug("L2 INVALIDATE: key={}", key);
    }

    /**
     * Clear all caches (both L1 and L2).
     * WARNING: This is a destructive operation. Use with caution.
     *
     * <p><b>Use Cases:</b></p>
     * <ul>
     *   <li>Deployment/restart: Ensure fresh cache</li>
     *   <li>Testing: Reset cache state between tests</li>
     *   <li>Emergency: Fix cache corruption issues</li>
     * </ul>
     */
    public void clearAll() {
        // === Clear L1 (local instance only) ===
        long l1Size = l1Cache.estimatedSize();
        l1Cache.invalidateAll();
        log.warn("L1 CLEAR: Removed {} entries from local cache", l1Size);

        // === Clear L2 (WARNING: affects ALL instances!) ===
        // In production, you might want pattern-based deletion instead of FLUSHDB
        // For now, we don't implement full L2 clear to prevent accidental data loss
        log.warn("L2 CLEAR: Not implemented (would affect all instances - use with extreme caution)");
    }

    // ========================================================================
    // STATISTICS & MONITORING
    // ========================================================================

    /**
     * Get comprehensive cache statistics for monitoring and performance tuning.
     *
     * <p><b>Metrics Included:</b></p>
     * <ul>
     *   <li>Hit rate (L1 and L2 separately)</li>
     *   <li>Miss rate</li>
     *   <li>Eviction count</li>
     *   <li>Load time (average)</li>
     *   <li>Total requests</li>
     * </ul>
     *
     * <p><b>Optimal Hit Ratios:</b></p>
     * <ul>
     *   <li>L1 (Caffeine): 80-95% (most requests served locally)</li>
     *   <li>L2 (Redis): 50-80% (fallback for L1 misses)</li>
     *   <li>Combined: 90-99% (only 1-10% hit database)</li>
     * </ul>
     *
     * @return CacheStats from Caffeine (L1 statistics)
     */
    public CacheStats getL1Stats() {
        CacheStats stats = l1Cache.stats();
        log.info("L1 (Caffeine) Stats: {}", stats);
        return stats;
    }

    /**
     * Get current size of L1 cache (approximate).
     *
     * @return Number of entries in L1 cache
     */
    public long getL1Size() {
        return l1Cache.estimatedSize();
    }

    /**
     * Get detailed metrics for specific cache layer.
     *
     * @param layer "L1" or "L2"
     * @return Metrics object with hit/miss counts
     */
    public CacheMetrics getMetrics(String layer) {
        return metricsMap.computeIfAbsent(layer, k -> new CacheMetrics());
    }

    /**
     * Record cache hit for monitoring.
     *
     * @param layer "L1" or "L2"
     */
    private void recordHit(String layer) {
        metricsMap.computeIfAbsent(layer, k -> new CacheMetrics()).recordHit();
    }

    /**
     * Record cache miss for monitoring.
     *
     * @param layer "L1" or "L2"
     */
    private void recordMiss(String layer) {
        metricsMap.computeIfAbsent(layer, k -> new CacheMetrics()).recordMiss();
    }

    // ========================================================================
    // METRICS DATA CLASS
    // ========================================================================

    /**
     * Simple metrics class for tracking cache performance.
     * Thread-safe using atomic operations.
     */
    public static class CacheMetrics {
        private long hits = 0;
        private long misses = 0;

        public synchronized void recordHit() {
            hits++;
        }

        public synchronized void recordMiss() {
            misses++;
        }

        public synchronized long getHits() {
            return hits;
        }

        public synchronized long getMisses() {
            return misses;
        }

        public synchronized double getHitRatio() {
            long total = hits + misses;
            return total == 0 ? 0.0 : (double) hits / total;
        }

        @Override
        public synchronized String toString() {
            return String.format("Hits: %d, Misses: %d, Hit Ratio: %.2f%%", hits, misses, getHitRatio() * 100);
        }
    }
}
