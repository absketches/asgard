package io.github.absketches.asgard.service;

import io.github.absketches.asgard.AsgardChannels;
import io.github.absketches.asgard.model.RequestRecord;
import io.github.absketches.asgard.model.UserBlock;
import io.github.absketches.asgard.util.UiLoader;
import berlin.yuna.typemap.model.LinkedTypeMap;
import berlin.yuna.typemap.model.TypeList;
import berlin.yuna.typemap.model.TypeMapI;
import org.nanonative.nano.core.model.Service;
import org.nanonative.nano.helper.event.model.Event;
import org.nanonative.nano.services.http.model.ContentType;
import org.nanonative.nano.services.http.model.HttpObject;

import java.util.List;
import java.util.function.Consumer;

import org.nanonative.nano.services.http.HttpServer;

import static org.nanonative.devconsole.util.SystemUtil.computeBaseUrl;
import static org.nanonative.nano.services.http.HttpServer.EVENT_HTTP_REQUEST;

/**
 * DashboardService — serves the Asgard UI and REST API.
 *
 * API:
 *   GET  /asgard                    → index.html
 *   GET  /asgard/api/init           → last 2000 rows, all classifications
 *   GET  /asgard/api/poll?since=ts  → new rows since timestamp
 *   GET  /asgard/api/blocks         → all user-defined blocks
 *   POST /asgard/api/block          → add block: body {"host":"evil.com","note":"optional"}
 *   DELETE /asgard/api/block?host=  → remove block
 *   GET  /asgard/{file}             → static asset
 */
public class DashboardService extends Service {

    private static final int    INIT_LIMIT = 2000;
    private static final int    POLL_LIMIT = 500;
    private static final String BASE       = "/asgard";

    private Consumer<Event<HttpObject, HttpObject>> httpListener;

    @Override
    public void start() {
        try { UiLoader.load(); } catch (final Exception e) {
            context.warn(() -> "[Asgard] UI files failed to load: {}", e.getMessage());
        }

        // Wait for StorageService before accepting requests — otherwise /api/init hits null connection
        StorageService storage;
        do {
            storage = context.nano().service(StorageService.class);
        } while (null == storage || !storage.isReady());

        httpListener = context.subscribeEvent(EVENT_HTTP_REQUEST, e -> true, this::handleHttp);

        HttpServer httpServer;
        do {
            httpServer = context.nano().service(HttpServer.class);
        } while (null == httpServer || !httpServer.isReady());
        context.info(() -> "[Asgard] DashboardService started at {}{} ", computeBaseUrl(httpServer), BASE);
    }

    @Override
    public void stop() {
        context.unsubscribeEvent(EVENT_HTTP_REQUEST, httpListener);
        context.info(() -> "[Asgard] DashboardService stopped");
    }

    @Override public void configure(final TypeMapI<?> config, final TypeMapI<?> merged) {}
    @Override public void onEvent(final Event<?, ?> event) {}

    @Override
    public Object onFailure(final Event<?, ?> event) {
        context.warn(() -> "[Asgard] DashboardService error: {}", event.error());
        return null;
    }

    private void handleHttp(final Event<HttpObject, HttpObject> event) {
        event.payloadOpt()
            .filter(req -> req.path().startsWith(BASE))
            .ifPresent(req -> {
                final String path   = req.path();
                final String method = req.method();

                if (path.equals(BASE) || path.equals(BASE + "/")) {
                    serveFile(event, req, "index.html");
                } else if (path.equals(BASE + "/api/init") && "GET".equalsIgnoreCase(method)) {
                    serveInit(event, req);
                } else if (path.equals(BASE + "/api/poll") && "GET".equalsIgnoreCase(method)) {
                    servePoll(event, req);
                } else if (path.equals(BASE + "/api/blocks") && "GET".equalsIgnoreCase(method)) {
                    serveBlocks(event, req);
                } else if (path.equals(BASE + "/api/block") && "POST".equalsIgnoreCase(method)) {
                    handleAddBlock(event, req);
                } else if (path.equals(BASE + "/api/block") && "DELETE".equalsIgnoreCase(method)) {
                    handleRemoveBlock(event, req);
                } else if (path.equals(BASE + "/api/requests") && "DELETE".equalsIgnoreCase(method)) {
                    handleClearRequests(event, req);
                } else if ("GET".equalsIgnoreCase(method)) {
                    final String file = path.substring(BASE.length() + 1);
                    serveFile(event, req, file);
                } else {
                    req.createResponse().statusCode(404).body("Not Found").respond(event);
                }
            });
    }

    private void serveInit(final Event<HttpObject, HttpObject> event, final HttpObject req) {
        serveJson(event, req, toRequestList(StorageService.getPage(0, INIT_LIMIT)).toJson());
    }

    private void servePoll(final Event<HttpObject, HttpObject> event, final HttpObject req) {
        final String since = req.queryParam("since");
        if (since == null || since.isBlank()) {
            req.createResponse().statusCode(400).body("Missing 'since' parameter").respond(event);
            return;
        }
        serveJson(event, req, toRequestList(StorageService.getRequestsSince(since, POLL_LIMIT)).toJson());
    }

    private void serveBlocks(final Event<HttpObject, HttpObject> event, final HttpObject req) {
        serveJson(event, req, toBlockList(StorageService.getUserBlocks()).toJson());
    }

    private void handleAddBlock(final Event<HttpObject, HttpObject> event, final HttpObject req) {
        try {
            final var body = req.bodyAsMap();
            final String host = body.asString("host");
            if (host == null || host.isBlank()) {
                req.createResponse().statusCode(400).body("{\"error\":\"host required\"}").respond(event);
                return;
            }
            final String note = body.asString("note");
            // Fire event to StorageService — it will persist and fire USER_BLOCK_CONFIRMED
            context.newEvent(AsgardChannels.USER_BLOCK_ADD, () -> new String[]{host, note}).async(true).send();
            serveJson(event, req, new LinkedTypeMap().putR("status", "added").putR("host", host).toJson());
        } catch (final Exception e) {
            req.createResponse().statusCode(400).body("{\"error\":\"invalid body\"}").respond(event);
        }
    }

    private void handleRemoveBlock(final Event<HttpObject, HttpObject> event, final HttpObject req) {
        final String host = req.queryParam("host");
        if (host == null || host.isBlank()) {
            req.createResponse().statusCode(400).body("{\"error\":\"host required\"}").respond(event);
            return;
        }
        context.newEvent(AsgardChannels.USER_BLOCK_REMOVE, () -> host).async(true).send();
        serveJson(event, req, new LinkedTypeMap().putR("status", "removed").putR("host", host).toJson());
    }

    private void handleClearRequests(final Event<HttpObject, HttpObject> event, final HttpObject req) {
        // ?cls=ALL|NORMAL|TRACKING|SUSPICIOUS|EXFILTRATION_RISK
        final String cls = req.queryParam("cls");
        final String classification = (cls == null || cls.isBlank()) ? "ALL" : cls.toUpperCase().trim();
        context.newEvent(AsgardChannels.CLEAR_REQUESTS, () -> classification).async(true).send();
        serveJson(event, req, new LinkedTypeMap().putR("status", "cleared").putR("classification", classification).toJson());
    }

    private static TypeList toRequestList(final List<RequestRecord> records) {
        final TypeList list = new TypeList();
        records.forEach(r -> list.add(new LinkedTypeMap()
            .putR("id",             r.id())
            .putR("timestamp",      r.timestamp().toString())
            .putR("sourceIp",       r.sourceIp())
            .putR("destination",    r.destination())
            .putR("method",         r.method())
            .putR("dataSize",       r.dataSize())
            .putR("classification", r.classification().name())
            .putR("blocked",        r.blocked())));
        return list;
    }

    private static TypeList toBlockList(final List<UserBlock> blocks) {
        final TypeList list = new TypeList();
        blocks.forEach(b -> list.add(new LinkedTypeMap()
            .putR("host",      b.host())
            .putR("createdAt", b.createdAt().toString())
            .putR("note",      b.note() != null ? b.note() : "")));
        return list;
    }

    // ─────────────────────────────────────────────────────────────────────────

    private void serveFile(final Event<HttpObject, HttpObject> event, final HttpObject req, final String fileName) {
        final byte[] content = UiLoader.STATIC_FILES.get(fileName);
        if (content == null) {
            req.createResponse().statusCode(404).body("Not Found").respond(event);
            return;
        }
        req.createResponse()
            .statusCode(200)
            .header("Content-Type", UiLoader.contentTypeFor(fileName))
            .body(new String(content))
            .respond(event);
    }

    private void serveJson(final Event<HttpObject, HttpObject> event, final HttpObject req, final String json) {
        req.createResponse()
            .statusCode(200)
            .header("Content-Type", ContentType.APPLICATION_JSON.value())
            .body(json)
            .respond(event);
    }
}
