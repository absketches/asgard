package io.github.absketches.asgard.model;

import java.time.Instant;

/**
 * Immutable record of a user-defined block entry.
 * Persisted in the user_blocks SQLite table.
 * Passed between StorageService, ClassifierService and DashboardService.
 */
public record UserBlock(
        String host,       // exact host — ClassifierService also blocks all subdomains
        Instant createdAt,
        String note        // optional, nullable
) {}
