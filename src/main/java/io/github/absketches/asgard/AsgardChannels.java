package io.github.absketches.asgard;

import io.github.absketches.asgard.model.RequestRecord;
import io.github.absketches.asgard.model.UserBlock;
import org.nanonative.nano.helper.event.model.Channel;

import java.util.List;
import java.util.Set;

import static org.nanonative.nano.helper.event.model.Channel.registerChannelId;

/**
 * All Asgard event channels.
 *
 * Services communicate ONLY through these — no direct calls between services.
 *
 * Flow:
 *   ProxyService         → REQUEST_RECEIVED      → ClassifierService
 *   ClassifierService    → REQUEST_CLASSIFIED     → StorageService
 *   ClassifierService    → REQUEST_BLOCKED        → StorageService
 *   StorageService       → USER_BLOCKS_LOADED     → ClassifierService  (on startup)
 *   DashboardService     → USER_BLOCK_ADD         → StorageService
 *   DashboardService     → USER_BLOCK_REMOVE      → StorageService
 *   DashboardService     → CLEAR_REQUESTS         → StorageService
 *   StorageService       → USER_BLOCK_CONFIRMED   → ClassifierService  (add/remove applied)
 */
@SuppressWarnings("unchecked")
public final class AsgardChannels {

    private AsgardChannels() {}

    // ── Proxy → Classifier ────────────────────────────────────────────────────
    /** Fired by ProxyService after a connection is accepted. */
    public static final Channel<RequestRecord, Void> REQUEST_RECEIVED =
            registerChannelId("ASGARD_REQUEST_RECEIVED", RequestRecord.class, Void.class);

    // ── Classifier → Storage ──────────────────────────────────────────────────
    /** Fired by ClassifierService after tagging a request as non-critical. */
    public static final Channel<RequestRecord, Void> REQUEST_CLASSIFIED =
            registerChannelId("ASGARD_REQUEST_CLASSIFIED", RequestRecord.class, Void.class);

    /** Fired by ClassifierService when a request is EXFILTRATION_RISK. */
    public static final Channel<RequestRecord, Void> REQUEST_BLOCKED =
            registerChannelId("ASGARD_REQUEST_BLOCKED", RequestRecord.class, Void.class);

    // ── Storage → Classifier ──────────────────────────────────────────────────
    /** Fired by StorageService on startup with the full user block host set. */
    public static final Channel<Set<String>, Void> USER_BLOCKS_LOADED =
            (Channel<Set<String>, Void>) (Channel<?, ?>) registerChannelId("ASGARD_USER_BLOCKS_LOADED", Set.class, Void.class);

    /**
     * Fired by StorageService after a block is added or removed.
     * Payload is the updated full set of user block hosts.
     * ClassifierService swaps its USER_BLOCKLIST to this set.
     */
    public static final Channel<Set<String>, Void> USER_BLOCK_CONFIRMED =
            (Channel<Set<String>, Void>) (Channel<?, ?>) registerChannelId("ASGARD_USER_BLOCK_CONFIRMED", Set.class, Void.class);

    // ── Dashboard → Storage ───────────────────────────────────────────────────
    /** Fired by DashboardService when user adds a block. Payload: [host, note]. */
    public static final Channel<String[], Void> USER_BLOCK_ADD =
            (Channel<String[], Void>) (Channel<?, ?>) registerChannelId("ASGARD_USER_BLOCK_ADD", String[].class, Void.class);

    /** Fired by DashboardService when user removes a block. Payload: host string. */
    public static final Channel<String, Void> USER_BLOCK_REMOVE =
            (Channel<String, Void>) (Channel<?, ?>) registerChannelId("ASGARD_USER_BLOCK_REMOVE", String.class, Void.class);

    /**
     * Fired by DashboardService to clear requests from the DB.
     * Payload: classification name to clear, or "ALL" to clear everything.
     */
    public static final Channel<String, Void> CLEAR_REQUESTS =
            (Channel<String, Void>) (Channel<?, ?>) registerChannelId("ASGARD_CLEAR_REQUESTS", String.class, Void.class);

    /**
     * Fired by StorageService in response to USER_BLOCK_ADD, carrying the full
     * up-to-date UserBlock list so DashboardService can return it in the HTTP response.
     * This is the only synchronous-style reply channel — StorageService sends it,
     * DashboardService reads it via the event reply mechanism.
     */
    public static final Channel<List<UserBlock>, Void> USER_BLOCKS_RESPONSE =
            (Channel<List<UserBlock>, Void>) (Channel<?, ?>) registerChannelId("ASGARD_USER_BLOCKS_RESPONSE", List.class, Void.class);
}
