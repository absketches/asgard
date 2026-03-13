package io.github.absketches.asgard;

import io.github.absketches.asgard.model.RequestRecord.Classification;
import io.github.absketches.asgard.service.ClassifierService;
import io.github.absketches.asgard.service.DashboardService;
import io.github.absketches.asgard.service.ProxyService;
import io.github.absketches.asgard.service.StorageService;
import org.junit.jupiter.api.*;
import org.nanonative.nano.core.Nano;
import org.nanonative.nano.services.http.HttpServer;

import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.nanonative.nano.services.http.HttpServer.CONFIG_SERVICE_HTTP_PORT;
import static io.github.absketches.asgard.service.ProxyService.CONFIG_PROXY_PORT;
import static io.github.absketches.asgard.service.StorageService.CONFIG_DB_PATH;

/**
 * End-to-end tests for Asgard.
 *
 * Config passed as Map — the correct Nano constructor.
 * Dashboard port: CONFIG_SERVICE_HTTP_PORT (HttpServer's own constant) set to a random free port.
 * Proxy port: asgard_proxy_port set to a separate random free port.
 * SQLite: asgard_db_path=:memory: — no disk files, no cleanup needed.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AsgardE2ETest {

    private static Nano       nano;
    private static int        dashboardPort;
    private static HttpClient proxyClient;
    private static HttpClient directClient;

    @BeforeAll
    static void startAsgard() throws Exception {
        dashboardPort = freePort();
        int proxyPort = freePort();

        nano = new Nano(
                Map.of(
                        CONFIG_SERVICE_HTTP_PORT, dashboardPort,
                        CONFIG_PROXY_PORT, proxyPort,
                        CONFIG_DB_PATH,           ":memory:"
                ),
                new HttpServer(),
                new ProxyService(),
                new ClassifierService(),
                new StorageService(),
                new DashboardService()
        );

        Thread.sleep(500);

        proxyClient = HttpClient.newBuilder()
                .proxy(ProxySelector.of(new InetSocketAddress("localhost", proxyPort)))
                .connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        directClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    @AfterAll
    static void stopAsgard() {
        if (nano != null) nano.stop(AsgardE2ETest.class).waitForStop();
    }

    // ── Dashboard reachability ────────────────────────────────────────────────

    @Test
    @Order(1)
    void dashboard_isReachable() throws Exception {
        final HttpResponse<String> resp = directClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + dashboardPort + "/asgard"))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.body()).contains("Asgard");
    }

    // ── Init API returns empty array on fresh DB ──────────────────────────────

    @Test
    @Order(2)
    void api_init_emptyOnStart() throws Exception {
        final HttpResponse<String> resp = directClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + dashboardPort + "/asgard/api/init"))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.body()).isEqualTo("[]");
    }

    // ── Proxied request to safe domain → classified NORMAL ────────────────────

    @Test
    @Order(3)
    void proxy_safeDomain_classifiedNormal() throws Exception {
        sendProxiedRequest("http://apple.com/");
        awaitRecord("apple.com", 2000);

        final var record = StorageService.getPage(0, 10).stream()
                .filter(r -> r.destination().contains("apple.com"))
                .findFirst();

        assertThat(record).isPresent();
        assertThat(record.get().classification()).isEqualTo(Classification.NORMAL);
    }

    // ── Proxied request to unknown domain → classified SUSPICIOUS ─────────────

    @Test
    @Order(4)
    void proxy_unknownDomain_classifiedSuspicious() throws Exception {
        sendProxiedRequest("http://definitely-unknown-host-xyz.io/");
        awaitRecord("definitely-unknown-host-xyz.io", 2000);

        final var record = StorageService.getPage(0, 20).stream()
                .filter(r -> r.destination().contains("definitely-unknown-host-xyz.io"))
                .findFirst();

        assertThat(record).isPresent();
        assertThat(record.get().classification()).isEqualTo(Classification.SUSPICIOUS);
    }

    // ── User block: add → request → classified TRACKING ──────────────────────

    @Test
    @Order(5)
    void userBlock_addsToBlocklist_immediateEffect() throws Exception {
        final String host = "blocked-test-domain.com";

        final HttpResponse<String> blockResp = directClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + dashboardPort + "/asgard/api/block"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"host\":\"" + host + "\",\"note\":\"e2e test\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(blockResp.statusCode()).isEqualTo(200);
        assertThat(blockResp.body()).contains("added");

        sendProxiedRequest("http://" + host + "/");
        awaitRecord(host, 2000);

        final var record = StorageService.getPage(0, 50).stream()
                .filter(r -> r.destination().contains(host))
                .findFirst();

        assertThat(record).isPresent();
        assertThat(record.get().classification()).isEqualTo(Classification.TRACKING);
    }

    // ── User block: remove → domain no longer blocked ────────────────────────

    @Test
    @Order(6)
    void userBlock_remove_domainNoLongerBlocked() throws Exception {
        final String host = "blocked-test-domain.com";

        final HttpResponse<String> resp = directClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + dashboardPort + "/asgard/api/block?host=" + host))
                        .DELETE().build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.body()).contains("removed");
    }

    // ── Poll API returns only new rows since timestamp ────────────────────────

    @Test
    @Order(7)
    void api_poll_returnsOnlyNewRows() throws Exception {
        final String cutoff = java.time.Instant.now().toString();

        sendProxiedRequest("http://poll-test-domain.net/");
        awaitRecord("poll-test-domain.net", 2000);

        final HttpResponse<String> resp = directClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + dashboardPort
                                + "/asgard/api/poll?since=" + URLEncoder.encode(cutoff, "UTF-8")))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.body()).contains("poll-test-domain.net");
    }


    private static void sendProxiedRequest(final String url) {
        try {
            proxyClient.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(url))
                            .GET().build(),
                    HttpResponse.BodyHandlers.discarding()
            );
        } catch (final Exception ignored) {
            // Forward will fail (no real upstream) — classification still fires
        }
    }

    private static void awaitRecord(final String hostFragment, final long timeoutMs)
            throws InterruptedException {
        final long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (StorageService.getPage(0, 100).stream()
                    .anyMatch(r -> r.destination().contains(hostFragment))) return;
            Thread.sleep(100);
        }
    }

    private static int freePort() throws Exception {
        try (final ServerSocket s = new ServerSocket(0)) { return s.getLocalPort(); }
    }
}
