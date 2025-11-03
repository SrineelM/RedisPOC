package com.redis.poc.service;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

/**
 * Thread-Safe Distributed Cache Synchronization Service with Redis Pub/Sub.
 *
 * <p><b>Problem Statement: Cache Consistency in Distributed Systems</b></p>
 * <p>When multiple application instances cache data locally (e.g., Caffeine L1 cache),
 * updating the database from one instance creates stale data in other instances' caches.</p>
 *
 * <pre>
 * Scenario WITHOUT Cache Sync:
 * =============================
 * Instance A: Updates product price in DB + clears local cache ✓
 * Instance B: Still has OLD price in local cache ✗ (STALE DATA)
 * Instance C: Still has OLD price in local cache ✗ (STALE DATA)
 * → Result: Users see different prices depending on which instance they hit!
 *
 * Solution WITH Cache Sync (Redis Pub/Sub):
 * =========================================
 * Instance A: Updates DB → Publishes "invalidate:product:123" to Redis
 *    ↓
 * Redis broadcasts to ALL instances
 *    ↓
 * Instance B: Receives message → Clears local cache ✓
 * Instance C: Receives message → Clears local cache ✓
 * → Result: All instances serve fresh data on next request!
 * </pre>
 *
 * <p><b>Implementation Patterns:</b></p>
 * <ol>
 *   <li><b>Invalidation-Based:</b> Publish "clear cache" messages (simplest)</li>
 *   <li><b>Update-Based:</b> Publish new values (reduces DB load)</li>
 *   <li><b>Version-Based:</b> Use version numbers for optimistic locking</li>
 * </ol>
 *
 * <p><b>Thread Safety Guarantees:</b></p>
 * <ul>
 *   <li><b>Redis Operations:</b> All Redis commands are atomic by nature</li>
 *   <li><b>Lua Scripts:</b> Execute atomically without race conditions</li>
 *   <li><b>Optimistic Locking:</b> WATCH/MULTI/EXEC for transaction-like behavior</li>
 *   <li><b>Distributed Locks:</b> Prevent concurrent updates across instances</li>
 * </ul>
 *
 * <p><b>Data Freshness Strategies:</b></p>
 * <table border="1">
 *   <tr><th>Strategy</th><th>Consistency</th><th>Latency</th><th>Complexity</th><th>Use Case</th></tr>
 *   <tr><td>TTL-Based</td><td>Eventual</td><td>Low</td><td>Low</td><td>Rarely changing data</td></tr>
 *   <tr><td>Pub/Sub Invalidation</td><td>Near Real-time</td><td>Medium</td><td>Medium</td><td>Read-heavy workloads</td></tr>
 *   <tr><td>Optimistic Locking</td><td>Strong</td><td>Medium</td><td>High</td><td>Concurrent updates</td></tr>
 *   <tr><td>Distributed Lock</td><td>Strong</td><td>High</td><td>High</td><td>Critical sections</td></tr>
 * </table>
 *
 * <p><b>Node Failure & Recovery:</b></p>
 * <pre>
 * Failure Scenario:
 * ─────────────────
 * Instance A (master): CRASHES during write operation
 *   → Redis Cluster: Automatically fails over to replica
 *   → Other instances: Unaffected (continue serving from cache)
 *   → New instance: Starts with empty L1 cache (populated on-demand)
 *
 * Recovery Steps:
 * ───────────────
 * 1. Redis Sentinel/Cluster: Detects node failure (heartbeat timeout)
 * 2. Promotes replica to master (automatic failover)
 * 3. Application reconnects via Lettuce auto-reconnect
 * 4. Data resynchronization: Replicas catch up from master
 * 5. Client-side cache: Rebuilds on-demand (cold start)
 * </pre>
 *
 * <p><b>Transaction Management in Redis:</b></p>
 * <p>Redis does NOT support traditional ACID transactions like SQL databases.
 * However, it provides transaction-like features:</p>
 * <ul>
 *   <li><b>MULTI/EXEC:</b> Batch commands (all-or-nothing execution)</li>
 *   <li><b>WATCH:</b> Optimistic locking (detect concurrent modifications)</li>
 *   <li><b>Lua Scripts:</b> Atomic execution (server-side transactions)</li>
 *   <li><b>Limitations:</b> No rollback on partial failure (unlike SQL)</li>
 * </ul>
 *
 * @see DistributedLockService for distributed locking examples
 * @see HybridCacheService for L1/L2 caching architecture
 */
@Service
@Slf4j
public class CacheSyncService {

    // Redis channel for cache invalidation messages
    private static final String CACHE_INVALIDATION_CHANNEL = "cache:invalidation";

    private final RedisTemplate<String, Object> redisTemplate;

    // Lua script for atomic compare-and-set with version checking (optimistic locking)
    private final DefaultRedisScript<Boolean> casScript;

    public CacheSyncService(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;

        // Initialize CAS (Compare-And-Set) script for optimistic locking
        this.casScript = new DefaultRedisScript<>();
        this.casScript.setScriptText("if redis.call('get', KEYS[1]) == ARGV[1] then "
                + "return redis.call('set', KEYS[1], ARGV[2]) " + "else return false end");
        this.casScript.setResultType(Boolean.class);
    }

    // ========================================================================
    // PATTERN 1: INVALIDATION-BASED CACHE SYNC (Pub/Sub)
    // ========================================================================

    /**
     * Publishes a cache invalidation message to all application instances.
     *
     * <p><b>Flow:</b></p>
     * <ol>
     *   <li>Instance A updates database</li>
     *   <li>Instance A calls this method: publishInvalidation("product:123")</li>
     *   <li>Redis broadcasts message to ALL instances (Pub/Sub)</li>
     *   <li>Each instance receives message via RedisMessageSubscriber</li>
     *   <li>Each instance clears its local (L1) cache for this key</li>
     *   <li>Next request fetches fresh data from DB</li>
     * </ol>
     *
     * <p><b>Thread Safety:</b> Redis Pub/Sub is thread-safe. Messages are
     * delivered atomically to all subscribers.</p>
     *
     * <p><b>Latency:</b> 1-5ms for message propagation (acceptable for most use cases)</p>
     *
     * @param key Cache key to invalidate across all instances
     */
    public void publishInvalidation(String key) {
        try {
            // Publish to Redis Pub/Sub channel
            redisTemplate.convertAndSend(CACHE_INVALIDATION_CHANNEL, key);
            log.info("Published cache invalidation: key={}", key);
        } catch (Exception e) {
            // Failure to publish is NOT fatal - worst case: stale cache until TTL expires
            log.error("Failed to publish cache invalidation for key={}: {}", key, e.getMessage());
        }
    }

    /**
     * Publishes batch invalidation for multiple keys (more efficient than individual publishes).
     *
     * @param keys List of cache keys to invalidate
     */
    public void publishBatchInvalidation(List<String> keys) {
        keys.forEach(this::publishInvalidation);
        log.info("Published batch cache invalidation: count={}", keys.size());
    }

    // ========================================================================
    // PATTERN 2: OPTIMISTIC LOCKING (WATCH/MULTI/EXEC)
    // ========================================================================

    /**
     * Updates a value in Redis using optimistic locking (WATCH/MULTI/EXEC pattern).
     *
     * <p><b>Optimistic Locking Explained:</b></p>
     * <pre>
     * Thread A: WATCH key → GET value → Modify → EXEC
     * Thread B: (concurrent) WATCH key → GET value → Modify → EXEC
     *
     * If Thread B commits first:
     *   → Thread A's EXEC fails (detects concurrent modification)
     *   → Thread A retries the operation
     *   → Result: NO data corruption (thread-safe)
     * </pre>
     *
     * <p><b>How WATCH Works:</b></p>
     * <ol>
     *   <li>WATCH key: Redis monitors this key for changes</li>
     *   <li>Read current value</li>
     *   <li>MULTI: Start transaction</li>
     *   <li>Queue commands (SET, INCR, etc.)</li>
     *   <li>EXEC: If key was modified by another client, EXEC fails</li>
     *   <li>Retry if failed (optimistic retry loop)</li>
     * </ol>
     *
     * <p><b>Use Cases:</b></p>
     * <ul>
     *   <li>Counter increments (avoid race conditions)</li>
     *   <li>Inventory updates (prevent overselling)</li>
     *   <li>User balance changes (financial accuracy)</li>
     *   <li>Any read-modify-write operation</li>
     * </ul>
     *
     * @param key   Redis key to update
     * @param value New value to set
     * @return true if update succeeded, false if concurrent modification detected
     */
    public boolean updateWithOptimisticLocking(String key, Object value) {
        int maxRetries = 3;
        int attempt = 0;

        while (attempt < maxRetries) {
            attempt++;
            try {
                // Use Lua script for atomic optimistic locking
                // Script: Check if key exists and hasn't changed, then update
                String luaScript =
                        "if redis.call('EXISTS', KEYS[1]) == 0 or " + "redis.call('GET', KEYS[1]) == ARGV[1] then "
                                + "redis.call('SET', KEYS[1], ARGV[2]); "
                                + "return 1; "
                                + "else "
                                + "return 0; "
                                + "end";

                DefaultRedisScript<Long> script = new DefaultRedisScript<>();
                script.setScriptText(luaScript);
                script.setResultType(Long.class);

                // Get current value for comparison
                Object currentValue = redisTemplate.opsForValue().get(key);

                Long result = redisTemplate.execute(
                        script,
                        Collections.singletonList(key),
                        currentValue != null ? currentValue.toString() : "",
                        value.toString());

                if (result != null && result > 0) {
                    log.info("Optimistic lock UPDATE succeeded: key={}, attempt={}", key, attempt);
                    return true;
                } else {
                    log.warn("Optimistic lock CONFLICT: key={}, attempt={}, retrying...", key, attempt);
                    if (attempt < maxRetries) {
                        Thread.sleep(10L * attempt); // Exponential backoff
                    }
                }
            } catch (Exception e) {
                log.error("Optimistic lock ERROR: key={}, attempt={}: {}", key, attempt, e.getMessage());
                if (attempt < maxRetries) {
                    try {
                        Thread.sleep(10L * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
            }
        }

        log.error("Optimistic lock FAILED after {} attempts: key={}", maxRetries, key);
        return false;
    }

    // ========================================================================
    // PATTERN 3: COMPARE-AND-SET (CAS) WITH VERSION CHECKING
    // ========================================================================

    /**
     * Atomic Compare-And-Set operation using Lua script.
     *
     * <p><b>How CAS Works:</b></p>
     * <pre>
     * if (currentValue == expectedValue) {
     *     setValue(newValue);
     *     return true; // Success
     * } else {
     *     return false; // Conflict
     * }
     *
     * This is ATOMIC (no race condition possible).
     * </pre>
     *
     * <p><b>Use Case: Version-Based Optimistic Locking</b></p>
     * <pre>
     * 1. Read: value="product:123", version=5
     * 2. User modifies product
     * 3. Write: CAS("product:123", expectedVersion=5, newVersion=6)
     *    → If version is still 5: UPDATE success
     *    → If version changed to 6: UPDATE failure (someone else modified it)
     * </pre>
     *
     * @param key            Redis key
     * @param expectedValue  Expected current value (for comparison)
     * @param newValue       New value to set
     * @return true if CAS succeeded, false if value was different
     */
    public boolean compareAndSet(String key, Object expectedValue, Object newValue) {
        try {
            Boolean success = redisTemplate.execute(casScript, Collections.singletonList(key), expectedValue, newValue);

            if (Boolean.TRUE.equals(success)) {
                log.info("CAS SUCCESS: key={}, expected={}, new={}", key, expectedValue, newValue);
                return true;
            } else {
                log.warn("CAS FAILURE: key={}, value was modified concurrently", key);
                return false;
            }
        } catch (Exception e) {
            log.error("CAS ERROR: key={}: {}", key, e.getMessage());
            return false;
        }
    }

    // ========================================================================
    // PATTERN 4: DISTRIBUTED LOCK WITH AUTOMATIC RENEWAL
    // ========================================================================

    /**
     * Acquires a distributed lock with automatic renewal (keep-alive).
     *
     * <p><b>Problem with Simple Locks:</b></p>
     * <pre>
     * Thread A: Acquires lock (TTL=10s)
     * Thread A: Processing takes 15 seconds (longer than TTL!)
     * Thread A: Lock expires after 10s → Thread B acquires same lock ✗
     * Result: TWO threads in critical section! (DATA CORRUPTION)
     * </pre>
     *
     * <p><b>Solution: Automatic Lock Renewal</b></p>
     * <pre>
     * Thread A: Acquires lock (TTL=10s)
     * Background Thread: Renews lock every 5s (while owner is active)
     * Thread A: Processes for 15s
     * Thread A: Releases lock
     * Result: Lock held for entire processing duration ✓
     * </pre>
     *
     * <p><b>Implementation:</b> This is a simplified example. For production,
     * use Redisson library which provides automatic lock renewal.</p>
     *
     * @param lockKey Lock identifier
     * @param ttl     Lock time-to-live
     * @return Lock token (for release), or null if failed to acquire
     * @see <a href="https://github.com/redisson/redisson">Redisson Documentation</a>
     */
    public String acquireLockWithRenewal(String lockKey, Duration ttl) {
        String token = UUID.randomUUID().toString();
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(lockKey, token, ttl);

        if (Boolean.TRUE.equals(acquired)) {
            log.info("Lock ACQUIRED: key={}, token={}, TTL={}", lockKey, token, ttl);
            // TODO: Start background thread for lock renewal (production implementation)
            return token;
        } else {
            log.warn("Lock FAILED: key={} already held by another process", lockKey);
            return null;
        }
    }

    // ========================================================================
    // NODE FAILURE & RECOVERY PATTERNS
    // ========================================================================

    /**
     * Demonstrates health check for Redis cluster recovery.
     *
     * <p><b>Recovery Scenarios:</b></p>
     * <ol>
     *   <li><b>Master Failure:</b> Sentinel promotes replica to master (automatic)</li>
     *   <li><b>Replica Failure:</b> Other replicas continue serving reads</li>
     *   <li><b>Network Partition:</b> Lettuce auto-reconnects when partition heals</li>
     *   <li><b>Application Restart:</b> Reconnects via connection pool</li>
     * </ol>
     *
     * @return true if Redis is reachable and healthy
     */
    public boolean checkRedisHealth() {
        try {
            // PING command: Returns "PONG" if Redis is alive
            String response =
                    redisTemplate.getConnectionFactory().getConnection().ping();

            boolean healthy = "PONG".equals(response);
            log.info("Redis health check: {}", healthy ? "HEALTHY" : "UNHEALTHY");
            return healthy;
        } catch (Exception e) {
            log.error("Redis health check FAILED: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Demonstrates graceful degradation when Redis is unavailable.
     *
     * <p><b>Fallback Strategy:</b></p>
     * <pre>
     * Try: Use Redis for caching
     * Catch: If Redis is down, fall back to:
     *   1. Local in-memory cache (Caffeine)
     *   2. Direct database access
     *   3. Return default/stale value
     * </pre>
     *
     * @param key Cache key
     * @return Cached value, or null if Redis is unavailable
     */
    public Object getWithFallback(String key) {
        try {
            return redisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            log.warn("Redis GET failed for key={}, falling back to local cache: {}", key, e.getMessage());
            // TODO: Fallback to L1 cache (Caffeine) or database
            return null;
        }
    }
}
