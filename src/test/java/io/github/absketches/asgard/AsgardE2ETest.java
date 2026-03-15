package io.github.absketches.asgard;

import io.github.absketches.asgard.dao.RequestDao;
import io.github.absketches.asgard.model.RequestRecord;
import io.github.absketches.asgard.model.RequestRecord.Classification;
import io.github.absketches.asgard.service.DashboardService;
import io.github.absketches.asgard.service.ProxyService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nanonative.nano.core.Nano;
import org.nanonative.nano.services.http.HttpClient;
import org.nanonative.nano.services.http.HttpServer;
import org.nanonative.nano.services.http.model.HttpObject;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.nanonative.nano.services.http.HttpServer.CONFIG_SERVICE_HTTP_PORT;
import static io.github.absketches.asgard.service.ProxyService.CONFIG_PROXY_PORT;

class AsgardE2ETest {

    private Nano       nano;
    private int        dashboardPort;
    private HttpClient httpClient;

    /**
     * Raw java.net.http.HttpClient used exclusively for sending traffic through
     * Asgard's proxy port. Nano's HttpClient service has no proxy configuration API.
     */
    private java.net.http.HttpClient proxyClient;

    @BeforeEach
    void start() throws Exception {
        dashboardPort = freePort();
        int proxyPort = freePort();

        RequestDao.init(":memory:");
        httpClient = new HttpClient();

        nano = new Nano(
            Map.of(
                CONFIG_SERVICE_HTTP_PORT, dashboardPort,
                CONFIG_PROXY_PORT, proxyPort
            ),
            new HttpServer(),
            httpClient,
            new ProxyService(),
            new DashboardService()
        );

        proxyClient = java.net.http.HttpClient.newBuilder()
            .proxy(ProxySelector.of(new InetSocketAddress("localhost", proxyPort)))
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
            .build();
    }

    @AfterEach
    void stop() {
        if (nano != null) nano.stop(AsgardE2ETest.class).waitForStop();
    }

    // ── Dashboard reachability ────────────────────────────────────────────────

    @Test
    void dashboard_isReachable() {
        final HttpObject resp = httpClient.send(get("http://localhost:" + dashboardPort + "/asgard"));
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.bodyAsString()).contains("Asgard");
    }

    // ── Init API returns empty array on fresh DB ──────────────────────────────

    @Test
    void api_init_emptyOnStart() {
        final HttpObject resp = httpClient.send(get("http://localhost:" + dashboardPort + "/asgard/api/init"));
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.bodyAsString()).isEqualTo("[]");
    }

    // ── Poll API: missing 'since' param → 400 ────────────────────────────────

    @Test
    void api_poll_missingSince_returns400() {
        final HttpObject resp = httpClient.send(get("http://localhost:" + dashboardPort + "/asgard/api/poll"));
        assertThat(resp.statusCode()).isEqualTo(400);
    }

    // ── Add block: missing host → 400 ────────────────────────────────────────

    @Test
    void api_addBlock_missingHost_returns400() {
        final HttpObject resp = httpClient.send(
            postJson("http://localhost:" + dashboardPort + "/asgard/api/block", "{\"note\":\"no host\"}")
        );
        assertThat(resp.statusCode()).isEqualTo(400);
    }

    // ── Proxied request to unknown domain → classified SUSPICIOUS ────────────

    @Test
    void proxy_unknownDomain_classifiedSuspicious() throws Exception {
        sendViaProxy("http://definitely-unknown-xyzzy-test.io/");

        final RequestRecord record = awaitRecord("definitely-unknown-xyzzy-test.io", 5_000);

        assertThat(record.classification()).isEqualTo(Classification.SUSPICIOUS);
        assertThat(record.blocked()).isFalse();
    }

    // ── Proxied request to known safe domain → classified NORMAL ─────────────
    // We add the domain to the user blocklist then immediately remove it so the
    // test is self-contained, but more importantly: we proxy a request to a known
    // safe domain suffix (icloud.com) and verify it comes through as NORMAL.
    // icloud.com is in SAFE_DOMAINS — connection will fail upstream but the proxy
    // records it before attempting to forward, so the record arrives immediately.

    @Test
    void proxy_safeDomain_classifiedNormal() throws Exception {
        // Use a subdomain of a known safe domain — no real network call succeeds,
        // but ClassifierHelper sees the suffix and returns NORMAL before forwarding.
        final String host = "test-" + System.nanoTime() + ".icloud.com";
        sendViaProxy("http://" + host + "/");

        final RequestRecord record = awaitRecord(host, 5_000);

        assertThat(record.classification()).isEqualTo(Classification.NORMAL);
    }

    // ── Block add → host appears in /api/blocks ───────────────────────────────

    @Test
    void api_addBlock_appearsInBlockList() {
        final String host = "evil-" + System.nanoTime() + ".com";

        final HttpObject addResp = httpClient.send(
            postJson("http://localhost:" + dashboardPort + "/asgard/api/block",
                "{\"host\":\"" + host + "\",\"note\":\"e2e\"}")
        );
        assertThat(addResp.statusCode()).isEqualTo(200);
        assertThat(addResp.bodyAsString()).contains("added");

        final HttpObject blocks = httpClient.send(
            get("http://localhost:" + dashboardPort + "/asgard/api/blocks")
        );
        assertThat(blocks.bodyAsString()).contains(host);
    }

    // ── Block add → proxy request → classified TRACKING ──────────────────────

    @Test
    void proxy_blockedDomain_classifiedTracking() throws Exception {
        final String host = "blocked-" + System.nanoTime() + ".com";

        httpClient.send(postJson(
            "http://localhost:" + dashboardPort + "/asgard/api/block",
            "{\"host\":\"" + host + "\",\"note\":\"e2e\"}"
        ));

        sendViaProxy("http://" + host + "/");

        final RequestRecord record = awaitRecord(host, 5_000);

        assertThat(record.classification()).isEqualTo(Classification.TRACKING);
    }

    // ── Block remove → host disappears from /api/blocks ──────────────────────

    @Test
    void api_removeBlock_disappearsFromBlockList() {
        final String host = "removable-" + System.nanoTime() + ".com";

        httpClient.send(postJson(
            "http://localhost:" + dashboardPort + "/asgard/api/block",
            "{\"host\":\"" + host + "\"}"
        ));

        final HttpObject removeResp = httpClient.send(
            delete("http://localhost:" + dashboardPort
                + "/asgard/api/block?host=" + URLEncoder.encode(host, StandardCharsets.UTF_8))
        );
        assertThat(removeResp.statusCode()).isEqualTo(200);
        assertThat(removeResp.bodyAsString()).contains("removed");

        assertThat(httpClient.send(
            get("http://localhost:" + dashboardPort + "/asgard/api/blocks")
        ).bodyAsString()).doesNotContain(host);
    }

    // ── Block remove → subsequent proxy request → classified SUSPICIOUS ───────

    @Test
    void proxy_afterBlockRemove_classifiedSuspicious() throws Exception {
        final String host = "unblock-" + System.nanoTime() + ".com";

        httpClient.send(postJson(
            "http://localhost:" + dashboardPort + "/asgard/api/block",
            "{\"host\":\"" + host + "\"}"
        ));
        httpClient.send(delete(
            "http://localhost:" + dashboardPort
                + "/asgard/api/block?host=" + URLEncoder.encode(host, StandardCharsets.UTF_8)
        ));

        sendViaProxy("http://" + host + "/");

        final RequestRecord record = awaitRecord(host, 5_000);

        assertThat(record.classification()).isEqualTo(Classification.SUSPICIOUS);
    }

    // ── Poll API returns only rows after the cutoff timestamp ─────────────────

    @Test
    void api_poll_returnsOnlyRowsAfterCutoff() throws Exception {
        final String beforeHost = "before-" + System.nanoTime() + ".net";
        sendViaProxy("http://" + beforeHost + "/");
        awaitRecord(beforeHost, 5_000);

        final String cutoff = Instant.now().toString();

        final String afterHost = "after-" + System.nanoTime() + ".net";
        sendViaProxy("http://" + afterHost + "/");
        awaitRecord(afterHost, 5_000);

        final HttpObject resp = httpClient.send(get(
            "http://localhost:" + dashboardPort
                + "/asgard/api/poll?since=" + URLEncoder.encode(cutoff, StandardCharsets.UTF_8)
        ));
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.bodyAsString()).contains(afterHost);
        assertThat(resp.bodyAsString()).doesNotContain(beforeHost);
    }

    // ── Clear requests removes records from the DB ────────────────────────────

    @Test
    void api_clearRequests_removesRecords() throws Exception {
        final String host = "clearme-" + System.nanoTime() + ".com";
        sendViaProxy("http://" + host + "/");
        awaitRecord(host, 5_000);

        assertThat(RequestDao.getPage(0, 100).stream()
            .anyMatch(r -> r.destination().contains(host))).isTrue();

        final HttpObject clearResp = httpClient.send(
            delete("http://localhost:" + dashboardPort + "/asgard/api/requests?cls=ALL")
        );
        assertThat(clearResp.statusCode()).isEqualTo(200);

        assertThat(RequestDao.getPage(0, 100).stream()
            .anyMatch(r -> r.destination().contains(host))).isFalse();
    }

    // ── Init API reflects proxied requests ────────────────────────────────────

    @Test
    void api_init_reflectsProxiedRequests() throws Exception {
        final String host = "init-check-" + System.nanoTime() + ".com";
        sendViaProxy("http://" + host + "/");
        awaitRecord(host, 5_000);

        final HttpObject resp = httpClient.send(
            get("http://localhost:" + dashboardPort + "/asgard/api/init")
        );
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.bodyAsString()).contains(host);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Sends a GET through Asgard's proxy port.
     * Upstream failures are ignored — ProxyService accepts the connection and
     * records + classifies it regardless of whether the upstream host resolves.
     */
    private void sendViaProxy(final String url) {
        try {
            proxyClient.send(get(url), HttpResponse.BodyHandlers.discarding());
        } catch (final Exception ignored) {
            // Expected for fake test domains — upstream won't resolve.
        }
    }

    /** Polls RequestDao until a record matching hostFragment appears, or throws. */
    private static RequestRecord awaitRecord(final String hostFragment, final long timeoutMs)
        throws InterruptedException {
        final long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            final var match = RequestDao.getPage(0, 200).stream()
                .filter(r -> r.destination().contains(hostFragment))
                .findFirst();
            if (match.isPresent()) return match.get();
            Thread.sleep(100);
        }
        throw new AssertionError("No record for '" + hostFragment + "' appeared within " + timeoutMs + "ms");
    }

    // ── Request builders ──────────────────────────────────────────────────────

    private static HttpRequest get(final String url) {
        return HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
    }

    private static HttpRequest postJson(final String url, final String body) {
        return HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    }

    private static HttpRequest delete(final String url) {
        return HttpRequest.newBuilder().uri(URI.create(url)).DELETE().build();
    }

    private static int freePort() throws Exception {
        try (final ServerSocket s = new ServerSocket(0)) { return s.getLocalPort(); }
    }
}
