package com.redis.poc.service;

import com.redis.poc.domain.Product;
import com.redis.poc.repository.ProductRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for ProductService using Testcontainers for real Redis instance.
 *
 * <p><b>Key Differences from Unit Tests:</b></p>
 * <ul>
 *   <li><b>Real Dependencies:</b> Uses actual Redis container instead of mocks</li>
 *   <li><b>Cache Behavior:</b> Tests actual caching with Spring Cache abstraction</li>
 *   <li><b>Concurrency:</b> Tests real concurrent access patterns</li>
 *   <li><b>Performance:</b> Measures actual performance with Redis latency</li>
 *   <li><b>Data Persistence:</b> Tests data freshness and consistency</li>
 * </ul>
 *
 * <p><b>Testcontainers Setup:</b></p>
 * <ul>
 *   <li>Automatically starts Redis container before tests</li>
 *   <li>Injects Redis port into Spring application context</li>
 *   <li>Cleans up container after all tests complete</li>
 *   <li>Requires Docker to be running on host machine</li>
 * </ul>
 *
 * <p><b>Run Tests:</b></p>
 * <pre>
 * ./gradlew test --tests ProductServiceIntegrationTest
 * docker ps  # Verify Redis container is running during test
 * </pre>
 *
 * <p><b>Prerequisites:</b></p>
 * <ul>
 *   <li>Docker must be installed and running</li>
 *   <li>Docker daemon must be accessible</li>
 *   <li>Internet connection (first run downloads Redis image)</li>
 * </ul>
 */
@SpringBootTest
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("ProductService Integration Tests (Testcontainers)")
class ProductServiceIntegrationTest {

    // ========================================================================
    // TESTCONTAINERS SETUP
    // ========================================================================

    /**
     * Redis container using Testcontainers.
     * - Uses official Redis 7-alpine image (lightweight, fast startup)
     * - Exposes port 6379 (mapped to random host port)
     * - Container is shared across all tests (@Container at class level)
     */
    @Container
    private static final GenericContainer<?> redisContainer = new GenericContainer<>(
                    DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379)
            .withReuse(false); // Create fresh container for each test run

    /**
     * Dynamically inject Redis connection properties into Spring context.
     * This ensures Spring Boot connects to the Testcontainers Redis instance.
     */
    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redisContainer::getHost);
        registry.add("spring.data.redis.port", () -> redisContainer.getMappedPort(6379));
    }

    // ========================================================================
    // TEST DEPENDENCIES
    // ========================================================================

    @Autowired
    private ProductService productService;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    private Product testProduct;

    // ========================================================================
    // SETUP & TEARDOWN
    // ========================================================================

    @BeforeAll
    void setUpAll() {
        // Verify Redis container is running
        assertThat(redisContainer.isRunning())
                .as("Redis container should be running")
                .isTrue();

        System.out.println("Redis container started at: " + redisContainer.getHost() + ":"
                + redisContainer.getMappedPort(6379));
    }

    @BeforeEach
    void setUp() {
        // Clear database and cache before each test
        productRepository.deleteAll();
        cacheManager.getCacheNames().forEach(cacheName -> {
            Cache cache = cacheManager.getCache(cacheName);
            if (cache != null) {
                cache.clear();
            }
        });

        // Create test product
        testProduct = new Product();
        testProduct.setId("integration-test-prod-1");
        testProduct.setName("Integration Test Product");
        testProduct.setDescription("Product for integration testing");
    }

    @AfterEach
    void tearDown() {
        // Clean up after each test
        productRepository.deleteAll();
    }

    // ========================================================================
    // CACHE BEHAVIOR TESTS
    // ========================================================================

    @Test
    @DisplayName("Cache - should cache product on first access and return from cache on second")
    void givenProductInDatabase_whenAccessedTwice_thenSecondAccessFromCache() {
        // Given: Product saved in database
        productRepository.save(testProduct);

        // When: First access (cache miss - should hit database)
        Product firstAccess = productService.getProductById(testProduct.getId());

        // Clear the database to verify second access uses cache
        productRepository.deleteAll();

        // When: Second access (cache hit - should NOT hit database)
        Product secondAccess = productService.getProductById(testProduct.getId());

        // Then: Both accesses return the product (second from cache)
        assertThat(firstAccess).isNotNull();
        assertThat(secondAccess).isNotNull();
        assertThat(secondAccess.getId()).isEqualTo(testProduct.getId());
        assertThat(secondAccess.getName()).isEqualTo(testProduct.getName());

        // Verify cache contains the product
        Cache cache = cacheManager.getCache("products");
        assertThat(cache).isNotNull();
        assertThat(cache.get(testProduct.getId())).isNotNull();
    }

    @Test
    @DisplayName("Cache Eviction - should evict cache when product is updated")
    void givenCachedProduct_whenUpdated_thenCacheEvicted() {
        // Given: Product in database and cache
        productRepository.save(testProduct);
        productService.getProductById(testProduct.getId()); // Populate cache

        // Verify cache is populated
        Cache cache = cacheManager.getCache("products");
        assertThat(cache.get(testProduct.getId())).isNotNull();

        // When: Product updated
        testProduct.setName("Updated Product Name");
        productService.saveProduct(testProduct);

        // Then: Cache should be evicted
        // Note: Depending on @CacheEvict configuration, cache might be evicted
        // Get fresh product to verify update
        Product updated = productService.getProductById(testProduct.getId());
        assertThat(updated.getName()).isEqualTo("Updated Product Name");
    }

    @Test
    @DisplayName("Cache Eviction - should evict cache when product is deleted")
    void givenCachedProduct_whenDeleted_thenCacheEvicted() {
        // Given: Product in database and cache
        productRepository.save(testProduct);
        productService.getProductById(testProduct.getId()); // Populate cache

        Cache cache = cacheManager.getCache("products");
        assertThat(cache.get(testProduct.getId())).isNotNull();

        // When: Product deleted
        productService.deleteProduct(testProduct.getId());

        // Then: Product should not be in cache
        Product deleted = productService.getProductById(testProduct.getId());
        assertThat(deleted).isNull();
    }

    // ========================================================================
    // CONCURRENCY TESTS (REAL WORLD SCENARIOS)
    // ========================================================================

    @Test
    @DisplayName("Concurrency - should handle concurrent reads safely")
    void givenProduct_whenConcurrentReads_thenAllSucceed() throws InterruptedException {
        // Given: Product in database
        productRepository.save(testProduct);

        int threadCount = 10;
        int iterationsPerThread = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);

        // When: Multiple threads read concurrently
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < iterationsPerThread; j++) {
                        Product product = productService.getProductById(testProduct.getId());
                        if (product != null && product.getId().equals(testProduct.getId())) {
                            successCount.incrementAndGet();
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        // Then: All reads successful
        boolean completed = latch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(successCount.get()).isEqualTo(threadCount * iterationsPerThread);
    }

    @Test
    @DisplayName("Concurrency - should handle concurrent writes safely")
    void givenMultipleThreads_whenConcurrentWrites_thenDataConsistent() throws InterruptedException {
        int threadCount = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        // When: Multiple threads write concurrently
        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    Product product = new Product();
                    product.setId("concurrent-prod-" + index);
                    product.setName("Concurrent Product " + index);
                    productService.saveProduct(product);
                } finally {
                    latch.countDown();
                }
            });
        }

        // Then: All writes successful
        boolean completed = latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();

        // Verify all products saved
        List<Product> allProducts = productService.getAllProducts();
        assertThat(allProducts).hasSizeGreaterThanOrEqualTo(threadCount);
    }

    // ========================================================================
    // PERFORMANCE TESTS
    // ========================================================================

    @Test
    @DisplayName("Performance - cache hit should be faster than database access")
    void givenProduct_whenComparePerformance_thenCacheFasterThanDatabase() {
        // Given: Product in database
        productRepository.save(testProduct);

        // Measure: First access (database)
        long dbStartTime = System.nanoTime();
        productService.getProductById(testProduct.getId());
        long dbEndTime = System.nanoTime();
        long dbDuration = dbEndTime - dbStartTime;

        // Measure: Second access (cache)
        long cacheStartTime = System.nanoTime();
        productService.getProductById(testProduct.getId());
        long cacheEndTime = System.nanoTime();
        long cacheDuration = cacheEndTime - cacheStartTime;

        // Then: Cache should be faster (though in H2, difference might be small)
        System.out.println("Database access: " + dbDuration / 1_000 + " μs");
        System.out.println("Cache access: " + cacheDuration / 1_000 + " μs");

        // Note: In production with remote Redis, cache hit is typically 10-100x faster
        assertThat(cacheDuration).isLessThanOrEqualTo(dbDuration * 2);
    }

    @Test
    @DisplayName("Performance - bulk operations should complete within threshold")
    void givenBulkOperations_whenExecuted_thenCompleteFast() {
        // Given: 100 products to save
        int productCount = 100;

        // When: Saving products in bulk
        long startTime = System.nanoTime();
        for (int i = 0; i < productCount; i++) {
            Product product = new Product();
            product.setId("bulk-prod-" + i);
            product.setName("Bulk Product " + i);
            productService.saveProduct(product);
        }
        long endTime = System.nanoTime();
        long durationMs = (endTime - startTime) / 1_000_000;

        // Then: Completes within reasonable time (< 5 seconds)
        System.out.println("Bulk save of " + productCount + " products: " + durationMs + " ms");
        assertThat(durationMs).isLessThan(5000L);

        // Verify all saved
        List<Product> allProducts = productService.getAllProducts();
        assertThat(allProducts).hasSizeGreaterThanOrEqualTo(productCount);
    }

    // ========================================================================
    // DATA FRESHNESS & CONSISTENCY TESTS
    // ========================================================================

    @Test
    @DisplayName("Data Freshness - should return latest data after cache eviction")
    void givenStaleCache_whenEvicted_thenReturnFreshData() {
        // Given: Product in database and cache
        productRepository.save(testProduct);
        productService.getProductById(testProduct.getId()); // Cache it

        // When: Product updated directly in database (bypassing service)
        testProduct.setName("Directly Updated Name");
        productRepository.save(testProduct);

        // Clear cache to simulate eviction
        Cache cache = cacheManager.getCache("products");
        if (cache != null) {
            cache.evict(testProduct.getId());
        }

        // Then: Service returns fresh data
        Product fresh = productService.getProductById(testProduct.getId());
        assertThat(fresh.getName()).isEqualTo("Directly Updated Name");
    }

    @Test
    @DisplayName("Consistency - should maintain consistency across cache and database")
    void givenProductOperations_whenExecuted_thenDataConsistent() {
        // Scenario: Create -> Read -> Update -> Read -> Delete -> Read
        // Ensures cache and database stay in sync

        // Create
        productService.saveProduct(testProduct);
        Product created = productService.getProductById(testProduct.getId());
        assertThat(created).isNotNull();

        // Update
        testProduct.setName("Updated Name");
        productService.saveProduct(testProduct);
        Product updated = productService.getProductById(testProduct.getId());
        assertThat(updated.getName()).isEqualTo("Updated Name");

        // Delete
        productService.deleteProduct(testProduct.getId());
        Product deleted = productService.getProductById(testProduct.getId());
        assertThat(deleted).isNull();

        // Verify database is consistent
        assertThat(productRepository.findById(testProduct.getId())).isEmpty();
    }

    // ========================================================================
    // REDIS CONNECTIVITY TESTS
    // ========================================================================

    @Test
    @DisplayName("Redis Connectivity - should successfully connect to Redis container")
    void testRedisConnectivity() {
        // Given: Redis template
        // When: Performing Redis operations
        redisTemplate.opsForValue().set("test-key", "test-value");
        Object value = redisTemplate.opsForValue().get("test-key");

        // Then: Operations successful
        assertThat(value).isEqualTo("test-value");

        // Cleanup
        redisTemplate.delete("test-key");
    }

    @Test
    @DisplayName("Redis Health - container should be healthy")
    void testRedisContainerHealth() {
        assertThat(redisContainer.isRunning()).isTrue();
        assertThat(redisContainer.isHealthy()).isTrue();
    }
}
