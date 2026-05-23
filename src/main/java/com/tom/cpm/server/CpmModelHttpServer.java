package com.tom.cpm.server;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.tom.cpm.server.admin.AdminAuthFilter;
import com.tom.cpm.server.admin.AdminHttpHandler;
import com.tom.cpm.server.model.ModelService;
import com.tom.cpm.shared.util.Log;

/**
 * Embedded HTTP server for the admin web dashboard.
 * Uses JDK HttpServer to avoid external classloader conflicts.
 */
public class CpmModelHttpServer {

    private final AdminHttpHandler adminHandler;
    private final String bindAddress;
    private final int port;
    private HttpServer server;
    private volatile boolean running;

    public CpmModelHttpServer(String bindAddress, int port, ModelService modelService,
                              char[] jwtSecret) {
        this.bindAddress = bindAddress;
        this.port = port;
        AdminAuthFilter authFilter = new AdminAuthFilter(jwtSecret);
        this.adminHandler = new AdminHttpHandler(modelService, authFilter);
    }

    public void startServer() {
        try {
            server = HttpServer.create(new InetSocketAddress(bindAddress, port), 0);
            server.createContext("/", this::handleRequest);
            server.setExecutor(Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "CPM-Admin-HTTP");
                t.setDaemon(true);
                return t;
            }));
            server.start();
            running = true;
            Log.info("CPM Admin Dashboard started on http://" + bindAddress + ":" + port);
        } catch (IOException e) {
            running = false;
            Log.error("Failed to start CPM Admin HTTP server", e);
        }
    }

    public void stopServer() {
        running = false;
        if (server != null) {
            server.stop(0);
            server = null;
        }
        Log.info("CPM Admin Dashboard stopped");
    }

    public boolean isRunning() {
        return running;
    }

    private void handleRequest(HttpExchange exchange) throws IOException {
        String uri = exchange.getRequestURI().getPath();
        String query = exchange.getRequestURI().getRawQuery();
        String method = exchange.getRequestMethod();

        Map<String, String> headers = toHeaderMap(exchange);
        byte[] bodyBytes = exchange.getRequestBody().readAllBytes();
        String body = new String(bodyBytes, StandardCharsets.UTF_8);

        // Body size limit (1MB)
        if (bodyBytes.length > 1024 * 1024) {
            writeResponse(exchange, 413, "application/json",
                "{\"error\":\"Request body too large\"}", defaultHeaders());
            return;
        }

        try {
            if (uri.startsWith("/api/admin/")) {
                AdminHttpHandler.ApiResponse response = adminHandler.handleApi(method, uri, query, headers, body);
                Map<String, String> outHeaders = defaultHeaders();
                writeResponse(exchange, response.status(), response.contentType(), response.body(), outHeaders);
                return;
            }

            if (uri.startsWith("/admin") || "/".equals(uri) || uri.endsWith(".html")) {
                StaticResponse staticResponse = serveStatic(uri);
                writeResponse(exchange, staticResponse.status, staticResponse.contentType,
                    staticResponse.body, defaultHeaders());
                return;
            }

            if ("/login".equals(uri)) {
                StaticResponse staticResponse = serveStatic("/admin/login.html");
                writeResponse(exchange, staticResponse.status, staticResponse.contentType,
                    staticResponse.body, defaultHeaders());
                return;
            }

            writeResponse(exchange, 404, "text/plain", "Not Found: " + uri, defaultHeaders());
        } catch (Exception e) {
            Log.error("Error handling HTTP request: " + uri, e);
            writeResponse(exchange, 500, "application/json",
                "{\"error\":\"Internal server error\"}", defaultHeaders());
        }
    }

    private StaticResponse serveStatic(String uri) {
        String path = uri;
        if (path.equals("/") || path.equals("/admin")) {
            path = "/admin/index.html";
        }
        if (!path.startsWith("/admin/")) {
            path = "/admin" + path;
        }

        String mime = "text/html";
        if (path.endsWith(".js")) mime = "application/javascript";
        else if (path.endsWith(".css")) mime = "text/css";
        else if (path.endsWith(".png")) mime = "image/png";
        else if (path.endsWith(".svg")) mime = "image/svg+xml";
        else if (path.endsWith(".json")) mime = "application/json";

        String resourcePath = "webapp" + path.substring("/admin".length());
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (is != null) {
                return new StaticResponse(200, mime, new String(is.readAllBytes(), StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            Log.warn("Failed to read static resource: " + resourcePath, e);
        }

        String fallback = getFallbackHtml(path);
        if (fallback != null) {
            return new StaticResponse(200, "text/html", fallback);
        }

        return new StaticResponse(404, "text/plain", "Resource not found: " + uri);
    }

    private String getFallbackHtml(String path) {
        if (path.endsWith("login.html")) return AdminPages.LOGIN;
        if (path.endsWith("index.html")) return AdminPages.INDEX;
        if (path.endsWith("model.html")) return AdminPages.MODEL_DETAIL;
        if (path.endsWith("players.html")) return AdminPages.PLAYERS;
        if (path.endsWith("audit.html")) return AdminPages.AUDIT;
        return null;
    }

    private Map<String, String> toHeaderMap(HttpExchange exchange) {
        Map<String, String> out = new HashMap<>();
        exchange.getRequestHeaders().forEach((k, v) -> {
            if (!v.isEmpty()) {
                out.put(k.toLowerCase(), v.get(0));
            }
        });
        out.put("remote-addr", exchange.getRemoteAddress() != null
            ? exchange.getRemoteAddress().getAddress().getHostAddress() : "127.0.0.1");
        return out;
    }

    private Map<String, String> defaultHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Access-Control-Allow-Origin", "*");
        headers.put("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        headers.put("Access-Control-Allow-Headers", "Content-Type, Authorization");
        return headers;
    }

    private void writeResponse(HttpExchange exchange, int status, String contentType,
                               String body, Map<String, String> headers) throws IOException {
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType + "; charset=UTF-8");
        headers.forEach((k, v) -> exchange.getResponseHeaders().set(k, v));
        exchange.sendResponseHeaders(status, data.length);
        try (var os = exchange.getResponseBody()) {
            os.write(data);
        }
    }

    private static class StaticResponse {
        final int status;
        final String contentType;
        final String body;

        StaticResponse(int status, String contentType, String body) {
            this.status = status;
            this.contentType = contentType;
            this.body = body;
        }
    }
}
