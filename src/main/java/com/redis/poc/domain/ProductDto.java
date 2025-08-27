package com.redis.poc.domain;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Best Practice: A Data Transfer Object (DTO) for the Product resource.
 * This decouples the API representation from the internal domain model (Product entity).
 * It allows the API to evolve independently of the database schema.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProductDto {
    /**
     * The unique identifier for the product.
     */
    @NotBlank(message = "Product id is required")
    private String id;

    /**
     * The name of the product.
     */
    @NotBlank(message = "Name is required")
    @Size(max = 120)
    private String name;

    /**
     * A description of the product.
     */
    @Size(max = 500)
    private String description;
}
