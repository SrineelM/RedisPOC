package com.redis.poc;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableCaching // Enables Spring's caching abstraction
@EnableScheduling // Enables support for scheduled tasks
@EnableAsync // Enables support for asynchronous methods
public class RedisPocApplication {

    public static void main(String[] args) {
        SpringApplication.run(RedisPocApplication.class, args);
    }
}
