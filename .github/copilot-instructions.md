# Redis POC - AI Coding Agent Instructions

## Project Overview

This is a comprehensive Spring Boot 3.2.2 application demonstrating advanced Redis patterns and capabilities. The project showcases production-grade implementations of caching, pub/sub messaging, streams, distributed locks, geospatial operations, leaderboards, and rate limiting.

## Architecture & Technology Stack

- **Framework**: Spring Boot 3.2.2 with Java 17
- **Build Tool**: Gradle with Spotless code formatting
- **Redis Client**: Dual approach - RedisTemplate (Spring integration) + Direct Lettuce (high performance)
- **Fault Tolerance**: Resilience4j (Circuit Breaker, Retry, Bulkhead, TimeLimiter)
- **Observability**: Micrometer with Prometheus and distributed tracing
- **Security**: JWT-based authentication with custom validation annotations
- **Database**: H2 (dev) / PostgreSQL (prod) with Spring Data JPA
- **Documentation**: Comprehensive JavaDoc and architectural documentation

## Core Patterns & Conventions

### 1. Configuration Layer

#### Redis Configuration Patterns
- **EnhancedLettuceConfig**: Production-grade Lettuce client with connection pooling, custom timeouts, and resilience
- **RedisConfig**: Unified RedisTemplate configuration supporting read/write separation
- **Profile-based Configuration**: Use `@Profile` annotations for environment-specific configs
- **Custom ObjectMapper**: Always configure with JavaTimeModule and polymorphic type validation restricted to project package

#### Key Configuration Principles
```java
@Configuration
@Profile("dev")  // Use profiles for environment-specific configs
public class EnhancedLettuceConfig {
    // 1. Connection pooling with Apache Commons Pool2
    // 2. Custom timeouts and socket options
    // 3. Auto-reconnect and fail-fast behavior
    // 4. RESP3 protocol for latest Redis features
}
```

### 2. Service Layer Patterns

#### Dual Redis Approach
- **RedisTemplate**: For Spring integration, caching, and standard operations
- **Direct Lettuce**: For high-performance scenarios requiring fine-grained control

#### Resilience & Observability Annotations
```java
@Service
@Slf4j
public class AdvancedLettuceClientService {
    // Always use Resilience4j annotations for fault tolerance
    @CircuitBreaker(name = "redis-resilience")
    @Retry(name = "redis-resilience")
    @Bulkhead(name = "redis-resilience")
    @TimeLimiter(name = "redis-resilience")
    
    // Always add observability
    @Timed(value = "redis.operation", percentiles = {0.5, 0.95, 0.99})
    @NewSpan("redis-operation")
    public CompletableFuture<String> asyncOperation(@SpanTag("key") String key) {
        // Implementation
    }
}
```

#### Async Operations
- Use `@Async` for non-blocking operations
- Return `CompletableFuture<T>` for async methods
- Always handle exceptions properly in async contexts

### 3. Controller Layer Patterns

#### REST Controller Standards
```java
@RestController
@RequestMapping("/api/{resource}")
public class ResourceController {
    
    private final ResourceService resourceService;
    
    // Constructor injection only
    public ResourceController(ResourceService resourceService) {
        this.resourceService = resourceService;
    }
    
    // Always use DTOs for API responses
    @GetMapping("/{id}")
    public ResponseEntity<ResourceDto> getResource(@PathVariable String id) {
        Resource resource = resourceService.getResource(id);
        return resource != null ? 
            ResponseEntity.ok(toDto(resource)) : 
            ResponseEntity.notFound().build();
    }
}
```

#### DTO Pattern
- Always create separate DTOs for API contracts
- Use `toDto()` methods in controllers for entity-to-DTO conversion
- Keep internal entities decoupled from API contracts

### 4. Data Layer Patterns

#### Domain Modeling
```java
@Entity
@Table(name = "products")
public class Product {
    @Id
    private String id;
    
    @Column(nullable = false)
    private String name;
    
    // Use appropriate JPA annotations
    // Include validation annotations
}
```

#### Repository Pattern
- Extend appropriate Spring Data interfaces
- Use custom query methods with `@Query` when needed
- Prefer derived query methods for simple operations

### 5. Error Handling & Logging

#### Logging Standards
```java
@Slf4j
public class ServiceClass {
    public void businessMethod(String param) {
        log.debug("Processing request with param: {}", param);
        try {
            // Business logic
            log.info("Successfully processed request");
        } catch (Exception e) {
            log.error("Failed to process request: {}", e.getMessage(), e);
            throw e;
        }
    }
}
```

#### Exception Handling
- Use custom exceptions for business logic errors
- Global exception handler with `@ControllerAdvice`
- Proper HTTP status code mapping

### 6. Testing Patterns

#### Unit Test Structure
```java
@SpringBootTest
class ServiceTest {
    
    @Autowired
    private ServiceClass service;
    
    @Test
    void testBusinessLogic() {
        // Given
        // When
        // Then
    }
}
```

#### Integration Test Structure
```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ControllerIntegrationTest {
    
    @Autowired
    private TestRestTemplate restTemplate;
    
    @Test
    void testApiEndpoint() {
        // Test full HTTP request/response cycle
    }
}
```

## Code Quality Standards

### 1. Documentation
- **JavaDoc**: Every public method/class must have comprehensive JavaDoc
- **Inline Comments**: Explain complex business logic and non-obvious decisions
- **README Updates**: Update README.md when adding new features

### 2. Code Formatting
- **Spotless**: Automatically applied via Gradle build
- **Palantir Java Format**: Consistent formatting across the codebase
- **Import Order**: Enforced by Spotless

### 3. Naming Conventions
- **Classes**: PascalCase, descriptive names
- **Methods**: camelCase, action-oriented names
- **Variables**: camelCase, descriptive names
- **Constants**: UPPER_SNAKE_CASE

### 4. Package Structure
```
com.redis.poc
├── config/          # Configuration classes
├── controller/      # REST controllers
├── domain/          # JPA entities and DTOs
├── repository/      # Data access layer
├── service/         # Business logic layer
├── pubsub/          # Pub/Sub related classes
├── ratelimit/       # Rate limiting components
├── streams/         # Redis Streams components
└── security/        # Security configuration
```

## Redis-Specific Patterns

### 1. Key Naming Strategy
```java
public class RedisKeys {
    public static final String PRODUCT_KEY = "product:%s";
    public static final String USER_SESSION = "session:user:%s";
    public static final String CACHE_PRODUCTS = "cache:products:recent";
    
    // Use consistent patterns
    public static String productKey(String id) {
        return String.format(PRODUCT_KEY, id);
    }
}
```

### 2. Serialization Strategy
- Use GenericJackson2JsonRedisSerializer with custom ObjectMapper
- Enable polymorphic typing for complex objects
- Restrict type validation to project package for security

### 3. Connection Management
- Prefer pooled connections over shared connections
- Use lazy initialization for connection factories
- Always configure proper timeouts and retry strategies

## Build & Deployment

### Gradle Commands
```bash
./gradlew build              # Build with Spotless formatting
./gradlew test               # Run tests
./gradlew bootRun            # Run with dev profile
./gradlew spotlessApply      # Apply code formatting
```

### Profiles
- **dev**: Development with H2 database and enhanced Lettuce config
- **prod**: Production with PostgreSQL and optimized settings

## Security Considerations

### 1. Input Validation
- Use Bean Validation annotations (@NotNull, @Size, etc.)
- Custom validation annotations for business rules
- Sanitize all user inputs

### 2. Redis Security
- Restrict polymorphic type validation to project package
- Use appropriate key expiration strategies
- Implement proper access controls

## Performance Optimization

### 1. Caching Strategy
- Use @Cacheable for method-level caching
- Configure appropriate TTL values per cache
- Monitor cache hit/miss ratios

### 2. Connection Pool Tuning
- Configure pool size based on expected load
- Set appropriate timeouts and retry policies
- Monitor connection pool metrics

### 3. Async Operations
- Use async methods for I/O operations
- Implement proper error handling in async contexts
- Monitor async operation performance

## Monitoring & Observability

### 1. Metrics
- Use @Timed for method-level metrics
- Custom metrics for business KPIs
- Prometheus integration for monitoring

### 2. Tracing
- Use @NewSpan for distributed tracing
- Add @SpanTag for important parameters
- Monitor service dependencies

## Common Pitfalls to Avoid

1. **Don't** use StatefulRedisConnection as a bean (connection pool antipattern)
2. **Don't** mix serialization strategies across the application
3. **Don't** forget to configure proper connection pooling
4. **Don't** implement business logic in controllers
5. **Don't** skip input validation
6. **Don't** use shared connections in high-concurrency scenarios
7. **Don't** forget to handle Redis connection failures gracefully
8. **Don't** implement custom caching without proper TTL strategies

## Feature Implementation Checklist

When implementing new Redis features:

1. [ ] Create appropriate service class(es)
2. [ ] Add controller with proper REST endpoints
3. [ ] Implement comprehensive error handling
4. [ ] Add Resilience4j annotations for fault tolerance
5. [ ] Add observability annotations (@Timed, @NewSpan)
6. [ ] Create DTOs for API contracts
7. [ ] Add comprehensive JavaDoc documentation
8. [ ] Update README.md with new feature description
9. [ ] Add unit and integration tests
10. [ ] Test with both RedisTemplate and Direct Lettuce approaches where applicable

## Getting Help

- Refer to existing implementations in the codebase
- Check README.md for architectural decisions
- Review configuration classes for setup patterns
- Look at test classes for testing patterns
- Use the existing services as templates for new implementations</content>
<parameter name="filePath">e:\redis\RedisPOC\.github\copilot-instructions.md
