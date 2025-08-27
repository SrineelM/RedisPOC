package com.redis.poc.cqrs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.redis.poc.cqrs.event.ProductEvent;
import com.redis.poc.cqrs.event.ProductEventStore;
import com.redis.poc.domain.Product;
import org.springframework.stereotype.Service;
import jakarta.annotation.PostConstruct;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.beans.factory.annotation.Value;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Service responsible for event sourcing of product data.
 * It records product events (creations, updates, deletions) and can reconstruct the product state from these events.
 * It also handles snapshotting for performance optimization and idempotency to prevent duplicate event processing.
 */
@Service
public class ProductEventSourcingService {
    private final ProductEventStore eventStore;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final AtomicInteger snapshotCount = new AtomicInteger();
    private volatile Instant lastSnapshotTime = Instant.EPOCH;
    private final Timer snapshotTimer;
    private final StringRedisTemplate stringRedisTemplate;
    @Value("${product.events.idempotency-ttl-seconds:86400}")
    private long idempotencyTtlSeconds;
    private static final String IDEMPOTENCY_SET_KEY = "product:events:processed";

    public ProductEventSourcingService(ProductEventStore eventStore, ObjectMapper objectMapper, MeterRegistry meterRegistry, StringRedisTemplate stringRedisTemplate) {
        this.eventStore = eventStore;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.snapshotTimer = Timer.builder("product.snapshot.duration").publishPercentileHistogram().register(meterRegistry);
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * Registers gauges for monitoring snapshot metrics.
     */
    @PostConstruct
    void registerGauges() {
        meterRegistry.gauge("product.snapshot.count", snapshotCount);
        meterRegistry.gauge("product.snapshot.age.seconds", this, s ->
                lastSnapshotTime == null ? -1.0 : (double) java.time.Duration.between(lastSnapshotTime, Instant.now()).getSeconds());
    }

    /**
     * Records a product creation event.
     * @param product The product that was created.
     */
    public void recordCreate(Product product) {
        try {
            String payload = objectMapper.writeValueAsString(product);
            ProductEvent event = new ProductEvent(ProductEvent.Type.CREATED, product.getId(), payload);
            appendWithIdempotency(event);
            maybeSnapshot();
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize product", e);
        }
    }

    /**
     * Records a product update event.
     * @param product The product that was updated.
     */
    public void recordUpdate(Product product) {
        try {
            String payload = objectMapper.writeValueAsString(product);
            ProductEvent event = new ProductEvent(ProductEvent.Type.UPDATED, product.getId(), payload);
            appendWithIdempotency(event);
            maybeSnapshot();
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize product", e);
        }
    }

    /**
     * Records a product deletion event.
     * @param productId The ID of the product that was deleted.
     */
    public void recordDelete(String productId) {
    ProductEvent event = new ProductEvent(ProductEvent.Type.DELETED, productId, null);
    appendWithIdempotency(event);
    maybeSnapshot();
    }

    /**
     * Reconstructs the list of products from snapshots and events.
     * @return A list of all current products.
     */
    public List<Product> reconstructProducts() {
        // For local: load all snapshots, then replay events after snapshot
        // This is a simplified example. In production, use distributed snapshot storage and advanced replay logic.
        List<Product> products = new java.util.ArrayList<>();
        Object rawSnapshots = eventStore.loadSnapshot("all");
        if (rawSnapshots instanceof java.util.Map<?, ?> snapshotMap) {
            for (Object obj : snapshotMap.values()) {
                if (obj instanceof Product p) {
                    products.add(p);
                }
            }
        }
        List<Object> events = eventStore.getAllEvents();
        java.util.Set<String> localProcessed = new java.util.HashSet<>();
        for (Object e : events) {
            if (e instanceof ProductEvent) {
                ProductEvent ev = (ProductEvent) e;
                String uniqueKey = ev.getType()+":"+ev.getProductId()+":"+ev.getTimestamp();
                if (Boolean.TRUE.equals(stringRedisTemplate.opsForSet().isMember(IDEMPOTENCY_SET_KEY, uniqueKey)) || !localProcessed.add(uniqueKey)) {
                    continue; // already applied
                }
                if (ev.getType() != ProductEvent.Type.DELETED) {
                    try {
                        Product p = objectMapper.readValue(ev.getPayload(), Product.class);
                        // For prod: check event version and upcast if needed
                        products.add(p);
                    } catch (Exception ex) {
                        throw new RuntimeException("Failed to deserialize product event", ex);
                    }
                }
                stringRedisTemplate.opsForSet().add(IDEMPOTENCY_SET_KEY, uniqueKey);
            }
        }
        stringRedisTemplate.expire(IDEMPOTENCY_SET_KEY, java.time.Duration.ofSeconds(idempotencyTtlSeconds));
        return products;
    }

    // Snapshot every N events (simple threshold). For prod: maintain versioned snapshots per aggregate
    private static final int SNAPSHOT_THRESHOLD = 50;

    /**
     * Creates a snapshot of the current product state if the number of events reaches a certain threshold.
     */
    private void maybeSnapshot() {
        int total = eventStore.getAllEvents().size();
        if (total % SNAPSHOT_THRESHOLD == 0) {
            // naive global snapshot of all products reconstructed; expensive O(n^2) for demo purposes
            long start = System.nanoTime();
            List<Product> current = reconstructProducts();
            java.util.Map<String, Product> map = current.stream().collect(Collectors.toMap(Product::getId, p -> p));
            eventStore.saveSnapshot("all", map);
            snapshotCount.incrementAndGet();
            lastSnapshotTime = Instant.now();
            snapshotTimer.record(System.nanoTime() - start, java.util.concurrent.TimeUnit.NANOSECONDS);
            // Metric hook (if MeterRegistry injected in future): gauge for snapshot count, timer for duration
        }
    }

    /**
     * Appends an event to the event store with an idempotency check.
     * @param event The event to append.
     */
    private void appendWithIdempotency(ProductEvent event) {
        String uniqueKey = event.getType()+":"+event.getProductId()+":"+event.getTimestamp();
        Boolean exists = stringRedisTemplate.opsForSet().isMember(IDEMPOTENCY_SET_KEY, uniqueKey);
        if (Boolean.TRUE.equals(exists)) return;
        eventStore.appendEvent(event);
        stringRedisTemplate.opsForSet().add(IDEMPOTENCY_SET_KEY, uniqueKey);
        stringRedisTemplate.expire(IDEMPOTENCY_SET_KEY, java.time.Duration.ofSeconds(idempotencyTtlSeconds));
    }
}
