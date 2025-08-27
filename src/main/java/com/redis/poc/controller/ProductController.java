package com.redis.poc.controller;

import com.redis.poc.domain.Product;
import com.redis.poc.domain.ProductDto;
import com.redis.poc.service.ProductService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Standard REST controller for handling CRUD (Create, Read, Update, Delete) operations for Products.
 */
@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    /**
     * Retrieves a single product by its unique ID.
     * Best Practice: Returns a DTO (Data Transfer Object) instead of the internal entity.
     * This decouples the API's public contract from the internal domain model, improving security and flexibility.
     * @param id The ID of the product to retrieve.
     * @return A ResponseEntity containing the ProductDto if found, or a 404 Not Found status.
     */
    @GetMapping("/{id}")
    public ResponseEntity<ProductDto> getProductById(@PathVariable String id) {
        Product product = productService.getProductById(id);
        if (product == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(toDto(product));
    }

    /**
     * Retrieves a list of all products.
     * Converts each Product entity to a ProductDto for the response.
     * @return A list of ProductDtos.
     */
    @GetMapping
    public List<ProductDto> getAllProducts() {
        return productService.getAllProducts().stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    /**
     * Creates a new product.
     * Note: For more complex scenarios, you might accept a DTO here as well to validate input.
     * @param product The Product object from the request body.
     * @return The created Product, including any server-generated fields (like a database ID).
     */
    @PostMapping
    public Product createProduct(@RequestBody Product product) {
        return productService.saveProduct(product);
    }

    /**
     * Updates an existing product.
     * It uses the ID from the path and the data from the request body.
     * @param id The ID of the product to update.
     * @param product The updated product data.
     * @return The updated Product.
     */
    @PutMapping("/{id}")
    public Product updateProduct(@PathVariable String id, @RequestBody Product product) {
        // Ensure the ID from the path is set on the object before saving.
        product.setId(id);
        return productService.saveProduct(product);
    }

    /**
     * Deletes a product by its ID.
     * @param id The ID of the product to delete.
     */
    @DeleteMapping("/{id}")
    public void deleteProduct(@PathVariable String id) {
        productService.deleteProduct(id);
    }

    /**
     * Private helper method to convert a Product entity to a ProductDto.
     * @param product The Product entity.
     * @return The corresponding ProductDto.
     */
    private ProductDto toDto(Product product) {
        return new ProductDto(product.getId(), product.getName(), product.getDescription());
    }
}
