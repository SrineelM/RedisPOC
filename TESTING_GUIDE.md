# Redis POC - Comprehensive Testing Guide

## Table of Contents
1. [Quick Start](#quick-start)
2. [Test Data Setup](#test-data-setup)
3. [Postman Testing](#postman-testing)
4. [Performance Testing](#performance-testing)
5. [Security Testing](#security-testing)
6. [Concurrency Testing](#concurrency-testing)
7. [Troubleshooting](#troubleshooting)

---

## Quick Start

### Prerequisites
- Java 17+
- Gradle 7.0+
- **For `local` profile**: Nothing! (embedded Redis starts automatically)
- **For `dev` profile**: Redis server running on localhost:6379
- **For `prod` profile**: PostgreSQL + Redis cluster + Kafka

### Starting the Application

```bash
# Local profile (8GB laptops, zero setup)
./gradlew bootRun --args='--spring.profiles.active=local'

# Dev profile (external Redis required)
redis-server  # Start Redis first
./gradlew bootRun --args='--spring.profiles.active=dev'

# Prod profile (full stack)
docker-compose up -d  # Starts PostgreSQL, Redis, Kafka
./gradlew bootRun --args='--spring.profiles.active=prod'
```

### Verify Application Started
```bash
curl http://localhost:8080/actuator/health
# Expected: {"status":"UP"}
```

---

## Test Data Setup

### 1. Pre-populate Database with Sample Products

```bash
# Create 100 sample products
for i in {1..100}; do
  curl -X POST http://localhost:8080/api/products \
    -H "Content-Type: application/json" \
    -d "{
      \"id\": \"prod-$i\",
      \"name\": \"Product $i\",
      \"description\": \"Test product $i\",
      \"price\": $(($i * 10))
    }"
done
```

### 2. Pre-populate Leaderboard

```bash
# Create leaderboard with 50 users
for i in {1..50}; do
  curl -X POST http://localhost:8080/api/leaderboard/add \
    -H "Content-Type: application/json" \
    -d "{
      \"userId\": \"user-$i\",
      \"score\": $((RANDOM % 1000))
    }"
done
```

### 3. Add Geospatial Data (Major US Cities)

```bash
# New York
curl -X POST http://localhost:8080/api/geo/add \
  -H "Content-Type: application/json" \
  -d '{"name": "New York", "longitude": -74.0060, "latitude": 40.7128}'

# Los Angeles
curl -X POST http://localhost:8080/api/geo/add \
  -H "Content-Type: application/json" \
  -d '{"name": "Los Angeles", "longitude": -118.2437, "latitude": 34.0522}'

# Chicago
curl -X POST http://localhost:8080/api/geo/add \
  -H "Content-Type": application/json" \
  -d '{"name": "Chicago", "longitude": -87.6298, "latitude": 41.8781}'
```

---

## Postman Testing

### Setup

1. **Import Collection**:
   - File → Import
   - Select: `postman/Redis-POC-Collection.postman_collection.json`

2. **Import Environment**:
   - File → Import
   - Select: `postman/Redis-POC-Local.postman_environment.json`

3. **Select Environment**:
   - Top-right dropdown → "Redis POC - Local Environment"

### Test Execution Order

Execute tests in this order for comprehensive coverage:

#### Phase 1: Basic Functionality
1. **Authentication** → Login & Get JWT (saves token for subsequent requests)
2. **Product CRUD** → Create Product
3. **Product CRUD** → Get Product (Cache MISS) - observe ~2s latency
4. **Product CRUD** → Get Product (Cache HIT) - observe <100ms latency

#### Phase 2: Caching Patterns
5. **Hybrid Cache** → Put to Hybrid Cache
6. **Hybrid Cache** → Get from Hybrid Cache (L1 HIT - ultra-fast)
7. **Hybrid Cache** → Get Cache Statistics (observe hit ratios)

#### Phase 3: Distributed Coordination
8. **Distributed Locks** → Acquire Lock (saves lock token)
9. **Distributed Locks** → Release Lock (uses saved token)

#### Phase 4: Rate Limiting
10. **Rate Limiting** → Test Rate Limit
    - Run 20 times rapidly (use Collection Runner)
    - First 10 requests: 200 OK
    - Remaining requests: 429 Too Many Requests

#### Phase 5: Data Structures
11. **Geospatial** → Add Location
12. **Geospatial** → Find Nearby Locations
13. **Leaderboard** → Update User Score
14. **Leaderboard** → Get Top 10 Users

#### Phase 6: Messaging
15. **Pub/Sub Messaging** → Publish Message (check logs for subscriber output)
16. **Redis Streams** → Publish Order Event (consumer processes async)

#### Phase 7: Resilience
17. **Advanced Lettuce** → Circuit Breaker Test
18. **Advanced Lettuce** → Bulkhead Test

#### Phase 8: Monitoring
19. **Health & Metrics** → Health Check
20. **Health & Metrics** → Prometheus Metrics

### Collection Runner (Automated Testing)

1. Click "Run Collection" button
2. Select all tests
3. Set iterations: 10 (for rate limit testing)
4. Set delay: 100ms between requests
5. Click "Run Redis POC"
6. Review results dashboard

### Expected Results

| Test Category | Success Rate | Avg Response Time |
|---------------|--------------|-------------------|
| Authentication | 100% | <200ms |
| Product CRUD (cached) | 100% | <100ms |
| Product CRUD (uncached) | 100% | <3000ms |
| Hybrid Cache | 100% | <50ms (L1), <200ms (L2) |
| Distributed Locks | 100% | <100ms |
| Rate Limiting | 50% (by design) | <50ms |
| Geospatial | 100% | <200ms |
| Leaderboard | 100% | <100ms |
| Pub/Sub | 100% | <100ms |
| Streams | 100% | <100ms |
| Resilience | 100% | <500ms |
| Monitoring | 100% | <100ms |

---

## Performance Testing

### Using Apache Bench (ab)

```bash
# Test product retrieval (cached)
ab -n 10000 -c 100 http://localhost:8080/api/products/prod-1

# Expected Results:
# - Requests per second: 5000-10000 (cached)
# - Mean response time: 10-20ms
# - 99th percentile: <50ms
```

### Using wrk (recommended for high load)

```bash
# Install wrk
brew install wrk  # macOS
apt-get install wrk  # Ubuntu

# Test hybrid cache L1 performance
wrk -t12 -c400 -d30s http://localhost:8080/api/cache/hybrid/get?key=test-key

# Expected Results (L1 cache):
# - Requests/sec: 50,000-100,000
# - Latency p50: <1ms
# - Latency p99: <10ms
```

### Load Testing Script (Gradual Ramp-Up)

```bash
#!/bin/bash
# load-test.sh - Progressive load testing

echo "Starting Progressive Load Test..."

for concurrency in 10 50 100 200 500 1000; do
  echo "Testing with $concurrency concurrent users..."
  ab -n 10000 -c $concurrency http://localhost:8080/api/products/prod-1 > results-$concurrency.txt
  sleep 5
done

echo "Load test complete. Check results-*.txt files."
```

---

## Security Testing

### 1. JWT Token Validation

```bash
# Test with invalid token
curl -H "Authorization: Bearer invalid-token" \
  http://localhost:8080/api/products
# Expected: 401 Unauthorized

# Test with expired token (after 1 hour for local profile)
# Expected: 401 Unauthorized

# Test without token
curl http://localhost:8080/api/products
# Expected: 401 Unauthorized (if endpoint requires auth)
```

### 2. Rate Limiting Bypass Attempts

```bash
# Rapid-fire requests to trigger rate limiter
for i in {1..100}; do
  curl http://localhost:8080/api/ratelimit/test
done
# Expected: First 10-20 succeed, rest return 429
```

### 3. SQL Injection Prevention

```bash
# Attempt SQL injection in product name
curl -X POST http://localhost:8080/api/products \
  -H "Content-Type: application/json" \
  -d '{"id": "prod-sql", "name": "Test'; DROP TABLE products;--", "price": 100}'
# Expected: Product created safely (no SQL injection)
```

---

## Concurrency Testing

### 1. Distributed Lock Concurrency

Run this script in **multiple terminal windows** simultaneously:

```bash
# terminal-1.sh, terminal-2.sh, terminal-3.sh
#!/bin/bash
for i in {1..100}; do
  TOKEN=$(curl -X POST http://localhost:8080/api/lock/acquire \
    -H "Content-Type: application/json" \
    -d '{"lockKey": "shared-resource", "ttl": 10}' \
    -s | jq -r '.token')
  
  if [ "$TOKEN" != "null" ]; then
    echo "Terminal $1: Lock acquired at $(date)"
    sleep 2  # Simulate work
    curl -X POST http://localhost:8080/api/lock/release \
      -H "Content-Type: application/json" \
      -d "{\"lockKey\": \"shared-resource\", \"token\": \"$TOKEN\"}" \
      -s
    echo "Terminal $1: Lock released"
  else
    echo "Terminal $1: Lock failed (held by another process)"
  fi
  sleep 0.5
done
```

Expected: Only ONE terminal can hold the lock at a time.

### 2. Cache Consistency Across Instances

Start **2 application instances** on different ports:

```bash
# Terminal 1
./gradlew bootRun --args='--spring.profiles.active=local --server.port=8080'

# Terminal 2
./gradlew bootRun --args='--spring.profiles.active=local --server.port=8081'
```

Test cache invalidation:

```bash
# Update product via instance 1
curl -X PUT http://localhost:8080/api/products/prod-1 \
  -H "Content-Type: application/json" \
  -d '{"id": "prod-1", "name": "Updated Product", "price": 200}'

# Verify cache cleared on instance 2 (should fetch new data)
curl http://localhost:8081/api/products/prod-1
# Expected: Returns "Updated Product" with price 200
```

### 3. Redis Streams Concurrent Consumers

Streams consumer is running in background (see `StreamConsumer.java`).

Publish 1000 events rapidly:

```bash
for i in {1..1000}; do
  curl -X POST http://localhost:8080/api/stream/publish \
    -H "Content-Type: application/json" \
    -d "{\"orderId\": \"order-$i\", \"amount\": $(($i * 10))}" \
    -s &
done
wait

# Check application logs
tail -f logs/local-redis-poc.log | grep "Processing message"
# Expected: All 1000 messages processed (check for idempotency)
```

---

## Troubleshooting

### Issue: Embedded Redis Failed to Start (Local Profile)

**Error**: `Port 6370 already in use`

**Solutions**:
1. Kill existing process:
   ```bash
   lsof -ti:6370 | xargs kill -9
   ```

2. Change port in `application-local.yml`:
   ```yaml
   spring:
     data:
       redis:
         port: 6371  # Use different port
   ```

### Issue: Rate Limiting Not Working

**Symptoms**: All requests return 200, never 429

**Solutions**:
1. Check rate limit configuration in `application-local.yml`
2. Verify path matches: `/api/ratelimit/**`
3. Check logs for interceptor registration
4. Ensure Redis is running and connected

### Issue: Cache HIT Never Happens

**Symptoms**: Every request takes 2+ seconds (database fetch)

**Solutions**:
1. Verify cache is enabled:
   ```bash
   curl http://localhost:8080/actuator/caches
   ```

2. Check cache configuration:
   ```yaml
   spring:
     cache:
       type: redis
   ```

3. Verify Redis connection:
   ```bash
   redis-cli -p 6370 KEYS "product*"
   ```

### Issue: High Memory Usage

**Symptoms**: Application uses >1GB RAM on local profile

**Solutions**:
1. Reduce Caffeine cache size in `HybridCacheService`:
   ```java
   .maximumSize(5_000)  // Reduced from 10_000
   ```

2. Reduce thread pool sizes in `application-local.yml`:
   ```yaml
   spring:
     task:
       execution:
         pool:
           max-size: 2  # Reduced from 4
   ```

3. Disable features:
   - Set `management.tracing.enabled: false`
   - Set `eventstoredb.enabled: false`

### Issue: Tests Fail with 401 Unauthorized

**Cause**: JWT token expired or not set

**Solution**:
1. Re-run "Login & Get JWT" in Postman
2. Verify token is saved to environment variable:
   ```
   Environment: jwt_token = eyJhbGc...
   ```
3. Check Authorization header is set in collection settings

---

## Advanced Testing Scenarios

### Scenario 1: Simulate Redis Failover

```bash
# Stop Redis mid-operation
redis-cli -p 6370 SHUTDOWN NOSAVE

# Application should:
# - Log connection errors
# - Fall back to database/local cache (graceful degradation)
# - Auto-reconnect when Redis comes back up

# Restart Redis
redis-server --port 6370 &

# Verify auto-reconnect
curl http://localhost:8080/actuator/health
# Expected: {"status":"UP"}
```

### Scenario 2: Test Optimistic Locking

```bash
# Terminal 1 & Terminal 2: Run simultaneously
#!/bin/bash
for i in {1..50}; do
  curl -X POST http://localhost:8080/api/cache/update-with-lock \
    -H "Content-Type: application/json" \
    -d '{"key": "counter", "value": '$i'}' \
    -s
done
```

Expected: Some requests fail with "ConcurrentModificationException" (by design).
Final value should be consistent (no data corruption).

### Scenario 3: Measure Cache Hit Ratio

```bash
# Generate traffic
ab -n 10000 -c 100 http://localhost:8080/api/products/prod-1

# Check Caffeine stats
curl http://localhost:8080/api/cache/hybrid/stats

# Expected:
# - Hit ratio: >95% (after warmup)
# - Misses: <500 (cold start + evictions)
```

---

## Performance Benchmarks (Reference)

| Operation | Local (8GB laptop) | Dev (16GB) | Prod (32GB, cluster) |
|-----------|-------------------|------------|----------------------|
| L1 Cache GET | 0.5ms | 0.3ms | 0.2ms |
| L2 Cache GET | 2ms | 1.5ms | 1ms |
| Database GET (uncached) | 50-100ms | 30-50ms | 10-30ms (tuned PostgreSQL) |
| Lock Acquire | 5ms | 3ms | 2ms |
| Pub/Sub Publish | 3ms | 2ms | 1ms |
| Stream Add | 5ms | 3ms | 2ms |
| Geo Query (<10km) | 10ms | 5ms | 3ms |
| Leaderboard Top 10 | 5ms | 3ms | 2ms |

**Note**: These are approximate values. Actual performance varies by hardware, load, and configuration.

---

## Next Steps

1. **Run Full Test Suite**:
   ```bash
   ./gradlew test
   ```

2. **Generate Test Coverage Report**:
   ```bash
   ./gradlew jacocoTestReport
   open build/reports/jacoco/test/html/index.html
   ```

3. **Load Test in Staging**:
   - Deploy to staging environment
   - Run `load-test.sh` with 10x traffic
   - Monitor with Grafana dashboards

4. **Security Audit**:
   - Run OWASP Dependency Check
   - Perform penetration testing
   - Review JWT token lifecycle

---

## GraphQL vs REST - When to Use What

### Use REST (Current Implementation)
✅ **Best For:**
- CRUD operations (Create, Read, Update, Delete)
- Simple resource-based APIs
- Caching at HTTP level (ETags, Cache-Control)
- Public APIs with broad compatibility
- When clients need full resources

✅ **Advantages:**
- Better caching support (HTTP-level)
- Simpler for simple use cases
- Well-understood by all developers
- Excellent tooling (Postman, Swagger)

### Use GraphQL
✅ **Best For:**
- Complex data fetching (nested resources)
- Mobile apps (reduce bandwidth)
- Microservices aggregation
- When clients need partial data

✅ **When to Migrate:**
- Under-fetching/over-fetching problems
- Mobile apps need smaller payloads
- Complex filtering/sorting requirements
- Real-time subscriptions needed

**Recommendation for Redis POC:**
- **Keep REST** for now (caching works great with REST)
- **Consider GraphQL** if:
  - Adding mobile app frontend
  - Clients need complex nested queries
  - Real-time features become primary focus

---

## Support

For issues or questions:
1. Check logs: `tail -f logs/local-redis-poc.log`
2. Review health endpoint: `http://localhost:8080/actuator/health`
3. Check Redis: `redis-cli -p 6370 PING`
4. Review this guide's [Troubleshooting](#troubleshooting) section
