package com.redis.poc.cqrs.event;

import com.eventstore.dbclient.*;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Integrates with EventStoreDB to persist and read product events.
 * This class acts as a client for EventStoreDB, a database specialized for event sourcing.
 * For production, the connection and credentials should be configured securely.
 */
@Component
public class ProductEventStoreDB {
    private final EventStoreDBClient client;

    public ProductEventStoreDB() {
        // In a production environment, use external configuration (e.g., application.properties)
        // for the connection string and handle credentials securely.
        EventStoreDBClientSettings settings =
                EventStoreDBConnectionString.parseOrThrow("esdb://localhost:2113?tls=false");
        this.client = EventStoreDBClient.create(settings);
    }

    /**
     * Appends a product event to the appropriate stream in EventStoreDB.
     * Each product has its own stream, named "product-{aggregateId}".
     * @param aggregateId The ID of the product (aggregate).
     * @param event The event to append.
     * @throws Exception if the append operation fails.
     */
    public void appendEventToEventStoreDB(String aggregateId, ProductEvent event) throws Exception {
        EventData eventData = EventData.builderAsJson(event.getType().name(), event)
                .eventId(java.util.UUID.randomUUID())
                .build();
        // Appends the event to a stream specific to the product ID.
        client.appendToStream("product-" + aggregateId, eventData).get();
    }

    /**
     * Reads all events for a specific product from its stream in EventStoreDB.
     * @param aggregateId The ID of the product (aggregate).
     * @return A list of all historical events for the product.
     * @throws Exception if the read operation fails.
     */
    public List<ProductEvent> readEventsFromEventStoreDB(String aggregateId) throws Exception {
        List<ProductEvent> events = new ArrayList<>();
        ReadStreamOptions options = ReadStreamOptions.get().fromStart();
        // Reads the entire event stream for the given product ID.
        ReadResult result = client.readStream("product-" + aggregateId, options).get();
        result.getEvents().forEach(resolvedEvent -> {
            try {
                // Deserializes the event data back into a ProductEvent object.
                ProductEvent event = resolvedEvent.getOriginalEvent().getEventDataAs(ProductEvent.class);
                events.add(event);
            } catch (Exception e) {
                // In production, consider more robust error handling or logging.
                // For example, logging a warning and skipping the unreadable event.
            }
        });
        return events;
    }
}
