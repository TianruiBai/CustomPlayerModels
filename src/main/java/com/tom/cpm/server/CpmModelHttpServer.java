package com.tom.cpm.server;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

import com.tom.cpm.server.admin.AdminAuthFilter;
import com.tom.cpm.server.admin.AdminHttpHandler;
import com.tom.cpm.server.model.ModelService;
import com.tom.cpm.shared.util.Log;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.Response;
import fi.iki.elonen.NanoHTTPD.IHTTPSession;

/**
 * Embedded HTTP server for the admin web dashboard.
 * Uses NanoHTTPD for simplicity (single-file, no extra deps beyond the JAR).
 * 
 * Binds to localhost by default for security. Model data never flows through
 * this server — it only serves the admin management UI and API.
 * 
 * Routes:
 *   GET  /admin              → SPA index page
 *   GET  /admin/*            → Static assets (JS, CSS)
 *   POST /api/admin/login    → Authenticate
 *   GET  /api/admin/models   → List all models
 *   GET  /api/admin/models/{id} → Model detail
 *   DELETE /api/admin/models/{id} → Delete model
 *   PUT  /api/admin/models/{id}/force → Force/unforce
 *   PUT  /api/admin/players/{uuid}/block → Block/unblock player
 *   GET  /api/admin/audit    → Audit log
 *   GET  /api/admin/db/stats → Database statistics
 *   POST /api/admin/db/backup → Trigger backup
 */
public class CpmModelHttpServer extends NanoHTTPD {

    private final AdminHttpHandler adminHandler;
    private final AdminAuthFilter authFilter;
    private final String bindAddress;
    private volatile boolean running;

    public CpmModelHttpServer(String bindAddress, int port, ModelService modelService,
                               char[] jwtSecret) {
        super(bindAddress, port);
        this.bindAddress = bindAddress;
        this.authFilter = new AdminAuthFilter(jwtSecret);
        this.adminHandler = new AdminHttpHandler(modelService, authFilter);
    }

    /**
     * Start the HTTP server in a daemon thread.
     */
    public void startServer() {
        try {
            start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
            running = true;
            Log.info("CPM Admin Dashboard started on http://" + bindAddress + ":" + getListeningPort());
        } catch (IOException e) {
            Log.error("Failed to start CPM Admin HTTP server", e);
            running = false;
        }
    }

    /**
     * Stop the HTTP server.
     */
    public void stopServer() {
        running = false;
        stop();
        Log.info("CPM Admin Dashboard stopped");
    }

    public boolean isRunning() {
        return running;
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        Method method = session.getMethod();

        // Size limit for request bodies (prevents memory exhaustion)
        String contentLength = session.getHeaders().get("content-length");
        if (contentLength != null) {
            try {
                long len = Long.parseLong(contentLength);
                if (len > 1024 * 1024) { // 1 MB max body
                    return newFixedLengthResponse(Response.Status.PAYLOAD_TOO_LARGE,
                        "application/json", "{\"error\":\"Request body too large\"}");
                }
            } catch (NumberFormatException ignored) {}
        }

        try {
            // CORS headers for local development
            Response r = null;

            // Admin API routes
            if (uri.startsWith("/api/admin/")) {
                r = adminHandler.handleApi(method, uri, session);
            }
            // Static SPA assets
            else if (uri.startsWith("/admin") || uri.equals("/") || uri.endsWith(".html")) {
                r = serveStatic(uri);
            }
            // Login page redirect
            else if (uri.equals("/login") || uri.equals("/")) {
                r = serveStatic("/admin/login.html");
            }

            if (r != null) {
                r.addHeader("Access-Control-Allow-Origin", "*");
                r.addHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
                r.addHeader("Access-Control-Allow-Headers", "Content-Type, Authorization");
                return r;
            }

            return newFixedLengthResponse(Response.Status.NOT_FOUND,
                "text/plain", "Not Found: " + uri);
        } catch (Exception e) {
            Log.error("Error handling HTTP request: " + uri, e);
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR,
                "application/json", "{\"error\":\"Internal server error\"}");
        }
    }

    /**
     * Serve a static file from the bundled webapp resources.
     * Files are loaded from the classpath at /webapp/.
     */
    private Response serveStatic(String uri) {
        // Normalize path
        String path = uri;
        if (path.equals("/") || path.equals("/admin")) {
            path = "/admin/index.html";
        }
        if (!path.startsWith("/admin/")) {
            path = "/admin" + path;
        }

        // Determine MIME type
        String mime = "text/html";
        if (path.endsWith(".js")) mime = "application/javascript";
        else if (path.endsWith(".css")) mime = "text/css";
        else if (path.endsWith(".png")) mime = "image/png";
        else if (path.endsWith(".svg")) mime = "image/svg+xml";
        else if (path.endsWith(".json")) mime = "application/json";

        // Try loading from classpath
        String resourcePath = "webapp" + path.substring("/admin".length());
        InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath);

        if (is != null) {
            try {
                byte[] data = is.readAllBytes();
                is.close();
                return newFixedLengthResponse(Response.Status.OK, mime,
                    new java.io.ByteArrayInputStream(data), data.length);
            } catch (IOException e) {
                Log.warn("Failed to read static resource: " + resourcePath);
            }
        }

        // Fallback: inline HTML for SPA
        String fallback = getFallbackHtml(path);
        if (fallback != null) {
            return newFixedLengthResponse(Response.Status.OK, "text/html", fallback);
        }

        return newFixedLengthResponse(Response.Status.NOT_FOUND,
            "text/plain", "Resource not found: " + uri);
    }

    /**
     * Inline fallback HTML for when resources aren't on classpath yet.
     * These provide functional admin pages without external files.
     */
    private String getFallbackHtml(String path) {
        if (path.endsWith("login.html")) return AdminPages.LOGIN;
        if (path.endsWith("index.html")) return AdminPages.INDEX;
        if (path.endsWith("model.html")) return AdminPages.MODEL_DETAIL;
        if (path.endsWith("players.html")) return AdminPages.PLAYERS;
        if (path.endsWith("audit.html")) return AdminPages.AUDIT;
        return null;
    }
}
