package io.github.absketches.asgard.service;

import io.github.absketches.asgard.model.RequestRecord;
import io.github.absketches.asgard.model.RequestRecord.Classification;
import berlin.yuna.typemap.model.TypeMapI;
import io.github.absketches.asgard.util.ClassifierHelper;
import org.nanonative.nano.core.model.Service;
import org.nanonative.nano.helper.event.model.Event;

import java.io.*;
import java.net.*;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.nanonative.nano.helper.config.ConfigRegister.registerConfig;

/**
 * ProxyService — raw TCP proxy using ServerSocket + virtual threads.
 * Runs independently of Nano's HttpServer (which is dashboard-only on port 8080).
 * Listens on port 8888. User points their device proxy settings here.
 * Two connection types:
 *
 *   Plain HTTP  — proxy reads the full request, forwards to upstream server,
 *                 relays response back. Full visibility: method, path, headers, body size.
 *
 *   HTTPS CONNECT — browser sends CONNECT host:443, proxy opens TCP to upstream,
 *                   responds "200 Connection Established", then becomes a dumb pipe.
 *                   Two virtual threads copy bytes in both directions.
 */
public class ProxyService extends Service {

    public static final String CONFIG_PROXY_PORT      = registerConfig("asgard_proxy_port",           "Proxy listen port (default 8888)");
    public static final String CONFIG_CONNECT_TIMEOUT = registerConfig("asgard_connect_timeout_ms",   "Upstream connect timeout ms (default 10000)");
    public static final String CONFIG_READ_TIMEOUT    = registerConfig("asgard_read_timeout_ms",      "Upstream read timeout ms (default 30000)");

    private static final int DEFAULT_PORT            = 8888;
    private static final int DEFAULT_CONNECT_TIMEOUT = 10_000;
    private static final int DEFAULT_READ_TIMEOUT    = 30_000;
    private static final int BUFFER_SIZE             = 8192;

    private int proxyPort      = DEFAULT_PORT;
    private int connectTimeout = DEFAULT_CONNECT_TIMEOUT;
    private int readTimeout    = DEFAULT_READ_TIMEOUT;

    private ServerSocket          serverSocket;
    private final AtomicBoolean   running = new AtomicBoolean(false);

    @Override
    public void configure(final TypeMapI<?> config, final TypeMapI<?> merged) {
        proxyPort      = merged.asIntOpt(CONFIG_PROXY_PORT).orElse(DEFAULT_PORT);
        connectTimeout = merged.asIntOpt(CONFIG_CONNECT_TIMEOUT).orElse(DEFAULT_CONNECT_TIMEOUT);
        readTimeout    = merged.asIntOpt(CONFIG_READ_TIMEOUT).orElse(DEFAULT_READ_TIMEOUT);
    }

    @Override
    public void start() {
        try {
            serverSocket = new ServerSocket(proxyPort);
            serverSocket.setReuseAddress(true);
            running.set(true);
            Thread.ofVirtual().name("asgard-proxy-accept").start(this::acceptLoop);
            context.info(() -> "[Asgard] ProxyService listening on port {}", proxyPort);
        } catch (final IOException e) {
            throw new RuntimeException("[Asgard] Failed to bind proxy on port " + proxyPort, e);
        }
    }

    @Override
    public void stop() {
        running.set(false);
        try {
            if (serverSocket != null && !serverSocket.isClosed()) serverSocket.close();
        } catch (final IOException ignored) {}
        context.info(() -> "[Asgard] ProxyService stopped");
    }

    @Override public void onEvent(final Event<?, ?> event) {}

    @Override
    public Object onFailure(final Event<?, ?> event) {
        context.warn(() -> "[Asgard] ProxyService error: {}", event.error());
        return null;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Accept loop
    // ─────────────────────────────────────────────────────────────────────────

    private void acceptLoop() {
        while (running.get()) {
            try {
                final Socket client = serverSocket.accept();
                Thread.ofVirtual().name("asgard-proxy-conn").start(() -> handleConnection(client));
            } catch (final IOException e) {
                if (running.get()) context.warn(() -> "[Asgard] Accept error: {}", e.getMessage());
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Connection handler
    // ─────────────────────────────────────────────────────────────────────────

    private void handleConnection(final Socket client) {
        try {
            client.setSoTimeout(readTimeout);
            final String sourceIp = ((InetSocketAddress) client.getRemoteSocketAddress())
                .getAddress().getHostAddress();

            final InputStream  clientIn  = client.getInputStream();
            final OutputStream clientOut = client.getOutputStream();

            final String requestLine = readLine(clientIn);
            if (requestLine == null || requestLine.isBlank()) { client.close(); return; }

            final String[] parts = requestLine.split(" ");
            if (parts.length < 2) { client.close(); return; }

            final String method = parts[0].toUpperCase();
            final String target = parts[1];
            final String host   = "CONNECT".equals(method) ? target.split(":")[0] : extractHost(target);

            if (ClassifierHelper.isBlocked(host)) {
                final String body = "Blocked by Asgard";
                clientOut.write(("HTTP/1.1 403 Forbidden\r\nContent-Length: " + body.length()
                    + "\r\nContent-Type: text/plain\r\nConnection: close\r\n\r\n" + body).getBytes());
                clientOut.flush();
                record(host, method, sourceIp, 0);
                return;
            }

            handleRequest(client, clientIn, clientOut, method, target, sourceIp);
        } catch (final IOException e) {
            context.warn(() -> "[Asgard] Connection error: {}", e.getMessage());
        } finally {
            closeQuietly(client);
        }
    }


    private void handleRequest(
        final Socket client,
        final InputStream clientIn,
        final OutputStream clientOut,
        final String method,
        final String target,
        final String sourceIp
    ) throws IOException {
        final boolean isTunnel = "CONNECT".equals(method);

        // ── Parse host/port ───────────────────────────────────────────────────
        final String host;
        final int    port;
        final String path;
        if (isTunnel) {
            final int colon = target.lastIndexOf(':');
            host = colon >= 0 ? target.substring(0, colon) : target;
            port = colon >= 0 ? parsePort(target.substring(colon + 1)) : 443;
            path = null;
        } else {
            final URL url;
            try { url = new URL(target); } catch (final MalformedURLException e) {
                sendError(clientOut, 400, "Bad Request");
                return;
            }
            host = url.getHost();
            port = url.getPort() != -1 ? url.getPort() : 80;
            path = url.getFile().isEmpty() ? "/" : url.getFile();
        }

        // ── Read client headers (HTTP: keep for forwarding; CONNECT: drain only) ──
        final StringBuilder headers = new StringBuilder();
        long contentLength = 0;
        String line;
        while (!(line = readLine(clientIn)).isEmpty()) {
            if (!isTunnel) {
                headers.append(line).append("\r\n");
                if (line.toLowerCase().startsWith("content-length:")) {
                    try { contentLength = Long.parseLong(line.substring(15).trim()); }
                    catch (final NumberFormatException ignored) {}
                }
            }
        }

        // ── Read body (HTTP only) ─────────────────────────────────────────────
        final byte[] body     = (!isTunnel && contentLength > 0) ? clientIn.readNBytes((int) contentLength) : new byte[0];
        final long   dataSize = body.length;

        record(host, method, sourceIp, dataSize);

        // ── Open upstream connection ──────────────────────────────────────────
        final Socket upstream = new Socket();
        try {
            upstream.connect(new InetSocketAddress(host, port), connectTimeout);
            upstream.setSoTimeout(readTimeout);
        } catch (final IOException e) {
            sendError(clientOut, 502, "Bad Gateway");
            context.warn(() -> "[Asgard] Upstream connect failed for {}: {}", target, e.getMessage());
            closeQuietly(upstream);
            return;
        }

        // ── Forward and relay ─────────────────────────────────────────────────
        try {
            final InputStream  upstreamIn  = upstream.getInputStream();
            final OutputStream upstreamOut = upstream.getOutputStream();

            if (isTunnel) {
                clientOut.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes());
                clientOut.flush();

                final Thread toUpstream = Thread.ofVirtual().name("asgard-pipe-up")
                    .start(() -> pipeAndClose(clientIn, upstreamOut, upstream));
                final Thread toClient = Thread.ofVirtual().name("asgard-pipe-down")
                    .start(() -> pipeAndClose(upstreamIn, clientOut, client));
                try {
                    toUpstream.join();
                    toClient.join();
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            } else {
                upstreamOut.write((method + " " + path + " HTTP/1.1\r\n").getBytes());
                upstreamOut.write(("Host: " + host + "\r\n").getBytes());
                upstreamOut.write("Connection: close\r\n".getBytes());
                for (final String h : headers.toString().split("\r\n")) {
                    final String lower = h.toLowerCase();
                    if (!lower.startsWith("host:") && !lower.startsWith("connection:")
                        && !lower.startsWith("proxy-")) {
                        upstreamOut.write((h + "\r\n").getBytes());
                    }
                }
                upstreamOut.write("\r\n".getBytes());
                if (body.length > 0) upstreamOut.write(body);
                upstreamOut.flush();

                final String statusLine = readLine(upstreamIn);
                clientOut.write((statusLine + "\r\n").getBytes());
                pipe(upstreamIn, clientOut);
                clientOut.flush();
            }
        } catch (final IOException e) {
            sendError(clientOut, 502, "Bad Gateway");
            context.warn(() -> "[Asgard] Forward failed for {}: {}", target, e.getMessage());
        } finally {
            closeQuietly(upstream);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Classify + persist
    // ─────────────────────────────────────────────────────────────────────────

    private static void record(
        final String destination,
        final String method,
        final String sourceIp,
        final long dataSize
    ) {
        final RequestRecord raw = new RequestRecord(
            UUID.randomUUID().toString(),
            Instant.now(),
            sourceIp,
            destination,
            method,
            dataSize,
            Classification.NORMAL,  // placeholder — ClassifierHelper will override
            false
        );
        ClassifierHelper.classifyAndPersist(raw);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // I/O helpers
    // ─────────────────────────────────────────────────────────────────────────

    private static String extractHost(final String target) {
        try {
            final String url = target.startsWith("http") ? target : "http://" + target;
            return new URL(url).getHost();
        } catch (final Exception e) {
            final String stripped = target.replaceFirst("https?://", "").split("/")[0];
            return stripped.contains(":") ? stripped.split(":")[0] : stripped;
        }
    }

    private static String readLine(final InputStream in) throws IOException {
        final StringBuilder sb = new StringBuilder();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') return sb.toString().stripTrailing();
            sb.append((char) b);
        }
        return !sb.isEmpty() ? sb.toString() : null;
    }

    private static void pipe(final InputStream in, final OutputStream out) throws IOException {
        final byte[] buf = new byte[BUFFER_SIZE];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
    }

    private static void pipeAndClose(
        final InputStream in,
        final OutputStream out,
        final Socket socketToClose
    ) {
        try { pipe(in, out); out.flush(); }
        catch (final IOException ignored) {}
        finally { closeQuietly(socketToClose); }
    }

    private static void sendError(final OutputStream out, final int code, final String msg) {
        try {
            out.write(("HTTP/1.1 " + code + " " + msg + "\r\nContent-Length: 0\r\n\r\n").getBytes());
            out.flush();
        } catch (final IOException ignored) {}
    }

    private static void closeQuietly(final Closeable c) {
        try { if (c != null) c.close(); } catch (final IOException ignored) {}
    }

    private static int parsePort(final String s) {
        try { return Integer.parseInt(s.trim()); } catch (final NumberFormatException e) { return 443; }
    }
}
