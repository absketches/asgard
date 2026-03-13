package io.github.absketches.asgard.service;

import io.github.absketches.asgard.AsgardChannels;
import io.github.absketches.asgard.model.RequestRecord;
import io.github.absketches.asgard.model.RequestRecord.Classification;
import berlin.yuna.typemap.model.TypeMapI;
import org.nanonative.nano.core.model.Service;
import org.nanonative.nano.helper.event.model.Event;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.nanonative.nano.helper.config.ConfigRegister.registerConfig;

/**
 * ClassifierService — tags every proxied request with one of four classifications.
 *
 * Fully independent — communicates only through Nano events.
 * Never calls StorageService directly.
 *
 * Listens for:
 *   REQUEST_RECEIVED      — classify and re-emit as REQUEST_CLASSIFIED or REQUEST_BLOCKED
 *   USER_BLOCKS_LOADED    — seed USER_BLOCKLIST from StorageService on startup
 *   USER_BLOCK_CONFIRMED  — swap USER_BLOCKLIST to updated set after add/remove
 *
 * Two separate blocklists:
 *   OISD_BLOCKLIST  — oisd.nl, refreshed every 24h (atomic swap, never touches user blocks)
 *   USER_BLOCKLIST  — seeded from StorageService on startup via event, updated via event
 */
public class ClassifierService extends Service {

    public static final String CONFIG_OISD_REFRESH_S   = registerConfig("asgard_oisd_refresh_s",  "oisd.nl refresh interval in seconds (default 86400)");
    public static final String CONFIG_BEACON_THRESHOLD = registerConfig("asgard_beacon_threshold", "Max req/min to same unknown host before EXFILTRATION_RISK (default 10)");
    public static final String CONFIG_EXFIL_SIZE_BYTES = registerConfig("asgard_exfil_size_bytes", "POST/PUT body threshold in bytes for EXFILTRATION_RISK (default 1048576)");

    private static final String OISD_BASIC_URL        = "https://small.oisd.nl/domainswild2";
    private static final long   DEFAULT_OISD_REFRESH  = 86_400L;
    private static final long   DEFAULT_BEACON_THRESH = 60L;
    private static final long   DEFAULT_EXFIL_BYTES   = 1_048_576L;

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

    private static final AtomicReference<Set<String>> OISD_BLOCKLIST =
        new AtomicReference<>(ConcurrentHashMap.newKeySet());

    private static final AtomicReference<Set<String>> USER_BLOCKLIST =
        new AtomicReference<>(ConcurrentHashMap.newKeySet());

    private static final AtomicReference<ConcurrentHashMap<String, AtomicLong>> BEACON_COUNTER =
        new AtomicReference<>(new ConcurrentHashMap<>());

    private long beaconThreshold = DEFAULT_BEACON_THRESH;
    private long exfilSizeBytes  = DEFAULT_EXFIL_BYTES;
    private long oisdRefreshSecs = DEFAULT_OISD_REFRESH;

    private Consumer<Event<RequestRecord, Void>> requestListener;
    private Consumer<Event<Set<String>, Void>>   blocksLoadedListener;
    private Consumer<Event<Set<String>, Void>>   blockConfirmedListener;

    @Override
    public void start() {
        requestListener        = context.subscribeEvent(AsgardChannels.REQUEST_RECEIVED,     e -> true, this::classify);
        blocksLoadedListener   = context.subscribeEvent(AsgardChannels.USER_BLOCKS_LOADED,   e -> true, this::onBlocksLoaded);
        blockConfirmedListener = context.subscribeEvent(AsgardChannels.USER_BLOCK_CONFIRMED, e -> true, this::onBlocksLoaded);

        // Wait for StorageService to finish start() (i.e. connection is open) before seeding
        StorageService storage;
        do {
            storage = context.nano().service(StorageService.class);
        } while (null == storage || !storage.isReady());
        USER_BLOCKLIST.set(StorageService.loadUserBlockHosts());

        loadOisdBlocklist();
        context.run(this::loadOisdBlocklist, oisdRefreshSecs, oisdRefreshSecs, TimeUnit.SECONDS);
        context.run(() -> BEACON_COUNTER.set(new ConcurrentHashMap<>()), 60, 60, TimeUnit.SECONDS);

        context.info(() -> "[Asgard] ClassifierService started");
    }

    @Override
    public void stop() {
        context.unsubscribeEvent(AsgardChannels.REQUEST_RECEIVED,    requestListener);
        context.unsubscribeEvent(AsgardChannels.USER_BLOCKS_LOADED,  blocksLoadedListener);
        context.unsubscribeEvent(AsgardChannels.USER_BLOCK_CONFIRMED, blockConfirmedListener);
        context.info(() -> "[Asgard] ClassifierService stopped");
    }

    @Override
    public void configure(final TypeMapI<?> config, final TypeMapI<?> merged) {
        beaconThreshold = merged.asLongOpt(CONFIG_BEACON_THRESHOLD).orElse(DEFAULT_BEACON_THRESH);
        exfilSizeBytes  = merged.asLongOpt(CONFIG_EXFIL_SIZE_BYTES).orElse(DEFAULT_EXFIL_BYTES);
        oisdRefreshSecs = merged.asLongOpt(CONFIG_OISD_REFRESH_S).orElse(DEFAULT_OISD_REFRESH);
    }


    @Override public void onEvent(final Event<?, ?> event) {}

    @Override
    public Object onFailure(final Event<?, ?> event) {
        context.warn(() -> "[Asgard] ClassifierService error: {}", event.error());
        return null;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Event handlers
    // ─────────────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void onBlocksLoaded(final Event<?, Void> event) {
        event.payloadOpt().ifPresent(payload -> {
            if (payload instanceof Set<?> s) {
                USER_BLOCKLIST.set((Set<String>) s);
                context.info(() -> "[Asgard] ClassifierService — user blocklist updated: {} hosts",
                    USER_BLOCKLIST.get().size());
            }
        });
    }

    private void classify(final Event<RequestRecord, Void> event) {
        event.payloadOpt().ifPresent(record -> {
            final Classification cls   = determine(record);
            final RequestRecord tagged = record.withClassification(cls);
            final var channel = cls == Classification.EXFILTRATION_RISK
                ? AsgardChannels.REQUEST_BLOCKED
                : AsgardChannels.REQUEST_CLASSIFIED;
            context.newEvent(channel, () -> tagged).async(true).send();
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Classification logic — package-private for unit tests
    // ─────────────────────────────────────────────────────────────────────────

    Classification determine(final RequestRecord record) {
        final String host = record.destination() == null ? "" : record.destination().toLowerCase();

        if (isSafe(host))                                             return Classification.NORMAL;

        final boolean isUpload = "POST".equalsIgnoreCase(record.method())
            || "PUT".equalsIgnoreCase(record.method());
        if (isUpload && record.dataSize() > exfilSizeBytes)          return Classification.EXFILTRATION_RISK;

        final long count = BEACON_COUNTER.get()
            .computeIfAbsent(host, k -> new AtomicLong(0))
            .incrementAndGet();
        if (count > beaconThreshold && !isBlocked(host) && isUpload) return Classification.EXFILTRATION_RISK;

        if (isBlocked(host))                                         return Classification.TRACKING;

        return Classification.SUSPICIOUS;
    }

    static boolean isBlocked(final String host) {
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

    private void loadOisdBlocklist() {
        try {
            final HttpURLConnection conn = (HttpURLConnection) new URL(OISD_BASIC_URL).openConnection(java.net.Proxy.NO_PROXY);
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(60_000);
            conn.setRequestProperty("User-Agent", "Asgard/1.0");

            final Set<String> fresh = ConcurrentHashMap.newKeySet();
            try (final BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim().toLowerCase();
                    if (!line.isEmpty() && !line.startsWith("#")) {
                        // domainswild2 format: entries are prefixed with "*." — strip it
                        if (line.startsWith("*.")) line = line.substring(2);
                        if (!line.isEmpty()) fresh.add(line);
                    }
                }
            }
            OISD_BLOCKLIST.set(fresh);
            final int loaded = fresh.size();
            context.info(() -> "[Asgard] oisd.nl refreshed — {} domains", loaded);
        } catch (final Exception e) {
            context.warn(() -> "[Asgard] oisd.nl refresh failed: {} — keeping existing list", e.getMessage());
        }
    }
}
