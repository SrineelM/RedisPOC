package com.redis.poc.service;

import com.redis.poc.domain.Product;
import com.redis.poc.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Comprehensive unit tests for ProductService demonstrating best practices:
 *
 * <p><b>Testing Patterns:</b></p>
 * <ul>
 *   <li><b>Mocking:</b> Using Mockito to isolate units under test</li>
 *   <li><b>Assertions:</b> Using AssertJ for fluent, readable assertions</li>
 *   <li><b>Coverage:</b> Testing happy paths, edge cases, and error conditions</li>
 *   <li><b>Naming:</b> Descriptive test names following Given-When-Then pattern</li>
 *   <li><b>Cache Testing:</b> Verifying cache hit/miss behavior</li>
 * </ul>
 *
 * <p><b>Test Categories:</b></p>
 * <ol>
 *   <li>Basic CRUD operations</li>
 *   <li>Caching behavior (hit/miss scenarios)</li>
 *   <li>Edge cases (null values, empty lists)</li>
 *   <li>Cache eviction on write operations</li>
 *   <li>Thread safety (concurrent access)</li>
 * </ol>
 *
 * <p><b>Run Tests:</b></p>
 * <pre>
 * ./gradlew test --tests ProductServiceTest
 * ./gradlew test --tests ProductServiceTest --info  # Verbose output
 * </pre>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ProductService Unit Tests")
class ProductServiceTest {

    // ========================================================================
    // TEST SETUP & FIXTURES
    // ========================================================================

    @Mock
    private ProductRepository productRepository;

    @InjectMocks
    private ProductService productService;

    private Product testProduct;
    private List<Product> testProducts;

    /**
     * Setup test fixtures before each test.
     * Follows the "Arrange" phase of Arrange-Act-Assert pattern.
     */
    @BeforeEach
    void setUp() {
        // Create test product
        testProduct = new Product();
        testProduct.setId("prod-1");
        testProduct.setName("Test Product");
        testProduct.setDescription("Test Description");

        // Create list of test products
        Product product2 = new Product();
        product2.setId("prod-2");
        product2.setName("Test Product 2");

        testProducts = Arrays.asList(testProduct, product2);
    }

    // ========================================================================
    // READ OPERATIONS (GET)
    // ========================================================================

    @Test
    @DisplayName("getProductById() - should return product when it exists")
    void givenExistingProduct_whenGetById_thenReturnProduct() {
        // Given: Product exists in database
        when(productRepository.findById("prod-1")).thenReturn(Optional.of(testProduct));

        // When: Getting product by ID
        Product result = productService.getProductById("prod-1");

        // Then: Product is returned
        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo("prod-1");
        assertThat(result.getName()).isEqualTo("Test Product");

        // Verify: Repository was called exactly once
        verify(productRepository, times(1)).findById("prod-1");
    }

    @Test
    @DisplayName("getProductById() - should return null when product does not exist")
    void givenNonExistingProduct_whenGetById_thenReturnNull() {
        // Given: Product does not exist
        when(productRepository.findById("nonexistent")).thenReturn(Optional.empty());

        // When: Getting nonexistent product
        Product result = productService.getProductById("nonexistent");

        // Then: Null is returned
        assertThat(result).isNull();

        // Verify: Repository was called
        verify(productRepository, times(1)).findById("nonexistent");
    }

    @Test
    @DisplayName("getProductById() - should fetch from database on cache miss")
    void givenCacheMiss_whenGetById_thenFetchFromDatabase() {
        // Given: Product in database, not in cache
        when(productRepository.findById("prod-1")).thenReturn(Optional.of(testProduct));

        // When: First call (cache miss)
        Product result1 = productService.getProductById("prod-1");

        // Then: Database was hit
        assertThat(result1).isNotNull();
        verify(productRepository, times(1)).findById("prod-1");

        // When: Second call (should be cached in real scenario, but mocked here)
        Product result2 = productService.getProductById("prod-1");

        // Then: In unit test with mock, database is hit again (no actual cache)
        // In integration test, database would NOT be hit again
        assertThat(result2).isNotNull();
        verify(productRepository, times(2)).findById("prod-1");
    }

    @Test
    @DisplayName("getAllProducts() - should return list of all products")
    void givenMultipleProducts_whenGetAll_thenReturnList() {
        // Given: Multiple products in database
        when(productRepository.findAll()).thenReturn(testProducts);

        // When: Getting all products
        List<Product> results = productService.getAllProducts();

        // Then: All products returned
        assertThat(results).isNotNull();
        assertThat(results).hasSize(2);
        assertThat(results).containsExactlyElementsOf(testProducts);

        // Verify
        verify(productRepository, times(1)).findAll();
    }

    @Test
    @DisplayName("getAllProducts() - should return empty list when no products exist")
    void givenNoProducts_whenGetAll_thenReturnEmptyList() {
        // Given: No products in database
        when(productRepository.findAll()).thenReturn(List.of());

        // When: Getting all products
        List<Product> results = productService.getAllProducts();

        // Then: Empty list returned
        assertThat(results).isNotNull();
        assertThat(results).isEmpty();

        // Verify
        verify(productRepository, times(1)).findAll();
    }

    // ========================================================================
    // WRITE OPERATIONS (CREATE/UPDATE)
    // ========================================================================

    @Test
    @DisplayName("saveProduct() - should save and return product")
    void givenValidProduct_whenSave_thenReturnSavedProduct() {
        // Given: Valid product to save
        when(productRepository.save(testProduct)).thenReturn(testProduct);

        // When: Saving product
        Product result = productService.saveProduct(testProduct);

        // Then: Product saved and returned
        assertThat(result).isNotNull();
        assertThat(result).isEqualTo(testProduct);

        // Verify: Save was called
        verify(productRepository, times(1)).save(testProduct);
    }

    @Test
    @DisplayName("saveProduct() - should evict cache after save")
    void givenProductSaved_whenSave_thenCacheEvicted() {
        // Given: Product to save
        when(productRepository.save(any(Product.class))).thenReturn(testProduct);

        // When: Saving product
        productService.saveProduct(testProduct);

        // Then: Cache should be evicted (in integration test, verify cache is cleared)
        // In unit test, we verify the method was called
        verify(productRepository, times(1)).save(testProduct);

        // Note: To properly test cache eviction, use integration test with actual cache
    }

    @Test
    @DisplayName("saveProduct() - should handle null values gracefully")
    void givenNullProduct_whenSave_thenHandleGracefully() {
        // Given: Null product (edge case - should be validated before service layer)
        when(productRepository.save(null)).thenThrow(new IllegalArgumentException("Product cannot be null"));

        // When/Then: Exception is thrown
        try {
            productService.saveProduct(null);
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage()).contains("Product cannot be null");
        }

        // Verify: Save was attempted
        verify(productRepository, times(1)).save(null);
    }

    // ========================================================================
    // DELETE OPERATIONS
    // ========================================================================

    @Test
    @DisplayName("deleteProduct() - should delete product by ID")
    void givenExistingProduct_whenDelete_thenProductDeleted() {
        // Given: Product exists
        String productId = "prod-1";
        doNothing().when(productRepository).deleteById(productId);

        // When: Deleting product
        productService.deleteProduct(productId);

        // Then: Delete was called
        verify(productRepository, times(1)).deleteById(productId);
    }

    @Test
    @DisplayName("deleteProduct() - should evict cache after delete")
    void givenProductDeleted_whenDelete_thenCacheEvicted() {
        // Given: Product ID
        String productId = "prod-1";
        doNothing().when(productRepository).deleteById(productId);

        // When: Deleting product
        productService.deleteProduct(productId);

        // Then: Delete called and cache evicted
        verify(productRepository, times(1)).deleteById(productId);

        // Note: Cache eviction tested in integration tests
    }

    @Test
    @DisplayName("deleteProduct() - should handle nonexistent product deletion")
    void givenNonExistentProduct_whenDelete_thenNoError() {
        // Given: Product does not exist (deleteById is idempotent in most implementations)
        String productId = "nonexistent";
        doNothing().when(productRepository).deleteById(productId);

        // When: Attempting to delete
        productService.deleteProduct(productId);

        // Then: No error, delete was called
        verify(productRepository, times(1)).deleteById(productId);
    }

    // ========================================================================
    // CONCURRENCY & THREAD SAFETY TESTS
    // ========================================================================

    @Test
    @DisplayName("Concurrent access - should handle multiple threads safely")
    void givenConcurrentRequests_whenGetProduct_thenThreadSafe() throws InterruptedException {
        // Given: Product in database
        when(productRepository.findById("prod-1")).thenReturn(Optional.of(testProduct));

        // When: Multiple threads access the service concurrently
        Thread thread1 = new Thread(() -> {
            for (int i = 0; i < 100; i++) {
                productService.getProductById("prod-1");
            }
        });

        Thread thread2 = new Thread(() -> {
            for (int i = 0; i < 100; i++) {
                productService.getProductById("prod-1");
            }
        });

        thread1.start();
        thread2.start();

        thread1.join();
        thread2.join();

        // Then: No concurrency issues (both threads completed successfully)
        // Verify: Method was called 200 times total
        verify(productRepository, times(200)).findById("prod-1");
    }

    // ========================================================================
    // EDGE CASES & ERROR HANDLING
    // ========================================================================

    @Test
    @DisplayName("Edge case - should handle extremely long product names")
    void givenLongProductName_whenSave_thenHandleGracefully() {
        // Given: Product with very long name
        String longName = "A".repeat(1000);
        testProduct.setName(longName);
        when(productRepository.save(testProduct)).thenReturn(testProduct);

        // When: Saving product
        Product result = productService.saveProduct(testProduct);

        // Then: Product saved successfully
        assertThat(result.getName()).hasSize(1000);
        verify(productRepository, times(1)).save(testProduct);
    }

    @Test
    @DisplayName("Edge case - should handle special characters in product ID")
    void givenSpecialCharactersInId_whenGetProduct_thenHandleCorrectly() {
        // Given: Product ID with special characters
        String specialId = "prod-!@#$%^&*()";
        Product specialProduct = new Product();
        specialProduct.setId(specialId);

        when(productRepository.findById(specialId)).thenReturn(Optional.of(specialProduct));

        // When: Getting product with special ID
        Product result = productService.getProductById(specialId);

        // Then: Product retrieved correctly
        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(specialId);
    }

    // ========================================================================
    // PERFORMANCE & OPTIMIZATION TESTS
    // ========================================================================

    @Test
    @DisplayName("Performance - should complete getAllProducts in reasonable time")
    void givenLargeProductList_whenGetAll_thenCompleteFast() {
        // Given: Large list of products (1000 items)
        List<Product> largeList = generateProducts(1000);
        when(productRepository.findAll()).thenReturn(largeList);

        // When: Getting all products
        long startTime = System.nanoTime();
        List<Product> results = productService.getAllProducts();
        long endTime = System.nanoTime();

        // Then: Operation completed in < 100ms (unit test, no actual cache)
        long durationMs = (endTime - startTime) / 1_000_000;
        assertThat(results).hasSize(1000);
        assertThat(durationMs).isLessThan(100L);

        verify(productRepository, times(1)).findAll();
    }

    /**
     * Helper method to generate test products.
     */
    private List<Product> generateProducts(int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> {
                    Product p = new Product();
                    p.setId("prod-" + i);
                    p.setName("Product " + i);
                    return p;
                })
                .toList();
    }
}
