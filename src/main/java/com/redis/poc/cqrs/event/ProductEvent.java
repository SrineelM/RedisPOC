package com.redis.poc.cqrs.event;

import java.io.Serializable;
import java.time.Instant;

public class ProductEvent implements Serializable {
    public enum Type { CREATED, UPDATED, DELETED }
    private final Type type;
    private final String productId;
    private final String payload;
    private final Instant timestamp;
    private final int version;

    // For prod: increment version when schema changes, add upcasting logic if needed
    public ProductEvent(Type type, String productId, String payload) {
        this(type, productId, payload, 1);
    }

    public ProductEvent(Type type, String productId, String payload, int version) {
        this.type = type;
        this.productId = productId;
        this.payload = payload;
        this.timestamp = Instant.now();
        this.version = version;
    }

    public Type getType() { return type; }
    public String getProductId() { return productId; }
    public String getPayload() { return payload; }
    public Instant getTimestamp() { return timestamp; }
    public int getVersion() { return version; }
}
