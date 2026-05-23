package com.tom.cpm.server.admin;

import java.io.ByteArrayInputStream;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import com.tom.cpm.server.model.ModelEntity;
import com.tom.cpm.server.model.ModelService;
import com.tom.cpm.shared.util.Log;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoHTTPD.Response;
import fi.iki.elonen.NanoHTTPD.Response.Status;

/**
 * Handles admin dashboard API endpoints.
 * All endpoints require a valid auth token (except /login).
 */
public class AdminHttpHandler {

    private final ModelService modelService;
    private final AdminAuthFilter auth;

    public AdminHttpHandler(ModelService modelService, AdminAuthFilter auth) {
        this.modelService = modelService;
        this.auth = auth;
    }

    /**
     * Route an API request to the appropriate handler.
     */
    public Response handleApi(NanoHTTPD.Method method, String uri, IHTTPSession session) {
        // OPTIONS preflight
        if (method == NanoHTTPD.Method.OPTIONS) {
            return newFixedLengthJson(Status.OK, "{\"ok\":true}");
        }

        // Login does not require auth
        if (uri.equals("/api/admin/login") && method == NanoHTTPD.Method.POST) {
            return handleLogin(session);
        }

        // All other endpoints require auth
        String token = AdminAuthFilter.extractToken(session.getHeaders());
        String user = auth.validateToken(token);
        if (user == null) {
            return newFixedLengthJson(Status.UNAUTHORIZED,
                "{\"error\":\"Unauthorized — invalid or expired token\"}");
        }

        try {
            // Strip prefix for routing
            String path = uri.substring("/api/admin".length());

            // Model CRUD
            if (path.startsWith("/models")) {
                return handleModels(method, path, session);
            }
            // Player management
            if (path.startsWith("/players")) {
                return handlePlayers(method, path, session);
            }
            // Audit log
            if (path.startsWith("/audit")) {
                return handleAudit(session);
            }
            // Database operations
            if (path.startsWith("/db")) {
                return handleDb(method, path);
            }

            return newFixedLengthJson(Status.NOT_FOUND,
                "{\"error\":\"Unknown API endpoint: " + path + "\"}");
        } catch (Exception e) {
            Log.error("API error: " + uri, e);
            return newFixedLengthJson(Status.INTERNAL_ERROR,
                "{\"error\":\"Internal server error\"}");
        }
    }

    // ================================================================
    // Login
    // ================================================================

    private Response handleLogin(IHTTPSession session) {
        try {
            // Rate limit check
            String ip = session.getHeaders().get("remote-addr");
            if (ip == null) ip = "127.0.0.1";
            if (!auth.checkRateLimit(ip)) {
                return newFixedLengthJson(Status.TOO_MANY_REQUESTS,
                    "{\"error\":\"Too many login attempts. Try again later.\"}");
            }

            // Parse JSON body
            Map<String, String> body = parseBody(session);
            String username = body.get("username");
            String password = body.get("password");

            if (username == null || password == null) {
                return newFixedLengthJson(Status.BAD_REQUEST,
                    "{\"error\":\"username and password required\"}");
            }

            // Verify credentials against config
            String storedHash = getConfigValue("cpmServer.admin.passwordHash", "");
            String storedUser = getConfigValue("cpmServer.admin.username", "admin");

            if (!username.equals(storedUser)) {
                return newFixedLengthJson(Status.UNAUTHORIZED,
                    "{\"error\":\"Invalid credentials\"}");
            }

            if (!auth.verifyPassword(password, storedHash)) {
                return newFixedLengthJson(Status.UNAUTHORIZED,
                    "{\"error\":\"Invalid credentials\"}");
            }

            String token = auth.generateToken(username);
            return newFixedLengthJson(Status.OK,
                "{\"token\":\"" + token + "\",\"username\":\"" + username + "\"}");
        } catch (Exception e) {
            Log.error("Login error", e);
            return newFixedLengthJson(Status.INTERNAL_ERROR,
                "{\"error\":\"Login failed\"}");
        }
    }

    // ================================================================
    // Models
    // ================================================================

    private Response handleModels(NanoHTTPD.Method method, String path,
                                   IHTTPSession session) throws SQLException {
        // GET /api/admin/models?page=0&size=50&player=<uuid>
        if (method == NanoHTTPD.Method.GET && path.equals("/models")) {
            Map<String, String> params = session.getParms();
            int page = parseInt(params.get("page"), 0);
            int size = Math.min(parseInt(params.get("size"), 50), 200);
            String player = params.get("player");

            List<ModelEntity> models;
            if (player != null) {
                models = modelService.getRepo().listModelsForPlayer(player);
            } else {
                models = modelService.getRepo().listAllModels(page * size, size);
            }

            StringBuilder json = new StringBuilder("{\"models\":[");
            boolean first = true;
            for (ModelEntity m : models) {
                if (!first) json.append(",");
                first = false;
                json.append(modelToJson(m));
            }
            int total = modelService.getRepo().countAllModels();
            json.append("],\"total\":").append(total).append("}");
            return newFixedLengthJson(Status.OK, json.toString());
        }

        // GET /api/admin/models/{id}
        if (method == NanoHTTPD.Method.GET && path.matches("/models/\\d+")) {
            long id = Long.parseLong(path.substring("/models/".length()));
            // Load model detail
            var blob = modelService.getRepo().loadModelBlob(id);
            if (blob == null) {
                return newFixedLengthJson(Status.NOT_FOUND,
                    "{\"error\":\"Model not found\"}");
            }
            // Find the model entity
            for (ModelEntity m : modelService.getRepo().listAllModels(0, Integer.MAX_VALUE)) {
                if (m.getId() == id) {
                    return newFixedLengthJson(Status.OK, modelToJson(m));
                }
            }
            return newFixedLengthJson(Status.NOT_FOUND,
                "{\"error\":\"Model not found\"}");
        }

        // DELETE /api/admin/models/{id}
        if (method == NanoHTTPD.Method.DELETE && path.matches("/models/\\d+")) {
            long id = Long.parseLong(path.substring("/models/".length()));
            boolean deleted = modelService.getRepo().deleteModel(id, null, true);
            if (deleted) {
                modelService.getRepo().logAction("ADMIN", "DELETE", null, id,
                    "Deleted via web dashboard", null);
                return newFixedLengthJson(Status.OK,
                    "{\"ok\":true,\"message\":\"Model deleted\"}");
            }
            return newFixedLengthJson(Status.NOT_FOUND,
                "{\"error\":\"Model not found\"}");
        }

        // PUT /api/admin/models/{id}/force
        if (method == NanoHTTPD.Method.PUT && path.matches("/models/\\d+/force")) {
            long id = Long.parseLong(path.substring("/models/".length())
                .replace("/force", ""));
            modelService.getRepo().setForced(id, true);
            modelService.getRepo().logAction("ADMIN", "FORCE", null, id,
                "Forced via web dashboard", null);
            return newFixedLengthJson(Status.OK,
                "{\"ok\":true,\"message\":\"Model forced\"}");
        }

        // DELETE /api/admin/models/{id}/force
        if (method == NanoHTTPD.Method.DELETE && path.matches("/models/\\d+/force")) {
            long id = Long.parseLong(path.substring("/models/".length())
                .replace("/force", ""));
            modelService.getRepo().setForced(id, false);
            modelService.getRepo().logAction("ADMIN", "UNFORCE", null, id,
                "Unforced via web dashboard", null);
            return newFixedLengthJson(Status.OK,
                "{\"ok\":true,\"message\":\"Model unforced\"}");
        }

        return newFixedLengthJson(Status.BAD_REQUEST,
            "{\"error\":\"Invalid models request\"}");
    }

    // ================================================================
    // Players
    // ================================================================

    private Response handlePlayers(NanoHTTPD.Method method, String path,
                                    IHTTPSession session) throws SQLException {
        // PUT /api/admin/players/{uuid}/block
        if (method == NanoHTTPD.Method.PUT && path.matches("/players/[^/]+/block")) {
            String uuid = path.split("/")[2];
            modelService.getRepo().setPlayerBlocked(uuid, true);
            modelService.getRepo().logAction("ADMIN", "BLOCK", uuid, null,
                "Blocked via web dashboard", null);
            return newFixedLengthJson(Status.OK,
                "{\"ok\":true,\"message\":\"Player blocked\"}");
        }

        // DELETE /api/admin/players/{uuid}/block
        if (method == NanoHTTPD.Method.DELETE && path.matches("/players/[^/]+/block")) {
            String uuid = path.split("/")[2];
            modelService.getRepo().setPlayerBlocked(uuid, false);
            modelService.getRepo().logAction("ADMIN", "UNBLOCK", uuid, null,
                "Unblocked via web dashboard", null);
            return newFixedLengthJson(Status.OK,
                "{\"ok\":true,\"message\":\"Player unblocked\"}");
        }

        return newFixedLengthJson(Status.BAD_REQUEST,
            "{\"error\":\"Invalid players request\"}");
    }

    // ================================================================
    // Audit
    // ================================================================

    private Response handleAudit(IHTTPSession session) throws SQLException {
        Map<String, String> params = session.getParms();
        int page = parseInt(params.get("page"), 0);
        int size = Math.min(parseInt(params.get("size"), 50), 200);
        String player = params.get("player");

        List<String> entries = modelService.getRepo().getAuditLog(
            page * size, size, player);

        StringBuilder json = new StringBuilder("{\"entries\":[");
        boolean first = true;
        for (String entry : entries) {
            if (!first) json.append(",");
            first = false;
            // Escape for JSON
            json.append("\"").append(entry.replace("\"", "\\\"").replace("\n", "\\n")).append("\"");
        }
        json.append("],\"page\":").append(page).append("}");
        return newFixedLengthJson(Status.OK, json.toString());
    }

    // ================================================================
    // Database
    // ================================================================

    private Response handleDb(NanoHTTPD.Method method, String path) throws SQLException {
        if (path.equals("/db/stats") && method == NanoHTTPD.Method.GET) {
            int count = modelService.getRepo().countAllModels();
            String stats = modelService.getDb().getStats();
            return newFixedLengthJson(Status.OK,
                "{\"modelCount\":" + count + ",\"stats\":\"" + stats + "\"}");
        }

        if (path.equals("/db/backup") && method == NanoHTTPD.Method.POST) {
            modelService.getDb().backup(new java.io.File("cpm_backups"));
            return newFixedLengthJson(Status.OK,
                "{\"ok\":true,\"message\":\"Backup completed\"}");
        }

        return newFixedLengthJson(Status.BAD_REQUEST,
            "{\"error\":\"Invalid db request\"}");
    }

    // ================================================================
    // Helpers
    // ================================================================

    private Response newFixedLengthJson(Status status, String json) {
        return NanoHTTPD.newFixedLengthResponse(status, "application/json", json);
    }

    private String modelToJson(ModelEntity m) {
        return String.format(
            "{\"id\":%d,\"playerUuid\":\"%s\",\"name\":\"%s\",\"sizeBytes\":%d," +
            "\"isDefault\":%b,\"isForced\":%b,\"createdAt\":\"%s\"}",
            m.getId(),
            escapeJson(m.getPlayerUuid()),
            escapeJson(m.getName()),
            m.getSizeBytes(),
            m.isDefault(),
            m.isForced(),
            m.getCreatedAt() != null ? m.getCreatedAt().toString() : "");
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    private Map<String, String> parseBody(IHTTPSession session) {
        // NanoHTTPD parses form data and JSON bodies into parms
        Map<String, String> parms = session.getParms();
        if (parms != null && !parms.isEmpty()) return parms;

        // Try parsing JSON body manually
        try {
            String body = new String(session.getInputStream().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
            if (body.startsWith("{")) {
                return parseSimpleJson(body);
            }
        } catch (Exception e) {
            // ignore
        }
        return java.util.Collections.emptyMap();
    }

    private Map<String, String> parseSimpleJson(String json) {
        Map<String, String> map = new java.util.HashMap<>();
        // Simple JSON parser for flat objects (good enough for login)
        String[] pairs = json.replace("{", "").replace("}", "").split(",");
        for (String pair : pairs) {
            String[] kv = pair.split(":", 2);
            if (kv.length == 2) {
                String key = kv[0].trim().replace("\"", "");
                String val = kv[1].trim().replace("\"", "");
                map.put(key, val);
            }
        }
        return map;
    }

    private int parseInt(String s, int defaultVal) {
        if (s == null) return defaultVal;
        try { return Integer.parseInt(s); }
        catch (NumberFormatException e) { return defaultVal; }
    }

    private String getConfigValue(String key, String defaultVal) {
        // Config values come from cpm.json, accessed via ConfigEntry
        // For now, return defaults that will be wired during integration
        return defaultVal;
    }
}
