package io.github.absketches.asgard;

import io.github.absketches.asgard.service.DashboardService;
import io.github.absketches.asgard.service.ProxyService;
import io.github.absketches.asgard.dao.RequestDao;
import org.nanonative.nano.core.Nano;
import org.nanonative.nano.services.http.HttpClient;
import org.nanonative.nano.services.http.HttpServer;

import java.util.Map;

import static org.nanonative.nano.services.http.HttpServer.CONFIG_SERVICE_HTTP_PORT;
import static io.github.absketches.asgard.service.ProxyService.CONFIG_PROXY_PORT;

/**
 * Asgard — lightweight HTTP/HTTPS traffic monitor
 * Two listeners:
 *   :8080  Nano HttpServer   — dashboard UI + REST API (/asgard/*)
 *   :8888  ProxyService      — raw ServerSocket, all proxy traffic (HTTP + HTTPS CONNECT)
 * Mac proxy setup: System Settings → Network → Wi-Fi → Proxies
 *   Web Proxy:        <pi-ip>:8888
 *   Secure Web Proxy: <pi-ip>:8888
 */
public class Main {

    public static void main(final String[] args) {
        RequestDao.init("asgard.db");

        new Nano(
                Map.of(
                        CONFIG_SERVICE_HTTP_PORT, 8080,
                        CONFIG_PROXY_PORT,        8888
                ),
                new HttpClient(),
                new HttpServer(),
                new ProxyService(),
                new DashboardService()
        );
    }
}
