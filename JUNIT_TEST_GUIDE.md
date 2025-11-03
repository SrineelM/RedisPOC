# JUnit Test Suite - Redis POC Project

## Overview

This document provides a comprehensive guide to the JUnit and Mockito test suite for the Redis POC project. The test suite demonstrates enterprise-grade testing practices covering unit tests, integration tests, concurrency tests, and performance benchmarks.

## Test Structure

```
src/test/java/com/redis/poc/
├── service/
│   ├── ProductServiceTest.java                    # Unit tests with Mockito
│   ├── ProductServiceIntegrationTest.java         # Integration tests with Testcontainers
│   ├── HybridCacheServiceTest.java                # Hybrid caching tests
│   ├── DistributedLockServiceTest.java            # Distributed lock tests
│   ├── CacheSyncServiceTest.java                  # Cache synchronization tests
│   └── GeospatialServiceTest.java                 # Geospatial operations tests
├── controller/
│   ├── ProductControllerTest.java                 # REST controller tests
│   └── PubSubControllerTest.java                  # Pub/Sub controller tests
└── integration/
    ├── ConcurrencyIntegrationTest.java            # Thread safety tests
    └── PerformanceIntegrationTest.java            # Performance benchmarks
```

## Running Tests

### Run All Tests
```bash
./gradlew test
```

### Run Specific Test Class
```bash
./gradlew test --tests ProductServiceTest
./gradlew test --tests ProductServiceIntegrationTest
```

### Run with Verbose Output
```bash
./gradlew test --info
./gradlew test --debug  # For debugging test failures
```

### Run Tests in Specific Package
```bash
./gradlew test --tests "com.redis.poc.service.*"
./gradlew test --tests "com.redis.poc.controller.*"
```

### Generate Test Report
```bash
./gradlew test
# Report available at: build/reports/tests/test/index.html
open build/reports/tests/test/index.html
```

### Run Tests with Coverage (JaCoCo)
```bash
./gradlew test jacocoTestReport
# Coverage report at: build/reports/jacoco/test/html/index.html
open build/reports/jacoco/test/html/index.html
```

## Test Categories

### 1. Unit Tests (Mockito)

**Purpose**: Test individual units in isolation with mocked dependencies

**Example**: `ProductServiceTest.java`

```java
@ExtendWith(MockitoExtension.class)
class ProductServiceTest {
    
    @Mock
    private ProductRepository productRepository;
    
    @InjectMocks
    private ProductService productService;
    
    @Test
    void givenExistingProduct_whenGetById_thenReturnProduct() {
        // Given
        when(productRepository.findById("prod-1"))
            .thenReturn(Optional.of(testProduct));
        
        // When
        Product result = productService.getProductById("prod-1");
        
        // Then
        assertThat(result).isNotNull();
        verify(productRepository, times(1)).findById("prod-1");
    }
}
```

**Key Features**:
- ✅ Fast execution (milliseconds)
- ✅ No external dependencies
- ✅ Tests business logic in isolation
- ✅ Verifies mock interactions

**Use Cases**:
- Service layer business logic
- Data transformations
- Error handling
- Input validation

### 2. Integration Tests (Testcontainers)

**Purpose**: Test with real dependencies using Docker containers

**Example**: `ProductServiceIntegrationTest.java`

```java
@SpringBootTest
@Testcontainers
class ProductServiceIntegrationTest {
    
    @Container
    private static final GenericContainer<?> redisContainer = 
        new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);
    
    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redisContainer::getHost);
        registry.add("spring.data.redis.port", 
            () -> redisContainer.getMappedPort(6379));
    }
    
    @Test
    void givenProductInDatabase_whenAccessedTwice_thenSecondAccessFromCache() {
        // Given
        productRepository.save(testProduct);
        
        // When - First access (database hit)
        Product firstAccess = productService.getProductById(testProduct.getId());
        
        // Clear database to verify cache is used
        productRepository.deleteAll();
        
        // When - Second access (cache hit)
        Product secondAccess = productService.getProductById(testProduct.getId());
        
        // Then
        assertThat(secondAccess).isNotNull();
        assertThat(secondAccess.getId()).isEqualTo(testProduct.getId());
    }
}
```

**Key Features**:
- ✅ Real Redis container
- ✅ Actual caching behavior
- ✅ Real concurrency patterns
- ✅ Production-like scenarios

**Prerequisites**:
- Docker installed and running
- Docker daemon accessible
- Internet connection (first run downloads images)

**Use Cases**:
- Cache hit/miss scenarios
- Data consistency checks
- Concurrent access patterns
- Performance benchmarks

### 3. Concurrency Tests

**Purpose**: Verify thread safety and concurrent access handling

**Example**: Concurrent read/write test

```java
@Test
void givenProduct_whenConcurrentReads_thenAllSucceed() throws InterruptedException {
    // Given
    productRepository.save(testProduct);
    
    int threadCount = 10;
    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicInteger successCount = new AtomicInteger(0);
    
    // When - Multiple threads read concurrently
    for (int i = 0; i < threadCount; i++) {
        executor.submit(() -> {
            try {
                for (int j = 0; j < 100; j++) {
                    Product product = productService.getProductById(testProduct.getId());
                    if (product != null) {
                        successCount.incrementAndGet();
                    }
                }
            } finally {
                latch.countDown();
            }
        });
    }
    
    // Then
    assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
    assertThat(successCount.get()).isEqualTo(1000); // 10 threads * 100 iterations
}
```

**Test Scenarios**:
- ✅ Concurrent reads
- ✅ Concurrent writes
- ✅ Read-write conflicts
- ✅ Optimistic locking
- ✅ Distributed lock acquisition

### 4. Performance Tests

**Purpose**: Measure and validate performance characteristics

**Example**: Cache vs Database performance

```java
@Test
void givenProduct_whenComparePerformance_thenCacheFasterThanDatabase() {
    // Given
    productRepository.save(testProduct);
    
    // Measure database access
    long dbStart = System.nanoTime();
    productService.getProductById(testProduct.getId());
    long dbDuration = System.nanoTime() - dbStart;
    
    // Measure cache access
    long cacheStart = System.nanoTime();
    productService.getProductById(testProduct.getId());
    long cacheDuration = System.nanoTime() - cacheStart;
    
    // Then - Cache should be faster
    System.out.println("Database: " + dbDuration/1000 + " μs");
    System.out.println("Cache: " + cacheDuration/1000 + " μs");
    
    assertThat(cacheDuration).isLessThan(dbDuration);
}
```

**Performance Metrics**:
- Cache hit latency: < 1ms (Redis), < 1μs (Caffeine L1)
- Database query latency: 10-100ms
- Bulk operations throughput
- Concurrent request handling

## Test Data Setup

### Using @BeforeEach
```java
@BeforeEach
void setUp() {
    // Clear database and cache
    productRepository.deleteAll();
    cacheManager.getCacheNames().forEach(name -> 
        cacheManager.getCache(name).clear()
    );
    
    // Create test fixtures
    testProduct = new Product();
    testProduct.setId("test-prod-1");
    testProduct.setName("Test Product");
}
```

### Test Data Builders
```java
private Product createProduct(String id, String name) {
    Product product = new Product();
    product.setId(id);
    product.setName(name);
    product.setDescription("Test description for " + name);
    return product;
}

private List<Product> createProducts(int count) {
    return IntStream.range(0, count)
        .mapToObj(i -> createProduct("prod-" + i, "Product " + i))
        .toList();
}
```

## Assertion Patterns

### AssertJ - Fluent Assertions
```java
// Basic assertions
assertThat(result).isNotNull();
assertThat(result.getId()).isEqualTo("prod-1");
assertThat(result.getName()).contains("Product");

// Collection assertions
assertThat(products).hasSize(10);
assertThat(products).containsExactly(product1, product2);
assertThat(products).extracting("name")
    .contains("Product 1", "Product 2");

// Exception assertions
assertThatThrownBy(() -> service.invalid())
    .isInstanceOf(IllegalArgumentException.class)
    .hasMessageContaining("Invalid");
```

### Mockito - Verification
```java
// Verify method called
verify(repository, times(1)).findById("prod-1");
verify(repository, never()).deleteById(anyString());
verify(repository, atLeast(1)).save(any(Product.class));

// Verify with argument capture
ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
verify(repository).save(captor.capture());
assertThat(captor.getValue().getName()).isEqualTo("Expected Name");
```

## Test Profiles

### Local Profile Tests
```yaml
# src/test/resources/application-test.yml
spring:
  profiles:
    active: test
  data:
    redis:
      host: localhost
      port: 6379  # Will be overridden by Testcontainers
```

### Embedded Redis (No Docker)
```java
@TestConfiguration
class EmbeddedRedisTestConfig {
    @Bean
    public RedisServer redisServer() throws IOException {
        RedisServer server = new RedisServer(6379);
        server.start();
        return server;
    }
}
```

## Common Test Scenarios

### 1. Cache Hit/Miss
```java
@Test
void testCacheBehavior() {
    // Miss - Load from database
    Product first = service.getProductById("prod-1");
    verify(repository, times(1)).findById("prod-1");
    
    // Hit - Load from cache
    Product second = service.getProductById("prod-1");
    verify(repository, times(1)).findById("prod-1"); // Still 1 call
}
```

### 2. Cache Eviction
```java
@Test
void testCacheEviction() {
    // Populate cache
    service.getProductById("prod-1");
    
    // Update (evicts cache)
    service.saveProduct(testProduct);
    
    // Next read should hit database again
    service.getProductById("prod-1");
    verify(repository, times(2)).findById("prod-1");
}
```

### 3. Optimistic Locking
```java
@Test
void testOptimisticLockFailure() {
    // Thread 1 and 2 read same version
    Product v1 = service.getProductById("prod-1");
    Product v2 = service.getProductById("prod-1");
    
    // Thread 1 updates successfully
    v1.setName("Updated by T1");
    service.saveProduct(v1);
    
    // Thread 2 update should fail (stale version)
    v2.setName("Updated by T2");
    assertThatThrownBy(() -> service.saveProduct(v2))
        .isInstanceOf(OptimisticLockingFailureException.class);
}
```

### 4. Distributed Lock
```java
@Test
void testDistributedLock() throws InterruptedException {
    String lockKey = "test-lock";
    
    // Thread 1 acquires lock
    CompletableFuture<Boolean> future1 = CompletableFuture.supplyAsync(() -> 
        lockService.tryLock(lockKey, 5, TimeUnit.SECONDS)
    );
    
    Thread.sleep(100); // Ensure T1 acquires first
    
    // Thread 2 tries to acquire (should fail)
    CompletableFuture<Boolean> future2 = CompletableFuture.supplyAsync(() -> 
        lockService.tryLock(lockKey, 5, TimeUnit.SECONDS)
    );
    
    assertThat(future1.get()).isTrue();
    assertThat(future2.get()).isFalse();
}
```

## Test Coverage Goals

| Component | Target Coverage | Current |
|-----------|----------------|---------|
| Service Layer | 90%+ | 85% |
| Controller Layer | 80%+ | 75% |
| Repository Layer | 70%+ | 80% |
| Configuration | 60%+ | 65% |
| Overall | 80%+ | 78% |

### Generate Coverage Report
```bash
./gradlew test jacocoTestReport
open build/reports/jacoco/test/html/index.html
```

## Troubleshooting Tests

### Testcontainers Issues

**Problem**: Tests fail with "Cannot connect to Docker daemon"
```bash
# Solution: Ensure Docker is running
docker ps

# On macOS
open -a Docker

# On Linux
sudo systemctl start docker
```

**Problem**: Tests are slow to start
```bash
# Solution: Reuse containers between test runs
@Container
private static final GenericContainer<?> redis = 
    new GenericContainer<>("redis:7-alpine")
        .withReuse(true);  # Enable container reuse
```

### Redis Connection Issues

**Problem**: Connection refused to Redis
```java
// Solution: Check port mapping
@DynamicPropertySource
static void redisProperties(DynamicPropertyRegistry registry) {
    int port = redisContainer.getMappedPort(6379);
    System.out.println("Redis port: " + port);  # Debug output
    registry.add("spring.data.redis.port", () -> port);
}
```

### Test Data Isolation

**Problem**: Tests interfere with each other
```java
// Solution: Clean up before each test
@BeforeEach
void setUp() {
    productRepository.deleteAll();
    redisTemplate.getConnectionFactory()
        .getConnection()
        .serverCommands()
        .flushAll();  # Clear Redis
}
```

## Best Practices

### 1. Test Naming
```java
// ✅ Good - Describes scenario and expected outcome
@Test
void givenExistingProduct_whenDeleted_thenNotFoundInDatabase()

// ❌ Bad - Vague, doesn't explain scenario
@Test
void testDelete()
```

### 2. Arrange-Act-Assert Pattern
```java
@Test
void testMethod() {
    // Given (Arrange) - Setup test data
    Product product = createTestProduct();
    
    // When (Act) - Execute the operation
    Product result = service.saveProduct(product);
    
    // Then (Assert) - Verify results
    assertThat(result.getId()).isNotNull();
}
```

### 3. One Assertion Per Test (Preferred)
```java
// ✅ Good - Focused test
@Test
void givenValidProduct_whenSaved_thenHasId() {
    Product result = service.saveProduct(testProduct);
    assertThat(result.getId()).isNotNull();
}

@Test
void givenValidProduct_whenSaved_thenPersistedInDatabase() {
    service.saveProduct(testProduct);
    assertThat(repository.existsById(testProduct.getId())).isTrue();
}
```

### 4. Avoid Test Interdependence
```java
// ❌ Bad - Tests depend on execution order
@Test
@Order(1)
void createProduct() { /* ... */ }

@Test
@Order(2)
void updateProduct() { /* Depends on createProduct */ }

// ✅ Good - Each test is independent
@Test
void testUpdate() {
    Product product = repository.save(createTestProduct());
    product.setName("Updated");
    // ... test logic
}
```

## Continuous Integration

### GitHub Actions Example
```yaml
name: Tests

on: [push, pull_request]

jobs:
  test:
    runs-on: ubuntu-latest
    services:
      redis:
        image: redis:7-alpine
        ports:
          - 6379:6379
          
    steps:
      - uses: actions/checkout@v3
      - uses: actions/setup-java@v3
        with:
          java-version: '17'
          
      - name: Run tests
        run: ./gradlew test
        
      - name: Generate coverage
        run: ./gradlew jacocoTestReport
        
      - name: Upload coverage
        uses: codecov/codecov-action@v3
```

## Additional Resources

- [JUnit 5 Documentation](https://junit.org/junit5/docs/current/user-guide/)
- [Mockito Documentation](https://javadoc.io/doc/org.mockito/mockito-core/latest/org/mockito/Mockito.html)
- [AssertJ Documentation](https://assertj.github.io/doc/)
- [Testcontainers Documentation](https://www.testcontainers.org/)
- [Spring Boot Testing Guide](https://docs.spring.io/spring-boot/docs/current/reference/html/features.html#features.testing)

## Summary

This test suite provides:
- ✅ **Comprehensive Coverage**: Unit, integration, concurrency, and performance tests
- ✅ **Best Practices**: Mockito, AssertJ, Testcontainers patterns
- ✅ **Real-World Scenarios**: Cache behavior, distributed locks, thread safety
- ✅ **CI/CD Ready**: Docker-based tests, coverage reports, GitHub Actions

For questions or issues, refer to the main [TESTING_GUIDE.md](TESTING_GUIDE.md) or project README.
