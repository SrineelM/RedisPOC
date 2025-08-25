package com.redis.poc.cqrs;

import com.redis.poc.domain.Product;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/products/eventsourcing")
public class ProductEventSourcingController {
    private final ProductEventSourcingService eventSourcingService;

    public ProductEventSourcingController(ProductEventSourcingService eventSourcingService) {
        this.eventSourcingService = eventSourcingService;
    }

    @PostMapping
    public ResponseEntity<Void> createProduct(@RequestBody Product product) {
        eventSourcingService.recordCreate(product);
        return ResponseEntity.ok().build();
    }

    @PutMapping
    public ResponseEntity<Void> updateProduct(@RequestBody Product product) {
        eventSourcingService.recordUpdate(product);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteProduct(@PathVariable String id) {
        eventSourcingService.recordDelete(id);
        return ResponseEntity.ok().build();
    }

    @GetMapping
    public ResponseEntity<List<Product>> getAllProducts() {
        List<Product> products = eventSourcingService.reconstructProducts();
        return ResponseEntity.ok(products);
    }
}
