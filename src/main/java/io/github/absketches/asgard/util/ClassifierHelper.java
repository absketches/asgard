package io.github.absketches.asgard.util;

import io.github.absketches.asgard.dao.RequestDao;
import io.github.absketches.asgard.model.RequestRecord;
import io.github.absketches.asgard.model.RequestRecord.Classification;
import org.nanonative.nano.services.http.HttpClient;
import org.nanonative.nano.services.http.model.HttpObject;

import java.net.URI;
import java.net.http.HttpRequest;
import java.sql.SQLException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class ClassifierHelper {

    private static final String OISD_URL         = "https://small.oisd.nl/domainswild2";
    static final         long   BEACON_THRESHOLD = 60L;
    public static final  long   EXFIL_SIZE_BYTES = 1_048_576L;

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

    /** Classifies the record and persists it via RequestDao. */
    public static void classifyAndPersist(final RequestRecord record) throws SQLException {
        final Classification cls   = determine(record);
        final RequestRecord tagged = record.withClassification(cls);
        RequestDao.persist(tagged);
    }

    /** Swaps the USER_BLOCKLIST atomically. */
    public static void updateUserBlocklist(final Set<String> updated) {
        USER_BLOCKLIST.set(updated);
    }

    /** Resets the per-host beacon counter. */
    public static void resetBeaconCounter() {
        BEACON_COUNTER.set(new ConcurrentHashMap<>());
    }

    /**
     * Downloads and atomically swaps the oisd.nl blocklist using Nano's HttpClient.
     * @return number of domains loaded, or -1 on failure
     */
    public static int refreshOisdBlocklist(final HttpClient http) {
        try {
            final HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(OISD_URL))
                .header("User-Agent", "Mozilla/5.0 (compatible; Asgard/1.0)")
                .header("Accept", "text/plain,*/*")
                .header("Accept-Language", "en-US,en;q=0.9")
                .GET()
                .build();

            final HttpObject response = http.send(request);
            if (response.hasFailed()) return -1;

            final Set<String> fresh = ConcurrentHashMap.newKeySet();
            for (final String line : response.bodyAsString().split("\n")) {
                String trimmed = line.trim().toLowerCase();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                if (trimmed.startsWith("*.")) trimmed = trimmed.substring(2);
                if (!trimmed.isEmpty()) fresh.add(trimmed);
            }
            OISD_BLOCKLIST.set(fresh);
            return fresh.size();
        } catch (final Exception ignored) {
            return -1;
        }
    }

    public static Classification determine(final RequestRecord record) {
        final String host = record.destination() == null ? "" : record.destination().toLowerCase();

        if (isSafe(host))
            return Classification.NORMAL;

        final boolean isUpload = "POST".equalsIgnoreCase(record.method())
            || "PUT".equalsIgnoreCase(record.method());
        if (isUpload && record.dataSize() > EXFIL_SIZE_BYTES)
            return Classification.EXFILTRATION_RISK;

        final long count = BEACON_COUNTER.get()
            .computeIfAbsent(host, k -> new AtomicLong(0))
            .incrementAndGet();
        if (count > BEACON_THRESHOLD && !isBlocked(host) && isUpload)
            return Classification.EXFILTRATION_RISK;

        if (isBlocked(host))
            return Classification.TRACKING;

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

    // Test helpers
    static void addOisdEntry(final String host)    { OISD_BLOCKLIST.get().add(host); }
    static void removeOisdEntry(final String host) { OISD_BLOCKLIST.get().remove(host); }
}
