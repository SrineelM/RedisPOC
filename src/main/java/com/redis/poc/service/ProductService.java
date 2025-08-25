package com.redis.poc.service;

import com.redis.poc.domain.Product;
import com.redis.poc.repository.ProductRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Slf4j
public class ProductService {

    private final ProductRepository productRepository;

    public ProductService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    /**
     * The @Cacheable annotation indicates that the result of this method should be cached.
     * Spring's caching abstraction is thread-safe. If multiple threads call this method with the same id concurrently,
     * only one thread will execute the method and populate the cache. Other threads will wait for the first thread to finish and then get the result from the cache.
     * The 'product-details' cache is used here, as defined in application.yml.
     */
    @Cacheable(value = "product-details", key = "#id")
    public Product getProductById(String id) {
        log.info("Fetching product with id {} from the database.", id);
        // Simulate a delay to demonstrate the performance benefits of caching
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return productRepository.findById(id).orElse(null);
    }

    /**
     * The 'products' cache is used to store the list of all products.
     */
    @Cacheable("products")
    public List<Product> getAllProducts() {
        log.info("Fetching all products from the database.");
        return productRepository.findAll();
    }

    /**
     * The @CacheEvict annotation is used to remove data from the cache.
     * The 'allEntries = true' attribute indicates that all entries in the 'products' cache should be removed.
     * We also evict the specific product from the 'product-details' cache.
     */
    @CacheEvict(value = {"products", "product-details"}, key = "#product.id", allEntries = true)
    public Product saveProduct(Product product) {
        log.info("Saving product with id {} to the database.", product.getId());
        return productRepository.save(product);
    }

    @CacheEvict(value = {"products", "product-details"}, key = "#id", allEntries = true)
    public void deleteProduct(String id) {
        log.info("Deleting product with id {} from the database.", id);
        productRepository.deleteById(id);
    }
}
