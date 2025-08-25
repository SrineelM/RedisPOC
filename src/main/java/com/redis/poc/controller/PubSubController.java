package com.redis.poc.controller;

import com.redis.poc.pubsub.RedisMessagePublisher;
import com.redis.poc.pubsub.RedisMessageSubscriber;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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
     * Best Practice: Returns 202 Accepted for asynchronous operations.
     * This correctly informs the client that the message has been accepted for publishing
     * but does not guarantee its immediate processing.
     */
    @PostMapping("/publish")
    public ResponseEntity<Void> publishMessage(@RequestBody String message) {
        publisher.publish(message);
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/messages")
    public List<String> getReceivedMessages() {
        return subscriber.getMessageList();
    }
}
