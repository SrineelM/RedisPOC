package com.redis.poc.cqrs.event;

import com.eventstore.dbclient.*;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;

/**
 * EventStoreDB integration for Product events (CQRS/Event Sourcing).
 * For production, configure EventStoreDB connection and credentials securely.
 */
@Component
public class ProductEventStoreDB {
    private final EventStoreDBClient client;

    public ProductEventStoreDB() {
        // For prod: Use config/env variables for connection string
        EventStoreDBClientSettings settings = EventStoreDBConnectionString.parseOrThrow("esdb://localhost:2113?tls=false");
        this.client = EventStoreDBClient.create(settings);
    }

    public void appendEventToEventStoreDB(String aggregateId, ProductEvent event) throws Exception {
        EventData eventData = EventData.builderAsJson(event.getType().name(), event)
                .eventId(java.util.UUID.randomUUID())
                .build();
        client.appendToStream("product-" + aggregateId, eventData).get();
    }

    public List<ProductEvent> readEventsFromEventStoreDB(String aggregateId) throws Exception {
        List<ProductEvent> events = new ArrayList<>();
        ReadStreamOptions options = ReadStreamOptions.get().fromStart();
        ReadResult result = client.readStream("product-" + aggregateId, options).get();
        result.getEvents().forEach(resolvedEvent -> {
            try {
                ProductEvent event = resolvedEvent.getOriginalEvent().getEventDataAs(ProductEvent.class);
                events.add(event);
            } catch (Exception e) {
                // Handle deserialization error
            }
        });
        return events;
    }
}
