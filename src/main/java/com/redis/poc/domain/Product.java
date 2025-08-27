package com.redis.poc.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Entity
@Data // Lombok annotation to generate getters, setters, toString, etc.
@NoArgsConstructor
@AllArgsConstructor
public class Product implements Serializable {

    @Id
    @NotBlank(message = "Product id is required")
    private String id;

    @NotBlank(message = "Name is required")
    @Size(max = 120, message = "Name too long")
    private String name;

    @Size(max = 500, message = "Description too long")
    private String description;

    @DecimalMin(value = "0.0", inclusive = true, message = "Price must be >= 0")
    private double price;
}
