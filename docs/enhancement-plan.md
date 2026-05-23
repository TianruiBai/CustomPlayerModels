# CPM Built-in Model Server — Enhancement Plan

## Table of Contents
1. [Executive Summary](#1-executive-summary)
2. [Current State Analysis](#2-current-state-analysis)
3. [Proposed Architecture](#3-proposed-architecture)
4. [Component Design](#4-component-design)
5. [Security Design](#5-security-design)
6. [Database Schema](#6-database-schema)
7. [Implementation Phases](#7-implementation-phases)
8. [Risk Assessment](#8-risk-assessment)

---

## 1. Executive Summary

This document describes the design for replacing CustomPlayerModels' dependency on the author's central hosting services with a **self-contained model server + database** that can run entirely offline. The new system enables:

- **Client-side model upload** via the in-game editor directly to the server, with support for **large model files** (up to several MB) via chunked/resumable transfer
- **Local database storage** per-server — no external services required; models are **server-bound** (accessible only when connected to that specific Minecraft server, not globally browseable)
- **Full offline-mode compatibility** using name-derived UUIDs for player identification
- **Admin management** via in-game OP-protected commands AND an optional web dashboard (HTTP on configurable port)
- **Defense-in-depth security** with AES-256-GCM (database + server RAM + transport), time-based key derivation, and client-side memory hardening
- **Native-port transport for model data**: ALL model upload/download/list traffic goes through Minecraft's existing network port (port 25565). The admin web dashboard runs on a separate HTTP port (localhost by default) for browser-based management
- **Time-based encryption**: session keys are bound to time windows (configurable, default 30-minute). Even with full source code access, captured ciphertext cannot be replayed or decrypted outside its validity window. Key rotation is automatic and transparent

**User Model Access Rule:** A player MUST be connected to the Minecraft server (with completed CPM HelloC2S/HelloS2C handshake) before they can list, upload, download, or delete models on that server. Model access is always scoped to the current server session. Server operators (OP level 2+) can manage all models via in-game commands or the web dashboard.

---

## 2. Current State Analysis

### 2.1 Existing Architecture Summary

```
┌─────────────────────────────────────────────────────────────────┐
│                          CLIENT                                  │
│  ┌──────────┐    ┌──────────────────┐    ┌───────────────────┐  │
│  │ Editor   │───▶│ player_models/   │───▶│ NetworkUtil.      │  │
│  │ Exporter │    │ (local .cpmmodel)│    │ sendSkinDataTo    │  │
│  └──────────┘    └──────────────────┘    │ Server()          │  │
│                                          └───────┬───────────┘  │
│                                                  │              │
│  ModelFile.load() → dataBlock byte[]             │ SetSkinC2S   │
│  (Header 0x53 + parts + checksum)                │ (NBTC2S)     │
└──────────────────────────────────────────────────┼──────────────┘
                                                   │ via
                                                   │ ByteArrayPayload
                                                   ▼
┌──────────────────────────────────────────────────────────────────┐
│                          SERVER                                  │
│  ┌───────────────┐    ┌──────────────────┐    ┌──────────────┐  │
│  │ SetSkinC2S.   │───▶│ PlayerData.data  │───▶│ World Config  │  │
│  │ handle()      │    │ (byte[] in RAM)  │    │ (JSON/Base64) │  │
│  └───────────────┘    └──────────────────┘    └──────────────┘  │
│                                                                  │
│  Persistence: skins.<uuid>.model = Base64(data)                  │
│              skins.<uuid>.forced = true/false                     │
│              (stored to world's cpm.json)                         │
└──────────────────────────────────────────────────────────────────┘
```

### 2.2 Key Findings from Codebase Review

| Area | Finding | Implication |
|---|---|---|
| **Model upload path** | `NetworkUtil.sendSkinDataToServer()` reads from `player_models/` dir, extracts `dataBlock` from `.cpmmodel` file, wraps in `NBTTagCompound` as `byte[]` under key `"data"`, sends via `SetSkinC2S` | The upload pipeline already exists — we just need to add a **UI trigger**, **chunking for large files**, and **server-side persistence** |
| **Model format** | Binary: `HEADER(0x53)` + `[UTF name, UTF desc, byte[] dataBlock, byte[] overflowLocal, Link, Image icon, Checksum]` | Format is stable and self-contained; ideal for database BLOB storage |
| **Model size** | `dataBlock.length` typically 5–200 KB for simple models, but can reach **2–5 MB** for models with embedded textures (high-res skins, animated textures). `convertable()` threshold is ≤ 2048 bytes for skin-embed mode. | Needs **chunked transfer** for models > 32KB (Minecraft packet size limit). Single DB row with BLOB still practical up to ~10 MB. |
| **Minecraft packet size limit** | NeoForge `CustomPacketPayload` is limited to `~32,767 bytes` (Minecraft's `FriendlyByteBuf` max). Larger payloads are rejected by the protocol layer. | Must implement **fragmentation protocol**: split model bytes into ≤30KB chunks, send as sequence of packets, reassemble on server. This avoids needing a separate HTTP port. |
| **Server storage** | `PlayerData.data` (byte[] in RAM) → `PlayerData.save()` writes Base64 string to world config JSON | JSON-based storage is **not scalable** — needs database replacement |
| **Player identity** | `ServerPlayer::getUUID` — in offline-mode, this is `UUID.nameUUIDFromBytes("OfflinePlayer:" + name)` | Offline UUIDs are deterministic; usable as primary DB key |
| **Model access gating** | Currently, any CPM-handshaked player receives model data. No per-server model scoping exists. | New requirement: models are **server-bound**. A player must be connected to the Minecraft server to access its model DB. No cross-server model browsing. The CPM handshake (`HelloC2S`/`HelloS2C`) is the authentication gate. |
| **Network layer** | All packets route through `ByteArrayPayload` → `NetHandler.processPacket()` → `IOHelper` deserialization. No encryption. | Clean extension point; encryption can be added as a **wrapper layer** around existing NBT serialization. **Native-port-only approach**: all model CRUD operations go through the existing Minecraft connection. No new ports. |
| **Handshake** | `HelloS2C`/`HelloC2S` exchanges `ServerCaps` bitfield | Can add capability flag for new protocol version. Handshake completion = authentication for model access. |
| **Config system** | `ModConfigFile` — hierarchical JSON with change listeners | New config entries needed for server URL, encryption keys, DB path |
| **Plugin API** | `ICPMPlugin` / `ICommonAPI` / `IClientAPI` — well-defined extension interface | The model server can be partially implemented as a plugin for testability; core integration in the mod itself |
| **Loader system** | `ResourceLoader` interface with 5 implementations (Gist, GitHub, Pastebin, ModelsCDN, HTTP) | A new `ResourceLoader` implementation for the built-in server DB is a natural fit |
| **Animation sync** | `GestureC2S`, `ServerAnimationS2C` handle animation state distribution | Unchanged; the model server only deals with model storage, not runtime animation sync |

### 2.3 Packet Size Constraint Analysis

Minecraft's network protocol imposes a hard limit on custom payload size:

```
NeoForge CustomPacketPayload → FriendlyByteBuf (max readable bytes: 32767)
                                                  │
                                    Anything larger → packet rejected
                                                  │
                               CPM model byte[] can easily exceed this
```

**Model Size Distribution (estimated from typical usage):**

| Category | Size Range | % of Models | Transfer Strategy |
|---|---|---|---|
| Skin-embed (convertable) | ≤ 2 KB | ~15% | Single packet (direct SetSkinC2S) |
| Simple model | 2–32 KB | ~40% | Single encrypted packet |
| Medium model | 32–256 KB | ~30% | 2–9 chunked packets |
| Large model (with textures) | 256 KB–2 MB | ~12% | 9–67 chunked packets |
| Very large model | 2–10 MB | ~3% | 67–334 chunked packets, with resume support |

**Conclusion:** A fragmentation protocol on the native Minecraft port handles >97% of models. For the <3% that exceed practical packet-based transfer limits, an optional HTTP/RSA fallback channel is provided.

### 2.4 Model Protection — Double-Check Analysis

Below is a trace of every point where model data exists in plaintext, with the proposed protection at each point:

```
PLAINTEXT MODEL DATA LOCATIONS (both sides):

CLIENT SIDE:
┌────────────────────────────┬──────────────────────────────────────────┐
│ Location                   │ Protection                               │
├────────────────────────────┼──────────────────────────────────────────┤
│ Editor memory (designing)  │ GuardedByteArray (XOR-masked), wiped on  │
│                            │ editor close. Not stored as String.      │
├────────────────────────────┼──────────────────────────────────────────┤
│ player_models/*.cpmmodel   │ NEW: AES-256-GCM encrypt local files     │
│ (local disk cache)         │ with key derived from OS user session.   │
│                            │ Optional; configurable.                  │
├────────────────────────────┼──────────────────────────────────────────┤
│ Export buffer (before send)│ GuardedByteArray, auto-wiped after send  │
│                            │ completes. GC-triggered wipe on close.   │
├────────────────────────────┼──────────────────────────────────────────┤
│ Network buffer (send)      │ AES-256-GCM encrypted before write to    │
│                            │ Netty buffer. Plaintext never in channel.│
├────────────────────────────┼──────────────────────────────────────────┤
│ GPU VRAM (rendered model)  │ Not practical to encrypt. Rendered       │
│                            │ geometry is derived data, not raw file.  │
│                            │ Acceptable residual risk.                │
└────────────────────────────┴──────────────────────────────────────────┘

SERVER SIDE:
┌────────────────────────────┬──────────────────────────────────────────┐
│ Location                   │ Protection                               │
├────────────────────────────┼──────────────────────────────────────────┤
│ Network buffer (receive)   │ AES-256-GCM decrypted in isolated buffer │
│                            │ then immediately re-encrypted for RAM.   │
├────────────────────────────┼──────────────────────────────────────────┤
│ PlayerData.data (RAM)      │ NEW: EncryptedModelBlob — stored as      │
│                            │ AES-256-GCM ciphertext in RAM. Decrypted  │
│                            │ on-demand only when sending to clients   │
│                            │ (SetSkinS2C). Re-encrypted after.        │
├────────────────────────────┼──────────────────────────────────────────┤
│ Network buffer (broadcast) │ Each client gets the model encrypted     │
│ SetSkinS2C → tracking      │ with THEIR session key (not a global     │
│                            │ key). Per-client encryption.             │
├────────────────────────────┼──────────────────────────────────────────┤
│ Database (disk)            │ AES-256-GCM column encryption + H2 file  │
│                            │ encryption. Two independent key layers.  │
├────────────────────────────┼──────────────────────────────────────────┤
│ Database cache (RAM)       │ H2 page cache may hold decrypted pages.  │
│                            │ Mitigated by H2 file encryption layer    │
│                            │ (pages encrypted on disk, decrypted in   │
│                            │ H2's internal buffer; acceptable risk).  │
├────────────────────────────┼──────────────────────────────────────────┤
│ DB backup files            │ Encrypted at column level; file-level    │
│                            │ encryption if backed up via H2 tools.    │
├────────────────────────────┼──────────────────────────────────────────┤
│ Server logs                │ Sanitized: never log model bytes. Only   │
│                            │ log: model ID, player UUID, size, name.  │
├────────────────────────────┼──────────────────────────────────────────┤
│ Admin audit log           │ Admin actions logged to audit_log table   │
│                            │ with acting player UUID. OP-gated, no     │
│                            │ separate web access needed.               │
└────────────────────────────┴──────────────────────────────────────────┘
```

**Key Design Decision — Server RAM Encryption:**
Models will be stored in server RAM as `EncryptedModelBlob` (AES-256-GCM ciphertext), not as plain `byte[]`. When `SetSkinS2C` needs to send the model, it is briefly decrypted, re-encrypted with the target client's session key, and sent. The decrypted form exists only for the duration of a single packet serialization (~microseconds). This prevents a server memory dump from exposing all player models at once.

### 2.5 Feasibility Assessment

| Concern | Assessment |
|---|---|
| Can client upload raw model data to server? | ✅ **Yes.** The `SetSkinC2S` packet already sends `byte[] data` from client to server. We add a UI button in the editor that calls this path. |
| Can large models (>32KB) be transferred? | ✅ **Yes, via chunking.** Implement a fragmentation protocol on the native Minecraft port: split data into ≤30KB chunks, send as `ModelDataChunkC2S` packets with sequence numbers, reassemble on server. Supports resume for interrupted transfers. |
| Can we use the Minecraft native port? | ✅ **Yes — only approach.** All model CRUD packed into new C2S/S2C packet types over the existing `ByteArrayPayload` channel. No new port to open, no firewall rules, no TLS certificates. Admin management via OP-protected in-game commands. |
| Can server store models in a database? | ✅ **Yes.** Replace `PlayerData.save()` JSON persistence with an embedded database (H2/SQLite). |
| Can offline UUIDs be used as identifiers? | ✅ **Yes.** The NetHandler already uses `getPlayerUUID.apply(player).toString()` as the lookup key. Offline UUIDs are deterministic. |
| Can models be server-bound (no cross-server access)? | ✅ **Yes.** Model access is gated by CPM handshake completion. The database is local to each Minecraft server instance. No global model registry. `HelloC2S`/`HelloS2C` handshake = authentication. |
| Can server RAM hold encrypted model data? | ✅ **Yes.** Introduce `EncryptedModelBlob` wrapper that stores AES-256-GCM ciphertext instead of plain `byte[]` in `PlayerData`. Decrypt only for the duration of packet serialization, then wipe. |
| Can local client model files be encrypted on disk? | ✅ **Yes.** Extend `ModelFile` with optional AES-256-GCM encryption using a key derived from the OS user session or a user-set passphrase. |
| Can we add admin management? | ✅ **Yes.** In-game OP-protected commands (`/cpm admin ...`) provide full model management. No web server needed. A web dashboard may be added as an optional future module. |
| Can encryption be added without breaking existing protocol? | ✅ **Yes.** The encryption layer wraps at the NBT or IOHelper level, transparent to existing packet handlers. |

---

## 3. Proposed Architecture

### 3.1 System Overview

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         CLIENT (Minecraft)                               │
│                                                                          │
│  ┌────────────┐   ┌──────────────┐   ┌────────────────────────────────┐ │
│  │ Editor GUI │──▶│ ExportAction │──▶│ CpmModelTransferClient         │ │
│  │            │   │ (new button) │   │  ├─ ChunkedUploader (split)    │ │
│  └────────────┘   └──────────────┘   │  ├─ ResumeManager (checkpoint) │ │
│                                       │  ├─ Progress UI (cancelable)   │ │
│  ┌────────────┐                       │  ├─ ModelListCache             │ │
│  │ Model List │◀──────────────────────│  ├─ Encrypt before send        │ │
│  │ GUI        │                       │  └─ GuardedByteArray wipe      │ │
│  └────────────┘                       └───────────────┬────────────────┘ │
│                                                        │                  │
│  ═══════════ ALL TRAFFIC (Native Minecraft Port 25565 only) ════════════ │
│                                                        │                  │
│  Packet Types (all via ByteArrayPayload):               │                  │
│  ┌────────────────────────────────────────────────────┐ │                  │
│  │ ModelListReqC2S / ModelListResS2C                  │ │                  │
│  │ ModelUploadInitC2S / ModelUploadInitAckS2C         │ │                  │
│  │ ModelDataChunkC2S / ModelDataChunkAckS2C           │ │                  │
│  │ ModelUploadCompleteC2S / ModelUploadResultS2C      │ │                  │
│  │ ModelDownloadReqC2S / ModelDownloadChunkS2C        │ │                  │
│  │ ModelDeleteReqC2S / ModelDeleteResultS2C           │ │                  │
│  │ (SetSkinC2S — existing, now with encryption)       │ │                  │
│  └────────────────────────────────────────────────────┘ │                  │
│                                                          │                  │
│  All data AES-256-GCM encrypted with time-bound keys     │                  │
│  Key derived from MC protocol shared secret via HKDF     │                  │
│  No RSA needed. No separate port.                        │                  │
└──────────────────────────────────────────────────────────┼──────────────────┘
                                                           │
                                      All traffic through   │
                                      Minecraft's Netty     │
                                      pipeline (port 25565) │
                                                           ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                     SERVER (Minecraft Server Process)                    │
│                                                                          │
│  ┌──────────────────────────┐   ┌─────────────────────────────────────┐ │
│  │ CpmModelPacketHandler    │   │ Admin Commands (OP-protected)       │ │
│  │ (native port receiver)   │   │  ├─ /cpm admin models list/delete   │ │
│  │  ├─ Chunk reassembly     │   │  ├─ /cpm admin models force/unforce │ │
│  │  ├─ Fragment ACK         │   │  ├─ /cpm admin players block/unblock│ │
│  │  ├─ Time-window validate │   │  ├─ /cpm admin audit               │ │
│  │  ├─ Decrypt & validate   │   │  └─ /cpm admin db backup/stats      │ │
│  │  └─ Forward to svc       │   └─────────────────────────────────────┘ │
│  └──────────┬───────────────┘                                            │
│             │                                                            │
│             │   ┌─────────────────────────────────────────────────────┐ │
│             │   │ Admin Web Dashboard (HTTP, localhost:8080 default)  │ │
│             │   │  ├─ NanoHTTPD embedded server                       │ │
│             │   │  ├─ bcrypt auth → JWT tokens                        │ │
│             │   │  ├─ Bundled SPA frontend                            │ │
│             │   │  └─ Shares ModelService backend                     │ │
│             │   └────────────────────────────┬────────────────────────┘ │
│             │                                │                           │
│             ▼                                ▼                           │
│  ┌──────────────────────────┐   ┌─────────────────────────────────────┐ │
│  │ ModelService             │──▶│ CryptoService                       │ │
│  │  ├─ storeModel()         │   │  ├─ AES-256-GCM encrypt/decrypt     │ │
│  │  ├─ getModelsFor(uuid)   │   │  ├─ Time-bound key derivation       │ │
│  │  ├─ deleteModel()        │   │  ├─ SessionKeyManager (HKDF-based)  │ │
│  │  └─ validateModel()      │   │  └─ EncryptedModelBlob (RAM-safe)   │ │
│  └──────────┬───────────────┘   └─────────────────────────────────────┘ │
│             │                                                            │
│  ┌──────────▼───────────┐   ┌─────────────────────────────────────────┐ │
│  │ ModelRepository      │   │ Config Keys (new)                       │ │
│  │  (H2 via JDBC)       │   │  cpm.server.enabled          = false    │ │
│  │  ├─ players          │   │  cpm.server.maxModelSizeMb   = 10       │ │
│  │  ├─ models           │   │  cpm.server.chunkSizeKb      = 30       │ │
│  │  ├─ upload_sessions   │   │  cpm.server.timeWindowMin   = 30       │ │
│  │  └─ audit_log         │   │  cpm.server.timeWindowSkew  = 1        │ │
│  └───────────────────────┘   │  ...                                   │ │
│                              │  (HTTP port for admin dashboard)      │ │
│                              └─────────────────────────────────────────┘ │
│                                                                          │
│  ┌────────────────────────────────────────────────────────────────────┐ │
│  │ Admin Web Dashboard — included in initial release (NanoHTTPD + SPA)│ │
│  └────────────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────────┘
```

**Transport Decision Matrix:**

| Traffic Type | Transport | Rationale |
|---|---|---|
| Model upload (≤32 KB) | Native port, single `SetSkinC2S` packet | Fits in one `FriendlyByteBuf` |
| Model upload (32 KB – 10 MB) | Native port, chunked `ModelDataChunkC2S` packets | 2–334 chunks at 30KB each with ACK/resume/time-bound keys |
| Model list, download, delete | Native port, C2S/S2C packets | Low bandwidth; native port keeps it simple |
| Admin web dashboard | HTTP on configurable port (default 8080, localhost) | Browser-based UI; bcrypt auth + JWT tokens required |

**All model data (upload/download/list/delete) goes through the Minecraft native port.** Only the admin web dashboard requires a separate HTTP port, and it binds to localhost by default for security.

### 3.2 Native-Port-Only Design Rationale

**Why use only the Minecraft native port?**

1. **No new attack surface** — no additional listening port that needs firewall rules, TLS, or DDoS protection
2. **Authentication is free** — the CPM `HelloC2S`/`HelloS2S` handshake already authenticates the player; models are naturally server-bound
3. **No TLS certificate management** — the Minecraft connection is already encrypted (Mojang's protocol encryption); we layer AES-256-GCM with time-bound keys inside it
4. **Works through NAT/proxies** — players already connected to the server; no need for the client to reach a separate port
5. **Simpler deployment** — zero additional configuration for model upload/download
6. **Open-source resilient** — all encryption logic is in the source code; security comes from the server's secret keys, not from hidden algorithms

**Admin management (two paths):**

**Path 1 — In-game commands (OP-protected, native port):**
```
/cpm admin models list [player]     — List all models (optionally filtered by player)
/cpm admin models delete <id>       — Delete a model
/cpm admin models force <id>        — Force a model on its owner
/cpm admin models unforce <id>      — Remove forced status
/cpm admin players block <uuid>     — Block a player from uploading
/cpm admin players unblock <uuid>   — Unblock a player
/cpm admin audit [page]             — View audit log
/cpm admin db backup                — Trigger manual database backup
/cpm admin db stats                 — Show database statistics
```

**Path 2 — Web dashboard (HTTP, localhost:8080 by default):**
- Browser-based management UI (bundled SPA)
- bcrypt-hashed admin credentials + JWT session tokens
- Full model browsing, search, force/delete, player blocking
- Audit log viewer with pagination
- Database statistics and backup triggers
- Configurable bind address (localhost by default for security)
- Can be disabled entirely (`cpmServer.httpPort.enabled=false`)

### 3.3 Chunked Transfer Protocol (Native Port)

### 3.4 Data Flows

#### Flow A: Client Uploads Model (Native Port, Chunked)

```
1. Player opens Editor → designs model → clicks "Upload to Server"
   ⚠ BUTTON ONLY VISIBLE if CPM handshake with server is complete (hasMod=true)

2. Exporter serializes to byte[] → size checked:
   ├─ ≤ 30 KB → use existing SetSkinC2S path (no change needed)
   └─ > 30 KB → chunked upload:

3. ChunkedUploader:
   a. Compute SHA-256 hash of full model byte[]
   b. Split into N chunks of ≤30,720 bytes (30KB)
   c. Encrypt each chunk with AES-256-GCM (session key from handshake)
   d. Send ModelUploadInitC2S { modelName, totalSize, numChunks, sha256 }
   e. Server responds ModelUploadInitAckS2C { uploadId, accepted }

4. For each chunk i in 0..N-1 (with progress bar in Editor UI):
   a. Send ModelDataChunkC2S { uploadId, chunkIndex, encryptedChunkData, chunkIv, chunkTag }
   b. Server responds ModelDataChunkAckS2C { uploadId, chunkIndex, status=OK|RETRY }
   c. If RETRY: resend chunk (up to 3 retries)
   d. Progress bar updates: (i+1)/N * 100%
   e. Player can CANCEL at any point → send ModelUploadCancelC2S { uploadId }

5. After all chunks sent:
   a. Send ModelUploadCompleteC2S { uploadId, fullSha256 }
   b. Server reassembles, verifies SHA-256, decrypts, validates ModelFile header
   c. Server stores in DB (encrypted-at-rest)
   d. Server responds ModelUploadResultS2C { uploadId, modelId, status=OK }

6. If connection drops mid-upload:
   a. Client stores uploadId + lastAckedChunk in memory
   b. On reconnect (new handshake), client sends ModelUploadResumeC2S { uploadId }
   c. Server responds with { lastReceivedChunk } → client resumes from chunk+1
   d. Upload sessions expire after 10 minutes of inactivity
```

#### Flow B: Player Accesses Models (Server-Bound Rule)

```
1. Player joins Minecraft server
2. CPM handshake completes (HelloS2C → HelloC2S)
   └─ Server sets net.cpm$setHasMod(true) — this is the AUTH GATE

3. Player opens Model List GUI (new button in Gesture menu or Settings)
   a. Client sends ModelListReqC2S { filter: "mine" }
   b. Server queries ModelRepository.getModelsFor(playerUUID)
      ⚠ Queries are ALWAYS scoped to the authenticated player's UUID
      ⚠ No cross-player browsing unless admin
   c. Server responds ModelListResS2C { models: [{id, name, size, isDefault, createdAt}] }

4. Player selects a model → "Set as Active"
   a. Client sends ModelSetActiveC2S { modelId }
   b. Server loads from DB (decrypt column), stores as EncryptedModelBlob in PlayerData
   c. Server broadcasts SetSkinS2C to tracking players
   d. Model is now visible to others

5. Player can also:
   - Download model (for local editing): ModelDownloadReqC2S → chunked S2C transfer
   - Delete model: ModelDeleteReqC2S → ModelDeleteResultS2C
   - Set default: ModelSetDefaultC2S

   ⚠ ALL operations require: net.cpm$hasMod() == true (handshake completed)
   ⚠ Player can only access their OWN models (enforced server-side by UUID match)
```

#### Flow C: Admin Manages Models (Commands + Web Dashboard)

```
Path 1 — In-Game Commands:
1. Admin (OP level 2+) executes /cpm admin ... in chat or server console
2. Commands route through existing CPM command framework (Command.java)
3. Results displayed in chat (paginated with clickable entries)
4. All actions logged to audit_log

Path 2 — Web Dashboard:
1. Admin opens browser → http://localhost:8080/admin (or configured host:port)
2. Login with admin credentials → bcrypt verification → JWT token issued
3. Dashboard shows ALL models across all players (admin privilege)
4. Admin actions: delete, force/unforce, block/unblock, view audit
5. All actions go through same ModelService backend
6. Dashboard is usable remotely if bind address is changed from localhost

Both paths share the same ModelService / ModelRepository backend.
The web dashboard runs on a separate HTTP port (NanoHTTPD, configurable).
It can be disabled entirely via cpmServer.httpPort.enabled=false.
```

---

## 4. Component Design

### 4.1 New Packages

```
src/main/java/com/tom/cpm/
├── server/                          # NEW: Built-in model server
│   ├── CpmModelPacketHandler.java   # Native-port packet receiver (chunk reassembly)
│   ├── CpmModelHttpServer.java      # Embedded HTTP server (NanoHTTPD, admin dashboard)
│   ├── CpmServerConfig.java         # Server configuration
│   ├── model/
│   │   ├── ModelService.java        # Business logic
│   │   ├── ModelRepository.java     # Database access layer
│   │   ├── ModelEntity.java         # POJO model entity
│   │   ├── UploadSession.java       # In-progress chunked upload state
│   │   └── ModelValidator.java      # Safety validation
│   ├── crypto/
│   │   ├── CryptoService.java       # AES-256-GCM + time-bound key derivation
│   │   ├── TimeBoundKeyManager.java # Time-window key derivation, clock-skew handling
│   │   ├── KeyManager.java          # Master key generation, JCEKS keystore storage
│   │   ├── SessionKeyManager.java   # Per-client session keys (HKDF from MC shared secret)
│   │   ├── EncryptedModelBlob.java  # RAM-safe encrypted model holder
│   │   └── MemoryProtector.java     # GuardedByteArray, auto-wipe utilities
│   ├── db/
│   │   ├── DatabaseManager.java     # H2 lifecycle (init, migrate, backup)
│   │   ├── MigrationManager.java    # Schema versioning
│   │   └── EncryptedBlob.java       # AES-GCM encrypted BLOB wrapper (DB column type)
│   ├── admin/
│   │   ├── AdminCommandHandler.java # /cpm admin ... command implementations
│   │   ├── AdminHttpHandler.java    # Admin dashboard API endpoints
│   │   ├── AdminAuthFilter.java     # bcrypt auth + JWT token validation
│   │   └── webapp/                  # Bundled SPA frontend (vanilla JS)
│   │       ├── index.html
│   │       ├── js/
│   │       └── css/
│   ├── transfer/
│   │   ├── ChunkedUploader.java     # Client-side: split, encrypt, send, resume
│   │   ├── ChunkedReceiver.java     # Server-side: receive, ACK, reassemble, verify
│   │   ├── TransferResumeManager.java # Resume state tracking (both sides)
│   │   └── ChunkProtocol.java       # Constants: chunk size, max retries, timeout
│   ├── compat/
│   │   ├── OfflineUUIDUtil.java     # Deterministic offline UUID generation
│   │   └── LegacyModelMigrator.java # Migrate JSON-stored models to DB
│   └── client/
│       ├── CpmModelTransferClient.java # Client-side transfer coordinator
│       ├── ModelListCache.java         # Cached model list with TTL
│       └── UploadProgressTracker.java  # UI progress state
│
├── shared/network/packet/           # NEW packet types (extend existing package)
│   ├── ModelListReqC2S.java         # C→S: request model list
│   ├── ModelListResS2C.java         # S→C: model list response
│   ├── ModelUploadInitC2S.java      # C→S: start chunked upload
│   ├── ModelUploadInitAckS2C.java   # S→C: upload accepted/rejected
│   ├── ModelDataChunkC2S.java       # C→S: single encrypted chunk
│   ├── ModelDataChunkAckS2C.java    # S→C: chunk received OK / retry
│   ├── ModelUploadCompleteC2S.java  # C→S: all chunks sent, verify hash
│   ├── ModelUploadResultS2C.java    # S→C: upload success/fail with modelId
│   ├── ModelUploadCancelC2S.java    # C→S: abort in-progress upload
│   ├── ModelUploadResumeC2S.java    # C→S: resume after disconnect
│   ├── ModelDownloadReqC2S.java     # C→S: request model download
│   ├── ModelDownloadChunkS2C.java   # S→C: single chunk of model data
│   ├── ModelSetActiveC2S.java       # C→S: set this model as active skin
│   ├── ModelSetDefaultC2S.java      # C→S: set this model as default
│   ├── ModelDeleteReqC2S.java       # C→S: delete model
│   └── ModelDeleteResultS2C.java    # S→C: delete result
```

### 4.2 Modified Existing Files

| File | Change | Reason |
|---|---|---|
| `CustomPlayerModels.java` | Register `CpmModelPacketHandler`, `CpmModelHttpServer`, and config keys on server start; register new packet types in `onPayloadRegister` | Server lifecycle + native port packet registration + HTTP server lifecycle |
| `Command.java` | Add `/cpm admin ...` subcommands (models list/delete/force/unforce, players block/unblock, audit, db backup/stats) | Admin management via in-game commands |
| `CommonBase.java` | Add `getModelService()`, `getCryptoService()` accessors | Dependency injection |
| `NetworkUtil.java` | Add `sendModelListRequest()`, `initChunkedUpload()` helpers | Client-side transfer orchestration |
| `ConfigKeys.java` | Add 18+ new keys (`CPM_SERVER_*`, `CPM_DB_*`, `CPM_TRANSFER_*`, `CPM_TIME_*`) | New configuration surface |
| `ServerHandler.java` | Hook into `onPlayerJoin` to auto-load default model from DB; hook disconnect to clean up upload sessions | Database integration + session cleanup |
| `ServerHandlerBase.java` | Add `ModelService` field, inject on init; add `UploadSession` tracking map | Service wiring |
| `Exporter.java` | Add `exportForUpload()` method; add "Upload to Server" button (visible only when `hasMod()==true`) | Editor integration |
| `ModelDefinitionLoader.java` | Add new `ResourceLoader` implementation for built-in DB (`loaderId = "cpmdb"`) | Model sourcing |
| `NetHandler.java` | Add `hasServerCap(CPM_BUILT_IN_SERVER)`; add `getSessionKey()` for time-bound encryption context; add `onDisconnect()` hook | Capability + session + cleanup |
| `ServerCaps.java` | Add `CPM_BUILT_IN_SERVER`, `CPM_CHUNKED_TRANSFER`, `CPM_TIME_BOUND_KEYS` capability flags | Protocol extension |
| `PlayerData.java` | Add `EncryptedModelBlob` field (replaces `byte[] data`); add `loadFromDb()` / `saveToDb()`; add `getDecryptedModel()` that decrypts on-demand and wipes after use | RAM-encrypted model storage |
| `ModConfig.java` | Add `getServerConfig()` method | Config accessor |
| `IPacket.java` | Add `isLargePayload()` method (default: false) to allow >32KB bypass for chunked packets | Packet routing hint |
| `ModelFile.java` | Add `encryptToFile()` / `decryptFromFile()` for optional local client-side model encryption | Client disk protection |
| `ByteArrayPayload.java` | No change needed — already handles arbitrary byte arrays | — |

### 4.3 Chunked Transfer Protocol Specification

```java
// Constants (in ChunkProtocol.java)
public static final int MAX_CHUNK_SIZE    = 30_720;  // 30 KB (safe under 32KB limit)
public static final int MAX_RETRIES       = 3;        // Per chunk
public static final int CHUNK_TIMEOUT_MS  = 30_000;   // 30 seconds per chunk
public static final int SESSION_TIMEOUT_MS = 600_000; // 10 minutes total

// Chunk wire format (inside NBTTagCompound):
// {
//   "uploadId": "<uuid>",        // Unique upload session ID
//   "chunkIndex": 0,             // 0-based chunk number
//   "totalChunks": 5,            // Total chunks expected
//   "data": <byte[]>,            // AES-256-GCM encrypted chunk data
//   "dataIv": <byte[12]>,        // GCM initialization vector
//   "dataTag": <byte[16]>,       // GCM authentication tag
//   "chunkSha256": <byte[32]>,   // SHA-256 of THIS chunk (plaintext)
//   "fullSha256": <byte[32]>     // SHA-256 of FULL model (for final verification)
// }
```

### 4.4 Embedded HTTP Server (Admin Dashboard)

The HTTP server serves ONLY the admin web dashboard. Model data never flows through it.

| Library | Pros | Cons |
|---|---|---|
| **NanoHTTPD** | Single-file, no deps, tiny (~40KB) | Limited HTTP/2, no WebSocket built-in |
| **Netty** (bundled in Minecraft) | Already in classpath, full-featured | Complex API; classloader conflicts possible |
| **Sun HTTP Server** (JDK built-in) | Zero dependency, simple API | No HTTPS, limited features |

**Recommendation: NanoHTTPD** — admin dashboard is low-traffic (1-2 concurrent users). Simplicity wins.

### 4.5 Database Options

| Database | Pros | Cons |
|---|---|---|
| **H2** (embedded) | Pure Java, no native deps, encryption extension, good SQL support | Slightly larger file size |
| **SQLite** (via JDBC) | Widely known, small, reliable | No built-in encryption, needs native driver loading |
| **H2 with AES encryption** | `CIPHER=AES` mode encrypts entire DB file | Slightly slower than plain H2 |

**Recommendation: H2 with file-based encryption (`jdbc:h2:file:./cpm_db;CIPHER=AES`)** — this provides database-level AES encryption, which combined with application-level AES-256-GCM for the BLOB columns, gives us two layers of encryption.

---

## 5. Security Design

### 5.1 Threat Model

```
Threat Actors:
├─ T1: Casual server admin curious about player models
├─ T2: Malicious player on the same server sniffing packets
├─ T3: Attacker with filesystem access to the server
├─ T4: Attacker with memory dump of client or server process
└─ T5: Attacker performing MITM between client and server
```

### 5.2 Encryption Layers

```
┌──────────────────────────────────────────────────────────────┐
│ LAYER 1: Database File Encryption (AES-256)                  │
│ ┌──────────────────────────────────────────────────────────┐ │
│ │ H2 CIPHER=AES mode → entire DB file encrypted on disk    │ │
│ │ Key: derived from server config + salt (PBKDF2)          │ │
│ └──────────────────────────────────────────────────────────┘ │
│                                                              │
│ LAYER 2: Column-Level Encryption (AES-256-GCM)               │
│ ┌──────────────────────────────────────────────────────────┐ │
│ │ Each model_data BLOB encrypted with unique per-row key   │ │
│ │ Key: row_key = HKDF(master_key, row_uuid, "model")       │ │
│ │ Provides: confidentiality + integrity (GCM auth tag)     │ │
│ │ Even if DB file key compromised, rows individually safe  │ │
│ └──────────────────────────────────────────────────────────┘ │
│                                                              │
│ LAYER 3: Transport Encryption (AES-256-GCM over Minecraft Port)    │
│ ┌──────────────────────────────────────────────────────────────┐   │
│ │ AES-256-GCM over existing Minecraft Netty pipeline           │   │
│ │ Key: derived from MC protocol shared secret via HKDF         │   │
│ │ Each packet encrypted independently with message counter     │   │
│ │ TIME-BOUND: session key changes every time window (30 min)    │   │
│ │ Replay protection: time-window validation + monotonic cntr   │   │
│ │ No RSA needed — all traffic through native port 25565        │   │
│ └──────────────────────────────────────────────────────────────┘   │
│                                                                    │
│ LAYER 4: Server RAM Encryption (EncryptedModelBlob)                │
│ ┌──────────────────────────────────────────────────────────────┐   │
│ │ Models stored as AES-256-GCM ciphertext in PlayerData (RAM)  │   │
│ │ Decrypted on-demand only for SetSkinS2C broadcast            │   │
│ │ Decrypted form lives for ~microseconds (single serialization) │   │
│ │ After serialization: key material wiped, buffer zeroed       │   │
│ │ Prevents memory dump from exposing all player models at once │   │
│ └──────────────────────────────────────────────────────────────┘   │
│                                                                    │
│ LAYER 5: Client Memory Protection                                  │
│ ┌──────────────────────────────────────────────────────────┐ │
│ │ GuardedByteArray: wipe after use (zero-fill + GC hint)   │ │
│ │ ObfuscatedKeyStore: XOR key with random pad in memory    │ │
│ │ Anti-dump: canary values, runtime integrity checks       │ │
│ │ Model data never in plaintext String (always byte[])     │ │
│ └──────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────┘
```

### 5.3 Cryptographic Specifications

#### Time-Bound Key Derivation (Open-Source Resilient)

Since the mod is open source, all algorithms are publicly visible. Security comes from the **server's secret keys**, not from algorithm obscurity. Time-binding adds defense against:

| Attack | Without Time-Binding | With Time-Binding |
|---|---|---|
| Capture ciphertext, decrypt later with stolen key | ✅ All data decryptable | ❌ Key expired — only current window decryptable |
| Replay captured packet | ❌ Replayed within window | ❌ Time-window ID checked; replay rejected if window expired |
| Long-term passive collection | ✅ All captured data decryptable with single key compromise | ❌ Each window needs its own key; attacker must compromise continuously |
| Offline brute-force of session key | Feasible with enough compute | Must complete within time window (e.g., 30 minutes) |

**Time-Window Key Derivation Scheme:**

```
                    ┌─────────────────────────┐
                    │ MC Shared Secret (HKDF)  │
                    │ (from Mojang auth proto) │
                    └───────────┬─────────────┘
                                │
                                ▼
                    ┌─────────────────────────┐
                    │ base_session_key (32B)    │
                    │ = HKDF-SHA512(shared,     │
                    │     salt="cpm_session_v1")│
                    └───────────┬─────────────┘
                                │
              ┌─────────────────┼─────────────────┐
              ▼                 ▼                 ▼
     window_key[-1]      window_key[0]      window_key[+1]
     (prev hour)         (current hour)     (next hour)

Each window_key[t] = HKDF-SHA512(
    IKM    = base_session_key,
    salt   = "cpm_time_window_v1",
    info   = t.toString(),           // window ID = floor(unix_time / window_seconds)
    length = 32
)
```

**Wire Format (each encrypted message):**

```java
// Inside NBTTagCompound for each packet:
{
    "timeWindowId": <uint64>,    // Window ID = floor(server_unix_time / 1800)
    "msgCounter":   <uint64>,    // Monotonic per-session message counter
    "dataIv":       <byte[12]>,  // GCM initialization vector (random)
    "data":         <byte[]>,    // AES-256-GCM ciphertext (includes 16-byte GCM tag appended)
}

// AAD (Additional Authenticated Data) for GCM:
// timeWindowId || msgCounter || packetType
// This binds the ciphertext to the specific window, counter, and packet type.
// Tampering with the window ID causes GCM authentication failure.
```

**Clock-Skew Tolerance:**

```
Server maintains: currentWindow = floor(serverUnixTime / windowSeconds)

Client sends: clientWindowId  (derived from server's clock, received via
                               time_sync field in HelloS2C during handshake)

Server accepts: clientWindowId ∈ {currentWindow - skew, currentWindow, currentWindow + skew}
                where skew = cpmServer.timeWindowSkew (default: 1)

If client's clock has drifted beyond skew tolerance:
  → Server responds with TimeSyncS2C { serverWindowId, serverUnixTime }
  → Client resynchronizes and retries
```

**Key Rotation Lifecycle:**

```
Window N-1:  [key_N-1 valid]  ← Can still decrypt window N-1 messages
Window N:    [key_N valid]    ← Current window
Window N+1:  [key_N+1 valid]  ← Pre-derived, ready for next window

When window transitions (e.g., 12:00 → 13:00):
  1. Server derives key_N+1 (already cached)
  2. Server starts accepting window N and N+1
  3. Server stops accepting window N-1 (after grace period of skew windows)
  4. key_N-1 is wiped from memory

Grace period: old windows accepted for up to (skew) windows after expiry
              to handle in-flight messages during transition.
```

**Security Properties (Open-Source Context):**

- **Algorithm is public** — the HKDF derivation, window calculation, and wire format are all in the source code
- **Security depends on the base_session_key** — derived from Minecraft's shared secret (established by Mojang's authentication protocol), which is unique per connection and never transmitted in plaintext
- **Time windows limit blast radius** — if an attacker extracts the current window key from a memory dump, they can only decrypt messages from ±skew windows (~1 hour with default 30-min windows). Historical data and future data are safe.
- **No RSA dependency** — all key material derived symmetrically via HKDF from the MC shared secret. No asymmetric key generation needed.
- **GCM AAD binding** — the `timeWindowId` is included in GCM's authenticated data, so an attacker cannot change the window ID without detection (authentication tag fails).

**Configuration:**

```java
// In ConfigKeys.java
public static final String CPM_TIME_WINDOW_MINUTES = "cpmServer.time.windowMinutes";  // default: 30
public static final String CPM_TIME_WINDOW_SKEW    = "cpmServer.time.windowSkew";     // default: 1
public static final String CPM_TIME_SYNC_ON_JOIN   = "cpmServer.time.syncOnJoin";     // default: true
```

#### AES-256-GCM (Data Encryption)

```
Algorithm:    AES/GCM/NoPadding
Key size:     256 bits
IV size:      96 bits (12 bytes) — randomly generated per encryption
Tag size:     128 bits (16 bytes) — appended to ciphertext
Mode:         Galois/Counter Mode (provides AEAD)
Library:      javax.crypto (JDK built-in, no external dependency)

Encrypt(plaintext, key):
    iv = SecureRandom.nextBytes(12)
    cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv))
    ciphertext = cipher.doFinal(plaintext)
    return iv || ciphertext  // prepend IV to ciphertext

Decrypt(iv || ciphertext, key):
    iv = input[0:12]
    ciphertext = input[12:]
    cipher.init(DECRYPT_MODE, key, new GCMParameterSpec(128, iv))
    return cipher.doFinal(ciphertext)
```

#### Session Key Derivation (Native Port)

```
Source:       Minecraft protocol shared secret (from Mojang auth / offline mode)
              Already established by the Minecraft network layer before CPM handshake.
              This is a 256-bit symmetric key unique to each client connection.

Derivation:
    base_session_key = HKDF-SHA512(
        IKM    = mc_shared_secret,
        salt   = "cpm_session_key_v2",
        info   = player_uuid || server_random,
        length = 32  // 256 bits
    )

    // base_session_key is then fed into time-window key derivation (see 5.3)
    
Usage:        All model data packets are encrypted with AES-256-GCM using
              the time-window-specific key derived from base_session_key.
              No RSA or asymmetric crypto needed at all.
Library:      javax.crypto (JDK built-in)
```

#### Database Column Key Derivation

```
master_key = HKDF-SHA512(
    IKM    = PBKDF2(password = server_db_password, salt = "cpm_db_v1", iterations = 600000),
    salt   = "cpm_column_encryption_v1",
    info   = "model_data_blob",
    length = 32  // 256 bits
)

per_row_key = HKDF-SHA512(
    IKM    = master_key,
    salt   = row_uuid.toString(),
    info   = "model_data_row",
    length = 32
)
```

### 5.4 Client-Side Memory Protection

```java
/**
 * A byte array wrapper that:
 * 1. Stores data XOR'd with a random one-time pad
 * 2. Wipes the underlying array on close (zero-fill, not just dereference)
 * 3. Discourages GC from copying the array (pinned in young gen via periodic access)
 * 4. Implements AutoCloseable for try-with-resources
 */
public class GuardedByteArray implements AutoCloseable {
    private byte[] data;
    private byte[] pad;
    private volatile boolean wiped;

    public GuardedByteArray(byte[] input) {
        this.pad = new byte[input.length];
        SecureRandom.get().nextBytes(pad);
        this.data = new byte[input.length];
        for (int i = 0; i < input.length; i++) {
            data[i] = (byte) (input[i] ^ pad[i]);
        }
        // Clear input immediately
        Arrays.fill(input, (byte) 0);
    }

    public byte[] getDecrypted() {
        if (wiped) throw new IllegalStateException("Already wiped");
        byte[] out = new byte[data.length];
        for (int i = 0; i < data.length; i++) {
            out[i] = (byte) (data[i] ^ pad[i]);
        }
        return out;
    }

    @Override
    public void close() {
        if (!wiped) {
            Arrays.fill(data, (byte) 0);
            Arrays.fill(pad, (byte) 0);
            wiped = true;
        }
    }
}
```

**Important Notes on Java Memory Protection:**

- Java's GC may copy objects in memory; `GuardedByteArray` can only reduce the window, not eliminate it
- For production-grade memory safety, the AES keys could be stored in a **JCEKS keystore with password callback** (keys never in application memory as plain bytes)
- `ByteBuffer.allocateDirect()` can be used for off-heap storage to avoid GC copying, at the cost of complexity
- The `java.security.SecureRandom` DRBG is FIPS 140-2 compliant when using the native PRNG on Windows

### 5.5 Server RAM Encryption — EncryptedModelBlob

```java
/**
 * Holds a model in server RAM as AES-256-GCM ciphertext.
 * Replaces the plain byte[] data field in PlayerData.
 *
 * The decrypted form exists ONLY for the duration of a single
 * getDecrypted() → use → wipe cycle. This prevents a server
 * memory dump from exposing all player models simultaneously.
 */
public class EncryptedModelBlob {
    private final byte[] ciphertext;  // AES-256-GCM encrypted
    private final byte[] iv;          // 12 bytes
    private final byte[] gcmTag;      // 16 bytes (stored separately for DB alignment)
    private final int plaintextSize;
    private final SecretKey perBlobKey;

    public EncryptedModelBlob(byte[] plaintext, SecretKey key) {
        this.plaintextSize = plaintext.length;
        this.perBlobKey = key;
        // Generate random IV
        this.iv = new byte[12];
        SecureRandom.get().nextBytes(iv);

        // Encrypt
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
        byte[] output = cipher.doFinal(plaintext);
        // output = ciphertext || tag (last 16 bytes)

        this.ciphertext = Arrays.copyOf(output, output.length - 16);
        this.gcmTag = Arrays.copyOfRange(output, output.length - 16, output.length);

        // Wipe plaintext immediately
        Arrays.fill(plaintext, (byte) 0);
    }

    /**
     * ⚠ CRITICAL: Caller MUST wipe the returned byte[] after use!
     * Use try-finally or try-with-resources pattern.
     */
    public byte[] getDecrypted() {
        byte[] combined = Arrays.copyOf(ciphertext, ciphertext.length + 16);
        System.arraycopy(gcmTag, 0, combined, ciphertext.length, 16);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, perBlobKey, new GCMParameterSpec(128, iv));
        return cipher.doFinal(combined);
    }

    public int getPlaintextSize() { return plaintextSize; }

    // For PlayerData serialization — NEVER exposes plaintext
    public byte[] getCiphertext() { return ciphertext; }
    public byte[] getIv() { return iv; }
    public byte[] getGcmTag() { return gcmTag; }
}

// Usage in PlayerData:
// OLD: public byte[] data;  // ← plaintext, insecure in RAM
// NEW:
//   private EncryptedModelBlob encryptedModel;
//   public void setModel(byte[] data, boolean forced, boolean save) {
//       if (data != null) {
//           this.encryptedModel = new EncryptedModelBlob(data, getPerPlayerKey());
//           Arrays.fill(data, (byte) 0);
//       } else {
//           this.encryptedModel = null;
//       }
//       this.forced = forced;
//       this.save = save;
//   }
//
//   public byte[] getModelForBroadcast() {
//       if (encryptedModel == null) return null;
//       return encryptedModel.getDecrypted();
//       // ⚠ CALLER MUST WIPE AFTER USE
//   }

// Usage in SetSkinS2C handler:
//   byte[] modelBytes = playerData.getModelForBroadcast();
//   try {
//       NBTTagCompound nbt = new NBTTagCompound();
//       nbt.setByteArray("data", modelBytes);
//       sendPacketToTracking(player, new SetSkinS2C(id, nbt));
//   } finally {
//       if (modelBytes != null) Arrays.fill(modelBytes, (byte) 0);
//   }
```

### 5.6 Offline Mode UUID Considerations

In Minecraft offline mode, the UUID is deterministically derived:

```java
// Minecraft's OfflinePlayer UUID generation (from Yggdrasil auth lib)
UUID.nameUUIDFromBytes(("OfflinePlayer:" + playerName).getBytes(StandardCharsets.UTF_8))
// Result: e.g. "OfflinePlayer:Steve" → UUID(6b089531-9f94-3094-aad3-4f3f83b4b0e5)
```

**Impact on CPM:**
- The UUID is stable for the same player name (deterministic)
- If a player changes their name, their UUID changes → **models are lost**
- This is **identical behavior** to Mojang's online mode for name-changed accounts (UUID stays same in online mode, but offline mode doesn't have Mojang's name-change tracking)

**Mitigation:**
- Store both UUID and **last known player name** in the database
- Provide admin UI to reassign models from old UUID to new UUID when name changes
- Optionally, allow the server admin to configure a custom UUID mapping

---

## 6. Database Schema

### 6.1 Tables

```sql
-- ============================================================
-- CPM Built-in Server Database Schema v2
-- ============================================================

-- Player registry
CREATE TABLE players (
    uuid        VARCHAR(36)  PRIMARY KEY,          -- Player UUID (offline or online)
    username    VARCHAR(16)  NOT NULL,             -- Last known username
    first_seen  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    is_blocked  BOOLEAN      NOT NULL DEFAULT FALSE -- Admin can block uploads
);

-- Model storage (column-level AES-256-GCM encrypted BLOB)
CREATE TABLE models (
    id          BIGINT       AUTO_INCREMENT PRIMARY KEY,
    player_uuid VARCHAR(36)  NOT NULL REFERENCES players(uuid) ON DELETE CASCADE,
    name        VARCHAR(128) NOT NULL,             -- Model name (from editor)
    description VARCHAR(512),                      -- Model description
    data_enc    BLOB         NOT NULL,             -- AES-256-GCM encrypted model bytes
    data_iv     BINARY(12)   NOT NULL,             -- GCM initialization vector
    data_tag    BINARY(16)   NOT NULL,             -- GCM authentication tag
    icon_enc    BLOB,                              -- AES-256-GCM encrypted icon
    icon_iv     BINARY(12),                        -- GCM initialization vector for icon
    icon_tag    BINARY(16),                        -- GCM authentication tag for icon
    size_bytes  INT          NOT NULL,             -- Original plaintext size
    sha256      BINARY(32),                        -- SHA-256 of plaintext (integrity verification)
    is_default  BOOLEAN      NOT NULL DEFAULT FALSE,-- Player's default model
    is_forced   BOOLEAN      NOT NULL DEFAULT FALSE,-- Admin-forced (overrides player choice)
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Active chunked upload sessions (temporary, for transfer coordination)
CREATE TABLE upload_sessions (
    id              VARCHAR(36)  PRIMARY KEY,      -- Upload UUID (generated by server)
    player_uuid     VARCHAR(36)  NOT NULL,         -- Uploading player
    model_name      VARCHAR(128) NOT NULL,
    model_desc      VARCHAR(512),
    total_chunks    INT          NOT NULL,         -- Expected number of chunks
    received_chunks INT          NOT NULL DEFAULT 0,-- How many received so far
    last_chunk_idx  INT          NOT NULL DEFAULT -1, -- Last successfully stored chunk index
    total_size      INT          NOT NULL,         -- Expected total plaintext size
    full_sha256     BINARY(32),                    -- Expected SHA-256 (sent on completion)
    chunk_data      BLOB,                          -- Accumulated encrypted data (reassembled)
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at      TIMESTAMP    NOT NULL,         -- Auto-cleanup after timeout
    status          VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' -- ACTIVE, COMPLETE, CANCELLED, EXPIRED
);

CREATE INDEX idx_upload_sessions_player ON upload_sessions(player_uuid);
CREATE INDEX idx_upload_sessions_expires ON upload_sessions(expires_at);

-- Admin operations are gated by Minecraft OP level (level 2+), not by a separate
-- admin table. The audit_log records the acting player's UUID for accountability.
-- No separate admin accounts or passwords needed.

-- Server configuration overrides (set via /cpm admin commands or cpm.json)
CREATE TABLE server_config (
    key         VARCHAR(128) PRIMARY KEY,
    value       TEXT         NOT NULL,
    updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Audit log
CREATE TABLE audit_log (
    id          BIGINT       AUTO_INCREMENT PRIMARY KEY,
    actor       VARCHAR(36)  NOT NULL,             -- admin UUID or "SYSTEM"
    action      VARCHAR(64)  NOT NULL,             -- UPLOAD, DELETE, FORCE, UNFORCE, BLOCK, UNBLOCK
    target      VARCHAR(36),                       -- target player UUID
    model_id    BIGINT,                            -- affected model ID
    details     TEXT,                              -- JSON details
    ip_address  VARCHAR(45),                       -- Admin IP for web actions
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Indexes
CREATE INDEX idx_models_player ON models(player_uuid);
CREATE INDEX idx_models_default ON models(player_uuid, is_default);
CREATE INDEX idx_audit_actor ON audit_log(actor);
CREATE INDEX idx_audit_target ON audit_log(target);
CREATE INDEX idx_audit_time ON audit_log(created_at DESC);
```

### 6.2 Configuration Keys (Added to ConfigKeys.java)

```java
// ---- Built-in Server Settings ----
public static final String CPM_SERVER_ENABLED              = "cpmServer.enabled";
public static final String CPM_SERVER_MAX_MODEL_SIZE_MB    = "cpmServer.maxModelSizeMb";

// ---- Admin HTTP Dashboard Settings ----
public static final String CPM_HTTP_PORT_ENABLED           = "cpmServer.httpPort.enabled";
public static final String CPM_HTTP_PORT                   = "cpmServer.httpPort.port";
public static final String CPM_HTTP_PORT_BIND              = "cpmServer.httpPort.bindAddress";
public static final String CPM_TLS_ENABLED                 = "cpmServer.tls.enabled";
public static final String CPM_TLS_KEYSTORE                = "cpmServer.tls.keystore";
public static final String CPM_TLS_KEYSTORE_PASS           = "cpmServer.tls.keystorePass";
public static final String CPM_ADMIN_USERNAME              = "cpmServer.admin.username";
public static final String CPM_ADMIN_PASSWORD_HASH         = "cpmServer.admin.passwordHash";
public static final String CPM_ADMIN_JWT_SECRET            = "cpmServer.admin.jwtSecret";

// ---- Transfer Settings ----
public static final String CPM_TRANSFER_CHUNK_SIZE_KB      = "cpmServer.transfer.chunkSizeKb";
public static final String CPM_TRANSFER_CHUNK_TIMEOUT_SEC  = "cpmServer.transfer.chunkTimeoutSec";
public static final String CPM_TRANSFER_SESSION_TIMEOUT_MIN = "cpmServer.transfer.sessionTimeoutMin";

// ---- Time-Bound Crypto Settings ----
public static final String CPM_TIME_WINDOW_MINUTES         = "cpmServer.time.windowMinutes";
public static final String CPM_TIME_WINDOW_SKEW            = "cpmServer.time.windowSkew";
public static final String CPM_TIME_SYNC_ON_JOIN           = "cpmServer.time.syncOnJoin";

// ---- Database Settings ----
public static final String CPM_DB_PATH                   = "cpmServer.db.path";
public static final String CPM_DB_FILE_ENCRYPTION        = "cpmServer.db.fileEncryption";
public static final String CPM_DB_FILE_PASSWORD          = "cpmServer.db.filePassword";
public static final String CPM_DB_COLUMN_ENCRYPTION      = "cpmServer.db.columnEncryption";
public static final String CPM_DB_MASTER_KEY             = "cpmServer.db.masterKey";

// ---- Client-Side Protection ----
public static final String CPM_CLIENT_ENCRYPT_LOCAL_MODELS = "cpmClient.encryptLocalModels";

// ---- Offline Mode ----
public static final String CPM_OFFLINE_MODE_ENABLED      = "cpmServer.offlineMode.enabled";
public static final String CPM_OFFLINE_MODE_ALLOW_UPLOAD = "cpmServer.offlineMode.allowUpload";
```

---

## 7. Implementation Phases

### Phase 1: Foundation (Database & Core Crypto)

**Duration estimate: 2–3 weeks**

| Task | Description | Files |
|---|---|---|
| P1.1 | Add H2 database dependency to `build.gradle` | `build.gradle` |
| P1.2 | Implement `DatabaseManager` (connection pool, WAL mode, lifecycle, migration runner) | `server/db/DatabaseManager.java` |
| P1.3 | Implement `MigrationManager` with v2 schema (players, models, upload_sessions, admins, server_config, audit_log) | `server/db/MigrationManager.java` |
| P1.4 | Implement `ModelRepository` (CRUD for models, players, upload sessions; scoped queries by player UUID) | `server/model/ModelRepository.java` |
| P1.5 | Implement `ModelEntity` and `UploadSession` POJOs | `server/model/` |
| P1.6 | Implement `ModelValidator` (size, format, SHA-256 verification, safety checks) | `server/model/ModelValidator.java` |
| P1.7 | Implement `CryptoService` (AES-256-GCM encrypt/decrypt, HKDF key derivation, per-row key generation) | `server/crypto/CryptoService.java` |
| P1.8 | Implement `KeyManager` (master key generation, JCEKS keystore storage, key rotation) | `server/crypto/KeyManager.java` |
| P1.9 | Implement `EncryptedModelBlob` (RAM-safe ciphertext holder — replaces plain `byte[] data` in `PlayerData`) | `server/crypto/EncryptedModelBlob.java` |
| P1.10 | Implement `MemoryProtector` (GuardedByteArray, secure wipe utilities) | `server/crypto/MemoryProtector.java` |
| P1.11 | Implement `SessionKeyManager` (per-client session key derivation from MC shared secret via HKDF; time-window key rotation) | `server/crypto/SessionKeyManager.java` |
| P1.12 | Implement `TimeBoundKeyManager` (time-window ID calculation, key derivation per window, clock-skew tolerance, key expiry/wipe, AAD binding) | `server/crypto/TimeBoundKeyManager.java` |
| P1.13 | Add all config keys to `ConfigKeys.java` with defaults (including `CPM_TIME_*` keys) | `shared/config/ConfigKeys.java` |
| P1.14 | Implement `OfflineUUIDUtil` with deterministic offline UUID generation | `server/compat/OfflineUUIDUtil.java` |
| P1.15 | Refactor `PlayerData.data` from `byte[]` to `EncryptedModelBlob`; update all call sites | `shared/config/PlayerData.java` |

### Phase 2: Chunked Transfer Protocol & Native Port Packets

**Duration estimate: 2–3 weeks**

| Task | Description | Files |
|---|---|---|
| P2.1 | Define `ChunkProtocol` constants and wire format | `server/transfer/ChunkProtocol.java` |
| P2.2 | Implement `ChunkedUploader` (client-side: split `byte[]` into chunks, encrypt each, send sequentially, handle ACK/RETRY, resume on reconnect) | `server/transfer/ChunkedUploader.java` |
| P2.3 | Implement `ChunkedReceiver` (server-side: receive chunks, send ACK, reassemble, verify SHA-256, cleanup expired sessions) | `server/transfer/ChunkedReceiver.java` |
| P2.4 | Implement `TransferResumeManager` (track in-progress uploads across disconnects; session timeout cleanup) | `server/transfer/TransferResumeManager.java` |
| P2.5 | Create 16 new packet types in `shared/network/packet/` (ModelListReq/Res, ModelUploadInit/Ack, ModelDataChunk/Ack, ModelUploadComplete/Result, ModelUploadCancel, ModelUploadResume, ModelDownloadReq/Chunk, ModelSetActive, ModelSetDefault, ModelDeleteReq/Result) | `shared/network/packet/Model*.java` × 16 |
| P2.6 | Register all new packet types in `NetHandler` constructor (add to `packetC2S`/`packetS2C` maps) | `shared/network/NetHandler.java` |
| P2.7 | Implement `CpmModelPacketHandler` (server-side packet dispatch: route to ModelService with auth check) | `server/CpmModelPacketHandler.java` |
| P2.8 | Add `ServerCaps.CPM_BUILT_IN_SERVER` and `ServerCaps.CPM_CHUNKED_TRANSFER` flags | `shared/network/ServerCaps.java` |
| P2.9 | Add `isLargePayload()` to `IPacket` for chunk-aware routing | `shared/network/IPacket.java` |

### Phase 3: Admin Commands & Client GUI

**Duration estimate: 2–3 weeks**

| Task | Description | Files |
|---|---|---|
| P3.1 | Implement admin subcommands in `Command.java`: `/cpm admin models list/delete/force/unforce`, `/cpm admin players block/unblock`, `/cpm admin audit`, `/cpm admin db backup/stats` | `common/Command.java` |
| P3.2 | Implement `AdminCommandHandler` (OP-level check, paginated chat output with clickable entries, console support) | `server/admin/AdminCommandHandler.java` |
| P3.3 | Implement `CpmModelTransferClient` (client coordinator: chunked upload/download with time-bound encryption, progress tracking) | `server/client/CpmModelTransferClient.java` |
| P3.4 | Implement `UploadProgressTracker` (UI model for progress bar, cancel button, error display) | `server/client/UploadProgressTracker.java` |
| P3.5 | Implement `ModelListCache` (local cache with TTL; invalidate on upload/delete) | `server/client/ModelListCache.java` |
| P3.6 | Add "Upload to Server" button to Editor GUI (visible ONLY when `netHandler.hasModClient()==true`) | Editor GUI (existing files) |
| P3.7 | Add "My Models" list GUI (accessible from Gesture menu/Settings; shows server models, allows set active/default/delete) | New GUI screen |
| P3.8 | Add upload progress bar overlay in Editor (chunk counter, percentage, cancel button) | Editor GUI |
| P3.9 | Add resume prompt on reconnect if upload was interrupted | Client event handler |
| P3.10 | Integrate with `NetworkUtil.sendSkinDataToServer()` — prefer server DB model if available | `NetworkUtil.java` |
| P3.11 | Add `ModelFile.encryptToFile()` / `ModelFile.decryptFromFile()` for optional local model encryption | `shared/io/ModelFile.java` |

### Phase 4: Admin Web Dashboard

**Duration estimate: 2–3 weeks**

| Task | Description | Files |
|---|---|---|
| P4.1 | Add NanoHTTPD, bcrypt, JWT dependencies to `build.gradle` | `build.gradle` |
| P4.2 | Implement `CpmModelHttpServer` (start/stop, route registration; binds to localhost:8080 by default) | `server/CpmModelHttpServer.java` |
| P4.3 | Implement `AdminHttpHandler` (login, model CRUD, player management, audit log API endpoints) | `server/admin/AdminHttpHandler.java` |
| P4.4 | Implement `AdminAuthFilter` (bcrypt password verification, JWT token issue/validation, rate limiting) | `server/admin/AdminAuthFilter.java` |
| P4.5 | Build SPA frontend: login page with bcrypt auth | `server/admin/webapp/login.html` |
| P4.6 | Build SPA frontend: model list with search, filter, sort, pagination | `server/admin/webapp/index.html` |
| P4.7 | Build SPA frontend: model detail with icon preview | `server/admin/webapp/model.html` |
| P4.8 | Build SPA frontend: player management (block/unblock, model count) | `server/admin/webapp/players.html` |
| P4.9 | Build SPA frontend: audit log viewer with filtering | `server/admin/webapp/audit.html` |
| P4.10 | Bundle static assets into JAR; serve from classpath via NanoHTTPD | `CpmModelHttpServer.java` |
| P4.11 | Wire HTTP server start/stop into `CustomPlayerModels` (only if `cpmServer.httpPort.enabled=true`) | `CustomPlayerModels.java` |

### Phase 5: Integration & Hardening

**Duration estimate: 2 weeks**

| Task | Description | Files |
|---|---|---|
| P5.1 | Implement `LegacyModelMigrator` (JSON → DB one-time migration with progress) | `server/compat/LegacyModelMigrator.java` |
| P5.2 | Add DB backup/restore functionality (auto-backup on shutdown, manual trigger from admin command/dashboard) | `server/db/DatabaseManager.java` |
| P5.3 | Add config validation on startup (DB permissions, key validity, chunk size vs packet limit sanity check, time window vs skew consistency, HTTP port conflict detection) | `server/CpmServerConfig.java` |
| P5.4 | Implement proper error handling (friendly i18n messages, no stack trace leaks, chunk retry logic, time-sync error recovery) | All server files |
| P5.5 | Add rate limiting (max concurrent uploads/player, max chunks/sec, max models/player, admin login attempts/min) | `server/CpmModelPacketHandler.java`, `AdminAuthFilter.java` |
| P5.6 | Add TLS support for admin HTTP port (optional, via Java SSLContext + keystore) | `server/CpmModelHttpServer.java` |
| P5.7 | Implement upload session cleanup (periodic task to delete expired `upload_sessions` rows) | `server/transfer/TransferResumeManager.java` |
| P5.8 | Add time-sync integration: `HelloS2C` includes `serverTime` and `timeWindowId` fields; client syncs clock on join | `HelloS2C.java` |
| P5.9 | Add large-model stress tests (2 MB, 5 MB, 10 MB models, network interruption mid-transfer, clock-skew edge cases) | `src/test/` |
| P5.10 | Write user documentation (wiki/README update with setup guide, admin command reference, dashboard guide, security best practices) | `docs/` |

---

## 8. Risk Assessment

### 8.1 Technical Risks

| Risk | Severity | Mitigation |
|---|---|---|
| **H2 database corruption on crash** | Medium | Write-Ahead Logging (WAL) mode; periodic auto-backup; integrity check on startup |
| **Java Crypto Policy restricts AES-256** | Low | JDK 21 ships with unlimited strength by default; fallback to AES-128 if needed |
| **Large model (>5 MB) chunked transfer congests game packet pipeline** | Medium | Chunked packets share the Minecraft Netty event loop. Mitigation: implement backpressure (client waits for ACK before sending next chunk); use separate `Executor` for chunk processing; configurable model size cap |
| **Chunk sequence errors (out-of-order, duplicate, missing)** | Medium | Each chunk has a monotonic index; server tracks received chunks in a bitset; duplicate chunks are idempotently ignored; missing chunks trigger selective re-request after timeout |
| **Upload session leak (abandoned uploads filling DB)** | Low | `upload_sessions` has `expires_at` column; periodic cleanup task runs every 5 minutes; disconnect handler cancels active sessions |
| **Minecraft packet size limit regression** | Low | Hardcode test: `assert MAX_CHUNK_SIZE < 32767`. Run integration test on NeoForge version update. Chunk size configurable by admin to work around future changes |
| **Client-server clock desync breaks time-window crypto** | Medium | Server sends `serverTime` and `timeWindowId` in `HelloS2C` during handshake. Client syncs clock on join. Clock-skew tolerance of ±1 window (configurable). If skew exceeded, server returns `TimeSyncS2C` error and client retries with corrected window. |
| **Time-window transition causes in-flight message failures** | Low | Grace period: old windows accepted for (skew) windows after expiry. GCM authentication fails gracefully with clear error code. Client retries with new window key automatically. |
| **Memory protection is not bulletproof in Java** | Medium | Document this limitation; recommend server admins use encrypted swap, secure OS; core security is in the crypto layers (time-bound EncryptedModelBlob in RAM, column encryption on disk), not memory obfuscation. Time-binding limits blast radius of any single key extraction. |
| **Offline UUID collision** | Very low | UUIDv3 from name is collision-resistant for practical player counts (< 10⁶) |
| **Name change breaks offline UUID** | Medium | Document limitation; provide admin command `/cpm admin models reassign <oldUUID> <newUUID>` for model migration; store `username` alongside `uuid` in players table for manual resolution |
| **Very large models (>10 MB) and server memory** | Low-Medium | Server reassembles full model in RAM before DB write. For a 10 MB model, this is negligible on modern servers. Upload is sequential (not parallel), so only one large model is reassembled at a time. Hard cap at 10 MB (configurable). |

### 8.2 Security Risks

| Risk | Severity | Mitigation |
|---|---|---|
| **AES master key in server config file** | High | Auto-generate on first run with `SecureRandom`; store in separate key file (0600), not in main config; warn admin to back up key; if key lost, models are irrecoverable (by design) |
| **EncryptedModelBlob key management in RAM** | Medium | Per-player encryption key derived via HKDF from master key. Key material in RAM only during active decrypt→encrypt→wipe cycle (~microseconds). JVM still subject to memory dump; defense is that each player has a different derived key, plus time-binding limits the window of exposure. |
| **Replay attack on chunked packets** | Medium | Time-window ID in GCM AAD prevents cross-window replay. Within-window replay prevented by monotonic message counter per session. GCM authentication tag detects any ciphertext manipulation. |
| **Time-window key extraction from memory dump** | Medium | If an attacker extracts a single window key from a memory dump, they can only decrypt messages from ±skew windows (~1 hour with default 30-min windows). Historical data uses expired keys (already wiped). Future data will use different keys. This is a significant improvement over a static session key. |
| **Model data leaks in server logs** | Medium | Sanitize all server logging; never log raw model bytes, IVs, keys, or time-window IDs; only log: model ID, player UUID, model name, size, chunk index (not data) |
| **Client-side memory dump exposes model during upload** | Low-Medium | GuardedByteArray reduces exposure window; model data is transient (only during chunk encryption); time-bound session key is ephemeral; local model file encryption (`ModelFile.encryptToFile()`) protects at rest |
| **Native port model data visible to other server plugins** | Low | Other mods on the server could theoretically read `ByteArrayPayload` data. This is an existing risk with any mod packet. Mitigation: AES-256-GCM layer inside NBT means intercepted data is ciphertext. Plugin would need to compromise the time-window session key to decrypt. Time-binding means even if they extract one key, it expires within the hour. |
| **Source code reveals all algorithms** | Very Low | This is by design — the mod is open source. Security relies on the server's secret keys (master DB key, per-connection shared secrets), not on algorithm secrecy. Time-binding adds defense against long-term data collection. Even with full source access, an attacker cannot decrypt captured traffic without the server's session keys. |
| **Admin dashboard brute-force (HTTP port)** | Medium | bcrypt hashing (cost factor 12); JWT tokens with 1-hour expiry; rate limiting on login endpoint (5 attempts/min per IP); dashboard binds to localhost by default — remote access requires explicit configuration; TLS optional |

### 8.3 Compatibility Risks

| Risk | Severity | Mitigation |
|---|---|---|
| **Existing servers without the update** | Low | Feature is opt-in via `cpmServer.enabled=false` default; existing behavior unchanged; new packet types are only sent if `ServerCaps.CPM_BUILT_IN_SERVER` is negotiated |
| **Old CPM clients connecting to new server** | Low | `HelloS2C` capability negotiation: old clients won't see `CPM_BUILT_IN_SERVER` or `CPM_TIME_BOUND_KEYS` flags, will fall back to existing SetSkinC2S behavior. Admin can also configure `cpmServer.requireNewClient=false` to allow mixed versions. |
| **Mixed online/offline players** | Low | UUID is always the lookup key; online players use Mojang UUID, offline use derived UUID; no conflict; `players` table stores both UUID and username for admin resolution |
| **NeoForge/Fabric dual support** | Medium | Server-side code (DB, crypto, chunk protocol, time-bound keys) is Minecraft-version-agnostic (plain Java + JDBC + javax.crypto). Client packet integration uses CPL abstractions. New NeoForge-specific `ByteArrayPayload` packet types need Fabric equivalents for multi-loader support. |
| **Chunk size vs packet buffer limit** | Low | Default chunk size (30KB) leaves 2KB headroom under the 32KB limit. Admin can reduce if needed. `ChunkProtocol.MAX_CHUNK_SIZE` is a named constant. |
| **Very large models (>10 MB) and server memory** | Low-Medium | Server reassembles full model in RAM before DB write. For a 10 MB model, this is negligible on modern servers. Upload is sequential (not parallel). Hard cap at 10 MB (configurable). |
| **JDK version differences in HKDF availability** | Low | `HKDF-SHA512` is available in JDK 21+ (`javax.crypto`). The mod targets JDK 21 for NeoForge 1.21. If porting to older MC versions, a pure-Java HKDF implementation (e.g., from Tink library) can be shaded. |

---

## Appendix A: Dependency Additions

```groovy
// build.gradle additions

dependencies {
    // Embedded database
    implementation 'com.h2database:h2:2.2.224'

    // Embedded HTTP server (admin web dashboard only — model data goes through native port)
    implementation 'org.nanohttpd:nanohttpd:2.3.1'

    // Password hashing for admin dashboard accounts
    implementation 'at.favre.lib:bcrypt:0.10.2'

    // JWT for admin dashboard session tokens
    implementation 'io.jsonwebtoken:jjwt-api:0.12.5'
    runtimeOnly 'io.jsonwebtoken:jjwt-impl:0.12.5'
    runtimeOnly 'io.jsonwebtoken:jjwt-jackson:0.12.5'
}

// All crypto (AES-256-GCM, HKDF-SHA512) is JDK built-in (Java 21+).
// No RSA dependency — all key material derived symmetrically via HKDF from MC shared secret.
```

**Total external dependency footprint: 4 libraries (H2, NanoHTTPD, bcrypt, JWT).** All model data traffic stays on Minecraft native port 25565. HTTP port is for admin dashboard only.

## Appendix B: Example Config (cpm.json additions)

```json
{
  "cpmServer": {
    "enabled": false,
    "maxModelSizeMb": 10,
    "httpPort": {
      "enabled": true,
      "port": 8080,
      "bindAddress": "127.0.0.1"
    },
    "transfer": {
      "chunkSizeKb": 30,
      "chunkTimeoutSec": 30,
      "sessionTimeoutMin": 10
    },
    "time": {
      "windowMinutes": 30,
      "windowSkew": 1,
      "syncOnJoin": true
    },
    "tls": {
      "enabled": false,
      "keystore": "cpm_keystore.jks",
      "keystorePass": ""
    },
    "db": {
      "path": "cpm_db",
      "fileEncryption": true,
      "filePassword": "",
      "columnEncryption": true,
      "masterKey": ""
    },
    "admin": {
      "username": "admin",
      "passwordHash": "",
      "jwtSecret": ""
    },
    "offlineMode": {
      "enabled": false,
      "allowUpload": true
    }
  },
  "cpmClient": {
    "encryptLocalModels": false
  }
}
```
*Note: HTTP port is for admin web dashboard only. All model data (upload/download/list/delete) goes through Minecraft native port 25565.*

## Appendix C: API / Command Reference

### Native Port (Minecraft Packets — ALL traffic)

All model operations go through the existing Minecraft connection. Packets are wrapped in `ByteArrayPayload` and routed by `NetHandler`. Each packet's data is AES-256-GCM encrypted inside its `NBTTagCompound`.

```
MODEL LIST:
C→S ModelListReqC2S      { }
S→C ModelListResS2C      { "models": [{ "id": 42, "name": "...", "size": 1234, "isDefault": true, "createdAt": "<iso>" }] }

CHUNKED UPLOAD (models > 30 KB):
C→S ModelUploadInitC2S   { "name": "...", "desc": "...", "totalSize": 102400, "numChunks": 4, "sha256": <32 bytes> }
S→C ModelUploadInitAckS2C { "uploadId": "<uuid>", "status": "OK"|"REJECTED", "reason": "..." }
C→S ModelDataChunkC2S    { "uploadId": "<uuid>", "chunkIdx": 0, "data": <encrypted>, "dataIv": <12>, "dataTag": <16>, "chunkSha256": <32> }
S→C ModelDataChunkAckS2C { "uploadId": "<uuid>", "chunkIdx": 0, "status": "OK"|"RETRY" }
C→S ModelUploadCompleteC2S { "uploadId": "<uuid>", "fullSha256": <32> }
S→C ModelUploadResultS2C { "uploadId": "<uuid>", "modelId": 42, "status": "OK"|"HASH_MISMATCH"|"VALIDATION_FAILED" }
C→S ModelUploadCancelC2S  { "uploadId": "<uuid>" }
C→S ModelUploadResumeC2S  { "uploadId": "<uuid>" }
S→C ModelUploadResumeAckS2C { "uploadId": "<uuid>", "lastReceivedChunk": 2 }

SINGLE-PACKET UPLOAD (models ≤ 30 KB, existing path):
C→S SetSkinC2S             { "data": <encrypted byte[]> }  ← existing packet, now with encryption layer

MODEL DOWNLOAD (chunked S→C):
C→S ModelDownloadReqC2S   { "modelId": 42 }
S→C ModelDownloadChunkS2C { "modelId": 42, "chunkIdx": 0, "totalChunks": 3, "data": <encrypted>, ... }

MODEL ACTIONS:
C→S ModelSetActiveC2S     { "modelId": 42 }
C→S ModelSetDefaultC2S    { "modelId": 42 }
C→S ModelDeleteReqC2S     { "modelId": 42 }
S→C ModelDeleteResultS2C  { "modelId": 42, "status": "OK"|"NOT_FOUND"|"NOT_OWNER" }
```

### Admin Commands (In-Game, OP Level 2+)

```
/cpm admin models list [player]          — List models (all, or filtered by player name/UUID)
/cpm admin models info <id>             — Show model details (owner, size, SHA-256, dates)
/cpm admin models delete <id> [reason]  — Delete a model (logs reason to audit_log)
/cpm admin models force <id>            — Force this model on its owner (overrides player choice)
/cpm admin models unforce <id>          — Remove forced status
/cpm admin models reassign <id> <uuid>  — Reassign model to a different player UUID
/cpm admin players block <uuid>         — Block player from uploading models
/cpm admin players unblock <uuid>       — Unblock player
/cpm admin players info <uuid>          — Show player info (models count, blocked status, last seen)
/cpm admin audit [page] [player]        — View audit log (paginated, 10 entries/page)
/cpm admin db backup                    — Trigger immediate database backup
/cpm admin db stats                     — Show DB statistics (model count, total size, uptime)
/cpm admin reload                       — Reload server configuration from cpm.json
```

Commands work from both in-game chat (OP required) and server console (implicit admin).

### Admin Web Dashboard API (HTTP, localhost:8080 default)

```
POST /api/admin/login                      → { "username": "admin", "password": "..." }
                                              ← { "token": "<JWT>" }

GET  /api/admin/models?page=0&size=50&sort=createdAt&player=<uuid>
                                           → { "models": [...], "total": 100 }

GET  /api/admin/models/{id}                → { "id": 42, "playerUuid": "...", "name": "...",
                                                "size": 1234, "icon": "<base64 icon>", ... }
DELETE /api/admin/models/{id}              → 204 No Content
PUT   /api/admin/models/{id}/force         → 200 OK
DELETE /api/admin/models/{id}/force        → 200 OK

PUT   /api/admin/players/{uuid}/block      → 200 OK
DELETE /api/admin/players/{uuid}/block     → 200 OK

GET   /api/admin/audit?page=0&size=50      → { "entries": [...], "total": 500 }
GET   /api/admin/db/stats                  → { "modelCount": 42, "totalSizeMb": 15.3, "uptime": "3d 2h" }
POST  /api/admin/db/backup                 → { "status": "OK", "path": "backups/cpm_db_2026-05-23.bak" }
```

---

*Document version: 4.0 — Updated 2026-05-23: web dashboard included in initial release (NanoHTTPD + bcrypt + JWT on configurable port, localhost by default); time window reduced to 30 minutes (blast radius ~1 hour); model data stays on native port 25565 — only admin dashboard uses separate HTTP port; 4 external dependencies (H2, NanoHTTPD, bcrypt, JWT).*
