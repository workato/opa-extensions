package com.mycompany.onprem.ntlm;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Loopback-only HTTP server used by NTLM extension tests. Binds on
 * {@code 127.0.0.1} to a random port and exposes two routes:
 *
 * <ul>
 *   <li>{@code /ok} — returns {@value #OK_BODY} with HTTP 200 and bumps
 *       {@link #okHits}.</li>
 *   <li>{@code /forbidden} — returns HTTP 403 with an empty body and bumps
 *       {@link #forbiddenHits}.</li>
 * </ul>
 * <p>
 * {@link #stop()} releases the bound port but keeps {@link #port()} readable,
 * so a test that wants to simulate "upstream is down" can call {@code stop()}
 * and still address the former port in a request it expects to fail.
 */
final class LocalUpstreamServer {

    static final String OK_BODY = "upstream-ok";

    final AtomicInteger okHits = new AtomicInteger();
    final AtomicInteger forbiddenHits = new AtomicInteger();

    private final HttpServer server;
    private final int port;
    private boolean stopped;

    private LocalUpstreamServer(HttpServer server) {
        this.server = server;
        this.port = server.getAddress().getPort();
    }

    static LocalUpstreamServer start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        LocalUpstreamServer upstream = new LocalUpstreamServer(server);
        server.createContext("/ok", exchange -> {
            upstream.okHits.incrementAndGet();
            byte[] body = OK_BODY.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.createContext("/forbidden", exchange -> {
            upstream.forbiddenHits.incrementAndGet();
            exchange.sendResponseHeaders(403, -1);
            exchange.close();
        });
        server.start();
        return upstream;
    }

    int port() {
        return port;
    }

    String urlFor(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    void stop() {
        if (stopped) {
            return;
        }
        stopped = true;
        try {
            server.stop(0);
        } catch (Exception ignored) {
        }
    }
}
