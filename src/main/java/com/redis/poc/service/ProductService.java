package com.redis.poc.service;

import com.redis.poc.domain.Product;
import com.redis.poc.repository.ProductRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Service layer for managing products. This class demonstrates the use of
 * Spring's caching abstraction to improve performance by caching database results.
 * Redis is used as the cache provider in this application.
 */
@Service
@Slf4j
public class ProductService {

    private final ProductRepository productRepository;

    public ProductService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    /**
     * Retrieves a product by its ID. The result of this method is cached.
     * The {@code @Cacheable} annotation triggers the caching logic. If a product
     * with the given ID is found in the 'product-details' cache, it is returned
     * immediately without executing the method body. Otherwise, the method is executed,
     * the product is fetched from the database, and the result is stored in the cache.
     *
     * @param id The ID of the product to retrieve.
     * @return The found Product, or null if it does not exist.
     */
    @Cacheable(value = "product-details", key = "#id")
    public Product getProductById(String id) {
        log.info("CACHE MISS: Fetching product with id {} from the database.", id);
        // Simulate a delay to demonstrate the performance benefits of caching.
        // This block will only be executed on a cache miss.
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return productRepository.findById(id).orElse(null);
    }

    /**
     * Retrieves a list of all products. The result of this method is cached.
     * On the first call, it fetches all products from the database and stores
     * the list in the 'products' cache. Subsequent calls will return the cached list.
     *
     * @return A list of all products.
     */
    @Cacheable("products")
    public List<Product> getAllProducts() {
        log.info("CACHE MISS: Fetching all products from the database.");
        return productRepository.findAll();
    }

    /**
     * Saves a new product or updates an existing one and then evicts relevant caches.
     * The {@code @CacheEvict} annotation is crucial for invalidating stale data to
     * maintain cache consistency with the database.
     *
     * 'value' specifies the caches to act on: "products" and "product-details".
     * 'allEntries = true' is a simple invalidation strategy that clears all entries
     * from the specified caches. This ensures that any subsequent reads will fetch
     * fresh data from the database.
     *
     * @param product The product to save.
     * @return The saved product.
     */
    @CacheEvict(value = {"products", "product-details"}, allEntries = true)
    public Product saveProduct(Product product) {
        log.info("Saving product with id {}. Caches will be evicted.", product.getId());
        return productRepository.save(product);
    }

    /**
     * Deletes a product by its ID and then evicts relevant caches.
     * This ensures that the deleted product is no longer served from the cache.
     *
     * 'allEntries = true' clears all entries from the "products" and "product-details"
     * caches to ensure consistency.
     *
     * @param id The ID of the product to delete.
     */
    @CacheEvict(value = {"products", "product-details"}, allEntries = true)
    public void deleteProduct(String id) {
        log.info("Deleting product with id {}. Caches will be evicted.", id);
        productRepository.deleteById(id);
    }
}
