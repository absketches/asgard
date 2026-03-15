package io.github.absketches.asgard.model;

import java.time.Instant;

/**
 * Immutable record of a single proxied request.
 * Passed between Asgard services and persisted to SQLite by RequestDao.
 */
public record RequestRecord(
    String id,
    Instant timestamp,
    String sourceIp,
    String destination,   // hostname only — no path for HTTPS
    String method,        // GET, POST, CONNECT etc.
    long dataSize,        // bytes
    Classification classification,
    boolean blocked
) {
    public enum Classification {
        NORMAL,
        TRACKING,
        SUSPICIOUS,
        EXFILTRATION_RISK
    }

    public RequestRecord withClassification(final Classification c) {
        return new RequestRecord(id, timestamp, sourceIp, destination, method, dataSize, c, blocked);
    }
}
