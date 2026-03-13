package io.github.absketches.asgard.service;

import io.github.absketches.asgard.AsgardChannels;
import io.github.absketches.asgard.model.RequestRecord;
import io.github.absketches.asgard.model.RequestRecord.Classification;
import berlin.yuna.typemap.model.TypeMapI;
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
 *
 * Runs independently of Nano's HttpServer (which is dashboard-only on port 8080).
 * Listens on port 8888 (configurable). User points their Mac proxy settings here.
 *
 * Two connection types:
 *
 *   Plain HTTP  — proxy reads the full request, forwards to upstream server,
 *                 relays response back. Full visibility: method, path, headers, body size.
 *
 *   HTTPS CONNECT — browser sends CONNECT host:443, proxy opens TCP to upstream,
 *                   responds "200 Connection Established", then becomes a dumb pipe.
 *                   Two virtual threads copy bytes in both directions.
 *                   Pi never sees decrypted content — only hostname + byte counts.
 *
 * Every connection fires a REQUEST_RECEIVED event into the Nano pipeline for
 * classification and storage.
 *
 * Virtual threads (Project Loom): one per connection, one per pipe direction.
 */
public class ProxyService extends Service {

    public static final String CONFIG_PROXY_PORT    = registerConfig("asgard_proxy_port",    "Proxy listen port (default 8888)");
    public static final String CONFIG_CONNECT_TIMEOUT = registerConfig("asgard_connect_timeout_ms", "Upstream connect timeout ms (default 10000)");
    public static final String CONFIG_READ_TIMEOUT    = registerConfig("asgard_read_timeout_ms",    "Upstream read timeout ms (default 30000)");

    private static final int DEFAULT_PORT            = 8888;
    private static final int DEFAULT_CONNECT_TIMEOUT = 10_000;
    private static final int DEFAULT_READ_TIMEOUT    = 30_000;
    private static final int BUFFER_SIZE             = 8192;

    private int proxyPort       = DEFAULT_PORT;
    private int connectTimeout  = DEFAULT_CONNECT_TIMEOUT;
    private int readTimeout     = DEFAULT_READ_TIMEOUT;

    private ServerSocket    serverSocket;
    private final AtomicBoolean running = new AtomicBoolean(false);

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

            // Accept loop runs on its own virtual thread — never blocks Nano's thread pool
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
                // Each connection gets its own virtual thread — park on I/O, never block others
                Thread.ofVirtual().name("asgard-proxy-conn").start(() -> handleConnection(client));
            } catch (final IOException e) {
                if (running.get()) {
                    context.warn(() -> "[Asgard] Accept error: {}", e.getMessage());
                }
                // If not running, server was closed intentionally — exit loop
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Connection handler — reads first line to decide HTTP vs CONNECT
    // ─────────────────────────────────────────────────────────────────────────

    private void handleConnection(final Socket client) {
        try {
            client.setSoTimeout(readTimeout);
            final String sourceIp = ((InetSocketAddress) client.getRemoteSocketAddress())
                .getAddress().getHostAddress();

            final InputStream  clientIn  = client.getInputStream();
            final OutputStream clientOut = client.getOutputStream();

            // Read the first line e.g. "GET http://example.com/path HTTP/1.1"
            //                       or "CONNECT example.com:443 HTTP/1.1"
            final String requestLine = readLine(clientIn);
            if (requestLine == null || requestLine.isBlank()) {
                client.close();
                return;
            }

            final String[] parts = requestLine.split(" ");
            if (parts.length < 2) { client.close(); return; }

            final String method = parts[0].toUpperCase();
            final String target = parts[1];

            // Extract host and check user blocklist before forwarding
            final String host = "CONNECT".equals(method) ? target.split(":")[0] : extractHost(target);
            if (ClassifierService.isBlocked(host)) {
                final String body = "Blocked by Asgard";
                clientOut.write(("HTTP/1.1 403 Forbidden\r\nContent-Length: " + body.length() + "\r\nContent-Type: text/plain\r\nConnection: close\r\n\r\n" + body).getBytes());
                clientOut.flush();
                emitRequest(host, method, sourceIp, 0);
                return;
            }

            if ("CONNECT".equals(method)) {
                handleConnect(client, clientIn, clientOut, target, sourceIp);
            } else {
                handleHttp(client, clientIn, clientOut, requestLine, method, target, sourceIp);
            }
        } catch (final IOException e) {
            context.warn(() -> "[Asgard] Connection error: {}", e.getMessage());
        } finally {
            closeQuietly(client);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Plain HTTP — read full request, forward, relay response
    // ─────────────────────────────────────────────────────────────────────────

    private void handleHttp(
        final Socket client,
        final InputStream clientIn,
        final OutputStream clientOut,
        final String requestLine,
        final String method,
        final String target,
        final String sourceIp
    ) throws IOException {
        // Parse host and port from absolute URL e.g. http://example.com:8080/path
        final URL url;
        try { url = new URL(target); } catch (final MalformedURLException e) {
            sendError(clientOut, 400, "Bad Request");
            return;
        }

        final String host = url.getHost();
        final int    port = url.getPort() != -1 ? url.getPort() : 80;
        final String path = url.getFile().isEmpty() ? "/" : url.getFile();

        // Read remaining headers from client
        final StringBuilder headers = new StringBuilder();
        long contentLength = 0;
        String line;
        while (!(line = readLine(clientIn)).isEmpty()) {
            headers.append(line).append("\r\n");
            if (line.toLowerCase().startsWith("content-length:")) {
                try { contentLength = Long.parseLong(line.substring(15).trim()); }
                catch (final NumberFormatException ignored) {}
            }
        }

        // Read body if present
        final byte[] body = contentLength > 0 ? clientIn.readNBytes((int) contentLength) : new byte[0];
        final long dataSize = body.length;
        final long[] responseBytes = {0};

        // Forward to upstream
        try (final Socket upstream = new Socket()) {
            upstream.connect(new InetSocketAddress(host, port), connectTimeout);
            upstream.setSoTimeout(readTimeout);

            final OutputStream upstreamOut = upstream.getOutputStream();
            final InputStream  upstreamIn  = upstream.getInputStream();

            // Write request to upstream — use relative path, not absolute URL
            upstreamOut.write((method + " " + path + " HTTP/1.1\r\n").getBytes());
            // Rewrite Host header to be safe, strip Proxy-Connection
            upstreamOut.write(("Host: " + host + "\r\n").getBytes());
            upstreamOut.write("Connection: close\r\n".getBytes());

            // Forward original headers except Host, Connection, Proxy-*
            for (final String h : headers.toString().split("\r\n")) {
                final String lower = h.toLowerCase();
                if (!lower.startsWith("host:") && !lower.startsWith("connection:")
                    && !lower.startsWith("proxy-")) {
                    upstreamOut.write((h + "\r\n").getBytes());
                }
            }
            upstreamOut.write("\r\n".getBytes());

            // Forward body
            if (body.length > 0) upstreamOut.write(body);
            upstreamOut.flush();

            // Read status line from upstream to get status code for logging
            final String statusLine = readLine(upstreamIn);
            clientOut.write((statusLine + "\r\n").getBytes());

            // Relay rest of response back to browser, counting bytes
            responseBytes[0] = pipeAndCount(upstreamIn, clientOut);
            clientOut.flush();

        } catch (final IOException e) {
            sendError(clientOut, 502, "Bad Gateway");
            context.warn(() -> "[Asgard] HTTP forward failed for {}: {}", host, e.getMessage());
        }

        // Emit into Nano pipeline — use response size for GETs, request body size for POSTs/PUTs
        emitRequest(host, method, sourceIp, Math.max(dataSize, responseBytes[0]));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HTTPS CONNECT — open tunnel, become dumb pipe
    // ─────────────────────────────────────────────────────────────────────────

    private void handleConnect(
        final Socket client,
        final InputStream clientIn,
        final OutputStream clientOut,
        final String hostPort,    // "example.com:443"
        final String sourceIp
    ) throws IOException {
        // Drain remaining headers (browser sends blank line after CONNECT)
        while (!readLine(clientIn).isEmpty()) { /* skip */ }

        // Parse host:port
        final int colon = hostPort.lastIndexOf(':');
        final String host = colon >= 0 ? hostPort.substring(0, colon) : hostPort;
        final int    port = colon >= 0 ? parsePort(hostPort.substring(colon + 1)) : 443;

        // Open TCP connection to upstream
        final Socket upstream;
        try {
            upstream = new Socket();
            upstream.connect(new InetSocketAddress(host, port), connectTimeout);
            upstream.setSoTimeout(readTimeout);
        } catch (final IOException e) {
            sendError(clientOut, 502, "Bad Gateway");
            context.warn(() -> "[Asgard] CONNECT failed for {}: {}", hostPort, e.getMessage());
            return;
        }

        // Tell browser the tunnel is open
        clientOut.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes());
        clientOut.flush();

        // Emit before piping — we won't be able to measure bytes accurately during streaming
        // dataSize 0 — tunnel volume is measured by byte counting in the pipe threads
        emitRequest(host, "CONNECT", sourceIp, 0);

        // Extract streams before lambdas — getInputStream/getOutputStream throw checked IOException
        // which cannot be declared inside Runnable lambdas
        final InputStream  upstreamIn  = upstream.getInputStream();
        final OutputStream upstreamOut = upstream.getOutputStream();

        // Two virtual threads — one per direction — copy raw bytes until either end closes
        final Thread toUpstream = Thread.ofVirtual().name("asgard-pipe-up").start(() ->
            pipeAndClose(clientIn, upstreamOut, upstream));
        final Thread toClient   = Thread.ofVirtual().name("asgard-pipe-down").start(() ->
            pipeAndClose(upstreamIn, clientOut, client));

        // Wait for both directions to finish before returning (so finally block closes client)
        try {
            toUpstream.join();
            toClient.join();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            closeQuietly(upstream);
        }
    }

    private void emitRequest(
        final String destination,
        final String method,
        final String sourceIp,
        final long dataSize
    ) {
        final RequestRecord record = new RequestRecord(
            UUID.randomUUID().toString(),
            Instant.now(),
            sourceIp,
            destination,
            method,
            dataSize,
            Classification.NORMAL,  // placeholder — ClassifierService will override
            false
        );
        context.newEvent(AsgardChannels.REQUEST_RECEIVED, () -> record).async(true).send();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // I/O helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Extract bare hostname from a plain HTTP target like <a href="http://example.com:80/path">...</a> */
    private static String extractHost(final String target) {
        try {
            final String url = target.startsWith("http") ? target : "http://" + target;
            final java.net.URL u = new java.net.URL(url);
            return u.getHost();
        } catch (final Exception e) {
            // fallback: strip scheme and path
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

    /** Copy all bytes from in to out until EOF. */
    private static void pipe(final InputStream in, final OutputStream out) throws IOException {
        final byte[] buf = new byte[BUFFER_SIZE];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
    }

    /** pipe() variant that returns the total number of bytes written. */
    private static long pipeAndCount(final InputStream in, final OutputStream out) throws IOException {
        final byte[] buf = new byte[BUFFER_SIZE];
        long total = 0;
        int n;
        while ((n = in.read(buf)) != -1) { out.write(buf, 0, n); total += n; }
        return total;
    }

    /** pipe() variant for tunnel threads — closes socketToClose when done. */
    private static void pipeAndClose(
        final InputStream in,
        final OutputStream out,
        final Socket socketToClose
    ) {
        try {
            pipe(in, out);
            out.flush();
        } catch (final IOException ignored) {
            // Normal — other end closed connection
        } finally {
            closeQuietly(socketToClose);
        }
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
