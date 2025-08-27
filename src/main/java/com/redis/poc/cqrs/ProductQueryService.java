package com.redis.poc.cqrs;

import com.redis.poc.domain.Product;
import com.redis.poc.repository.ProductRepository;
import java.util.List;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/**
 * Service for querying product information.
 * This represents the "Query" side of the CQRS pattern.
 * It uses caching to optimize read operations.
 */
@Service
public class ProductQueryService {
    private final ProductRepository productRepository;

    public ProductQueryService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    /**
     * Retrieves all products, caching the result.
     * @return A list of all products.
     */
    @Cacheable("products")
    public List<Product> getAllProducts() {
        return productRepository.findAll();
    }

    /**
     * Retrieves a single product by its ID, caching the result.
     * @param id The ID of the product to retrieve.
     * @return The product, or null if not found.
     */
    @Cacheable(value = "product-details", key = "#id")
    public Product getProductById(String id) {
        return productRepository.findById(id).orElse(null);
    }
}
