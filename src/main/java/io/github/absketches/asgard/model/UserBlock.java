package io.github.absketches.asgard.model;

import java.time.Instant;

/**
 * Immutable record of a user-defined block entry.
 * Persisted in the user_blocks SQLite table.
 * ClassifierHelper blocks all subdomains of the specified host.
 */
public record UserBlock(
    String host,       // exact host — ClassifierHelper also blocks all subdomains
    Instant createdAt,
    String note        // optional, nullable
) {}
