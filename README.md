# Redis POC: A Java-Based Showcase of Redis Capabilities

This project is a comprehensive Proof-of-Concept (POC) application built with Spring Boot, Java 17, and Gradle. It demonstrates various powerful, production-grade features of Redis, including robust caching, decoupled pub/sub, reliable streams, safe distributed locks, and efficient rate limiting.

It also includes a special section on advanced, client-side resilience patterns using the Lettuce client directly with Resilience4j, and a simple example of using `RedisTemplate` for comparison.

## Architectural Comparison: RedisTemplate vs. Direct Lettuce Client

This project uses two different approaches to interact with Redis, each with its own trade-offs:

### `RedisTemplate` (The High-Level Abstraction)

This is Spring's default, general-purpose tool for Redis interactions. It provides a simplified, productive developer experience.

*   **Pros:**
    *   **Simplicity:** Very easy to use for common operations (`opsForValue()`, `opsForHash()`, etc.), significantly reducing boilerplate code.
    *   **Full Integration:** Seamlessly integrates with the Spring ecosystem, including transaction management.
    *   **Automatic Resource Management:** Automatically handles connection management from the underlying Lettuce connection pool.

*   **Cons:**
    *   **Hides Power:** The abstraction makes it harder to access the full, low-level power of the underlying Lettuce client.
    *   **Primarily Blocking:** The default API is synchronous. While it's built on the async Lettuce driver, building a fully non-blocking application requires using the separate `ReactiveRedisTemplate`.
    *   **Less Control:** It offers less fine-grained control over client-side behavior like command-specific timeouts or advanced retry strategies.

**Conclusion:** Use `RedisTemplate` for the vast majority of standard Spring applications. It is the pragmatic and productive choice for most use cases.

### Direct Lettuce Client (The Low-Level Power Tool)

This approach involves bypassing `RedisTemplate` and using the Lettuce client directly, as demonstrated in the `AdvancedLettuceClientService`.

*   **Pros:**
    *   **Full Control:** You have access to every feature the Lettuce client offers, allowing for precise configuration of timeouts, retries, and other options.
    *   **Asynchronous by Nature:** Lettuce is a non-blocking, async client at its core, making it the ideal choice for highly scalable, reactive applications.
    *   **Advanced Resilience:** It is the perfect layer for implementing advanced patterns like Circuit Breakers, Bulkheads, and TimeLimiters.

*   **Cons:**
    *   **Complexity & Verbosity:** It is significantly more verbose and requires more manual configuration.
    *   **Manual Resource Management:** You are more responsible for the lifecycle of clients and connections.

**Conclusion:** Use a direct Lettuce implementation when you need to build a high-throughput, non-blocking/reactive application or when you require fine-grained control to implement advanced, client-side resilience patterns.

---

## Features Implemented

(Feature list remains the same...)

## Environment Setup & Running the Application

(Instructions remain the same...)

## Testing the Features

You can use a tool like `curl` or Postman to test the API endpoints.

### RedisTemplate Example (Basic Operations)

*   **Set a value**: `curl -X POST -H "Content-Type: text/plain" -d "hello-world" "http://localhost:8080/api/template-example?key=my-simple-key"`
*   **Get the value**: `curl http://localhost:8080/api/template-example?key=my-simple-key`
*   **Delete the value**: `curl -X DELETE http://localhost:8080/api/template-example?key=my-simple-key`

(Instructions for Caching, Pub/Sub, Streams, Locks, Rate Limiting, Leaderboard, Geospatial, and Advanced Resilience remain the same...)

---

## Acknowledgements

This project was enhanced by analyzing and incorporating best practices from reference implementations. Thanks to the examples provided by **DeepSeek** and **ChatGPT** which served as valuable points of comparison and inspiration.

# Redis POC - Developer Guide

## Purpose
A comprehensive Spring Boot (Java 17) application showcasing production-grade Redis usage: caching, pub/sub, streams (with DLQ), geospatial, leaderboard (sorted sets), distributed locking, rate limiting (Lua), read/write separation, CQRS + basic event sourcing (list + stream + optional EventStoreDB), resilience patterns (Resilience4j), security (JWT), monitoring (Micrometer/Prometheus), and observability.

## Key Modules
- config: Redis + Lettuce client tuning, security, read/write separation, cluster readiness.
- security: JWT auth filter + service.
- controller: REST endpoints for each Redis capability.
- service: Business logic (advanced Lettuce usage, batching, locks, geospatial, leaderboard, streams).
- cqrs: Command/Query separation + event sourcing (Product domain) with Redis list & stream backends.
- streams: Order events via Redis Streams + consumer group + DLQ.
- monitoring: Custom metrics collector (latency, cache hits/misses, memory usage placeholder).
- validation: Key validation utilities.
- ratelimit: Interceptors (basic and enhanced) using token bucket Lua script.
- pubsub: Publisher/subscriber + key expiry listener.
- audit: Structured audit logger.

## Running Locally
Prereqs: Java 17+, Gradle, Redis (localhost:6379). Optional: EventStoreDB (localhost:2113).

Steps:
1. Start Redis.
2. (Optional) Start EventStoreDB if testing external event store.
3. Run: ./gradlew bootRun
4. Active profile defaults to dev; override: SPRING_PROFILES_ACTIVE=prod

## Important Endpoints
- Security: POST /api/auth/login
- Advanced Lettuce: /api/advanced-lettuce/* (circuit-breaker, bulkhead, time-limiter)
- Product CRUD: /api/products/*
- Event Sourcing: /api/products/eventsourcing (create/update/delete/list)
- Streams: /api/stream/publish , /api/stream/consume (via scheduler/consumer)
- Leaderboard: /api/leaderboard/*
- Geospatial: /api/geo/*
- Locks: /api/lock/*
- Rate Limiting: /api/ratelimit/test
- Actuator: /actuator/health , /actuator/metrics , /actuator/prometheus

## Observability
Micrometer + Prometheus registry. Add Grafana dashboards in production. Tracing via Micrometer tracing bridge (Zipkin). Extend metrics with Redis exporter / OSS dashboards.

## Resilience Patterns
Resilience4j annotations over AdvancedLettuceClientService. Configurable in application-*.yml. Extend with TimeLimiter + Retry compositions or fallback strategies per operation.

## Event Sourcing Options (Experimental)
1. Redis List (baseline) - simple append.
2. Redis Stream (enhanced) - ordered, replayable, consumer groups.
3. EventStoreDB (optional) - requires adding its dependency; see ProductEventStoreDB.

## Production Hardening (Guidance & Experimental Flags)
- Enable TLS for Redis (ssl.enabled=true).
- Use AUTH/passwords managed via secrets.
- Add network policies / security groups.
- External log aggregation (ELK / OpenSearch).
- Snapshotting & AOF backups for persistence; off-site storage.
- Replace list-based events with stream or dedicated event store.
 - Token revocation (per-jti keys + TTL namespace).
 - Stream trimming (approx MAXLEN) & consumer lag gauge.

## Testing Strategy Suggestions
- Unit test services (mock RedisTemplate / Lettuce connection).
- Integration tests with Testcontainers (Redis + optionally EventStoreDB).
- Load tests for rate limiting & resilience features (Gatling / k6).

## Extending
- Add projections for event-sourced Product to a read model cache.
- Add snapshot interval logic (every N events).
- Add consumer group metrics & lag tracking.
- Implement security roles & fine-grained authorities.

## License
POC - internal educational use. Add explicit license if publishing.

