# CPM Stage 2 Security Enhancement Plan

**Date:** 2026-05-26  
**Status:** Implementation In Progress — Branch `feature/1.21-security2`  
**Context:** Builds on Stage 1 foundation (AES-256-GCM, HKDF, time-bound keys, EncryptedModelBlob, JCEKS keystore, admin bcrypt auth).

---

## Table of Contents

1. [Enhancement 2.1: Model Download Authorization](#enhancement-21-model-download-authorization)
2. [Enhancement 2.2: Non-Sequential Model IDs](#enhancement-22-non-sequential-model-ids)
3. [Enhancement 2.3: Rate Limiting & Anti-Enumeration](#enhancement-23-rate-limiting--anti-enumeration)
4. [Enhancement 2.4: Model Signing / Attestation](#enhancement-24-model-signing--attestation)
5. [Enhancement 2.5: Audit Trail Hardening](#enhancement-25-audit-trail-hardening)
6. [Database Migration: Schema v2](#database-migration-schema-v2)
7. [Implementation Sequence](#implementation-sequence)

---

## Enhancement 2.1: Model Download Authorization

### Problem

`CpmModelPacketHandler.handleDownload()` (lines 220–260) loads **any** model by ID and serves it to **any** authenticated player — there is zero ownership verification. A connected player who knows or brute-forces a model ID can download another player's private model.

The only barrier is that model IDs are currently sequential (`BIGINT AUTO_INCREMENT`), making enumeration trivial. The fix needs to work alongside Enhancement 2.2.

### Root Cause

```java
// CpmModelPacketHandler.java, handleDownload():
UUID uuid = handler.resolvePlayerUUID(player);
long modelId = tag.getLong("mid");
// ❌ Loads blob directly — no ownership query
EncryptedModelBlob blob = modelService.getRepo().loadModelBlob(modelId);
// ❌ Serves data regardless of who requested it
```

### Design

Add three authorization tiers:

| Tier | Who | Access Rule |
|------|-----|-------------|
| **Owner** | `model.player_uuid == requestingUuid` | Full access (download, delete, update, set active, set default) |
| **Admin** | OP level ≥ 2 OR web dashboard authenticated | Force/unforce, delete only — **cannot download or view model content** |
| **Forced model** | `model.is_forced == true` | Any player can download (admin has made it public) |
| **Cloneable model** | `model.is_cloneable == true` | Any player can download (owner marked it as cloneable in editor) |

**Denied requests** are silently treated as "not found" (404 semantics) to avoid leaking whether a model ID exists.

**Cloneable / UUID-Lock detection:** During upload, the server parses the model's binary header to detect `ModelPartCloneable` (type 0x0F, ordinal 15) and `ModelPartUUIDLockout` (type 0x09, ordinal 9) markers. A model is marked `is_cloneable=true` only if it contains a cloneable part AND does NOT contain a UUID lock. This honors the original CPM copy-protection mechanism: UUID-locked models are never cloneable, even if the editor UI allowed both settings.

### Files to Modify

| File | Change |
|------|--------|
| `src/main/java/com/tom/cpm/server/model/ModelRepository.java` | Add `getModelMetadata()`, `setCloneable()`, update `mapRow()` with `is_cloneable` |
| `src/main/java/com/tom/cpm/server/model/ModelEntity.java` | Add `isCloneable` field with getter/setter |
| `src/main/java/com/tom/cpm/server/CpmModelPacketHandler.java` | Add `canAccessModel()`, `canAdminOperateModel()`, `detectCloneable()`; rewrite all handlers with auth checks |
| `src/main/java/com/tom/cpm/shared/network/NetHandler.java` | Add `isPlayerAdmin`, `getPlayerIP` fields and `isAdmin()`, `getPlayerIp()` accessors |
| `src/main/java/com/tom/cpm/common/ServerHandlerBase.java` | Wire `isPlayerAdmin` (OP ≥ 2) and `getPlayerIP` (remote address) |
| `src/main/java/com/tom/cpm/server/db/MigrationManager.java` | V2 migration: `is_cloneable`, `signature`, `chain_hash` columns |
| `src/main/java/com/tom/cpm/server/security/RateLimitFilter.java` | **NEW** — Token-bucket rate limiter |
| `src/main/java/com/tom/cpm/server/crypto/SessionKeyManager.java` | Wire `RateLimitFilter.clearPlayer()` in `destroySession()` |
| `src/main/java/com/tom/cpm/server/crypto/KeyManager.java` | Add `signingKey` generation and `getSigningKey()` |

### New/Modified Methods

#### `ModelRepository.getModelMetadata(long modelId)`

```java
/**
 * Retrieve model metadata (owner UUID, forced flag) without loading the encrypted blob.
 * Used for authorization checks before serving downloads.
 * 
 * @return ModelEntity with id, playerUuid, isForced populated; null if not found
 */
public ModelEntity getModelMetadata(long modelId) throws SQLException {
    String sql = "SELECT id, player_uuid, name, is_forced, is_default FROM models WHERE id = ?";
    try (Connection conn = dbManager.getConnection();
         PreparedStatement ps = conn.prepareStatement(sql)) {
        ps.setLong(1, modelId);
        try (ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                ModelEntity m = new ModelEntity();
                m.setId(rs.getLong("id"));
                m.setPlayerUuid(rs.getString("player_uuid"));
                m.setName(rs.getString("name"));
                m.setForced(rs.getBoolean("is_forced"));
                m.setDefault(rs.getBoolean("is_default"));
                return m;
            }
        }
    }
    return null;
}
```

#### `CpmModelPacketHandler.canAccessModel(UUID playerUuid, long modelId)`

```java
/**
 * Authorization check for model DATA access (download, set active).
 * 
 * Rules:
 * 1. Owner UUID match → allowed (full access to own models)
 * 2. Model is_forced → allowed (admin has made it public)
 * 3. Model is_cloneable → allowed (owner marked it as cloneable in editor)
 * 4. Admin → DENIED for data access (admins can only force/delete, not view content)
 * 5. Otherwise → denied (logged to audit trail as ACCESS_DENIED)
 */
private ModelEntity canAccessModel(UUID playerUuid, long modelId) {
    try {
        ModelEntity meta = modelService.getRepo().getModelMetadata(modelId);
        if (meta == null) return null; // Not found — don't leak existence
        
        // Admin override
        if (isAdmin) return meta;
        
        // Owner check
        if (meta.getPlayerUuid().equals(playerUuid.toString())) return meta;
        
        // Forced (admin-public) model
        if (meta.isForced()) return meta;
        
        // Access denied — log the attempt
        modelService.getRepo().logAction(
            playerUuid.toString(), "ACCESS_DENIED",
            meta.getPlayerUuid(), modelId,
            "Unauthorized model access attempt",
            null  // IP will be populated by Enhancement 2.5
        );
        return null;
    } catch (SQLException e) {
        Log.error("Authorization check failed for model " + modelId, e);
        return null;
    }
}
```

#### Modified `handleDownload()`

```java
@Override
public <P> void handleDownload(NetHandler<?, P, ?> handler, ServerNetH net, P player,
                                NBTTagCompound tag) {
    UUID uuid = handler.resolvePlayerUUID(player);
    long modelId = tag.getLong("mid");
    boolean isAdmin = handler.isAdmin(player);

    // Authorization check
    ModelEntity meta = canAccessModel(uuid, modelId, isAdmin);
    if (meta == null) {
        // Return "not found" — don't leak whether the model exists
        NBTTagCompound resp = new NBTTagCompound();
        resp.setLong("mid", modelId);
        resp.setInteger("idx", -1);
        resp.setInteger("total", 0);
        resp.setByteArray("data", new byte[0]);
        handler.sendPacketTo(net, new ModelDownloadChunkS2C(resp));
        return;
    }

    try {
        EncryptedModelBlob blob = modelService.getRepo().loadModelBlob(modelId);
        // ... rest of existing download logic unchanged ...
    } catch (Exception e) {
        Log.error("Failed to serve model download: modelId=" + modelId, e);
    }
}
```

### Client Impact

- **Legitimate owners**: No change. Downloads work exactly as before.
- **Unauthorized attempts**: Receive empty response (0 chunks, idx=-1). Client should display "Model not found or access denied."
- **Admins**: Can download any model (needed for moderation).

---

## Enhancement 2.2: Non-Sequential Model IDs

### Problem

Model IDs use `BIGINT AUTO_INCREMENT` (`MigrationManager.java` line 97). Sequential integers make enumeration trivial:

```
GET model 1  → exists
GET model 2  → exists  
GET model 3  → not found (deleted)
GET model 4  → exists
...
```

An attacker can walk the entire model database in minutes.

### Design

Replace `AUTO_INCREMENT` with cryptographically random `long` values (signed 64-bit). With $2^{63}$ possible values and ~$10^6$ models, collision probability is ~$2.7 \times 10^{-8}$ per insert — negligible. A collision-retry loop handles the edge case.

Use `ThreadLocalRandom.current().nextLong()` for generation speed, falling back to `SecureRandom` for the random source. The IDs only need to be unpredictable, not cryptographically secret (the actual data is AES-256-GCM encrypted).

### Files to Modify

| File | Change |
|------|--------|
| `src/main/java/com/tom/cpm/server/model/ModelRepository.java` | Modify `storeModel()` to generate random ID, insert with explicit ID, retry on collision |
| `src/main/java/com/tom/cpm/server/db/MigrationManager.java` | Add V2 migration: `ALTER TABLE models ALTER COLUMN id DROP AUTO_INCREMENT` (or recreate without AUTO_INCREMENT) |
| `src/main/java/com/tom/cpm/server/model/ModelEntity.java` | No change needed — `long id` already supports full 64-bit range |
| `src/main/java/com/tom/cpm/server/CpmModelPacketHandler.java` | Any `long modelId` parsing from NBT already works with full 64-bit |

### Migration (V2)

```sql
-- H2 does not support ALTER COLUMN DROP DEFAULT for AUTO_INCREMENT.
-- Strategy: recreate the models table without AUTO_INCREMENT, copy data.
-- This migration MUST run before any data is inserted into the models table
-- (i.e., before the server opens to players after upgrade).

-- If data already exists, we preserve existing IDs (they won't collide with
-- future random IDs since the random space is 2^63).

CREATE TABLE IF NOT EXISTS models_v2 (
    id          BIGINT       PRIMARY KEY,   -- ← NO AUTO_INCREMENT
    player_uuid VARCHAR(36)  NOT NULL,
    name        VARCHAR(128) NOT NULL,
    description VARCHAR(512),
    data_enc    BLOB         NOT NULL,
    data_iv     BINARY(12)   NOT NULL,
    data_tag    BINARY(16)   NOT NULL,
    icon_enc    BLOB,
    icon_iv     BINARY(12),
    icon_tag    BINARY(16),
    size_bytes  INT          NOT NULL,
    sha256      BINARY(32),
    is_default  BOOLEAN      NOT NULL DEFAULT FALSE,
    is_forced   BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (player_uuid) REFERENCES players(uuid) ON DELETE CASCADE
);

-- Copy existing data if any
INSERT INTO models_v2 SELECT * FROM models;

-- Swap tables
DROP TABLE models;
ALTER TABLE models_v2 RENAME TO models;

-- Recreate indexes
CREATE INDEX IF NOT EXISTS idx_models_player ON models(player_uuid);
CREATE INDEX IF NOT EXISTS idx_models_default ON models(player_uuid, is_default);
```

### New ID Generation in `storeModel()`

```java
private static final java.util.concurrent.atomic.AtomicLong ID_COLLISION_COUNT 
    = new java.util.concurrent.atomic.AtomicLong(0);
private static final int MAX_ID_RETRIES = 5;

private long generateModelId(Connection conn) throws SQLException {
    java.util.concurrent.ThreadLocalRandom rng = java.util.concurrent.ThreadLocalRandom.current();
    
    for (int attempt = 0; attempt < MAX_ID_RETRIES; attempt++) {
        long candidate = rng.nextLong();
        if (candidate == 0) continue; // 0 reserved for "no model"
        
        // Check collision
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM models WHERE id = ?")) {
            ps.setLong(1, candidate);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return candidate;
                }
            }
        }
        ID_COLLISION_COUNT.incrementAndGet();
    }
    
    // Extremely unlikely: 5 collisions in a row
    long collisions = ID_COLLISION_COUNT.get();
    Log.warn("Model ID collision after " + MAX_ID_RETRIES + " retries (total collisions: " 
        + collisions + "). Using SecureRandom fallback.");
    
    // Final attempt with SecureRandom
    byte[] randomBytes = new byte[8];
    new java.security.SecureRandom().nextBytes(randomBytes);
    long fallback = java.nio.ByteBuffer.wrap(randomBytes).getLong();
    if (fallback == 0) fallback = 1;
    return fallback;
}
```

### Client Impact

- **MyModelsPopup / model list**: Already displays model IDs as opaque `long` values. No change needed.
- **SetActive / SetDefault / Delete / Download**: Already pass `long modelId` in NBT. No wire-format change.
- **Admin commands**: `/cpm admin models info <id>` already accepts `long`. No change.
- **Web dashboard**: Already treats model ID as opaque. No change.

---

## Enhancement 2.3: Rate Limiting & Anti-Enumeration

### Problem

No per-player rate limits exist on model operations. A malicious client could:
- Rapidly iterate model IDs to enumerate the database
- Flood the server with download requests (DoS)
- Brute-force model list queries

**Constraint:** Rate limits must NOT lock out legitimate players who disconnect and reconnect (client swap, network drop, server restart). Limits must be per-session, not permanent.

### Design

Token-bucket rate limiter per player UUID, with automatic reset on disconnect. Three rate-limited action classes:

| Action Class | Limit | Window | Rationale |
|---|---|---|---|
| `download` | 10 | 60 sec | Downloading models (including retries) |
| `list` | 5 | 60 sec | Model list queries |
| `upload_init` | 3 | 60 sec | Upload session creation |

**Session-scoped reset**: When `SessionKeyManager.destroySession()` is called (player disconnect), the rate limiter state for that UUID is cleared. This ensures reconnecting players start fresh.

**Admin bypass**: Players with OP ≥ 2 are exempt from rate limits.

### Files to Create/Modify

| File | Change |
|------|--------|
| `src/main/java/com/tom/cpm/server/security/RateLimitFilter.java` | **NEW** — Token-bucket rate limiter |
| `src/main/java/com/tom/cpm/server/CpmModelPacketHandler.java` | Integrate rate limit checks into `handleDownload()`, `handleModelList()`, `handleUploadInit()` |
| `src/main/java/com/tom/cpm/server/crypto/SessionKeyManager.java` | Call `RateLimitFilter.clearPlayer(uuid)` in `destroySession()` |

### RateLimitFilter.java (New)

```java
package com.tom.cpm.server.security;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Token-bucket rate limiter for player model operations.
 * 
 * Limits are session-scoped: state is cleared when a player disconnects.
 * This ensures legitimate client-swapping is never penalized.
 * Admins (OP ≥ 2) are exempt.
 */
public final class RateLimitFilter {

    // ---- Configuration ----
    private static final int DOWNLOAD_LIMIT = 10;     // per window
    private static final int LIST_LIMIT = 5;          // per window
    private static final int UPLOAD_INIT_LIMIT = 3;   // per window
    private static final long WINDOW_MS = 60_000;     // 1 minute

    public enum Action { DOWNLOAD, LIST, UPLOAD_INIT }

    // ---- State ----
    private static final Map<UUID, TokenBucket> buckets = new ConcurrentHashMap<>();

    private RateLimitFilter() {}

    /**
     * Check whether a player is allowed to perform an action.
     * 
     * @param playerUuid the player's UUID
     * @param action     the action class
     * @param isAdmin    if true, always allow (bypass rate limits)
     * @return true if allowed, false if rate-limited
     */
    public static boolean allow(UUID playerUuid, Action action, boolean isAdmin) {
        if (isAdmin) return true;
        
        TokenBucket bucket = buckets.computeIfAbsent(playerUuid, 
            k -> new TokenBucket(getLimit(action), WINDOW_MS));
        return bucket.tryConsume();
    }

    /**
     * Clear all rate limit state for a player.
     * Called on player disconnect via SessionKeyManager.destroySession().
     */
    public static void clearPlayer(UUID playerUuid) {
        buckets.remove(playerUuid);
    }

    /**
     * Get the number of remaining tokens for a player/action (for debugging).
     */
    public static int remaining(UUID playerUuid, Action action) {
        TokenBucket bucket = buckets.get(playerUuid);
        return bucket != null ? bucket.available() : getLimit(action);
    }

    private static int getLimit(Action action) {
        return switch (action) {
            case DOWNLOAD -> DOWNLOAD_LIMIT;
            case LIST -> LIST_LIMIT;
            case UPLOAD_INIT -> UPLOAD_INIT_LIMIT;
        };
    }

    // ---- Token Bucket Implementation ----

    private static class TokenBucket {
        private final int capacity;
        private final long windowMs;
        private volatile double tokens;
        private volatile long lastRefill;

        TokenBucket(int capacity, long windowMs) {
            this.capacity = capacity;
            this.windowMs = windowMs;
            this.tokens = capacity;
            this.lastRefill = System.currentTimeMillis();
        }

        synchronized boolean tryConsume() {
            refill();
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        }

        synchronized int available() {
            refill();
            return (int) Math.floor(tokens);
        }

        private void refill() {
            long now = System.currentTimeMillis();
            long elapsed = now - lastRefill;
            if (elapsed <= 0) return;
            
            double refillAmount = (double) elapsed / windowMs * capacity;
            tokens = Math.min(capacity, tokens + refillAmount);
            lastRefill = now;
        }
    }
}
```

### Integration Points

```java
// In CpmModelPacketHandler.handleDownload():
if (!RateLimitFilter.allow(uuid, RateLimitFilter.Action.DOWNLOAD, isAdmin)) {
    Log.warn("Rate limit exceeded: download by " + uuid);
    NBTTagCompound resp = new NBTTagCompound();
    resp.setLong("mid", modelId);
    resp.setInteger("idx", -2); // -2 = rate limited
    resp.setInteger("total", 0);
    handler.sendPacketTo(net, new ModelDownloadChunkS2C(resp));
    return;
}

// In CpmModelPacketHandler.handleModelList():
if (!RateLimitFilter.allow(uuid, RateLimitFilter.Action.LIST, isAdmin)) {
    // Return cached list if available, or empty
    ...
}

// In SessionKeyManager.destroySession():
public void destroySession(UUID playerUuid) {
    SessionKeyEntry entry = sessions.remove(playerUuid);
    if (entry != null) {
        entry.wipe();
    }
    RateLimitFilter.clearPlayer(playerUuid);  // ← Clear rate limits on disconnect
}
```

### Client Impact

- **Legitimate players**: 10 downloads/min and 5 list requests/min is far above normal usage. No impact.
- **Client-swapping**: Rate limits are cleared on disconnect. Reconnecting = fresh bucket. No impact.
- **Rate-limited clients**: Receive `idx=-2` in download response. Client should show "Too many requests — please wait."

---

## Enhancement 2.4: Model Signing / Attestation

### Problem

A rogue server operator (or SQL injection attacker who gains DB write access) could:
- Replace a model's encrypted blob with a different model
- Modify `sha256` hash to match the replacement
- The existing SHA-256 column becomes a self-consistent lie

The `data_enc` column is AES-256-GCM encrypted, so an attacker cannot create a valid ciphertext without the per-row key (which is derived from the column master key in the JCEKS keystore). However, an attacker with full filesystem access could:
- Extract the JCEKS keystore
- Derive the per-row key
- Encrypt malicious data
- Replace the blob

### Design: Server Key Signature Chain

Each model gets an **upload-time HMAC-SHA256 signature** using the **server's master signing key** (a separate key from the encryption key). The signature covers:

```
signature = HMAC-SHA256(signingKey, modelId || playerUuid || sha256 || createdAt)
```

The signing key is stored in the JCEKS keystore alongside the DB master key (never leaves the keystore). At download time, the server re-computes and verifies the signature before serving the model.

**What this protects against:**
- Database row tampering (attacker modifies `data_enc` + `sha256` but can't generate a valid signature without the signing key)
- Offline DB copy attacks (attacker copies the DB file but can't sign new models)

**What this does NOT protect against:**
- Attacker with JCEKS keystore + password (full compromise — game over at this level)
- Memory-corruption attacks (model data is briefly plaintext in RAM during serving — see `EncryptedModelBlob` for mitigation)

### Files to Modify

| File | Change |
|------|--------|
| `src/main/java/com/tom/cpm/server/crypto/KeyManager.java` | Add `signingKey` generation and loading (separate from `dbMasterKey`) |
| `src/main/java/com/tom/cpm/server/model/ModelRepository.java` | Add `signature` column to INSERT/UPDATE; add `verifyModelSignature()` method |
| `src/main/java/com/tom/cpm/server/CpmModelPacketHandler.java` | Call `verifyModelSignature()` in `handleDownload()` before serving |
| `src/main/java/com/tom/cpm/server/db/MigrationManager.java` | V2 migration: `ALTER TABLE models ADD COLUMN signature BINARY(32)` |

### Database Schema Addition

```sql
ALTER TABLE models ADD COLUMN signature BINARY(32);
-- NULL for pre-existing models (grandfathered; verified on first download attempt)
```

### KeyManager Changes

```java
// New alias
private static final String SIGNING_KEY_ALIAS = "cpm-signing-key";
private SecretKey signingKey;

// In initialize():
if (keyStore.containsAlias(SIGNING_KEY_ALIAS)) {
    KeyStore.SecretKeyEntry entry = (KeyStore.SecretKeyEntry)
        keyStore.getEntry(SIGNING_KEY_ALIAS,
            new KeyStore.PasswordProtection(keystorePassword));
    this.signingKey = entry.getSecretKey();
} else {
    this.signingKey = crypto.generateAesKey(); // AES-256 key used for HMAC-SHA256
    keyStore.setEntry(SIGNING_KEY_ALIAS,
        new KeyStore.SecretKeyEntry(signingKey),
        new KeyStore.PasswordProtection(keystorePassword));
    save();
}

public SecretKey getSigningKey() { return signingKey; }
```

### Signature Computation & Verification

```java
// In ModelRepository:

/**
 * Compute HMAC-SHA256 signature for a model.
 * Covers: modelId || playerUuid (UTF-8) || sha256 (32 bytes) || createdAt (long, big-endian)
 */
public byte[] computeSignature(long modelId, String playerUuid, byte[] sha256, 
                                long createdAtEpochMs, SecretKey signingKey) {
    try {
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(signingKey.getEncoded(), "HmacSHA256"));
        
        mac.update(longToBytes(modelId));
        mac.update(playerUuid.getBytes(StandardCharsets.UTF_8));
        mac.update(sha256);
        mac.update(longToBytes(createdAtEpochMs));
        
        return mac.doFinal();
    } catch (Exception e) {
        throw new EncryptedModelBlob.CryptoException("Signature computation failed", e);
    }
}

/**
 * Verify a model's signature before serving.
 * Models with NULL signature (pre-enhancement) are allowed through 
 * but trigger a one-time re-sign on next update.
 */
public boolean verifyModelSignature(long modelId) throws SQLException {
    String sql = "SELECT player_uuid, sha256, signature, created_at FROM models WHERE id = ?";
    try (Connection conn = dbManager.getConnection();
         PreparedStatement ps = conn.prepareStatement(sql)) {
        ps.setLong(1, modelId);
        try (ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) return false;
            
            byte[] storedSig = rs.getBytes("signature");
            if (storedSig == null) {
                // Pre-enhancement model — grandfathered
                // Re-sign now for future verification
                byte[] sha256 = rs.getBytes("sha256");
                String playerUuid = rs.getString("player_uuid");
                long createdAt = rs.getTimestamp("created_at").getTime();
                
                if (sha256 != null && playerUuid != null) {
                    byte[] newSig = computeSignature(modelId, playerUuid, sha256, 
                        createdAt, keyManager.getSigningKey());
                    updateSignature(modelId, newSig);
                }
                return true; // Allow through
            }
            
            // Verify existing signature
            byte[] sha256 = rs.getBytes("sha256");
            String playerUuid = rs.getString("player_uuid");
            long createdAt = rs.getTimestamp("created_at").getTime();
            
            byte[] expectedSig = computeSignature(modelId, playerUuid, sha256, 
                createdAt, keyManager.getSigningKey());
            
            if (!MemoryProtector.constantTimeEquals(storedSig, expectedSig)) {
                Log.warn("SIGNATURE VERIFICATION FAILED for model " + modelId 
                    + " — possible database tampering!");
                logAction("SYSTEM", "SIGNATURE_FAIL", playerUuid, modelId,
                    "Model signature verification failed — possible tampering", null);
                return false;
            }
            return true;
        }
    }
}

private void updateSignature(long modelId, byte[] signature) throws SQLException {
    try (Connection conn = dbManager.getConnection();
         PreparedStatement ps = conn.prepareStatement(
             "UPDATE models SET signature = ? WHERE id = ?")) {
        ps.setBytes(1, signature);
        ps.setLong(2, modelId);
        ps.executeUpdate();
    }
}
```

### Integration in Download Flow

```java
// In CpmModelPacketHandler.handleDownload(), after authorization, before serving:
if (!modelService.getRepo().verifyModelSignature(modelId)) {
    Log.error("Model signature invalid — refusing to serve model " + modelId);
    // Log to audit trail already done by verifyModelSignature()
    NBTTagCompound resp = new NBTTagCompound();
    resp.setLong("mid", modelId);
    resp.setInteger("idx", -3); // -3 = integrity check failed
    resp.setInteger("total", 0);
    handler.sendPacketTo(net, new ModelDownloadChunkS2C(resp));
    return;
}
```

### Client Impact

- **Valid models**: No change. Signature verified server-side only.
- **Tampered models**: Download fails with `idx=-3`. Client should show "Model data integrity check failed. Contact server administrator."
- **Pre-enhancement models**: Auto-re-signed on first download. Transparent to users.

---

## Enhancement 2.5: Audit Trail Hardening

### Problem

Current `audit_log` table (`MigrationManager.java` lines 140–152) lacks:
- **Hash-chain integrity**: Entries can be silently modified/deleted by a DB admin
- **Retention policy**: Logs grow unbounded
- **Failed attempt logging**: Only successful actions are recorded; failed enumeration/access attempts are not
- **Client IP**: Column exists but is populated as `null` in all current `logAction()` calls

### Design

Three complementary changes:

#### 2.5.1 Hash-Chained Audit Integrity

Each audit entry includes `SHA-256(previous_entry_hash || current_entry_data)`. The latest hash is also stored in `server_config` table under key `audit_chain_head`. Verification walks the chain from head to genesis.

```sql
ALTER TABLE audit_log ADD COLUMN chain_hash BINARY(32);
```

#### 2.5.2 Configurable Retention

Add config keys:
```json
{
  "cpmServer.audit.retentionDays": 90,
  "cpmServer.audit.maxEntries": 100000
}
```

Background cleanup runs on server start and every 24 hours, deleting entries older than `retentionDays` and trimming to `maxEntries` (oldest first, FIFO).

#### 2.5.3 Failed Attempt Logging + IP Capture

- All `ACCESS_DENIED` events are logged (added in Enhancement 2.1)
- `logAction()` captures the requesting player's IP address from the Minecraft connection
- Failed rate limit hits are logged at WARN level

### Files to Modify

| File | Change |
|------|--------|
| `src/main/java/com/tom/cpm/server/model/ModelRepository.java` | Update `logAction()` to compute chain hash; add `cleanupAuditLog()` |
| `src/main/java/com/tom/cpm/server/db/MigrationManager.java` | V2 migration: add `chain_hash` column, create genesis entry |
| `src/main/java/com/tom/cpm/server/CpmServerConfig.java` | Add audit config keys |
| `src/main/java/com/tom/cpm/server/CpmServerInit.java` | Call `cleanupAuditLog()` on startup and schedule periodic cleanup |
| `src/main/java/com/tom/cpm/server/CpmModelPacketHandler.java` | Pass player IP to `logAction()` calls |
| `src/main/java/com/tom/cpm/shared/network/NetHandler.java` | Add `String getPlayerIP(P player)` method |

### Updated logAction()

```java
/**
 * Log an auditable action with hash-chain integrity.
 * 
 * @param actor     who performed the action (UUID or "SYSTEM"/"ADMIN")
 * @param action    action type (UPLOAD, DELETE, ACCESS_DENIED, etc.)
 * @param target    target player UUID (may be null)
 * @param modelId   model ID (may be null)
 * @param details   human-readable description
 * @param ipAddress client IP address (nullable; populate from Minecraft connection)
 */
public void logAction(String actor, String action, String target,
                       Long modelId, String details, String ipAddress) throws SQLException {
    // Compute chain hash
    byte[] previousHash = getLatestChainHash();
    
    // Build entry data for hashing
    String entryData = String.format("%s|%s|%s|%s|%s|%d",
        actor, action, target != null ? target : "",
        modelId != null ? modelId.toString() : "",
        details != null ? details : "",
        System.currentTimeMillis());
    
    byte[] chainHash;
    try {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        if (previousHash != null) md.update(previousHash);
        chainHash = md.digest(entryData.getBytes(StandardCharsets.UTF_8));
    } catch (Exception e) {
        throw new SQLException("SHA-256 not available for audit chain", e);
    }

    String sql = """
        INSERT INTO audit_log (actor, action, target, model_id, details, 
                               ip_address, chain_hash, created_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
        """;
    try (Connection conn = dbManager.getConnection();
         PreparedStatement ps = conn.prepareStatement(sql)) {
        ps.setString(1, actor);
        ps.setString(2, action);
        ps.setString(3, target);
        if (modelId != null) ps.setLong(4, modelId);
        else ps.setNull(4, java.sql.Types.BIGINT);
        ps.setString(5, details);
        ps.setString(6, ipAddress);
        ps.setBytes(7, chainHash);
        ps.executeUpdate();
    }
    
    // Update chain head in server_config
    setConfigValue("audit_chain_head", bytesToHex(chainHash));
}
```

### IP Capture from Minecraft Connection

```java
// In NetHandler.java, add method:
public <T> String getPlayerIP(T player) {
    // NeoForge: ServerPlayer.connection.getRemoteAddress()
    if (player instanceof net.minecraft.server.level.ServerPlayer sp) {
        return sp.connection.getRemoteAddress() != null 
            ? sp.connection.getRemoteAddress().toString() 
            : "unknown";
    }
    return "unknown";
}
```

### Audit Log Cleanup

```java
/**
 * Delete audit entries older than retentionDays and enforce maxEntries.
 * Called on server start and every 24 hours.
 */
public void cleanupAuditLog(int retentionDays, int maxEntries) throws SQLException {
    try (Connection conn = dbManager.getConnection();
         Statement stmt = conn.createStatement()) {
        // Delete by age
        stmt.execute("DELETE FROM audit_log WHERE created_at < DATEADD('DAY', " 
            + (-retentionDays) + ", CURRENT_TIMESTAMP)");
        
        // Delete by count (keep newest maxEntries)
        stmt.execute("DELETE FROM audit_log WHERE id NOT IN ("
            + "SELECT id FROM audit_log ORDER BY created_at DESC LIMIT " + maxEntries + ")");
    }
}
```

---

## Database Migration: Schema v2

All schema changes are consolidated into Migration V2, which runs once when the server upgrades.

```java
// In MigrationManager.registerMigrations():
migrations.add(new Migration(2, "Stage 2 Security Enhancements", this::migrateV2));

private void migrateV2(Connection conn) throws SQLException {
    try (Statement stmt = conn.createStatement()) {
        // 2.2: Non-sequential IDs — drop AUTO_INCREMENT
        // H2 workaround: recreate models table
        stmt.execute("""
            CREATE TABLE IF NOT EXISTS models_v2 (
                id          BIGINT       PRIMARY KEY,
                player_uuid VARCHAR(36)  NOT NULL,
                name        VARCHAR(128) NOT NULL,
                description VARCHAR(512),
                data_enc    BLOB         NOT NULL,
                data_iv     BINARY(12)   NOT NULL,
                data_tag    BINARY(16)   NOT NULL,
                icon_enc    BLOB,
                icon_iv     BINARY(12),
                icon_tag    BINARY(16),
                size_bytes  INT          NOT NULL,
                sha256      BINARY(32),
                is_default  BOOLEAN      NOT NULL DEFAULT FALSE,
                is_forced   BOOLEAN      NOT NULL DEFAULT FALSE,
                created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (player_uuid) REFERENCES players(uuid) ON DELETE CASCADE
            )
            """);
        
        // Copy existing data (if any)
        stmt.execute("INSERT INTO models_v2 SELECT * FROM models");
        stmt.execute("DROP TABLE models");
        stmt.execute("ALTER TABLE models_v2 RENAME TO models");
        
        // Recreate indexes
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_models_player ON models(player_uuid)");
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_models_default ON models(player_uuid, is_default)");

        // 2.4: Model signing
        stmt.execute("ALTER TABLE models ADD COLUMN IF NOT EXISTS signature BINARY(32)");

        // 2.5: Audit chain hash
        stmt.execute("ALTER TABLE audit_log ADD COLUMN IF NOT EXISTS chain_hash BINARY(32)");
        
        // Create genesis audit entry if table is empty
        stmt.execute("""
            INSERT INTO audit_log (actor, action, target, model_id, details, ip_address, chain_hash, created_at)
            SELECT 'SYSTEM', 'GENESIS', NULL, NULL, 'Audit log initialized — Stage 2 Security', '127.0.0.1',
                   HASH('SHA256', 'CPM_AUDIT_GENESIS'), CURRENT_TIMESTAMP
            WHERE NOT EXISTS (SELECT 1 FROM audit_log)
            """);
        
        Log.info("Migration V2 complete: Stage 2 Security Enhancements applied");
    }
}
```

---

## Implementation Sequence

Recommended build order (each step is independently testable):

| Step | Enhancement | Effort | Depends On |
|------|-------------|--------|------------|
| **1** | 2.1 — Download Authorization | 2–3 days | — (standalone) |
| **2** | 2.2 — Non-Sequential Model IDs | 1–2 days | — (standalone) |
| **3** | 2.3 — Rate Limiting | 1–2 days | — (standalone, but wires into handlers modified in step 1) |
| **4** | 2.4 — Model Signing | 2–3 days | Step 2 (needs V2 migration for signature column) |
| **5** | 2.5 — Audit Trail Hardening | 2 days | Steps 1–4 (chains onto all new logAction calls) |

### Testing Strategy Per Step

| Step | Test |
|------|------|
| 2.1 | Player A uploads model → Player B tries to download by ID → receives empty response. Admin downloads same model → succeeds. |
| 2.2 | Upload 3 models → verify IDs are random-looking (not 1,2,3). Restart server → upload more → no collisions. |
| 2.3 | Send 11 download requests in 60s → 11th returns rate-limited. Disconnect/reconnect → bucket reset, downloads work again. |
| 2.4 | Upload model → manually corrupt `sha256` in DB via SQL → download fails with integrity error. Re-sign on first download works. |
| 2.5 | Trigger ACCESS_DENIED → verify audit entry with chain hash exists. Wait/force cleanup → old entries removed. |

---

## Out of Scope (Deferred)

| Item | Reason |
|------|--------|
| Offline-mode hardening | Solved via login/authentication mod instead of in-CPM defense |
| GPU encryption | Not feasible on consumer GPUs; handled by EncryptedModelBlob + MemoryProtector at system RAM level |
| IP-bound sessions | Defense-in-depth; lower priority than the 5 items above |
| Admin 2FA | Lower priority; bcrypt (cost 12) + HMAC-SHA256 is sufficient for initial release |
