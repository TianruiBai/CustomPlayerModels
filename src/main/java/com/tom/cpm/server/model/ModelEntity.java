package com.tom.cpm.server.model;

import java.sql.Timestamp;

/**
 * POJO representing a stored player model.
 */
public class ModelEntity {

    private long id;
    private String playerUuid;
    private String name;
    private String description;
    private byte[] dataEnc;
    private byte[] dataIv;
    private byte[] dataTag;
    private byte[] iconEnc;
    private byte[] iconIv;
    private byte[] iconTag;
    private int sizeBytes;
    private byte[] sha256;
    private boolean isDefault;
    private boolean isForced;
    private boolean isCloneable;
    private Timestamp createdAt;
    private Timestamp updatedAt;

    public ModelEntity() {
    }

    // Getters and setters

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }

    public String getPlayerUuid() { return playerUuid; }
    public void setPlayerUuid(String playerUuid) { this.playerUuid = playerUuid; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public byte[] getDataEnc() { return dataEnc; }
    public void setDataEnc(byte[] dataEnc) { this.dataEnc = dataEnc; }

    public byte[] getDataIv() { return dataIv; }
    public void setDataIv(byte[] dataIv) { this.dataIv = dataIv; }

    public byte[] getDataTag() { return dataTag; }
    public void setDataTag(byte[] dataTag) { this.dataTag = dataTag; }

    public byte[] getIconEnc() { return iconEnc; }
    public void setIconEnc(byte[] iconEnc) { this.iconEnc = iconEnc; }

    public byte[] getIconIv() { return iconIv; }
    public void setIconIv(byte[] iconIv) { this.iconIv = iconIv; }

    public byte[] getIconTag() { return iconTag; }
    public void setIconTag(byte[] iconTag) { this.iconTag = iconTag; }

    public int getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(int sizeBytes) { this.sizeBytes = sizeBytes; }

    public byte[] getSha256() { return sha256; }
    public void setSha256(byte[] sha256) { this.sha256 = sha256; }

    public boolean isDefault() { return isDefault; }
    public void setDefault(boolean isDefault) { this.isDefault = isDefault; }

    public boolean isForced() { return isForced; }
    public void setForced(boolean isForced) { this.isForced = isForced; }

    public boolean isCloneable() { return isCloneable; }
    public void setCloneable(boolean isCloneable) { this.isCloneable = isCloneable; }

    public Timestamp getCreatedAt() { return createdAt; }
    public void setCreatedAt(Timestamp createdAt) { this.createdAt = createdAt; }

    public Timestamp getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Timestamp updatedAt) { this.updatedAt = updatedAt; }

    @Override
    public String toString() {
        return "ModelEntity{id=" + id + ", name='" + name + "', player=" + playerUuid +
               ", size=" + sizeBytes + "B, default=" + isDefault + "}";
    }
}
