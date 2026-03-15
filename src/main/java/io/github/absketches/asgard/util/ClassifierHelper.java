package io.github.absketches.asgard.util;

import io.github.absketches.asgard.dao.RequestDao;
import io.github.absketches.asgard.model.RequestRecord;
import io.github.absketches.asgard.model.RequestRecord.Classification;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ClassifierHelper — pure static utility. No lifecycle, no events.
 *
 * Classifies every proxied request with one of four classifications and
 * persists it via StorageService.
 *
 * Two separate blocklists:
 *   OISD_BLOCKLIST  — oisd.nl, refreshed every 24h from DashboardService scheduler
 *   USER_BLOCKLIST  — updated directly by StorageService after DB writes
 *
 * Beacon counter resets every 60s — also driven by DashboardService scheduler.
 */
public final class ClassifierHelper {

    private static final String OISD_BASIC_URL       = "https://small.oisd.nl/domainswild2";
    static final         long   BEACON_THRESHOLD      = 60L;
    public static final         long   EXFIL_SIZE_BYTES      = 1_048_576L;

    private static final Set<String> SAFE_DOMAINS = Set.of(
        "apple.com", "icloud.com", "apple-dns.net", "mzstatic.com",
        "google.com", "googleapis.com", "gstatic.com", "googlevideo.com",
        "microsoft.com", "windows.com", "windowsupdate.com", "office.com", "live.com",
        "github.com", "githubusercontent.com",
        "cloudflare.com", "fastly.net", "akamai.net", "akamaiedge.net",
        "amazon.com", "amazonaws.com",
        "netflix.com", "nflxvideo.net",
        "spotify.com", "scdn.co"
    );

    static final AtomicReference<Set<String>> OISD_BLOCKLIST =
        new AtomicReference<>(ConcurrentHashMap.newKeySet());

    static final AtomicReference<Set<String>> USER_BLOCKLIST =
        new AtomicReference<>(ConcurrentHashMap.newKeySet());

    static final AtomicReference<ConcurrentHashMap<String, AtomicLong>> BEACON_COUNTER =
        new AtomicReference<>(new ConcurrentHashMap<>());

    private ClassifierHelper() {}

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Classifies the record and persists it via StorageService in one call.
     * Called directly by ProxyService after each connection.
     */
    public static void classifyAndPersist(final RequestRecord record) {
        final Classification cls    = determine(record);
        final RequestRecord  tagged = record.withClassification(cls);
        RequestDao.persist(tagged);
    }

    /**
     * Called by StorageService after a user block is added or removed.
     * Swaps the USER_BLOCKLIST atomically.
     */
    public static void updateUserBlocklist(final Set<String> updated) {
        USER_BLOCKLIST.set(updated);
    }

    /** Resets the beacon counter — called every 60s from DashboardService scheduler. */
    public static void resetBeaconCounter() {
        BEACON_COUNTER.set(new ConcurrentHashMap<>());
    }

    /** Downloads and atomically swaps the oisd.nl blocklist. */
    public static void refreshOisdBlocklist() {
        try {
            final HttpURLConnection conn =
                (HttpURLConnection) new URL(OISD_BASIC_URL).openConnection(java.net.Proxy.NO_PROXY);
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(60_000);
            conn.setRequestProperty("User-Agent", "Asgard/1.0");

            final Set<String> fresh = ConcurrentHashMap.newKeySet();
            try (final BufferedReader reader =
                     new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim().toLowerCase();
                    if (!line.isEmpty() && !line.startsWith("#")) {
                        if (line.startsWith("*.")) line = line.substring(2);
                        if (!line.isEmpty()) fresh.add(line);
                    }
                }
            }
            OISD_BLOCKLIST.set(fresh);
        } catch (final Exception ignored) {
            // Keep existing list on failure — logged by caller (DashboardService)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test helpers — package-private, not for production use
    // ─────────────────────────────────────────────────────────────────────────

    public static void addOisdEntry(final String host)    { OISD_BLOCKLIST.get().add(host); }
    public static void removeOisdEntry(final String host) { OISD_BLOCKLIST.get().remove(host); }

    // ─────────────────────────────────────────────────────────────────────────
    // Classification logic — package-private for unit tests
    // ─────────────────────────────────────────────────────────────────────────

    public static Classification determine(final RequestRecord record) {
        final String host = record.destination() == null ? "" : record.destination().toLowerCase();

        if (isSafe(host))                                               return Classification.NORMAL;

        final boolean isUpload = "POST".equalsIgnoreCase(record.method())
            || "PUT".equalsIgnoreCase(record.method());
        if (isUpload && record.dataSize() > EXFIL_SIZE_BYTES)          return Classification.EXFILTRATION_RISK;

        final long count = BEACON_COUNTER.get()
            .computeIfAbsent(host, k -> new AtomicLong(0))
            .incrementAndGet();
        if (count > BEACON_THRESHOLD && !isBlocked(host) && isUpload)  return Classification.EXFILTRATION_RISK;

        if (isBlocked(host))                                            return Classification.TRACKING;

        return Classification.SUSPICIOUS;
    }

    public static boolean isBlocked(final String host) {
        if (host == null || host.isEmpty()) return false;
        final Set<String> oisd = OISD_BLOCKLIST.get();
        final Set<String> user = USER_BLOCKLIST.get();
        if (oisd.contains(host) || user.contains(host)) return true;
        final String[] parts = host.split("\\.");
        for (int i = 1; i < parts.length - 1; i++) {
            final StringBuilder suffix = new StringBuilder();
            for (int j = i; j < parts.length; j++) {
                if (j > i) suffix.append('.');
                suffix.append(parts[j]);
            }
            final String s = suffix.toString();
            if (oisd.contains(s) || user.contains(s)) return true;
        }
        return false;
    }

    static boolean isSafe(final String host) {
        if (host == null || host.isEmpty()) return false;
        if (SAFE_DOMAINS.contains(host)) return true;
        final String[] parts = host.split("\\.");
        for (int i = 1; i < parts.length - 1; i++) {
            final StringBuilder suffix = new StringBuilder();
            for (int j = i; j < parts.length; j++) {
                if (j > i) suffix.append('.');
                suffix.append(parts[j]);
            }
            if (SAFE_DOMAINS.contains(suffix.toString())) return true;
        }
        return false;
    }
}
