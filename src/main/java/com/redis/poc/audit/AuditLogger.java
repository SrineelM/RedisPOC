package com.redis.poc.audit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Service
@Slf4j
public class AuditLogger {

    private static final String AUDIT_PREFIX = "AUDIT";

    public void logOperation(String user, String operation, String key, boolean success) {
        logOperation(user, operation, key, success, null);
    }

    public void logOperation(String user, String operation, String key, boolean success, String additionalInfo) {
        Map<String, Object> auditEvent = new HashMap<>();
        auditEvent.put("timestamp", LocalDateTime.now());
        auditEvent.put("user", user);
        auditEvent.put("operation", operation);
        auditEvent.put("key", key);
        auditEvent.put("success", success);
        auditEvent.put("thread", Thread.currentThread().getName());
        
        if (additionalInfo != null) {
            auditEvent.put("additionalInfo", additionalInfo);
        }

        // Structured logging for audit events
        log.info("{}: {}", AUDIT_PREFIX, auditEvent);
    }

    public void logSecurityEvent(String user, String event, String ipAddress, boolean success) {
        Map<String, Object> securityEvent = new HashMap<>();
        securityEvent.put("timestamp", LocalDateTime.now());
        securityEvent.put("user", user);
        securityEvent.put("event", event);
        securityEvent.put("ipAddress", ipAddress);
        securityEvent.put("success", success);
        securityEvent.put("type", "SECURITY");

        log.warn("{}: {}", AUDIT_PREFIX, securityEvent);
    }

    public void logPerformanceEvent(String operation, long durationMs, boolean success) {
        if (durationMs > 1000) { // Log slow operations
            Map<String, Object> performanceEvent = new HashMap<>();
            performanceEvent.put("timestamp", LocalDateTime.now());
            performanceEvent.put("operation", operation);
            performanceEvent.put("durationMs", durationMs);
            performanceEvent.put("success", success);
            performanceEvent.put("type", "PERFORMANCE");

            log.warn("{}: {}", AUDIT_PREFIX, performanceEvent);
        }
    }
}
