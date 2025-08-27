package com.redis.poc.cqrs;

import com.redis.poc.domain.Product;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller for handling product operations via event sourcing.
 * This controller receives commands (create, update, delete) and passes them to the event sourcing service.
 * It also provides an endpoint to query the reconstructed state of all products.
 */
@RestController
@RequestMapping("/api/products/eventsourcing")
public class ProductEventSourcingController {
    private final ProductEventSourcingService eventSourcingService;

    public ProductEventSourcingController(ProductEventSourcingService eventSourcingService) {
        this.eventSourcingService = eventSourcingService;
    }

    /**
     * Endpoint to create a new product.
     * @param product The product to create.
     * @return A response entity with a success status.
     */
    @PostMapping
    public ResponseEntity<Void> createProduct(@RequestBody Product product) {
        eventSourcingService.recordCreate(product);
        return ResponseEntity.ok().build();
    }

    /**
     * Endpoint to update an existing product.
     * @param product The product to update.
     * @return A response entity with a success status.
     */
    @PutMapping
    public ResponseEntity<Void> updateProduct(@RequestBody Product product) {
        eventSourcingService.recordUpdate(product);
        return ResponseEntity.ok().build();
    }

    /**
     * Endpoint to delete a product by its ID.
     * @param id The ID of the product to delete.
     * @return A response entity with a success status.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteProduct(@PathVariable String id) {
        eventSourcingService.recordDelete(id);
        return ResponseEntity.ok().build();
    }

    /**
     * Endpoint to retrieve all products by reconstructing them from events.
     * @return A response entity containing the list of products.
     */
    @GetMapping
    public ResponseEntity<List<Product>> getAllProducts() {
        List<Product> products = eventSourcingService.reconstructProducts();
        return ResponseEntity.ok(products);
    }
}
