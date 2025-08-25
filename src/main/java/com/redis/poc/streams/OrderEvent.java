package com.redis.poc.streams;

import java.io.Serializable;

/**
 * A record to represent an order event in the Redis Stream.
 * Using a record provides a concise, immutable data carrier.
 */
public record OrderEvent(String id, String customer, double amount) implements Serializable {
}
