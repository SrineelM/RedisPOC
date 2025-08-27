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

The application showcases the following Redis capabilities through dedicated controllers and services:

- **Caching & Basic Operations**: Using `RedisTemplate` for simple key-value storage, retrieval, and deletion (via `RedisTemplateExampleController` and `RedisTemplateExampleService`).
- **Advanced Lettuce Client**: Direct Lettuce usage with resilience patterns (Circuit Breaker, Bulkhead, Time Limiter) via `AdvancedLettuceController` and `AdvancedLettuceClientService`.
- **Pub/Sub Messaging**: Publish/subscribe with event listeners for real-time messaging (via `PubSubController`, `RedisMessagePublisher`, `RedisMessageSubscriber`, and `KeyExpiredListener`).
- **Streams**: Redis Streams for event processing with consumer groups and Dead Letter Queue (DLQ) support (via `StreamController`, `OrderEventProducer`, and `StreamConsumer`).
- **Distributed Locks**: Redlock algorithm for safe distributed synchronization (via `LockController` and `DistributedLockService`).
- **Rate Limiting**: Token bucket implementation using Lua scripts and interceptors (via `EnhancedRateLimitInterceptor` and `rate-limit.lua`).
- **Geospatial Queries**: Location-based operations using Redis geospatial data structures (via `GeospatialController` and `GeospatialService`).
- **Leaderboards**: Sorted sets for ranking and scoring systems (via `LeaderboardController` and `LeaderboardService`).
- **CQRS & Event Sourcing**: Command Query Responsibility Segregation with event sourcing for the Product domain, supporting Redis List, Stream, and optional EventStoreDB backends (via `ProductCommandService`, `ProductQueryService`, `ProductEventSourcingService`, and related event classes).
- **Batch Operations**: Efficient bulk Redis operations for performance optimization (via `BatchRedisOperationsService`).
- **Security**: JWT-based authentication with filters and services (via `JwtAuthenticationFilter`, `JwtService`, and `AuthController`).
- **Health & Monitoring**: Comprehensive health checks and custom metrics collection (via `RedisHealthIndicator` and `RedisMetricsCollector`).
- **Validation**: Custom annotations and validators for Redis key validation (via `RedisKeyValidator` and `ValidRedisKey`).
- **Auditing**: Structured audit logging for security and performance events (via `AuditLogger`).
- **Exception Handling**: Global exception handling with custom error responses (via `GlobalExceptionHandler` and `ErrorResponse`).

## Environment Setup & Running the Application

### Prerequisites
- Java 17 or higher
- Gradle 7.0+ (or use the included Gradle wrapper)
- Redis 6.0+ running locally on `localhost:6379` (or configure cluster nodes in `application.yml`)
- Optional: EventStoreDB for advanced event sourcing (running on `localhost:2113`)

### Setup Steps
1. **Clone the repository** (if applicable) and navigate to the project directory.
2. **Start Redis**: Ensure a Redis instance is running. For local development, use `redis-server` or Docker: `docker run -p 6379:6379 redis:alpine`.
3. **Optional: Start EventStoreDB**: If testing external event store features, run EventStoreDB locally or via Docker.
4. **Build the application**: Run `./gradlew clean build` to compile and resolve dependencies.
5. **Run the application**: Execute `./gradlew bootRun`. The app will start on `http://localhost:8080` by default.
   - Active profile defaults to `dev`; override with `SPRING_PROFILES_ACTIVE=prod` for production settings.
   - Check logs for any configuration issues, such as Redis connectivity.

### Configuration
- **Profiles**: Use `application-dev.yml` for development (with enhanced logging) and `application-prod.yml` for production (optimized settings).
- **Redis Settings**: Customize host, port, timeouts, and pooling in `application.yml`. Enable SSL/TLS and authentication for secure environments.
- **Security**: JWT secrets and other sensitive configs should be externalized using environment variables or secrets management in production.

## Testing the Features

You can use a tool like `curl` or Postman to test the API endpoints.

### RedisTemplate Example (Basic Operations)

*   **Set a value**: `curl -X POST -H "Content-Type: text/plain" -d "hello-world" "http://localhost:8080/api/template-example?key=my-simple-key"`
*   **Get the value**: `curl http://localhost:8080/api/template-example?key=my-simple-key`
*   **Delete the value**: `curl -X DELETE http://localhost:8080/api/template-example?key=my-simple-key`

### Caching Operations
*   **Set cache**: `curl -X POST -H "Content-Type: application/json" -d '{"key":"cache:key","value":"cached-value","ttl":300}' "http://localhost:8080/api/cache/set"`
*   **Get cache**: `curl "http://localhost:8080/api/cache/get?key=cache:key"`

### Pub/Sub Messaging
*   **Publish message**: `curl -X POST -H "Content-Type: application/json" -d '{"channel":"news","message":"Breaking news!"}' "http://localhost:8080/api/pubsub/publish"`
*   **Subscribe (polling example)**: `curl "http://localhost:8080/api/pubsub/subscribe?channel=news"`

### Streams
*   **Publish event**: `curl -X POST -H "Content-Type: application/json" -d '{"orderId":"123","amount":100.0}' "http://localhost:8080/api/stream/publish"`
*   **Consume events**: The consumer runs via a scheduled task; check logs or use WebSocket endpoints if implemented.

### Distributed Locks
*   **Acquire lock**: `curl -X POST -H "Content-Type: application/json" -d '{"lockKey":"resource:123","ttl":30}' "http://localhost:8080/api/lock/acquire"`
*   **Release lock**: `curl -X POST -H "Content-Type: application/json" -d '{"lockKey":"resource:123","token":"lock-token"}' "http://localhost:8080/api/lock/release"`

### Rate Limiting
*   **Test rate limit**: `curl "http://localhost:8080/api/ratelimit/test"` (repeated requests will be throttled based on the token bucket script).

### Leaderboard
*   **Add score**: `curl -X POST -H "Content-Type: application/json" -d '{"member":"user:123","score":100}' "http://localhost:8080/api/leaderboard/add"`
*   **Get top scores**: `curl "http://localhost:8080/api/leaderboard/top?count=10"`

### Geospatial
*   **Add location**: `curl -X POST -H "Content-Type: application/json" -d '{"key":"locations","member":"store:1","longitude":40.7128,"latitude":-74.0060}' "http://localhost:8080/api/geo/add"`
*   **Find nearby**: `curl "http://localhost:8080/api/geo/nearby?key=locations&longitude=40.7128&latitude=-74.0060&radius=10&unit=km"`

### Advanced Resilience (Lettuce Client)
*   **Circuit Breaker Test**: `curl "http://localhost:8080/api/advanced-lettuce/circuit-breaker?key=test"`
*   **Bulkhead Test**: `curl "http://localhost:8080/api/advanced-lettuce/bulkhead?key=test"`
*   **Time Limiter Test**: `curl "http://localhost:8080/api/advanced-lettuce/time-limiter?key=test"`

### CQRS & Event Sourcing
*   **Create Product**: `curl -X POST -H "Content-Type: application/json" -d '{"name":"Sample Product","price":50.0}' "http://localhost:8080/api/products/eventsourcing"`
*   **List Events**: `curl "http://localhost:8080/api/products/eventsourcing/events"`

### Security
*   **Login**: `curl -X POST -H "Content-Type: application/json" -d '{"username":"user","password":"pass"}' "http://localhost:8080/api/auth/login"` (returns JWT token for authenticated requests).

### Health & Metrics
*   **Health Check**: `curl "http://localhost:8080/actuator/health"`
*   **Metrics**: `curl "http://localhost:8080/actuator/metrics"`
*   **Prometheus**: `curl "http://localhost:8080/actuator/prometheus"`

---

## Acknowledgements

This project was enhanced by analyzing and incorporating best practices from reference implementations. Thanks to the examples provided by **DeepSeek** and **ChatGPT** which served as valuable points of comparison and inspiration.

# Redis POC - Developer Guide

## Purpose
A comprehensive Spring Boot (Java 17) application showcasing production-grade Redis usage: caching, pub/sub, streams (with DLQ), geospatial, leaderboard (sorted sets), distributed locking, rate limiting (Lua), read/write separation, CQRS + basic event sourcing (list + stream + optional EventStoreDB), resilience patterns (Resilience4j), security (JWT), monitoring (Micrometer/Prometheus), and observability.

## Key Modules
- **config**: Redis + Lettuce client tuning, security, read/write separation, cluster readiness (files: `EnhancedLettuceConfig.java`, `RedisClusterConfig.java`, `RedisConfig.java`, `RedisPubSubConfig.java`, `SecurityConfig.java`, `WebMvcConfig.java`).
- **security**: JWT auth filter + service (files: `JwtAuthenticationFilter.java`, `JwtService.java`).
- **controller**: REST endpoints for each Redis capability (files: `AdvancedLettuceController.java`, `AuthController.java`, `GeospatialController.java`, `LeaderboardController.java`, `LockController.java`, `ProductController.java`, `PubSubController.java`, `RedisTemplateExampleController.java`, `StreamController.java`).
- **service**: Business logic (advanced Lettuce usage, batching, locks, geospatial, leaderboard, streams) (files: `AdvancedLettuceClientService.java`, `BatchRedisOperationsService.java`, `DistributedLockService.java`, `EnhancedRedisService.java`, `GeospatialService.java`, `LeaderboardService.java`, `ProductService.java`, `RedisTemplateExampleService.java`).
- **cqrs**: Command/Query separation + event sourcing (Product domain) with Redis list & stream backends (files: `ProductCommandService.java`, `ProductEventSourcingController.java`, `ProductEventSourcingService.java`, `ProductQueryService.java`, and event classes in `cqrs/event/`).
- **streams**: Order events via Redis Streams + consumer group + DLQ (files: `OrderEvent.java`, `OrderEventProducer.java`, `StreamConsumer.java`).
- **monitoring**: Custom metrics collector (latency, cache hits/misses, memory usage placeholder) (files: `RedisMetricsCollector.java`).
- **validation**: Key validation utilities (files: `RedisKeyValidator.java`, `ValidRedisKey.java`).
- **ratelimit**: Interceptors (basic and enhanced) using token bucket Lua script (files: `EnhancedRateLimitInterceptor.java`, and `scripts/rate-limit.lua`).
- **pubsub**: Publisher/subscriber + key expiry listener (files: `ApplicationEventListener.java`, `KeyExpiredEvent.java`, `KeyExpiredListener.java`, `RedisMessagePublisher.java`, `RedisMessageSubscriber.java`).
- **audit**: Structured audit logger (files: `AuditLogger.java`).
- **domain**: Core entities and DTOs (files: `Product.java`, `ProductDto.java`).
- **exception**: Global exception handling (files: `ErrorResponse.java`, `GlobalExceptionHandler.java`).
- **health**: Redis health indicators (files: `RedisHealthIndicator.java`).
- **repository**: Data access layer (files: `ProductRepository.java`).

## Running Locally
Prereqs: Java 17+, Gradle, Redis (localhost:6379). Optional: EventStoreDB (localhost:2113).

Steps:
1. Start Redis.
2. (Optional) Start EventStoreDB if testing external event store.
3. Run: `./gradlew bootRun`
4. Active profile defaults to dev; override: `SPRING_PROFILES_ACTIVE=prod`

## Important Endpoints
- **Security**: `POST /api/auth/login`
- **Advanced Lettuce**: `/api/advanced-lettuce/*` (circuit-breaker, bulkhead, time-limiter)
- **Product CRUD**: `/api/products/*`
- **Event Sourcing**: `/api/products/eventsourcing` (create/update/delete/list)
- **Streams**: `/api/stream/publish`, `/api/stream/consume` (via scheduler/consumer)
- **Leaderboard**: `/api/leaderboard/*`
- **Geospatial**: `/api/geo/*`
- **Locks**: `/api/lock/*`
- **Rate Limiting**: `/api/ratelimit/test`
- **Actuator**: `/actuator/health`, `/actuator/metrics`, `/actuator/prometheus`

## Observability
Micrometer + Prometheus registry. Add Grafana dashboards in production. Tracing via Micrometer tracing bridge (Zipkin). Extend metrics with Redis exporter / OSS dashboards.

## Resilience Patterns
Resilience4j annotations over `AdvancedLettuceClientService`. Configurable in `application-*.yml`. Extend with TimeLimiter + Retry compositions or fallback strategies per operation.

## Event Sourcing Options (Experimental)
1. **Redis List (baseline)**: Simple append (implemented in `ProductEventSourcingService`).
2. **Redis Stream (enhanced)**: Ordered, replayable, consumer groups (integrated in CQRS services).
3. **EventStoreDB (optional)**: Requires adding its dependency; see `ProductEventStoreDB.java` for implementation.

## Production Hardening (Guidance & Experimental Flags)
- Enable TLS for Redis (`ssl.enabled=true`).
- Use AUTH/passwords managed via secrets.
- Add network policies / security groups.
- External log aggregation (ELK / OpenSearch).
- Snapshotting & AOF backups for persistence; off-site storage.
- Replace list-based events with stream or dedicated event store.
- Token revocation (per-jti keys + TTL namespace).
- Stream trimming (approx MAXLEN) & consumer lag gauge.

## Testing Strategy Suggestions
- Unit test services (mock `RedisTemplate` / Lettuce connection).
- Integration tests with Testcontainers (Redis + optionally EventStoreDB).
- Load tests for rate limiting & resilience features (Gatling / k6).

## Extending
- Add projections for event-sourced Product to a read model cache.
- Add snapshot interval logic (every N events).
- Add consumer group metrics & lag tracking.
- Implement security roles & fine-grained authorities.

## License
POC - internal educational use. Add explicit license if publishing.