package com.redis.poc.domain;

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
    @jakarta.validation.constraints.NotBlank(message = "Product id is required")
    private String id;
    @jakarta.validation.constraints.NotBlank(message = "Name is required")
    @jakarta.validation.constraints.Size(max = 120)
    private String name;
    @jakarta.validation.constraints.Size(max = 500)
    private String description;
}
