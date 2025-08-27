package com.redis.poc.controller;

import com.redis.poc.pubsub.RedisMessagePublisher;
import com.redis.poc.pubsub.RedisMessageSubscriber;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller to demonstrate Redis Publish/Subscribe functionality.
 * Provides endpoints to publish messages and retrieve received messages.
 */
@RestController
@RequestMapping("/api/pubsub")
public class PubSubController {

    private final RedisMessagePublisher publisher;
    private final RedisMessageSubscriber subscriber;

    public PubSubController(RedisMessagePublisher publisher, RedisMessageSubscriber subscriber) {
        this.publisher = publisher;
        this.subscriber = subscriber;
    }

    /**
     * Publishes a message to the configured Redis channel.
     * Best Practice: Returns 202 Accepted for asynchronous operations.
     * This correctly informs the client that the message has been accepted for publishing
     * but does not guarantee its immediate processing by subscribers.
     * @param message The raw string message from the request body.
     * @return A ResponseEntity with an HTTP 202 Accepted status.
     */
    @PostMapping("/publish")
    public ResponseEntity<Void> publishMessage(@RequestBody String message) {
        publisher.publish(message);
        return ResponseEntity.accepted().build();
    }

    /**
     * Retrieves the list of messages received by the subscriber.
     * This is for demonstration purposes to verify that messages are being received.
     * @return A list of messages consumed by the subscriber.
     */
    @GetMapping("/messages")
    public List<String> getReceivedMessages() {
        return subscriber.getMessageList();
    }
}
