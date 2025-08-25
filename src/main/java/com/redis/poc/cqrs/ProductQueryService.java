package com.redis.poc.cqrs;

import com.redis.poc.domain.Product;
import com.redis.poc.repository.ProductRepository;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ProductQueryService {
    private final ProductRepository productRepository;

    public ProductQueryService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    @Cacheable("products")
    public List<Product> getAllProducts() {
        return productRepository.findAll();
    }

    @Cacheable(value = "product-details", key = "#id")
    public Product getProductById(String id) {
        return productRepository.findById(id).orElse(null);
    }
}
