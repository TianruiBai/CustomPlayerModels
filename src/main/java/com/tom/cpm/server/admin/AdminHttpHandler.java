package com.tom.cpm.server.admin;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import com.tom.cpl.config.ConfigEntry;
import com.tom.cpm.server.model.ModelEntity;
import com.tom.cpm.server.model.ModelService;
import com.tom.cpm.shared.config.ModConfig;
import com.tom.cpm.shared.util.Log;

/**
 * Handles admin dashboard API endpoints.
 * All endpoints require a valid auth token (except /login).
 */
public class AdminHttpHandler {

    public record ApiResponse(int status, String contentType, String body) {}

    private final ModelService modelService;
    private final AdminAuthFilter auth;

    public AdminHttpHandler(ModelService modelService, AdminAuthFilter auth) {
        this.modelService = modelService;
        this.auth = auth;
    }

    /**
     * Route an API request to the appropriate handler.
     */
    public ApiResponse handleApi(String method, String uri, String rawQuery,
                                 Map<String, String> headers, String body) {
        // OPTIONS preflight
        if ("OPTIONS".equalsIgnoreCase(method)) {
            return json(200, "{\"ok\":true}");
        }

        // Login does not require auth
        if ("/api/admin/login".equals(uri) && "POST".equalsIgnoreCase(method)) {
            return handleLogin(headers, body);
        }

        // All other endpoints require auth
        String token = AdminAuthFilter.extractToken(headers);
        String user = auth.validateToken(token);
        if (user == null) {
            return json(401, "{\"error\":\"Unauthorized - invalid or expired token\"}");
        }

        try {
            // Strip prefix for routing
            String path = uri.substring("/api/admin".length());
            Map<String, String> query = parseQuery(rawQuery);

            if (path.startsWith("/models")) {
                return handleModels(method, path, query);
            }
            if (path.startsWith("/players")) {
                return handlePlayers(method, path);
            }
            if (path.startsWith("/audit")) {
                return handleAudit(query);
            }
            if (path.startsWith("/db")) {
                return handleDb(method, path);
            }

            return json(404, "{\"error\":\"Unknown API endpoint: " + escapeJson(path) + "\"}");
        } catch (Exception e) {
            Log.error("API error: " + uri, e);
            return json(500, "{\"error\":\"Internal server error\"}");
        }
    }

    private ApiResponse handleLogin(Map<String, String> headers, String rawBody) {
        try {
            // Rate limit check
            String ip = headers.get("x-forwarded-for");
            if (ip == null || ip.isBlank()) ip = headers.get("remote-addr");
            if (ip == null || ip.isBlank()) ip = "127.0.0.1";
            if (!auth.checkRateLimit(ip)) {
                return json(429, "{\"error\":\"Too many login attempts. Try again later.\"}");
            }

            Map<String, String> body = parseSimpleJson(rawBody);
            String username = body.get("username");
            String password = body.get("password");

            if (username == null || password == null) {
                return json(400, "{\"error\":\"username and password required\"}");
            }

            String storedHash = getConfigValue("cpmServer.admin.passwordHash", "");
            String storedUser = getConfigValue("cpmServer.admin.username", "admin");

            if (storedHash == null || storedHash.isBlank()) {
                return json(503, "{\"error\":\"Admin password hash is not configured. Set cpmServer.admin.passwordHash in cpm.json.\"}");
            }

            if (!username.equals(storedUser)) {
                return json(401, "{\"error\":\"Invalid credentials\"}");
            }

            if (!auth.verifyPassword(password, storedHash)) {
                return json(401, "{\"error\":\"Invalid credentials\"}");
            }

            String token = auth.generateToken(username);
            return json(200, "{\"token\":\"" + token + "\",\"username\":\"" + escapeJson(username) + "\"}");
        } catch (Exception e) {
            Log.error("Login error", e);
            return json(500, "{\"error\":\"Login failed\"}");
        }
    }

    private ApiResponse handleModels(String method, String path, Map<String, String> params) throws SQLException {
        if ("GET".equalsIgnoreCase(method) && "/models".equals(path)) {
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
            return json(200, json.toString());
        }

        if ("GET".equalsIgnoreCase(method) && path.matches("/models/\\d+")) {
            long id = Long.parseLong(path.substring("/models/".length()));
            var blob = modelService.getRepo().loadModelBlob(id);
            if (blob == null) {
                return json(404, "{\"error\":\"Model not found\"}");
            }
            for (ModelEntity m : modelService.getRepo().listAllModels(0, Integer.MAX_VALUE)) {
                if (m.getId() == id) {
                    return json(200, modelToJson(m));
                }
            }
            return json(404, "{\"error\":\"Model not found\"}");
        }

        if ("DELETE".equalsIgnoreCase(method) && path.matches("/models/\\d+")) {
            long id = Long.parseLong(path.substring("/models/".length()));
            boolean deleted = modelService.getRepo().deleteModel(id, null, true);
            if (deleted) {
                modelService.getRepo().logAction("ADMIN", "DELETE", null, id,
                    "Deleted via web dashboard", null);
                return json(200, "{\"ok\":true,\"message\":\"Model deleted\"}");
            }
            return json(404, "{\"error\":\"Model not found\"}");
        }

        if ("PUT".equalsIgnoreCase(method) && path.matches("/models/\\d+/force")) {
            long id = Long.parseLong(path.substring("/models/".length()).replace("/force", ""));
            modelService.getRepo().setForced(id, true);
            modelService.getRepo().logAction("ADMIN", "FORCE", null, id,
                "Forced via web dashboard", null);
            return json(200, "{\"ok\":true,\"message\":\"Model forced\"}");
        }

        if ("DELETE".equalsIgnoreCase(method) && path.matches("/models/\\d+/force")) {
            long id = Long.parseLong(path.substring("/models/".length()).replace("/force", ""));
            modelService.getRepo().setForced(id, false);
            modelService.getRepo().logAction("ADMIN", "UNFORCE", null, id,
                "Unforced via web dashboard", null);
            return json(200, "{\"ok\":true,\"message\":\"Model unforced\"}");
        }

        return json(400, "{\"error\":\"Invalid models request\"}");
    }

    private ApiResponse handlePlayers(String method, String path) throws SQLException {
        if ("PUT".equalsIgnoreCase(method) && path.matches("/players/[^/]+/block")) {
            String uuid = path.split("/")[2];
            modelService.getRepo().setPlayerBlocked(uuid, true);
            modelService.getRepo().logAction("ADMIN", "BLOCK", uuid, null,
                "Blocked via web dashboard", null);
            return json(200, "{\"ok\":true,\"message\":\"Player blocked\"}");
        }

        if ("DELETE".equalsIgnoreCase(method) && path.matches("/players/[^/]+/block")) {
            String uuid = path.split("/")[2];
            modelService.getRepo().setPlayerBlocked(uuid, false);
            modelService.getRepo().logAction("ADMIN", "UNBLOCK", uuid, null,
                "Unblocked via web dashboard", null);
            return json(200, "{\"ok\":true,\"message\":\"Player unblocked\"}");
        }

        return json(400, "{\"error\":\"Invalid players request\"}");
    }

    private ApiResponse handleAudit(Map<String, String> params) throws SQLException {
        int page = parseInt(params.get("page"), 0);
        int size = Math.min(parseInt(params.get("size"), 50), 200);
        String player = params.get("player");

        List<String> entries = modelService.getRepo().getAuditLog(page * size, size, player);

        StringBuilder json = new StringBuilder("{\"entries\":[");
        boolean first = true;
        for (String entry : entries) {
            if (!first) json.append(",");
            first = false;
            json.append("\"").append(entry.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")).append("\"");
        }
        json.append("],\"page\":").append(page).append("}");
        return json(200, json.toString());
    }

    private ApiResponse handleDb(String method, String path) throws SQLException {
        if ("/db/stats".equals(path) && "GET".equalsIgnoreCase(method)) {
            int count = modelService.getRepo().countAllModels();
            String stats = modelService.getDb().getStats();
            return json(200, "{\"modelCount\":" + count + ",\"stats\":\"" + escapeJson(stats) + "\"}");
        }

        if ("/db/backup".equals(path) && "POST".equalsIgnoreCase(method)) {
            modelService.getDb().backup(new java.io.File("cpm_backups"));
            return json(200, "{\"ok\":true,\"message\":\"Backup completed\"}");
        }

        return json(400, "{\"error\":\"Invalid db request\"}");
    }

    private ApiResponse json(int status, String body) {
        return new ApiResponse(status, "application/json", body);
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
            m.getCreatedAt() != null ? escapeJson(m.getCreatedAt().toString()) : "");
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    private Map<String, String> parseQuery(String query) {
        java.util.Map<String, String> out = new java.util.HashMap<>();
        if (query == null || query.isBlank()) return out;
        String[] pairs = query.split("&");
        for (String pair : pairs) {
            String[] kv = pair.split("=", 2);
            String key = urlDecode(kv[0]);
            String val = kv.length > 1 ? urlDecode(kv[1]) : "";
            out.put(key, val);
        }
        return out;
    }

    private Map<String, String> parseSimpleJson(String json) {
        java.util.Map<String, String> map = new java.util.HashMap<>();
        if (json == null) return map;
        String trimmed = json.trim();
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return map;

        String inner = trimmed.substring(1, trimmed.length() - 1).trim();
        if (inner.isEmpty()) return map;

        String[] pairs = inner.split(",");
        for (String pair : pairs) {
            String[] kv = pair.split(":", 2);
            if (kv.length != 2) continue;
            String key = stripJson(kv[0]);
            String val = stripJson(kv[1]);
            map.put(key, val);
        }
        return map;
    }

    private String stripJson(String s) {
        String t = s == null ? "" : s.trim();
        if (t.startsWith("\"") && t.endsWith("\"") && t.length() >= 2) {
            t = t.substring(1, t.length() - 1);
        }
        return t.replace("\\\"", "\"").replace("\\n", "\n").replace("\\r", "\r").replace("\\\\", "\\");
    }

    private int parseInt(String s, int defaultVal) {
        if (s == null) return defaultVal;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }

    private String getConfigValue(String key, String defaultVal) {
        try {
            ConfigEntry cfg = ModConfig.getCommonConfig();
            return cfg.getString(key, defaultVal);
        } catch (Throwable t) {
            Log.warn("Failed to read config key: " + key, t);
            return defaultVal;
        }
    }

    private String urlDecode(String s) {
        try {
            return java.net.URLDecoder.decode(s, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s;
        }
    }
}
