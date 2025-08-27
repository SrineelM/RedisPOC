package com.redis.poc.cqrs;

import com.redis.poc.domain.Product;
import com.redis.poc.repository.ProductRepository;
import org.springframework.stereotype.Service;

/**
 * Handles the command side of CQRS for products.
 * It is responsible for creating, updating, and deleting products.
 * This service modifies the state of the application but does not query it.
 */
@Service
public class ProductCommandService {
    private final ProductRepository productRepository;

    public ProductCommandService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    /**
     * Saves a product (creates if new, updates if existing) to the database.
     * This is the primary method for all write operations that are not deletions.
     * @param product The product to save.
     * @return The saved product, which might have been updated by the persistence layer.
     */
    public Product saveProduct(Product product) {
        return productRepository.save(product);
    }

    /**
     * Deletes a product from the database by its ID.
     * This is a permanent removal of the product record.
     * @param id The ID of the product to delete.
     */
    public void deleteProduct(String id) {
        productRepository.deleteById(id);
    }
}
